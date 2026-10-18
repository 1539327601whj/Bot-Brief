# -*- coding: utf-8 -*-
"""行业板块与成分（``md_industry_board`` / ``md_industry_constituent``）。

板块列表每天刷新一次，成本只有一次 clist 请求；**成分按需拉取**，不做全市场每日回填
（一次全量要几十到上百次请求，对按 IP 限流的东财不划算）。

行业板块没有「数据日期」字段，成分的 ``as_of`` 只能用**抓取日**。这是如实的：
它表示「这一天看到的成分」，不是交易所公布的调整日。
"""

from __future__ import annotations

import logging
from datetime import datetime

from ..db import WarehouseDb
from ..normalize import now_beijing, today_beijing
from ..sources.eastmoney import (
    SOURCE_BOARD,
    SOURCE_INDUSTRY_CONSTITUENT,
    EastmoneyClient,
)
from . import SyncOutcome

logger = logging.getLogger(__name__)

BOARD_COLUMNS = ("board_code", "name", "source", "fetched_at")

CONSTITUENT_COLUMNS = (
    "board_code",
    "stock_code",
    "stock_name",
    "as_of",
    "source",
    "fetched_at",
)


def sync_industry_boards(
    db: WarehouseDb,
    eastmoney: EastmoneyClient,
    *,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    stamp = fetched_at or now_beijing()
    boards = eastmoney.fetch_industry_boards()
    if not boards:
        return SyncOutcome("EMPTY", source=SOURCE_BOARD)
    rows = [
        {
            "board_code": board["board_code"],
            "name": board["name"],
            "source": SOURCE_BOARD,
            "fetched_at": stamp,
        }
        for board in boards
    ]
    written = db.upsert("md_industry_board", rows, ["board_code"])
    return SyncOutcome("OK", rows=written, source=SOURCE_BOARD)


def sync_industry_constituents(
    db: WarehouseDb,
    eastmoney: EastmoneyClient,
    board_code: str,
    *,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    stamp = fetched_at or now_beijing()
    members = eastmoney.fetch_industry_constituents(board_code)
    if not members:
        return SyncOutcome("EMPTY", source=SOURCE_INDUSTRY_CONSTITUENT)
    as_of = today_beijing()
    rows = [
        {
            "board_code": board_code.upper(),
            "stock_code": member["stock_code"],
            "stock_name": member.get("stock_name"),
            "as_of": as_of,
            "source": SOURCE_INDUSTRY_CONSTITUENT,
            "fetched_at": stamp,
        }
        for member in members
    ]
    written = db.upsert(
        "md_industry_constituent", rows,
        ["board_code", "stock_code", "as_of", "source"],
    )
    return SyncOutcome("OK", rows=written, source=SOURCE_INDUSTRY_CONSTITUENT,
                       detail={"asOf": as_of.isoformat()})


__all__ = [
    "BOARD_COLUMNS",
    "CONSTITUENT_COLUMNS",
    "sync_industry_boards",
    "sync_industry_constituents",
]
