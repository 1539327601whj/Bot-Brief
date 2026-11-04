# -*- coding: utf-8 -*-
"""A 股标的目录（``md_instrument``）。

每日全市场快照只取总市值前 ``WAREHOUSE_UNIVERSE_SIZE``（默认 3000）只，剩下的中小盘股
在本地库里没有名字：名称搜索只能落到腾讯 smartbox，smartbox 不可用或限流时就搜不到。
这里用同一个 `datacenter-web` 清单接口补全 A 股身份，**只取三列、不取行情**，
比把快照上限整体抬高便宜得多，也不影响筛选池。

与 ETF 目录一样只补新、不覆盖：已有行的行业、别名、上市日期来自快照/指数池，
不能被目录的空字段冲掉。北交所在 :meth:`EastmoneyClient.fetch_stock_catalog` 里已跳过。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Iterable

from ..db import WarehouseDb
from ..normalize import asset_instrument_code, now_beijing, truncate
from ..sources.eastmoney import EastmoneyClient
from . import SyncOutcome
from .instruments import upsert_instruments

logger = logging.getLogger(__name__)

SOURCE_STOCK_CATALOG = "eastmoney-stock-catalog"


def stock_rows(items: Iterable[dict[str, Any]],
               fetched_at: datetime | None = None) -> list[dict[str, Any]]:
    stamp = fetched_at or now_beijing()
    rows: list[dict[str, Any]] = []
    for item in items:
        raw_code = str(item.get("raw_code") or "").strip().upper()
        name = item.get("name")
        market = item.get("market")
        if not raw_code or not name or market not in ("SH", "SZ"):
            continue
        rows.append({
            "instrument_code": asset_instrument_code("STOCK", raw_code),
            "asset_type": "STOCK",
            "market": market,
            "raw_code": raw_code,
            "name": truncate(name, 100),
            "aliases": None,
            "industry": None,
            "industry_code": None,
            "tracking_index_code": None,
            "list_date": None,
            "status": "ACTIVE",
            "source": SOURCE_STOCK_CATALOG,
            "fetched_at": stamp,
        })
    return rows


def existing_stock_codes(db: WarehouseDb) -> set[str]:
    rows = db.query_all(
        "SELECT raw_code FROM md_instrument WHERE asset_type = 'STOCK'"
    )
    return {
        str(row.get("raw_code") or "").strip().upper()
        for row in rows
        if row.get("raw_code")
    }


def sync_stock_catalog(
    db: WarehouseDb,
    eastmoney: EastmoneyClient,
    *,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    stamp = fetched_at or now_beijing()
    items = eastmoney.fetch_stock_catalog()
    if not items:
        return SyncOutcome("EMPTY", source=SOURCE_STOCK_CATALOG, error="A 股目录为空")

    existing = existing_stock_codes(db)
    all_rows = stock_rows(items, stamp)
    new_rows = [row for row in all_rows if row["raw_code"] not in existing]
    written = upsert_instruments(db, new_rows) if new_rows else 0
    return SyncOutcome(
        "OK",
        rows=written,
        source=SOURCE_STOCK_CATALOG,
        detail={
            "fetched": len(items),
            "known": len(existing),
            "new": len(new_rows),
        },
    )


__all__ = [
    "SOURCE_STOCK_CATALOG",
    "existing_stock_codes",
    "stock_rows",
    "sync_stock_catalog",
]
