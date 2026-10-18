# -*- coding: utf-8 -*-
"""个股估值历史同步（``md_valuation_daily``）。

个股 PE/PB 只有东财 ``RPT_VALUEANALYSIS_DET`` 一条来源，2018 年至今约 2100 行。
**不要用行情接口的 f115 拼历史**：那是当日快照值，和历史序列不是同一口径；一旦混用，
同一只票会出现两条 PE 曲线，分位也失去意义。

指数 PE 历史不在这里：由 16:30 的 poller 走 ``index_pool_sync.py`` 写入
``market_valuation_history``，warehouse 不重复抓。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Sequence

from ..db import WarehouseDb
from ..normalize import asset_instrument_code, now_beijing
from ..sources.eastmoney import SOURCE_VALUATION, EastmoneyClient
from . import SyncOutcome

logger = logging.getLogger(__name__)

VALUATION_COLUMNS = (
    "instrument_code",
    "trade_date",
    "pe_ttm",
    "pb_mrq",
    "ps_ttm",
    "dividend_yield",
    "close_price",
    "percentile_method",
    "source",
    "fetched_at",
)


def valuation_rows(stock_code: str, rows: Sequence[dict], fetched_at: datetime) -> list[dict]:
    instrument_code = asset_instrument_code("STOCK", stock_code)
    result = []
    for row in rows:
        if all(row.get(key) is None for key in ("pe_ttm", "pb_mrq", "ps_ttm", "close_price")):
            continue
        result.append({
            "instrument_code": instrument_code,
            "trade_date": row["trade_date"],
            "pe_ttm": row.get("pe_ttm"),
            "pb_mrq": row.get("pb_mrq"),
            "ps_ttm": row.get("ps_ttm"),
            "dividend_yield": None,
            "close_price": row.get("close_price"),
            # 个股分位由 Java 规则层按同一序列现算，仓库不预存一个可能过期的分位。
            "percentile_method": None,
            "source": SOURCE_VALUATION,
            "fetched_at": fetched_at,
        })
    return result


def sync_stock_valuation(
    db: WarehouseDb,
    eastmoney: EastmoneyClient,
    stock_code: str,
    *,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    stamp = fetched_at or now_beijing()
    rows = eastmoney.fetch_valuation_history(stock_code)
    if not rows:
        return SyncOutcome("EMPTY", source=SOURCE_VALUATION)
    prepared = valuation_rows(stock_code, rows, stamp)
    written = db.upsert(
        "md_valuation_daily",
        prepared,
        ["instrument_code", "trade_date", "source"],
    )
    return SyncOutcome("OK", rows=written, source=SOURCE_VALUATION)


__all__ = ["VALUATION_COLUMNS", "sync_stock_valuation", "valuation_rows"]
