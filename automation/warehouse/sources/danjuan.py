# -*- coding: utf-8 -*-
"""蛋卷基金指数估值（danjuanfunds.com）。

**一次请求回全部指数**（当前约 63 条），所以永远不要按指数逐个调用。蛋卷只给
「当前 PE + 当前分位」，**没有历史序列**；分位是蛋卷自己按周频算的，和本项目
按日频、10 年滚动窗口算出来的分位不是同一口径。因此：

* ``danjuan`` 来源的指数，分位方法固定写 ``DANJUAN_PE_TTM_PROVIDER``；
* 绝不拿蛋卷 PE 去补 csindex 指数的历史缺口（会变成混源序列）；
* 指数 PE 历史仍由 16:30 的 poller 走 ``index_pool_sync.py`` 写入
  ``market_valuation_history``，warehouse 只把这里的**当前快照**用于画像与交叉核对。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any

from ..config import Config
from ..normalize import BEIJING_OFFSET, clean_text, now_beijing, parse_date, to_number, truncate
from ..rate_limit import HttpSource, SourceError

logger = logging.getLogger(__name__)

PROVIDER = "danjuan"
SOURCE_ESTIMATE = "danjuan-index-eva"

URL = "https://danjuanfunds.com/djapi/index_eva/dj"
HEADERS = {
    "Referer": "https://danjuanfunds.com/djmodule/value-center",
    "Accept": "application/json, text/plain, */*",
}
PERCENTILE_METHOD = "DANJUAN_PE_TTM_PROVIDER"


def _item_date(item: dict[str, Any]) -> Any:
    ts = to_number(item.get("ts"))
    if ts is not None and ts > 0:
        seconds = ts / 1000.0 if ts > 10_000_000_000 else ts
        try:
            return datetime.fromtimestamp(seconds, BEIJING_OFFSET).date()
        except (OverflowError, OSError, ValueError):
            return None
    return parse_date(item.get("date"))


def parse_estimate_items(body: dict[str, Any]) -> list[dict[str, Any]]:
    result_code = str(body.get("result_code", 0))
    if result_code not in ("0", "200"):
        raise SourceError(PROVIDER, f"蛋卷估值业务错误: {result_code} {body.get('result_msg', '')}")
    items = (body.get("data") or {}).get("items") or []
    rows: list[dict[str, Any]] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        code = clean_text(item.get("index_code"))
        if not code:
            continue
        raw_percentile = to_number(item.get("pe_percentile"))
        percentile = (
            raw_percentile * 100
            if raw_percentile is not None and 0 <= raw_percentile <= 1
            else raw_percentile
        )
        rows.append({
            "index_code": code.upper(),
            "name": truncate(clean_text(item.get("name")), 100),
            "pe_ttm": to_number(item.get("pe")),
            "pe_percentile": percentile,
            "trade_date": _item_date(item),
            "percentile_method": PERCENTILE_METHOD,
            "source": SOURCE_ESTIMATE,
        })
    return rows


class DanjuanClient:
    def __init__(self, http: HttpSource, config: Config):
        self.http = http
        self.config = config
        self._cache: tuple[str, list[dict[str, Any]]] | None = None

    def fetch_estimates(self) -> list[dict[str, Any]]:
        """按北京日期缓存：估值一天一个数，同一进程内重复调用不再外呼。"""
        cache_key = now_beijing().date().isoformat()
        if self._cache is not None and self._cache[0] == cache_key:
            return self._cache[1]
        body = self.http.get_json(PROVIDER, URL, headers=HEADERS)
        rows = parse_estimate_items(body)
        if not rows:
            raise SourceError(PROVIDER, "蛋卷估值未返回任何指数")
        self._cache = (cache_key, rows)
        return rows


__all__ = ["DanjuanClient", "PERCENTILE_METHOD", "PROVIDER", "SOURCE_ESTIMATE", "parse_estimate_items"]
