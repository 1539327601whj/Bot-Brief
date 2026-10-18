# -*- coding: utf-8 -*-
"""东方财富取数与解析。

东财是主力源，也是最容易被 IP 限流的一个。这里的客户端**只发一次请求**：
重试、换域名、换关键词都在上层被禁止；429 由 :class:`~warehouse.rate_limit.HttpSource`
写冷却后抛 ``RateLimited``。

端点与实测形状（2026-10 探针）：

* 行情 ``push2 ulist.np``：``data.diff`` 可能是 list 也可能是 dict；
* 日线 ``push2his kline``：``data.klines`` 是逗号分隔字符串；
* 估值历史 ``datacenter-web RPT_VALUEANALYSIS_DET``：2018 年起约 2100 行，
  一次 ``pageSize=5000`` 可取完；
* 财务 ``RPT_DMSK_FN_BALANCE / INCOME / CASHFLOW + RPT_F10_FINANCE_MAINFINADATA``：
  **不能带 ``sortColumns=REPORT_DATE``**，实测那样会返回 0 行；
* 资金流 ``push2 fflow/daykline``：``lmt=0`` 只回 1 行，必须显式给 ``lmt``。
"""

from __future__ import annotations

import logging
from datetime import date, datetime
from typing import Any, Iterable, Sequence

from ..config import Config
from ..normalize import (
    asset_instrument_code,
    clean_text,
    eastmoney_secid,
    guess_market,
    parse_date,
    parse_datetime,
    report_type_of,
    to_decimal,
    to_number,
    today_beijing,
    truncate,
)
from ..rate_limit import HttpSource, SourceError, SourceUnavailable

logger = logging.getLogger(__name__)

PROVIDER = "eastmoney:batch"

SOURCE_QUOTE = "eastmoney-quote"
SOURCE_KLINE = "eastmoney-kline"
SOURCE_VALUATION = "eastmoney-valueanalysis"
SOURCE_FINANCIAL = "eastmoney-datacenter"
SOURCE_MONEYFLOW = "eastmoney-fflow"
SOURCE_UNIVERSE = "eastmoney-clist"
SOURCE_BOARD = "eastmoney-board"
SOURCE_INDUSTRY_CONSTITUENT = "eastmoney-industry-constituent"

QUOTE_URL = "https://push2.eastmoney.com/api/qt/ulist.np/get"
KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
DATACENTER_URL = "https://datacenter-web.eastmoney.com/api/data/v1/get"
MONEYFLOW_URL = "https://push2.eastmoney.com/api/qt/stock/fflow/daykline/get"
CLIST_URL = "https://push2.eastmoney.com/api/qt/clist/get"

QUOTE_HEADERS = {"Referer": "https://quote.eastmoney.com/"}
KLINE_HEADERS = {"Referer": "https://quote.eastmoney.com/"}
DATACENTER_HEADERS = {"Referer": "https://data.eastmoney.com/"}
CLIST_HEADERS = {"Referer": "https://quote.eastmoney.com/center/gridlist.html"}

QUOTE_FIELDS = "f2,f3,f6,f8,f9,f12,f14,f20,f21,f23,f100,f115,f124,f133"
UNIVERSE_FIELDS = QUOTE_FIELDS
UNIVERSE_FS = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23"
INDUSTRY_FS = "m:90+t:2"

# 财务接口的四个报表。实测 _NEW / DMSK_FN_MAIN 没有数据，保留会让每只票多两次空请求。
FINANCIAL_REPORTS = (
    "RPT_DMSK_FN_BALANCE",
    "RPT_DMSK_FN_INCOME",
    "RPT_DMSK_FN_CASHFLOW",
    "RPT_F10_FINANCE_MAINFINADATA",
)


def _diff_rows(data: dict[str, Any] | None) -> list[dict[str, Any]]:
    if not isinstance(data, dict):
        return []
    diff = data.get("diff")
    if isinstance(diff, dict):
        return [row for row in diff.values() if isinstance(row, dict)]
    if isinstance(diff, list):
        return [row for row in diff if isinstance(row, dict)]
    return []


def _epoch_datetime(value: Any) -> datetime | None:
    number = to_number(value)
    if number is None or number <= 0:
        return None
    try:
        from ..normalize import BEIJING_OFFSET
        return datetime.fromtimestamp(number, BEIJING_OFFSET).replace(tzinfo=None)
    except (OverflowError, OSError, ValueError):
        return None


