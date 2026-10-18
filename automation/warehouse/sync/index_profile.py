# -*- coding: utf-8 -*-
"""指数画像、前十大成分、行业权重（``md_index_profile`` / ``md_index_constituent`` /
``md_index_industry_weight``）。

**不抓指数 PE 历史**：那由 16:30 的 poller 写入 ``market_valuation_history``。
这里只补中证官网的「画像」类数据。csindex 系画像的 ``pe_ttm`` 保持 NULL——
不是漏了，是刻意不与 poller 抢同一份数据；danjuan 系指数直接吃蛋卷目录的当前值
（一次请求回全部指数，不按指数逐个调用）。

``index_code`` 统一用池子里的 ``indexCode``（``SH000300`` / ``SZ399006`` / ``CSI930901``），
与 ``md_instrument.raw_code`` 一致；``csindex_code`` 只用于拼中证官网 URL。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Sequence

from ..db import WarehouseDb
from ..normalize import now_beijing
from ..sources.csindex import (
    SOURCE_INDUSTRY,
    SOURCE_PROFILE,
    SOURCE_TOP10,
    CsindexClient,
)
from ..sources.danjuan import SOURCE_ESTIMATE, DanjuanClient
from . import SyncOutcome, combine

logger = logging.getLogger(__name__)

SOURCE_CSINDEX_PROFILE = "csindex"
SOURCE_DANJUAN_PROFILE = SOURCE_ESTIMATE

PROFILE_COLUMNS = (
    "index_code",
    "as_of",
    "name",
    "constituent_count",
    "top10_weight",
    "pe_ttm",
    "dividend_yield",
    "total_market_cap",
    "tracking_index_code",
    "source",
    "fetched_at",
)

CONSTITUENT_COLUMNS = (
    "index_code",
    "as_of",
    "stock_code",
    "stock_name",
    "weight",
    "source",
    "fetched_at",
)

INDUSTRY_WEIGHT_COLUMNS = (
    "index_code",
    "as_of",
    "industry_code",
    "industry_name",
    "weight",
    "source",
    "fetched_at",
)


def _profile_row(index_code: str, as_of, source: str, fetched_at: datetime, **fields: Any) -> dict:
    row = {column: None for column in PROFILE_COLUMNS}
    row.update({
        "index_code": index_code,
        "as_of": as_of,
        "tracking_index_code": None,
        "source": source,
        "fetched_at": fetched_at,
    })
    for key, value in fields.items():
        if key in row:
            row[key] = value
    return row


def _constituent_rows(index_code: str, as_of, rows: Sequence[dict],
                      fetched_at: datetime) -> list[dict]:
    result = []
    for item in rows:
        if not item.get("stock_code"):
            continue
        row = {column: item.get(column) for column in CONSTITUENT_COLUMNS}
        row.update({
            "index_code": index_code,
            "as_of": as_of,
            "source": SOURCE_TOP10,
            "fetched_at": fetched_at,
        })
        result.append(row)
    return result


def _industry_rows(index_code: str, as_of, rows: Sequence[dict],
                   fetched_at: datetime) -> list[dict]:
    result = []
    for item in rows:
        if not item.get("industry_code"):
            continue
        row = {column: item.get(column) for column in INDUSTRY_WEIGHT_COLUMNS}
        row.update({
            "index_code": index_code,
            "as_of": as_of,
            "source": SOURCE_INDUSTRY,
            "fetched_at": fetched_at,
        })
        result.append(row)
    return result


def sync_csindex_detail(
    db: WarehouseDb,
    csindex: CsindexClient,
    index_code: str,
    csindex_code: str,
    *,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    stamp = fetched_at or now_beijing()
    parts: dict[str, SyncOutcome] = {}

    basic: dict[str, Any] = {}
    try:
        basic = csindex.fetch_basic_info(csindex_code)
        parts["basic"] = SyncOutcome("OK", rows=1, source=SOURCE_PROFILE)
    except Exception as exc:  # noqa: BLE001 - 单段失败要隔离
        parts["basic"] = SyncOutcome("FAILED", source=SOURCE_PROFILE, error=str(exc))

    top10: dict[str, Any] = {}
    try:
        top10 = csindex.fetch_top10(csindex_code)
        rows = _constituent_rows(index_code, top10.get("as_of"), top10.get("rows") or [], stamp)
        written = db.upsert(
            "md_index_constituent", rows,
            ["index_code", "as_of", "stock_code", "source"],
        ) if rows else 0
        parts["top10"] = SyncOutcome(
            "OK" if rows else "EMPTY", rows=written, source=SOURCE_TOP10
        )
    except Exception as exc:  # noqa: BLE001
        parts["top10"] = SyncOutcome("FAILED", source=SOURCE_TOP10, error=str(exc))

    industry: dict[str, Any] = {}
    try:
        industry = csindex.fetch_industry_weight(csindex_code)
        rows = _industry_rows(index_code, industry.get("as_of"), industry.get("rows") or [], stamp)
        written = db.upsert(
            "md_index_industry_weight", rows,
            ["index_code", "as_of", "industry_code", "source"],
        ) if rows else 0
        parts["industry"] = SyncOutcome(
            "OK" if rows else "EMPTY", rows=written, source=SOURCE_INDUSTRY
        )
    except Exception as exc:  # noqa: BLE001
        parts["industry"] = SyncOutcome("FAILED", source=SOURCE_INDUSTRY, error=str(exc))

    as_of = top10.get("as_of") or industry.get("as_of")
    if as_of is not None:
        profile = _profile_row(
            index_code, as_of, SOURCE_CSINDEX_PROFILE, stamp,
            name=basic.get("name"),
            constituent_count=basic.get("constituent_count"),
            top10_weight=top10.get("top10_weight"),
        )
        try:
            db.upsert("md_index_profile", [profile], ["index_code", "as_of", "source"])
            parts["profile"] = SyncOutcome("OK", rows=1, source=SOURCE_CSINDEX_PROFILE)
        except Exception as exc:  # noqa: BLE001
            parts["profile"] = SyncOutcome("FAILED", source=SOURCE_CSINDEX_PROFILE, error=str(exc))
    else:
        parts["profile"] = SyncOutcome(
            "EMPTY", source=SOURCE_CSINDEX_PROFILE, error="权重接口没有返回数据日期，画像不落库"
        )

    return combine(f"INDEX_DETAIL:{index_code}", parts)


def sync_danjuan_profile(
    db: WarehouseDb,
    estimate: dict[str, Any],
    index_code: str,
    *,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    if not estimate or estimate.get("trade_date") is None:
        return SyncOutcome("EMPTY", source=SOURCE_DANJUAN_PROFILE,
                           error="蛋卷估值缺少数据日期，画像不落库")
    stamp = fetched_at or now_beijing()
    profile = _profile_row(
        index_code, estimate["trade_date"], SOURCE_DANJUAN_PROFILE, stamp,
        name=estimate.get("name"),
        pe_ttm=estimate.get("pe_ttm"),
    )
    db.upsert("md_index_profile", [profile], ["index_code", "as_of", "source"])
    return SyncOutcome("OK", rows=1, source=SOURCE_DANJUAN_PROFILE)


def sync_index_detail(
    db: WarehouseDb,
    csindex: CsindexClient,
    danjuan: DanjuanClient,
    *,
    index_code: str,
    csindex_code: str | None,
    danjuan_code: str | None,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    parts: dict[str, SyncOutcome] = {}
    if csindex_code:
        parts["csindex"] = sync_csindex_detail(
            db, csindex, index_code, csindex_code, fetched_at=fetched_at
        )
    if danjuan_code:
        try:
            estimates = {row["index_code"]: row for row in danjuan.fetch_estimates()}
            parts["danjuan"] = sync_danjuan_profile(
                db, estimates.get(danjuan_code.upper(), {}), index_code, fetched_at=fetched_at
            )
        except Exception as exc:  # noqa: BLE001 - 蛋卷失败不影响中证部分
            parts["danjuan"] = SyncOutcome("FAILED", source=SOURCE_ESTIMATE, error=str(exc))
    if not parts:
        return SyncOutcome("SKIPPED", error="该指数没有可用的估值/权重来源")
    return combine(f"INDEX_DETAIL:{index_code}", parts)


__all__ = [
    "CONSTITUENT_COLUMNS",
    "INDUSTRY_WEIGHT_COLUMNS",
    "PROFILE_COLUMNS",
    "SOURCE_CSINDEX_PROFILE",
    "SOURCE_DANJUAN_PROFILE",
    "sync_csindex_detail",
    "sync_danjuan_profile",
    "sync_index_detail",
]
