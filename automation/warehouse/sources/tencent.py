# -*- coding: utf-8 -*-
"""腾讯行情（qt.gtimg.cn / web.ifzq.gtimg.cn）。

只做东财的**兜底**：同一条日线序列一旦已经由东财写入，就不要再用腾讯覆盖——
同日线的 ``source`` 不同会变成两条序列。上层 ``sync.prices`` 负责这条规则。

行情返回是 GBK 的 ``v_sh600519="..."`` 文本；日线是 JSON，单页超过约 640 根会
**静默截断**（传 1200 只回 640；传 2600 直接 ``param error``），所以必须按结束
日期向前翻页。
"""

from __future__ import annotations

import logging
from datetime import date, datetime, timedelta
from typing import Any, Sequence

from ..config import Config
from ..normalize import clean_text, parse_date, tencent_symbol, to_number
from ..rate_limit import HttpSource

logger = logging.getLogger(__name__)

PROVIDER = "tencent"
SOURCE_QUOTE = "tencent-quote"
SOURCE_KLINE = "tencent-kline"

QUOTE_URL = "https://qt.gtimg.cn/q="
KLINE_URL = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"

QUOTE_HEADERS = {"Referer": "https://finance.qq.com/"}
KLINE_HEADERS = {"Referer": "https://gu.qq.com/"}
TEXT_ENCODING = "gbk"

# 实测单页上限：640 根稳定；更大 count 会退回 640 或 param error。
PAGE_SIZE = 640


def _parse_compact_time(value: Any) -> datetime | None:
    text = clean_text(value)
    if not text or len(text) != 14 or not text.isdigit():
        return None
    try:
        return datetime.strptime(text, "%Y%m%d%H%M%S")
    except ValueError:
        return None


def parse_quote_text(text: str) -> list[dict[str, Any]]:
    """解析 ``v_sh600519="1~贵州茅台~600519~1329.00~..."``。

    字段下标来自实测（2026-10）：3 现价、4 昨收、5 今开、6 成交量(手)、
    30 行情时间、31 涨跌额、32 涨跌幅、33 最高、34 最低、37 成交额(万元)、
    38 换手率、39 PE(TTM)、44 流通市值(亿)、45 总市值(亿)、46 PB。
    """
    rows: list[dict[str, Any]] = []
    for statement in str(text or "").split(";"):
        statement = statement.strip()
        if not statement or "=" not in statement:
            continue
        _, _, raw = statement.partition("=")
        parts = raw.strip().strip('"').split("~")
        if len(parts) < 47:
            continue
        code = clean_text(parts[2])
        if not code:
            continue
        amount_wan = to_number(parts[37])
        float_cap_yi = to_number(parts[44])
        total_cap_yi = to_number(parts[45])
        rows.append({
            "raw_code": code.upper(),
            "name": clean_text(parts[1]),
            "price": to_number(parts[3]),
            "prev_close": to_number(parts[4]),
            "open_price": to_number(parts[5]),
            "volume": to_number(parts[6]),
            "quote_time": _parse_compact_time(parts[30]),
            "change": to_number(parts[31]),
            "pct_change": to_number(parts[32]),
            "high_price": to_number(parts[33]),
            "low_price": to_number(parts[34]),
            "amount": amount_wan * 10000 if amount_wan is not None else None,
            "turnover_rate": to_number(parts[38]),
            "pe_ttm": to_number(parts[39]),
            "float_market_cap": float_cap_yi * 1e8 if float_cap_yi is not None else None,
            "total_market_cap": total_cap_yi * 1e8 if total_cap_yi is not None else None,
            "pb": to_number(parts[46]),
        })
    return rows


def parse_kline_rows(rows: Sequence[Sequence[Any]], adj_type: str = "QFQ") -> list[dict[str, Any]]:
    """腾讯 ``qfqday`` 每行：date, open, close, high, low, volume（注意 close 在第 3 位）。"""
    parsed = []
    for row in rows or []:
        if not isinstance(row, (list, tuple)) or len(row) < 6:
            continue
        trade_date = parse_date(row[0])
        close = to_number(row[2])
        if trade_date is None or close is None or close <= 0:
            continue
        open_price = to_number(row[1])
        high = to_number(row[3])
        low = to_number(row[4])
        if open_price is not None and open_price <= 0:
            open_price = None
        if high is not None and (high <= 0 or high < close or (open_price is not None and high < open_price)):
            high = None
        if low is not None and (low <= 0 or low > close or (open_price is not None and low > open_price)):
            low = None
        parsed.append({
            "trade_date": trade_date,
            "adj_type": adj_type,
            "open_price": open_price,
            "high_price": high,
            "low_price": low,
            "close_price": close,
            "volume": to_number(row[5]),
            "amount": None,
            "turnover_rate": None,
            "pct_change": None,
        })
    parsed.sort(key=lambda item: item["trade_date"])
    return parsed


class TencentClient:
    def __init__(self, http: HttpSource, config: Config):
        self.http = http
        self.config = config

    def fetch_quotes(self, symbols: Sequence[tuple[str, str]]) -> list[dict[str, Any]]:
        """``symbols`` 是 ``(raw_code, market)``；一次最多约 50 个代码。"""
        result: list[dict[str, Any]] = []
        codes = [tencent_symbol(raw_code, market) for raw_code, market in symbols]
        for start in range(0, len(codes), 50):
            chunk = codes[start:start + 50]
            if not chunk:
                continue
            text = self.http.get_text(
                PROVIDER, QUOTE_URL + ",".join(chunk),
                headers=QUOTE_HEADERS, encoding=TEXT_ENCODING,
            )
            result.extend(parse_quote_text(text))
        return result

    def fetch_kline(
        self,
        raw_code: str,
        market: str | None = None,
        *,
        limit: int | None = None,
        adj_type: str = "QFQ",
    ) -> list[dict[str, Any]]:
        symbol = tencent_symbol(raw_code, market)
        wanted = limit or self.config.kline_limit
        mode = "qfq" if adj_type == "QFQ" else "day"
        payload_key = "qfqday" if adj_type == "QFQ" else "day"
        collected: dict[date, dict[str, Any]] = {}
        end_date: date | None = None
        while len(collected) < wanted:
            param = f"{symbol},day,,{end_date.isoformat() if end_date else ''},{PAGE_SIZE},{mode}"
            body = self.http.get_json(PROVIDER, KLINE_URL, params={"param": param}, headers=KLINE_HEADERS)
            data = (body.get("data") or {}).get(symbol)
            if not isinstance(data, dict):
                # 超出可选范围时腾讯回 data:[] 且 msg=param error；此时已经没有更早的数据。
                break
            rows = data.get(payload_key) or []
            page = parse_kline_rows(rows, adj_type)
            if not page:
                break
            before = len(collected)
            for row in page:
                collected.setdefault(row["trade_date"], row)
            oldest = page[0]["trade_date"]
            if len(collected) == before or (end_date is not None and oldest >= end_date):
                break
            end_date = oldest - timedelta(days=1)
        ordered = sorted(collected.values(), key=lambda item: item["trade_date"])
        return ordered[-wanted:]


__all__ = [
    "PAGE_SIZE",
    "PROVIDER",
    "SOURCE_KLINE",
    "SOURCE_QUOTE",
    "TencentClient",
    "parse_kline_rows",
    "parse_quote_text",
]