def parse_quote_row(row: dict[str, Any]) -> dict[str, Any] | None:
    code = clean_text(row.get("f12"))
    if not code:
        return None
    return {
        "raw_code": code.upper(),
        "name": clean_text(row.get("f14")),
        "price": to_number(row.get("f2")),
        "pct_change": to_number(row.get("f3")),
        "amount": to_number(row.get("f6")),
        "turnover_rate": to_number(row.get("f8")),
        "pe_dynamic": to_number(row.get("f9")),
        "pe_ttm": to_number(row.get("f115")),
        "pb": to_number(row.get("f23")),
        "dividend_yield": to_number(row.get("f133")),
        "total_market_cap": to_number(row.get("f20")),
        "float_market_cap": to_number(row.get("f21")),
        "industry": clean_text(row.get("f100")),
        "quote_time": _epoch_datetime(row.get("f124")),
    }


def parse_kline_line(line: str, adj_type: str = "QFQ") -> dict[str, Any] | None:
    """东财日线：date,open,close,high,low,volume,amount,amplitude,pct,change,turnover。"""
    parts = str(line or "").split(",")
    if len(parts) < 11:
        return None
    trade_date = parse_date(parts[0])
    close = to_number(parts[2])
    if trade_date is None or close is None or close <= 0:
        return None
    open_price = to_number(parts[1])
    high = to_number(parts[3])
    low = to_number(parts[4])
    # OHLC 关系不成立时把可疑字段置空，而不是丢掉整根 K 线：收盘价仍然可用，
    # 而 md_price_daily 的约束允许 open/high/low 为 NULL。0/负值（停牌占位）同样置空，
    # 否则整批 upsert 会被 CHECK 约束拒绝。
    if open_price is not None and open_price <= 0:
        open_price = None
    if high is not None and (high <= 0 or (open_price is not None and high < open_price) or high < close):
        high = None
    if low is not None and (low <= 0 or (open_price is not None and low > open_price) or low > close):
        low = None
    return {
        "trade_date": trade_date,
        "adj_type": adj_type,
        "open_price": open_price,
        "high_price": high,
        "low_price": low,
        "close_price": close,
        "volume": to_number(parts[5]),
        "amount": to_number(parts[6]),
        "turnover_rate": to_number(parts[10]),
        "pct_change": to_number(parts[8]),
    }


def _parse_report_date(value: Any) -> date | None:
    return parse_date(value)


def _merge_financial_row(merged: dict[date, dict[str, Any]], report_date: date) -> dict[str, Any]:
    return merged.setdefault(
        report_date,
        {
            "report_date": report_date,
            "report_type": report_type_of(report_date),
            "announce_date": None,
            "revenue": None,
            "revenue_yoy": None,
            "net_profit": None,
            "net_profit_yoy": None,
            "roe": None,
            "gross_margin": None,
            "net_margin": None,
            "ocf": None,
            "total_assets": None,
            "total_equity": None,
            "total_liabilities": None,
            "current_assets": None,
            "current_liabilities": None,
            "interest_expense": None,
            "ebit": None,
            "goodwill": None,
            "accounts_receivable": None,
        },
    )


def _take(target: dict[str, Any], key: str, value: Any, *, prefer: bool = False) -> None:
    parsed = to_number(value)
    if parsed is None:
        return
    if prefer or target.get(key) is None:
        target[key] = parsed


