# -*- coding: utf-8 -*-
"""个股详情同步（用户查询触发的 ``STOCK_DETAIL``）。

核心链路（行情 / 日线 / PE 历史）由 Java ``OnDemandSyncService`` 在请求预算内同步做，
不走 Python 队列；这里只补「慢且大」的部分：财务、资金流、行业归属、事件。

**分段隔离**：财务接口挂掉不影响资金流，资金流挂掉也不影响已经写入的财务。
段状态最终合成 ``OK/PARTIAL/FAILED`` 返回给任务层。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any

from ..db import WarehouseDb
from ..normalize import asset_instrument_code, eastmoney_secid, guess_market, now_beijing
from ..rate_limit import RateLimited
from ..sources.eastmoney import EastmoneyClient
from ..sources.news import NewsClients
from . import SyncOutcome, combine
from .events import sync_events
from .financials import sync_stock_financials
from .instruments import sync_quote_instruments
from .moneyflow import sync_stock_moneyflow

logger = logging.getLogger(__name__)


def sync_stock_detail(
    db: WarehouseDb,
    eastmoney: EastmoneyClient,
    news: NewsClients,
    stock_code: str,
    *,
    name: str | None = None,
    with_events: bool = True,
    fetched_at: datetime | None = None,
) -> SyncOutcome:
    stamp = fetched_at or now_beijing()
    parts: dict[str, SyncOutcome] = {}
    limited: RateLimited | None = None

    def safe(loader) -> SyncOutcome:
        """单段失败只记录；限流也先记下，等所有段跑完再抛给任务层。

        财务走 datacenter-web、资金流走 push2，是**不同的域名族**；
        一个被限流不代表另一个也挂，丢掉能拿的那一段是纯损失。
        """
        nonlocal limited
        try:
            return loader()
        except RateLimited as exc:
            limited = exc
            return SyncOutcome("FAILED", error=str(exc))
        except Exception as exc:  # noqa: BLE001 - 单段失败隔离
            return SyncOutcome("FAILED", error=str(exc))

    parts["financials"] = safe(lambda: sync_stock_financials(
        db, eastmoney, stock_code, fetched_at=stamp
    ))
    parts["moneyflow"] = safe(lambda: sync_stock_moneyflow(
        db, eastmoney, stock_code, fetched_at=stamp
    ))

    industry: str | None = None
    try:
        market = guess_market(stock_code, "STOCK")
        quotes = eastmoney.fetch_quotes([eastmoney_secid("STOCK", stock_code, market)])
        quote = next((item for item in quotes if item.get("raw_code") == stock_code.upper()), None)
        if quote is None:
            parts["instrument"] = SyncOutcome("EMPTY")
        else:
            industry = quote.get("industry")
            parts["instrument"] = sync_quote_instruments(db, quotes, stamp)
    except RateLimited as exc:
        parts["instrument"] = SyncOutcome("FAILED", error=str(exc))
        limited = exc
    except Exception as exc:  # noqa: BLE001
        parts["instrument"] = SyncOutcome("FAILED", error=str(exc))

    if with_events:
        instrument_code = asset_instrument_code("STOCK", stock_code)
        try:
            parts["events"] = sync_events(
                db, news,
                instrument_code=instrument_code,
                keyword=name,
                stock_code=stock_code,
                policy_subject=industry,
                fetched_at=stamp,
            )
        except RateLimited as exc:
            parts["events"] = SyncOutcome("FAILED", error=str(exc))
            limited = exc

    outcome = combine(f"STOCK_DETAIL:{stock_code}", parts)
    outcome.detail["industry"] = industry
    if limited is not None:
        # 已写入的段保留；任务层据此进入冷却。
        raise limited
    return outcome


__all__ = ["sync_stock_detail"]
