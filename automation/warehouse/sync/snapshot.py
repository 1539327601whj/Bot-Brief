# -*- coding: utf-8 -*-
"""全市场估值快照（每日 17:00）。

一次 `clist` 分页 + 批量 `ulist` 的产物直接落两处：

* ``md_instrument``：名称、行业（东财 ``f100``，本项目唯一的行业口径）；
* ``md_valuation_daily``：当日 PE/PB/股息率/收盘价，用于行业分位与同行对比。

**交易日不猜。** 从行情的 ``f124`` 时间戳推；整批都没有时间戳时任务直接失败，
而不是写一个「今天」——节假日写错日期会污染整段分位历史。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Sequence

from ..db import WarehouseDb
from ..normalize import asset_instrument_code, now_beijing
from ..sources.eastmoney import EastmoneyClient, quote_trade_date
from . import SyncOutcome
from .instruments import sync_quote_instruments

logger = logging.getLogger(__name__)

SOURCE_SNAPSHOT = "eastmoney-quote-snapshot"


def valuation_rows(quotes: Sequence[dict[str, Any]], trade_date, fetched_at: datetime) -> list[dict]:
    rows = []
    for quote in quotes:
        raw_code = quote.get("raw_code")
        if not raw_code:
            continue
        if all(quote.get(key) is None for key in ("price", "pe_ttm", "pb", "dividend_yield")):
            continue
        rows.append({
            "instrument_code": asset_instrument_code("STOCK", raw_code),
            "trade_date": trade_date,
            "pe_ttm": quote.get("pe_ttm"),
            "pb_mrq": quote.get("pb"),
            "ps_ttm": None,
            "dividend_yield": quote.get("dividend_yield"),
            "close_price": quote.get("price"),
            "percentile_method": None,
            "source": SOURCE_SNAPSHOT,
            "fetched_at": fetched_at,
        })
    return rows


def sync_market_snapshot(
    db: WarehouseDb,
    eastmoney: EastmoneyClient,
    *,
    max_count: int | None = None,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    stamp = fetched_at or now_beijing()
    quotes = eastmoney.fetch_universe(max_count)
    if not quotes:
        return SyncOutcome("EMPTY", source=SOURCE_SNAPSHOT)

    trade_date = quote_trade_date(quotes)
    if trade_date is None:
        return SyncOutcome(
            "FAILED", source=SOURCE_SNAPSHOT,
            error="行情没有返回时间戳，无法确定交易日；不写快照",
        )

    instruments = sync_quote_instruments(db, quotes, stamp)
    rows = valuation_rows(quotes, trade_date, stamp)
    written = db.upsert(
        "md_valuation_daily",
        rows,
        ["instrument_code", "trade_date", "source"],
    ) if rows else 0
    return SyncOutcome(
        "OK",
        rows=written,
        source=SOURCE_SNAPSHOT,
        detail={
            "tradeDate": trade_date.isoformat(),
            "quotes": len(quotes),
            "instruments": instruments.rows,
        },
    )


__all__ = ["SOURCE_SNAPSHOT", "sync_market_snapshot", "valuation_rows"]