def parse_financial_payloads(payloads: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    """把四张报表的响应合成 ``md_financial_report`` 行。

    ``payloads`` 的键是 reportName。缺失的报表键直接跳过——部分报表失败时仍然
    写出已有的字段，页面显示 PARTIAL，而不是整段消失。
    """
    merged: dict[date, dict[str, Any]] = {}
    for report_name, payload in payloads.items():
        rows = ((payload or {}).get("result") or {}).get("data") or []
        for row in rows:
            if not isinstance(row, dict):
                continue
            report_date = _parse_report_date(row.get("REPORT_DATE"))
            if report_date is None or report_date > today_beijing():
                continue
            target = _merge_financial_row(merged, report_date)
            announce = parse_date(row.get("NOTICE_DATE"))
            if announce and (target["announce_date"] is None or announce > target["announce_date"]):
                target["announce_date"] = announce

            if report_name == "RPT_DMSK_FN_BALANCE":
                _take(target, "total_assets", row.get("TOTAL_ASSETS"))
                _take(target, "total_liabilities", row.get("TOTAL_LIABILITIES"))
                _take(target, "total_equity", row.get("TOTAL_EQUITY"))
                _take(target, "accounts_receivable", row.get("ACCOUNTS_RECE"))
            elif report_name == "RPT_DMSK_FN_INCOME":
                _take(target, "revenue", row.get("TOTAL_OPERATE_INCOME") or row.get("OPERATE_INCOME"))
                _take(target, "revenue_yoy", row.get("TOI_RATIO"))
                _take(target, "net_profit", row.get("PARENT_NETPROFIT"))
                _take(target, "net_profit_yoy", row.get("PARENT_NETPROFIT_RATIO"))
                finance_expense = to_number(row.get("FINANCE_EXPENSE"))
                total_profit = to_number(row.get("TOTAL_PROFIT"))
                if finance_expense is not None and finance_expense > 0:
                    target["interest_expense"] = finance_expense
                if total_profit is not None:
                    target["ebit"] = total_profit + max(finance_expense or 0.0, 0.0)
            elif report_name == "RPT_DMSK_FN_CASHFLOW":
                _take(target, "ocf", row.get("NETCASH_OPERATE"))
            elif report_name == "RPT_F10_FINANCE_MAINFINADATA":
                _take(target, "revenue", row.get("TOTALOPERATEREVE"))
                _take(target, "revenue_yoy", row.get("TOTALOPERATEREVETZ"))
                _take(target, "net_profit", row.get("PARENTNETPROFIT"))
                _take(target, "net_profit_yoy", row.get("PARENTNETPROFITTZ"))
                _take(target, "roe", row.get("ROEJQ"))
                _take(target, "gross_margin", row.get("XSMLL"))
                _take(target, "net_margin", row.get("XSJLL"))
                _take(target, "ocf", row.get("NETCASH_OPERATE_PK"))
                _take(target, "total_assets", row.get("TOTAL_ASSETS_PK"))
                _take(target, "total_equity", row.get("TOTAL_EQUITY_PK"))

    result: list[dict[str, Any]] = []
    for report_date, row in merged.items():
        if not row.get("report_type"):
            continue
        # 净利率缺失时用净利/营收补算；毛利率没有可靠分子（报表口径不一致），保持 NULL。
        revenue = row.get("revenue")
        if row.get("net_margin") is None and revenue and revenue != 0 and row.get("net_profit") is not None:
            row["net_margin"] = row["net_profit"] / revenue * 100.0
        announce = row.get("announce_date") or report_date
        row["available_from"] = max(announce, report_date)
        result.append(row)
    result.sort(key=lambda item: item["report_date"], reverse=True)
    return result


class EastmoneyClient:
    def __init__(self, http: HttpSource, config: Config):
        self.http = http
        self.config = config

    # ------------------------------------------------------------------
    # 行情
    # ------------------------------------------------------------------
    def fetch_quotes(self, secids: Sequence[str]) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        batch_size = max(1, self.config.quote_batch_size)
        for start in range(0, len(secids), batch_size):
            batch = list(secids[start:start + batch_size])
            if not batch:
                continue
            body = self.http.get_json(
                PROVIDER,
                QUOTE_URL,
                params={
                    "fltt": "2",
                    "invt": "2",
                    "fields": QUOTE_FIELDS,
                    "secids": ",".join(batch),
                },
                headers=QUOTE_HEADERS,
            )
            data = body.get("data")
            if data is None:
                # 单只票查不到是正常空结果；整批都为空时上层按 EMPTY 处理。
                continue
            for row in _diff_rows(data):
                parsed = parse_quote_row(row)
                if parsed:
                    result.append(parsed)
        return result

    # ------------------------------------------------------------------
    # 日线
    # ------------------------------------------------------------------
    def fetch_kline(
        self,
        asset_type: str,
        raw_code: str,
        market: str | None = None,
        *,
        limit: int | None = None,
        adj_type: str = "QFQ",
    ) -> list[dict[str, Any]]:
        secid = eastmoney_secid(asset_type, raw_code, market)
        body = self.http.get_json(
            PROVIDER,
            KLINE_URL,
            params={
                "secid": secid,
                "klt": "101",
                "fqt": "1" if adj_type == "QFQ" else "0",
                "lmt": str(limit or self.config.kline_limit),
                "end": "20500101",
                "fields1": "f1,f2,f3,f4,f5,f6",
                "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
            },
            headers=KLINE_HEADERS,
        )
        data = body.get("data")
        if not isinstance(data, dict):
            return []
        rows = []
        for line in data.get("klines") or []:
            parsed = parse_kline_line(line, adj_type)
            if parsed:
                rows.append(parsed)
        return rows

    # ------------------------------------------------------------------
    # 估值历史
    # ------------------------------------------------------------------
    def fetch_valuation_history(self, stock_code: str) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        page_size = max(100, self.config.valuation_page_size)
        page = 1
        pages = 1
        while page <= pages:
            body = self.http.get_json(
                PROVIDER,
                DATACENTER_URL,
                params={
                    "reportName": "RPT_VALUEANALYSIS_DET",
                    "columns": "TRADE_DATE,PE_TTM,PB_MRQ,PS_TTM,CLOSE_PRICE",
                    "filter": f'(SECURITY_CODE="{stock_code}")',
                    "pageNumber": str(page),
                    "pageSize": str(page_size),
                    "sortColumns": "TRADE_DATE",
                    "sortTypes": "1",
                    "source": "WEB",
                    "client": "WEB",
                },
                headers=DATACENTER_HEADERS,
            )
            if body.get("success") is False:
                raise SourceError(PROVIDER, f"估值历史业务错误: {body.get('message')}")
            result = body.get("result") or {}
            for row in result.get("data") or []:
                trade_date = parse_date(row.get("TRADE_DATE"))
                if trade_date is None:
                    continue
                rows.append({
                    "trade_date": trade_date,
                    "pe_ttm": to_number(row.get("PE_TTM")),
                    "pb_mrq": to_number(row.get("PB_MRQ")),
                    "ps_ttm": to_number(row.get("PS_TTM")),
                    "close_price": to_number(row.get("CLOSE_PRICE")),
                })
            try:
                pages = max(1, int(result.get("pages") or 1))
            except (TypeError, ValueError):
                pages = 1
            page += 1
        rows.sort(key=lambda item: item["trade_date"])
        return rows

    # ------------------------------------------------------------------
    # 财务
    # ------------------------------------------------------------------
    def fetch_financial_reports(self, stock_code: str) -> tuple[list[dict[str, Any]], list[str]]:
        payloads: dict[str, dict[str, Any]] = {}
        errors: list[str] = []
        for report_name in FINANCIAL_REPORTS:
            try:
                payload = self.http.get_json(
                    PROVIDER,
                    DATACENTER_URL,
                    params={
                        "reportName": report_name,
                        "columns": "ALL",
                        "filter": f'(SECURITY_CODE="{stock_code}")',
                        "pageNumber": "1",
                        "pageSize": str(self.config.financial_page_size),
                        "source": "WEB",
                        "client": "WEB",
                    },
                    headers=DATACENTER_HEADERS,
                )
            except (SourceError, SourceUnavailable) as exc:
                errors.append(f"{report_name}: {exc}")
                continue
            if payload.get("success") is False:
                errors.append(f"{report_name}: {payload.get('message')}")
                continue
            payloads[report_name] = payload
        return parse_financial_payloads(payloads), errors

    # ------------------------------------------------------------------
    # 资金流
    # ------------------------------------------------------------------
    def fetch_moneyflow(self, stock_code: str, limit: int | None = None) -> list[dict[str, Any]]:
        secid = eastmoney_secid("STOCK", stock_code)
        body = self.http.get_json(
            PROVIDER,
            MONEYFLOW_URL,
            params={
                "lmt": str(limit or self.config.moneyflow_limit),
                "klt": "101",
                "secid": secid,
                "fields1": "f1,f2,f3,f7",
                "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65",
            },
            headers=QUOTE_HEADERS,
        )
        data = body.get("data")
        if not isinstance(data, dict):
            return []
        rows = []
        for line in data.get("klines") or []:
            parts = str(line or "").split(",")
            if len(parts) < 7:
                continue
            trade_date = parse_date(parts[0])
            if trade_date is None:
                continue
            rows.append({
                "trade_date": trade_date,
                "main_net": to_number(parts[1]),
                "small_net": to_number(parts[2]),
                "medium_net": to_number(parts[3]),
                "large_net": to_number(parts[4]),
                "super_net": to_number(parts[5]),
                "main_ratio": to_number(parts[6]),
            })
        rows.sort(key=lambda item: item["trade_date"])
        return rows

    # ------------------------------------------------------------------
    # 全市场快照 / 行业板块
    # ------------------------------------------------------------------
    def fetch_universe(self, max_count: int | None = None) -> list[dict[str, Any]]:
        limit = max_count or self.config.universe_size
        page_size = max(1, self.config.universe_page_size)
        rows: list[dict[str, Any]] = []
        page = 1
        while len(rows) < limit:
            body = self.http.get_json(
                PROVIDER,
                CLIST_URL,
                params={
                    "pn": str(page),
                    "pz": str(page_size),
                    "po": "1",
                    "np": "1",
                    "fltt": "2",
                    "invt": "2",
                    "fid": "f20",
                    "fs": UNIVERSE_FS,
                    "fields": UNIVERSE_FIELDS,
                },
                headers=CLIST_HEADERS,
            )
            data = body.get("data")
            page_rows = _diff_rows(data)
            if not page_rows:
                break
            for row in page_rows:
                parsed = parse_quote_row(row)
                if parsed:
                    rows.append(parsed)
            total = to_number((data or {}).get("total"))
            if total is not None and page * page_size >= total:
                break
            if len(page_rows) < page_size:
                break
            page += 1
        return rows[:limit]

    def fetch_industry_boards(self, page_size: int = 200) -> list[dict[str, Any]]:
        body = self.http.get_json(
            PROVIDER,
            CLIST_URL,
            params={
                "pn": "1",
                "pz": str(page_size),
                "po": "1",
                "np": "1",
                "fltt": "2",
                "invt": "2",
                "fid": "f3",
                "fs": INDUSTRY_FS,
                "fields": "f12,f14",
            },
            headers=CLIST_HEADERS,
        )
        rows = []
        for row in _diff_rows(body.get("data")):
            code = clean_text(row.get("f12"))
            name = clean_text(row.get("f14"))
            if code and name:
                rows.append({"board_code": code.upper(), "name": name})
        return rows

    def fetch_industry_constituents(self, board_code: str, page_size: int = 200) -> list[dict[str, Any]]:
        """行业板块成分。``fs=b:BK1036`` 与普通 clist 同一套分页。**按需**调用。"""
        rows: list[dict[str, Any]] = []
        page = 1
        while True:
            body = self.http.get_json(
                PROVIDER,
                CLIST_URL,
                params={
                    "pn": str(page),
                    "pz": str(page_size),
                    "po": "1",
                    "np": "1",
                    "fltt": "2",
                    "invt": "2",
                    "fid": "f20",
                    "fs": f"b:{board_code.upper()}",
                    "fields": "f12,f14",
                },
                headers=CLIST_HEADERS,
            )
            data = body.get("data")
            page_rows = _diff_rows(data)
            if not page_rows:
                break
            for row in page_rows:
                code = clean_text(row.get("f12"))
                if not code:
                    continue
                rows.append({"stock_code": code.upper(), "stock_name": clean_text(row.get("f14"))})
            total = to_number((data or {}).get("total"))
            if total is not None and page * page_size >= total:
                break
            if len(page_rows) < page_size:
                break
            page += 1
        return rows


def quote_to_instrument(quote: dict[str, Any]) -> dict[str, Any]:
    raw_code = quote["raw_code"]
    market = guess_market(raw_code, "STOCK")
    return {
        "instrument_code": asset_instrument_code("STOCK", raw_code),
        "asset_type": "STOCK",
        "market": market,
        "raw_code": raw_code,
        "name": quote.get("name"),
        "aliases": None,
        "industry": quote.get("industry"),
        "industry_code": None,
        "tracking_index_code": None,
        "list_date": None,
        "status": "ACTIVE",
        "source": SOURCE_QUOTE,
    }


def quote_trade_date(quotes: Sequence[dict[str, Any]]) -> date | None:
    """从行情时间戳推出快照交易日；任何一只有时间即可（同一批行情同一时刻）。"""
    for quote in quotes:
        quote_time = quote.get("quote_time")
        if isinstance(quote_time, datetime):
            return quote_time.date()
    return None


__all__ = [
    "EastmoneyClient",
    "PROVIDER",
    "SOURCE_BOARD",
    "SOURCE_FINANCIAL",
    "SOURCE_INDUSTRY_CONSTITUENT",
    "SOURCE_KLINE",
    "SOURCE_MONEYFLOW",
    "SOURCE_QUOTE",
    "SOURCE_UNIVERSE",
    "SOURCE_VALUATION",
    "parse_financial_payloads",
    "parse_kline_line",
    "parse_quote_row",
    "quote_to_instrument",
    "quote_trade_date",
]
