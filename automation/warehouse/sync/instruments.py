# -*- coding: utf-8 -*-
"""标的母表（``md_instrument``）。

指数池是 ETF/指数身份的唯一来源：池子里有 42 个指数，每个指数对应一只代表 ETF。
**不在 automation 下存第二份池子**——仓库内直接读后端的 ``index-pool.json``，
容器里读不到时退回后端 ``/api/index-pool``。两份池子必然漂移。

个股身份由行情快照补：东财行情的 ``f100`` 是行业名，这是本项目唯一的行业口径，
不要再混入数据中心返回的 ``BOARD_NAME``。
"""

from __future__ import annotations

import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable, Sequence

import requests

from ..config import Config
from ..db import WarehouseDb
from ..normalize import (
    asset_instrument_code,
    clean_text,
    guess_market,
    now_beijing,
    truncate,
)
from ..sources.eastmoney import SOURCE_QUOTE, quote_to_instrument
from . import SyncOutcome

logger = logging.getLogger(__name__)

SOURCE_POOL = "index-pool"

INSTRUMENT_COLUMNS = (
    "instrument_code",
    "asset_type",
    "market",
    "raw_code",
    "name",
    "aliases",
    "industry",
    "industry_code",
    "tracking_index_code",
    "list_date",
    "status",
    "source",
    "fetched_at",
)


def load_index_pool(config: Config, timeout: float = 10.0) -> list[dict[str, Any]]:
    """本地池子优先；容器里没有 backend 目录时退回后端 API。"""
    path = config.index_pool_path
    if path and Path(path).exists():
        with open(path, "r", encoding="utf-8") as handle:
            payload = json.load(handle)
        return _pool_items(payload, str(path))

    if not config.backend_url:
        raise RuntimeError("找不到 index-pool.json，也没有配置 BACKEND_API_URL")

    session = requests.Session()
    session.trust_env = config.trust_env
    headers = {"Accept": "application/json"}
    if config.ingest_token:
        headers["X-Ingest-Token"] = config.ingest_token
    response = session.get(f"{config.backend_url}/api/index-pool", headers=headers, timeout=timeout)
    response.raise_for_status()
    return _pool_items(response.json(), "backend:/api/index-pool")


def _pool_items(payload: Any, origin: str) -> list[dict[str, Any]]:
    """本地 ``index-pool.json`` 与后端 ``/api/index-pool`` 的两种外壳都接受。

    本地文件是 ``{"indices": [...]}``；后端走统一 ``Result`` 包装，
    实际是 ``{"code":200,"message":"success","data":[...]}``。只认 ``indices``
    会让容器里永远读不到池子，还必须等到任务失败三次才会发现。
    """
    if isinstance(payload, dict):
        if "data" in payload and "code" in payload:
            code = payload.get("code")
            if str(code) != "200":
                message = clean_text(payload.get("message")) or "未知错误"
                raise RuntimeError(f"指数池接口返回 {code}: {message}（{origin}）")
            items = payload.get("data")
        else:
            items = payload.get("indices")
    else:
        items = payload
    if not isinstance(items, list) or not items:
        raise RuntimeError(f"指数池为空或格式错误: {origin}")
    return [item for item in items if isinstance(item, dict)]


def index_market(index_code: str) -> str | None:
    code = (index_code or "").upper()
    if code.startswith("SH"):
        return "SH"
    if code.startswith("SZ"):
        return "SZ"
    if code.startswith("CSI"):
        return "CSI"
    if code.startswith(("HK", "NDX", "SP500", "GDAXI", "HSFML")):
        return "OFFSHORE"
    return guess_market(code, "INDEX")


def _etf_market(etf_code: str, raw_market: Any) -> str | None:
    market = guess_market(etf_code, "ETF")
    if market:
        return market
    try:
        return {1: "SH", 0: "SZ"}.get(int(raw_market))
    except (TypeError, ValueError):
        return None


