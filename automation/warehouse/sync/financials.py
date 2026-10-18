# -*- coding: utf-8 -*-
"""财务报告同步（``md_financial_report``）。

四张东财报表分开取、合并写。**单张失败不拖垮其余三张**：结果标 ``PARTIAL``，
页面能用的字段照常显示，缺的字段保持 NULL。

``available_from`` 取「公告日与报告期较晚者」，防止拿当时还没公告的财报做判断
（前视偏差）。公告日缺失时退回报告期本身——宁可保守，不要用一个可能更早的日期。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Sequence

from ..db import WarehouseDb
from ..normalize import now_beijing
from ..sources.eastmoney import SOURCE_FINANCIAL, EastmoneyClient
from . import SyncOutcome

logger = logging.getLogger(__name__)

FINANCIAL_COLUMNS = (
    "stock_code",
    "report_date",
    "report_type",
    "announce_date",
    "available_from",
    "revenue",
    "revenue_yoy",
    "net_profit",
    "net_profit_yoy",
    "roe",
    "gross_margin",
    "net_margin",
    "ocf",
    "total_assets",
    "total_equity",
    "total_liabilities",
    "current_assets",
    "current_liabilities",
    "interest_expense",
    "ebit",
    "goodwill",
    "accounts_receivable",
    "source",
    "fetched_at",
)


def financial_rows(stock_code: str, reports: Sequence[dict], fetched_at: datetime) -> list[dict]:
    rows = []
    for report in reports:
        if not report.get("report_date") or not report.get("report_type"):
            continue
        row = {column: report.get(column) for column in FINANCIAL_COLUMNS}
        row["stock_code"] = stock_code
        row["source"] = SOURCE_FINANCIAL
        row["fetched_at"] = fetched_at
        rows.append(row)
    return rows


def sync_stock_financials(
    db: WarehouseDb,
    eastmoney: EastmoneyClient,
    stock_code: str,
    *,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    stamp = fetched_at or now_beijing()
    reports, errors = eastmoney.fetch_financial_reports(stock_code)
    if not reports:
        return SyncOutcome(
            "FAILED" if errors else "EMPTY",
            source=SOURCE_FINANCIAL,
            error="; ".join(errors)[:1000] or None,
            detail={"errors": errors},
        )
    written = db.upsert(
        "md_financial_report",
        financial_rows(stock_code, reports, stamp),
        ["stock_code", "report_date", "report_type", "source"],
    )
    return SyncOutcome(
        "PARTIAL" if errors else "OK",
        rows=written,
        source=SOURCE_FINANCIAL,
        error="; ".join(errors)[:1000] or None,
        detail={"errors": errors, "reports": len(reports)},
    )


__all__ = ["FINANCIAL_COLUMNS", "financial_rows", "sync_stock_financials"]
