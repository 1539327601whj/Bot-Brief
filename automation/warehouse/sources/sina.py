# -*- coding: utf-8 -*-
"""新浪行情（hq.sinajs.cn）与 ETF 目录。

行情是最后一道兜底，只在东财、腾讯都拿不到时使用。返回 GBK 文本，必须带
``Referer: https://finance.sina.com.cn/``，否则会被 403。

字段下标（实测）：0 名称、1 今开、2 昨收、3 现价、4 最高、5 最低、8 成交量(股)、
9 成交额(元)、30 日期、31 时间。新浪的成交量是**股**，本项目统一存**手**，
这里除以 100，避免同一张表里混两种单位。

ETF 目录用新浪的行情中心节点 ``etf_hq_fund``，一次 100 条、按 symbol 升序分页。
它覆盖沪深两市全部挂牌 ETF（实测约 1700 只），名称与代码都来自交易所行情，
**不需要东财 clist**——后者会按 IP 封禁，项目里明令禁止。
"""

from __future__ import annotations

import json
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
ETF_LIST_URL = (
    "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
    "Market_Center.getHQNodeData"
)
ETF_NODE = "etf_hq_fund"
ETF_PAGE_SIZE = 100
# 实测约 1700 只；上限只用来防「接口忽略分页参数导致死循环」，不是业务上限。
ETF_MAX_PAGES = 40
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


def parse_etf_catalog_row(row: Any) -> dict[str, Any] | None:
    """新浪 ETF 目录行 → ``md_instrument`` 需要的最小身份。

    只认 ``sh`` / ``sz`` 前缀且代码为 6 位数字的行；认不出就跳过，
    **不猜市场**——把一只 ETF 写到错误市场，页面会静默查不到行情。
    """
    if not isinstance(row, dict):
        return None
    symbol = clean_text(row.get("symbol")) or ""
    market = {"sh": "SH", "sz": "SZ"}.get(symbol[:2].lower())
    code = clean_text(row.get("code")) or symbol[2:]
    name = clean_text(row.get("name"))
    if market is None or not code or not name:
        return None
    code = code.upper()
    if len(code) != 6 or not code.isdigit():
        return None
    return {"raw_code": code, "name": name, "market": market}


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

    def fetch_etf_catalog(self, page_size: int = ETF_PAGE_SIZE) -> list[dict[str, Any]]:
        """翻页取沪深全部 ETF 的身份（代码、名称、市场）。

        新浪这个接口**每页最多 100 条**（传 200 也只回 100），按 ``symbol`` 升序稳定分页。
        短页即最后一页；失败不重试，由上层决定这一轮是否跳过。
        """
        size = max(1, min(int(page_size or ETF_PAGE_SIZE), 100))
        rows: list[dict[str, Any]] = []
        seen: set[str] = set()
        for page in range(1, ETF_MAX_PAGES + 1):
            text = self.http.get_text(
                PROVIDER, ETF_LIST_URL,
                params={
                    "page": str(page),
                    "num": str(size),
                    "sort": "symbol",
                    "asc": "1",
                    "node": ETF_NODE,
                },
                headers=HEADERS,
            )
            try:
                payload = json.loads(text)
            except ValueError as exc:
                raise ValueError(f"{PROVIDER} ETF 目录不是 JSON") from exc
            if not isinstance(payload, list):
                # 接口在参数异常时会返回空对象/字符串；这已经是契约变化，不能当「没有更多」。
                raise ValueError(f"{PROVIDER} ETF 目录返回体不是列表: {type(payload).__name__}")
            for item in payload:
                parsed = parse_etf_catalog_row(item)
                if parsed and parsed["raw_code"] not in seen:
                    seen.add(parsed["raw_code"])
                    rows.append(parsed)
            if len(payload) < size:
                break
        return rows


__all__ = [
    "ETF_LIST_URL",
    "ETF_MAX_PAGES",
    "ETF_NODE",
    "ETF_PAGE_SIZE",
    "PROVIDER",
    "SOURCE_QUOTE",
    "SinaClient",
    "parse_etf_catalog_row",
    "parse_quote_text",
]
