# -*- coding: utf-8 -*-
"""新闻 / 公告 / 政策落库（``md_event``）。

三路源彼此独立；一路失败不影响另外两路。事件**只存事实**：标题、日期、链接、
来源、分类。``polarity`` 一律写 ``NEUTRAL``——事件极性由 Java 规则层在读取时判定，
这样规则版本升级后可以重新分类，不用重新抓取。

``content_hash`` 是唯一键，重复抓取天然幂等。``RateLimited`` 在三路都尝试过后
才向上抛：已经写进去的事件保留，任务层据此写冷却并重排。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any

from ..db import WarehouseDb
from ..normalize import now_beijing
from ..rate_limit import RateLimited
from ..sources.news import NewsClients, event_row
from . import SyncOutcome, combine

logger = logging.getLogger(__name__)

EVENT_COLUMNS = (
    "instrument_code",
    "category",
    "event_type",
    "title",
    "summary",
    "url",
    "published_at",
    "source",
    "importance",
    "polarity",
    "content_hash",
    "fetched_at",
)


def sync_events(
    db: WarehouseDb,
    news: NewsClients,
    *,
    instrument_code: str | None,
    keyword: str | None = None,
    stock_code: str | None = None,
    policy_subject: str | None = None,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    stamp = fetched_at or now_beijing()
    parts: dict[str, SyncOutcome] = {}
    items: dict[str, dict[str, Any]] = {}
    limited: RateLimited | None = None

    def collect(route: str, loader) -> None:
        nonlocal limited
        try:
            found = loader()
        except RateLimited as exc:
            parts[route] = SyncOutcome("FAILED", error=str(exc))
            limited = exc
            return
        except Exception as exc:  # noqa: BLE001 - 单路失败隔离
            parts[route] = SyncOutcome("FAILED", error=str(exc))
            return
        for item in found:
            items.setdefault(
                str(item.get("url")) + "|" + str(item.get("title")),
                item,
            )
        parts[route] = SyncOutcome("OK" if found else "EMPTY", rows=len(found))

    if keyword:
        collect("news", lambda: news.search_news(keyword))
    if stock_code:
        # ETF 公告恒为空（东财接口只覆盖股票），这是正常结果，不是失败。
        collect("notice", lambda: news.fetch_notices(stock_code))
    if policy_subject:
        collect("policy", lambda: news.search_policy(policy_subject))

    written = 0
    if items:
        rows = [event_row(item, instrument_code, stamp) for item in items.values()]
        try:
            written = db.upsert("md_event", rows, ["content_hash"])
            parts["write"] = SyncOutcome("OK", rows=written)
        except Exception as exc:  # noqa: BLE001
            parts["write"] = SyncOutcome("FAILED", error=str(exc))

    outcome = combine("EVENTS", parts)
    outcome.rows = written
    if limited is not None:
        # 已经写入的部分保留；抛给任务层写冷却。
        raise limited
    return outcome


__all__ = ["EVENT_COLUMNS", "sync_events"]
