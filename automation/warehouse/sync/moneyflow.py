# -*- coding: utf-8 -*-
"""资金流同步（``md_moneyflow_daily``）。

东财 ``fflow/daykline`` 一次可取 260 个交易日。字段单位是**元**，``main_ratio``
是百分比，均按源原样存；不要在这里做「5 日累计」，那是规则层的事，存原始日值即可。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Sequence

from ..db import WarehouseDb
from ..normalize import now_beijing
from ..sources.eastmoney import SOURCE_MONEYFLOW, EastmoneyClient
from . import SyncOutcome

logger = logging.getLogger(__name__)

MONEYFLOW_COLUMNS = (
    "stock_code",
    "trade_date",
    "main_net",
    "super_net",
    "large_net",
    "medium_net",
    "small_net",
    "main_ratio",
    "source",
    "fetched_at",
)


def moneyflow_rows(stock_code: str, rows: Sequence[dict], fetched_at: datetime) -> list[dict]:
    result = []
    for row in rows:
        if row.get("trade_date") is None:
            continue
        if all(row.get(key) is None for key in (
            "main_net", "super_net", "large_net", "medium_net", "small_net", "main_ratio"
        )):
            continue
        item = {column: row.get(column) for column in MONEYFLOW_COLUMNS}
        item["stock_code"] = stock_code
        item["source"] = SOURCE_MONEYFLOW
        item["fetched_at"] = fetched_at
        result.append(item)
    return result


def sync_stock_moneyflow(
    db: WarehouseDb,
    eastmoney: EastmoneyClient,
    stock_code: str,
    *,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    stamp = fetched_at or now_beijing()
    rows = eastmoney.fetch_moneyflow(stock_code)
    if not rows:
        return SyncOutcome("EMPTY", source=SOURCE_MONEYFLOW)
    written = db.upsert(
        "md_moneyflow_daily",
        moneyflow_rows(stock_code, rows, stamp),
        ["stock_code", "trade_date", "source"],
    )
    return SyncOutcome("OK", rows=written, source=SOURCE_MONEYFLOW)


__all__ = ["MONEYFLOW_COLUMNS", "moneyflow_rows", "sync_stock_moneyflow"]
