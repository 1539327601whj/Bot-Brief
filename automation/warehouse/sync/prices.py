# -*- coding: utf-8 -*-
"""日线同步（``md_price_daily``）。

**一条序列只允许一个 source。** 这条规则的实现方式是先查库：已经有
``eastmoney-kline`` 的标的，就不再用腾讯补；反过来也一样。跨源拼接会造出两条
同日期的序列（唯一键含 ``source``），页面上会出现「同一只票两套 OHLC」。

东财被限流时**不降级到腾讯**：限流是暂时的，退到腾讯会把一条缺 ``amount`` /
``turnover`` 的序列永久钉死（旧行不会被新源覆盖）。只有东财明确返回空/不可用，
才启用腾讯兜底；新浪不在日线链路里（只有行情）。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Sequence

from ..db import WarehouseDb
from ..normalize import now_beijing
from ..rate_limit import RateLimited, SourceError, SourceUnavailable
from ..sources.eastmoney import SOURCE_KLINE as EM_KLINE, EastmoneyClient
from ..sources.tencent import SOURCE_KLINE as TX_KLINE, TencentClient
from . import SyncOutcome

logger = logging.getLogger(__name__)

PRICE_COLUMNS = (
    "instrument_code",
    "trade_date",
    "adj_type",
    "open_price",
    "high_price",
    "low_price",
    "close_price",
    "volume",
    "amount",
    "turnover_rate",
    "pct_change",
    "source",
    "fetched_at",
)


def existing_sources(db: WarehouseDb, instrument_code: str, adj_type: str = "QFQ") -> list[str]:
    rows = db.query_all(
        "SELECT DISTINCT source FROM md_price_daily "
        "WHERE instrument_code = %s AND adj_type = %s",
        (instrument_code, adj_type),
    )
    return [str(row.get("source")) for row in rows if row.get("source")]


def _rows_for(instrument_code: str, rows: Sequence[dict], source: str,
              fetched_at: datetime) -> list[dict]:
    result = []
    for row in rows:
        item = {column: None for column in PRICE_COLUMNS}
        item.update({key: row.get(key) for key in PRICE_COLUMNS if key in row})
        item["instrument_code"] = instrument_code
        item["source"] = source
        item["fetched_at"] = fetched_at
        if item.get("close_price") is None or item["close_price"] <= 0:
            continue
        result.append(item)
    return result


def sync_price_series(
    db: WarehouseDb,
    eastmoney: EastmoneyClient,
    tencent: TencentClient,
    *,
    instrument_code: str,
    asset_type: str,
    raw_code: str,
    market: str | None = None,
    adj_type: str = "QFQ",
    limit: int | None = None,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    stamp = fetched_at or now_beijing()
    locked = existing_sources(db, instrument_code, adj_type)
    keys = ["instrument_code", "trade_date", "adj_type", "source"]

    def write(rows, source: str) -> SyncOutcome:
        # 源可能返回了行，但全部因为缺收盘价被过滤掉；这时按 EMPTY 处理，
        # 不要拿空列表去 upsert（会得到一个「成功但 0 行」的假象）。
        prepared = _rows_for(instrument_code, rows, source, stamp)
        if not prepared:
            return SyncOutcome("EMPTY", source=source)
        return SyncOutcome("OK", rows=db.upsert("md_price_daily", prepared, keys), source=source)

    if EM_KLINE in locked:
        return write(
            eastmoney.fetch_kline(asset_type, raw_code, market, limit=limit, adj_type=adj_type),
            EM_KLINE,
        )

    if TX_KLINE in locked:
        return write(
            tencent.fetch_kline(raw_code, market, limit=limit, adj_type=adj_type),
            TX_KLINE,
        )

    # 新序列：东财优先。
    try:
        rows = eastmoney.fetch_kline(asset_type, raw_code, market, limit=limit, adj_type=adj_type)
    except RateLimited:
        # 不降级，交给任务层写冷却后重排。
        raise
    except (SourceUnavailable, SourceError) as exc:
        logger.info("东财日线不可用，尝试腾讯兜底 %s: %s", instrument_code, exc)
        rows = []
    if rows:
        return write(rows, EM_KLINE)

    try:
        rows = tencent.fetch_kline(raw_code, market, limit=limit, adj_type=adj_type)
    except RateLimited:
        raise
    except (SourceUnavailable, SourceError) as exc:
        return SyncOutcome("FAILED", source=TX_KLINE, error=f"东财与腾讯日线均不可用: {exc}")
    return write(rows, TX_KLINE)


__all__ = ["PRICE_COLUMNS", "existing_sources", "sync_price_series"]