def pool_rows(pool: Sequence[dict[str, Any]], fetched_at: datetime | None = None) -> list[dict[str, Any]]:
    """指数池 → ``md_instrument`` 行（指数 + 代表 ETF）。"""
    stamp = fetched_at or now_beijing()
    rows: dict[str, dict[str, Any]] = {}
    for entry in pool:
        index_code = clean_text(entry.get("indexCode"))
        if not index_code:
            continue
        index_code = index_code.upper()
        index_key = asset_instrument_code("INDEX", index_code)
        rows[index_key] = {
            "instrument_code": index_key,
            "asset_type": "INDEX",
            "market": index_market(index_code),
            "raw_code": truncate(index_code, 32),
            "name": truncate(clean_text(entry.get("indexName")), 100),
            "aliases": truncate(clean_text(entry.get("tracking")), 500),
            "industry": None,
            "industry_code": None,
            "tracking_index_code": None,
            "list_date": None,
            "status": "ACTIVE",
            "source": SOURCE_POOL,
            "fetched_at": stamp,
        }

        etf_code = clean_text(entry.get("etfCode"))
        if not etf_code:
            continue
        etf_code = etf_code.upper()
        etf_key = asset_instrument_code("ETF", etf_code)
        rows[etf_key] = {
            "instrument_code": etf_key,
            "asset_type": "ETF",
            "market": _etf_market(etf_code, entry.get("market")),
            "raw_code": truncate(etf_code, 32),
            "name": truncate(clean_text(entry.get("etfName")) or clean_text(entry.get("indexName")), 100),
            "aliases": truncate(clean_text(entry.get("tracking")), 500),
            "industry": None,
            "industry_code": None,
            "tracking_index_code": index_key,
            "list_date": None,
            "status": "ACTIVE",
            "source": SOURCE_POOL,
            "fetched_at": stamp,
        }
    return list(rows.values())


def quote_rows(quotes: Iterable[dict[str, Any]], fetched_at: datetime | None = None) -> list[dict[str, Any]]:
    """行情里的股票 → ``md_instrument`` 行。ETF/指数不从这里写入。"""
    stamp = fetched_at or now_beijing()
    rows = []
    for quote in quotes:
        if not quote.get("raw_code"):
            continue
        row = quote_to_instrument(quote)
        row["name"] = truncate(row.get("name"), 100)
        row["industry"] = truncate(row.get("industry"), 100)
        row["fetched_at"] = stamp
        rows.append(row)
    return rows


def upsert_instruments(db: WarehouseDb, rows: Sequence[dict[str, Any]]) -> int:
    return db.upsert("md_instrument", rows, ["instrument_code"])


def sync_index_pool(db: WarehouseDb, config: Config, fetched_at: datetime | None = None) -> SyncOutcome:
    try:
        pool = load_index_pool(config)
    except Exception as exc:
        return SyncOutcome("FAILED", source=SOURCE_POOL, error=f"指数池读取失败: {exc}")
    rows = pool_rows(pool, fetched_at)
    if not rows:
        return SyncOutcome("EMPTY", source=SOURCE_POOL, error="指数池没有可用条目")
    written = upsert_instruments(db, rows)
    return SyncOutcome("OK", rows=written, source=SOURCE_POOL, detail={"poolSize": len(pool)})


def sync_quote_instruments(db: WarehouseDb, quotes: Sequence[dict[str, Any]],
                           fetched_at: datetime | None = None) -> SyncOutcome:
    rows = quote_rows(quotes, fetched_at)
    if not rows:
        return SyncOutcome("EMPTY", source=SOURCE_QUOTE)
    return SyncOutcome("OK", rows=upsert_instruments(db, rows), source=SOURCE_QUOTE)


__all__ = [
    "INSTRUMENT_COLUMNS",
    "SOURCE_POOL",
    "index_market",
    "load_index_pool",
    "pool_rows",
    "quote_rows",
    "sync_index_pool",
    "sync_quote_instruments",
    "upsert_instruments",
]
