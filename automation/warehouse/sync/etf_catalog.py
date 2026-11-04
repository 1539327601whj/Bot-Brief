# -*- coding: utf-8 -*-
"""ETF 标的目录（``md_instrument``）。

指数池只覆盖 42 只代表 ETF，用户搜索池外 ETF（例如 588000、159915）时本地库没有名字，
输入框就只能靠腾讯 smartbox 在线兜底；smartbox 不可用时等于搜不到。这里用新浪行情中心的
ETF 节点把沪深全部挂牌 ETF 的身份补进母表，**不碰东财 clist**。

每日只做「补新」，不覆盖已有行：指数池写入的 ``tracking_index_code`` / ``aliases`` 以及
更完整的名称不能被新浪简称冲掉。目录是搜索用的，不是行情快照——不写价格、不写规模。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Iterable, Sequence

from ..db import WarehouseDb
from ..normalize import asset_instrument_code, now_beijing, truncate
from ..sources.sina import SinaClient
from . import SyncOutcome
from .instruments import upsert_instruments

logger = logging.getLogger(__name__)

SOURCE_ETF_CATALOG = "sina-etf-list"


def etf_rows(items: Iterable[dict[str, Any]],
             fetched_at: datetime | None = None) -> list[dict[str, Any]]:
    """新浪目录 → ``md_instrument`` 行。身份之外的字段一律留空，让已有值保留。"""
    stamp = fetched_at or now_beijing()
    rows: list[dict[str, Any]] = []
    for item in items:
        raw_code = str(item.get("raw_code") or "").strip().upper()
        name = item.get("name")
        market = item.get("market")
        if not raw_code or not name or not market:
            continue
        rows.append({
            "instrument_code": asset_instrument_code("ETF", raw_code),
            "asset_type": "ETF",
            "market": market,
            "raw_code": raw_code,
            "name": truncate(name, 100),
            "aliases": None,
            "industry": None,
            "industry_code": None,
            "tracking_index_code": None,
            "list_date": None,
            "status": "ACTIVE",
            "source": SOURCE_ETF_CATALOG,
            "fetched_at": stamp,
        })
    return rows


def existing_etf_codes(db: WarehouseDb) -> set[str]:
    rows = db.query_all(
        "SELECT raw_code FROM md_instrument WHERE asset_type = 'ETF'"
    )
    return {
        str(row.get("raw_code") or "").strip().upper()
        for row in rows
        if row.get("raw_code")
    }


def sync_etf_catalog(
    db: WarehouseDb,
    sina: SinaClient,
    *,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    stamp = fetched_at or now_beijing()
    items = sina.fetch_etf_catalog()
    if not items:
        return SyncOutcome("EMPTY", source=SOURCE_ETF_CATALOG, error="新浪 ETF 目录为空")

    existing = existing_etf_codes(db)
    all_rows = etf_rows(items, stamp)
    new_rows = [row for row in all_rows if row["raw_code"] not in existing]
    written = upsert_instruments(db, new_rows) if new_rows else 0
    return SyncOutcome(
        "OK",
        rows=written,
        source=SOURCE_ETF_CATALOG,
        detail={
            "fetched": len(items),
            "known": len(existing),
            "new": len(new_rows),
        },
    )


__all__ = [
    "SOURCE_ETF_CATALOG",
    "etf_rows",
    "existing_etf_codes",
    "sync_etf_catalog",
]
