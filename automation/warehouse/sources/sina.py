# -*- coding: utf-8 -*-
"""新浪行情（hq.sinajs.cn）。

最后一道兜底，只在东财、腾讯都拿不到时使用。返回 GBK 文本，必须带
``Referer: https://finance.sina.com.cn/``，否则会被 403。

字段下标（实测）：0 名称、1 今开、2 昨收、3 现价、4 最高、5 最低、8 成交量(股)、
9 成交额(元)、30 日期、31 时间。新浪的成交量是**股**，本项目统一存**手**，
这里除以 100，避免同一张表里混两种单位。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Sequence

from ..config import Config
from ..normalize import clean_text, parse_date, tencent_symbol, to_number
from ..rate_limit import HttpSource

logger = logging.getLogger(__name__)

PROVIDER = "sina"
SOURCE_QUOTE = "sina-quote"

QUOTE_URL = "https://hq.sinajs.cn/list="
HEADERS = {"Referer": "https://finance.sina.com.cn/"}
TEXT_ENCODING = "gbk"


def _quote_time(date_text: Any, time_text: Any) -> datetime | None:
    day = parse_date(date_text)
    clock = clean_text(time_text)
    if day is None or not clock:
        return None
    try:
        return datetime.combine(day, datetime.strptime(clock, "%H:%M:%S").time())
    except ValueError:
        return None


def parse_quote_text(text: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for statement in str(text or "").split(";"):
        statement = statement.strip()
        if not statement or "hq_str_" not in statement or "=" not in statement:
            continue
        key, _, raw = statement.partition("=")
        symbol = key.rsplit("hq_str_", 1)[-1].strip()
        parts = raw.strip().strip('"').split(",")
        if len(parts) < 32:
            continue
        code = symbol[2:] if symbol[:2].lower() in ("sh", "sz") else symbol
        volume_shares = to_number(parts[8])
        rows.append({
            "raw_code": code.upper(),
            "name": clean_text(parts[0]),
            "open_price": to_number(parts[1]),
            "prev_close": to_number(parts[2]),
            "price": to_number(parts[3]),
            "high_price": to_number(parts[4]),
            "low_price": to_number(parts[5]),
            "volume": volume_shares / 100.0 if volume_shares is not None else None,
            "amount": to_number(parts[9]),
            "quote_time": _quote_time(parts[30], parts[31]),
        })
    return rows


class SinaClient:
    def __init__(self, http: HttpSource, config: Config):
        self.http = http
        self.config = config

    def fetch_quotes(self, symbols: Sequence[tuple[str, str]]) -> list[dict[str, Any]]:
        codes = [tencent_symbol(raw_code, market) for raw_code, market in symbols]
        rows: list[dict[str, Any]] = []
        for start in range(0, len(codes), 50):
            chunk = codes[start:start + 50]
            if not chunk:
                continue
            text = self.http.get_text(
                PROVIDER, QUOTE_URL + ",".join(chunk),
                headers=HEADERS, encoding=TEXT_ENCODING,
            )
            rows.extend(parse_quote_text(text))
        return rows


__all__ = ["PROVIDER", "SOURCE_QUOTE", "SinaClient", "parse_quote_text"]
