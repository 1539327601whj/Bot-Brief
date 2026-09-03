# -*- coding: utf-8 -*-
"""
ETF 市场数据简报脚本
用于汇总沪深300ETF、纳指100ETF与标普500ETF的行情、估值、来源和数据风险。
"""

import calendar
import json
import math
import os
import re
import sys
import time
from bisect import bisect_left, bisect_right, insort
from datetime import date, datetime, timezone, timedelta
from typing import Any, Literal, Optional, TypedDict

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

from etf_allocation_analyst import analyze_snapshot

BEIJING_TZ = timezone(timedelta(hours=8))

ETF_LIST = [
    {
        "name": "沪深300ETF 华泰柏瑞",
        "code": "510300",
        "eastmoney_secid": "1.510300",
        "sina_code": "sh510300",
        "index_name": "沪深300指数",
        "valuation_index_code": "SH000300",
        "valuation_env_prefix": "CSI300",
        "percentile_method": "CSI_PE_TTM_ROLLING_10Y",
        "qdii": False,
    },
    {
        "name": "纳指100ETF 国泰",
        "code": "513100",
        "eastmoney_secid": "1.513100",
        "sina_code": "sh513100",
        "index_name": "纳斯达克100指数",
        "valuation_index_code": "NDX",
        "valuation_env_prefix": "NASDAQ100",
        "percentile_method": "DANJUAN_PE_TTM_PROVIDER",
        "qdii": True,
    },
    {
        "name": "标普500ETF 博时",
        "code": "513500",
        "eastmoney_secid": "1.513500",
        "sina_code": "sh513500",
        "index_name": "标普500指数",
        "valuation_index_code": "SP500",
        "valuation_env_prefix": "SP500",
        "percentile_method": "DANJUAN_PE_TTM_PROVIDER",
        "qdii": True,
    },
]


def etf_is_qdii(etf: dict[str, Any]) -> bool:
    return bool(etf.get("qdii")) or etf.get("code") in {"513100", "513500"}

A_SHARE_PICK_COUNT = 2
A_SHARE_PAGE_SIZE = 100
A_SHARE_BOARD_PAGE_SIZE = 50
A_SHARE_MARKET_FS = "m:1+t:2,m:0+t:6,m:0+t:80"
A_SHARE_MARKET_BOARDS = ("m:1+t:2", "m:0+t:6", "m:0+t:80")
A_SHARE_CLIST_FIELDS = "f12,f14,f2,f3,f6,f8,f9,f10,f15,f16,f20,f23,f24,f25,f62"
A_SHARE_EASTMONEY_UT = "fa5fd1943c7bdc76815634f86e88ea48"
A_SHARE_EASTMONEY_HOSTS = (
    "https://push2delay.eastmoney.com/api/qt/clist/get",
    "https://82.push2.eastmoney.com/api/qt/clist/get",
    "https://push2.eastmoney.com/api/qt/clist/get",
)
A_SHARE_EASTMONEY_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    ),
    "Referer": "https://quote.eastmoney.com/center/gridlist.html",
    "Accept": "application/json, text/plain, */*",
}
A_SHARE_SINA_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    ),
    "Referer": "https://vip.stock.finance.sina.com.cn/",
    "Accept": "application/json, text/javascript, */*;q=0.01",
}
A_SHARE_MIN_AMOUNT = 300000000
A_SHARE_MIN_MARKET_CAP = 10000000000
A_SHARE_MAX_ABS_PCT_CHANGE = 6
A_SHARE_BANNED_NAME_KEYWORDS = ("ST", "*ST", "退")
A_SHARE_SINA_MARKET_CAP_UNIT = 10000
CSI300_PE_WINDOW_YEARS = 10
CURRENT_VALUATION_MAX_STALENESS_DAYS = 15
VALUATION_ARCHIVE_URL = "https://raw.githubusercontent.com/caibingcheng/djeva/master/json/{date}.json"
CSI_PE_TTM_ROLLING_10Y = "CSI_PE_TTM_ROLLING_10Y"
DANJUAN_PE_TTM_PROVIDER = "DANJUAN_PE_TTM_PROVIDER"
PRICE_ADJUSTMENT_TYPE = "QFQ"
PRICE_HISTORY_LIMIT = 800
PRICE_CACHE_QUERY_LIMIT = 800
PRICE_INGEST_BATCH_SIZE = 250
VALUATION_HISTORY_LIMIT = 800
PRICE_MAX_STALENESS_DAYS = 15
PE_LOOKBACK_DAYS = 15
NAV_HISTORY_PAGES = 20
NAV_PAGE_SIZE = 40
UNADJUSTED_CLOSE_LIMIT = 800
PREMIUM_HISTORY_STALENESS_DAYS = 15
SIGNED_CHANGE_TOKEN = re.compile(
    r"(?<![.\d])(\+[\d.]+(?:%|pt|点)|-[\d.]+(?:%|pt|点)|0(?:\.00)?(?:%|pt|点))(?!\d)"
)
EASTMONEY_FUND_HEADERS = {
    "User-Agent": "Mozilla/5.0",
    "Referer": "https://fundf10.eastmoney.com/",
}
RETRYABLE_STATUS_CODES = (408, 429, 500, 502, 503, 504)
WECHAT_RETRYABLE_ERRCODES = (-1, 45009)


class AShareObservationResult(TypedDict):
    status: Literal["available", "empty", "provider_error"]
    items: list[dict[str, str]]
    source: str
    error: Optional[str]


_HTTP_SESSION: Optional[requests.Session] = None
_JSON_UNSET = object()

DISCLAIMER = "数据说明：本报告汇总公开市场数据，仅用于核对数据、市场状态与风险；不同估值口径不可直接横向比较，不构成投资建议或买卖依据。"
ETF_REFRESH_MARKER = "<!-- ETF_DATA_REFRESH:IOPV -->"


def now_beijing() -> datetime:
    return datetime.now(BEIJING_TZ)


def build_http_session() -> requests.Session:
    session = requests.Session()
    retry = Retry(
        total=2,
        connect=2,
        read=2,
        status=2,
        allowed_methods=frozenset(("GET",)),
        status_forcelist=RETRYABLE_STATUS_CODES,
        backoff_factor=0.25,
        raise_on_status=False,
        respect_retry_after_header=True,
    )
    adapter = HTTPAdapter(max_retries=retry)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    return session


def http_get(url: str, **kwargs: Any) -> requests.Response:
    """统一 GET；注入或 mock 本函数即可离线测试。"""
    global _HTTP_SESSION
    if _HTTP_SESSION is None:
        _HTTP_SESSION = build_http_session()
    return _HTTP_SESSION.get(url, **kwargs)


def finite_positive(value: Any, maximum: Optional[float] = None) -> Optional[float]:
    number = to_optional_float(value)
    if number is None or not math.isfinite(number) or number <= 0:
        return None
    if maximum is not None and number > maximum:
        return None
    return number


def parse_data_time(value: Any) -> Optional[datetime]:
    text = str(value or "").strip()
    if not text:
        return None
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        try:
            parsed = datetime.strptime(text, "%Y-%m-%d %H:%M:%S")
        except ValueError:
            return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=BEIJING_TZ)
    return parsed.astimezone(BEIJING_TZ)


def validate_ohlc(open_price: Any, high: Any, low: Any, close: Any) -> bool:
    values = [finite_positive(value) for value in (open_price, high, low, close)]
    if any(value is None for value in values):
        return False
    open_value, high_value, low_value, close_value = values
    return low_value <= min(open_value, close_value) <= max(open_value, close_value) <= high_value


def validate_quote(quote: dict[str, Any], etf: dict[str, str]) -> dict[str, Any]:
    if str(quote.get("code") or "") != etf["code"]:
        raise RuntimeError(f"行情代码不匹配: {quote.get('code')} != {etf['code']}")
    latest = finite_positive(quote.get("latest_price"))
    previous_close = finite_positive(quote.get("previous_close"))
    data_time = parse_data_time(quote.get("data_time"))
    if latest is None or previous_close is None:
        raise RuntimeError("行情最新价或昨收不是有限正数")
    if data_time is None:
        raise RuntimeError("行情缺少可解析的真实时间戳")
    reference_time = now_beijing()
    if data_time > reference_time + timedelta(minutes=5):
        raise RuntimeError("行情时间戳位于未来")
    if (reference_time.date() - data_time.date()).days > PRICE_MAX_STALENESS_DAYS:
        raise RuntimeError("行情时间戳过旧")
    if not validate_ohlc(quote.get("open"), quote.get("high"), quote.get("low"), latest):
        raise RuntimeError("行情 OHLC 关系异常")
    quote["latest_price"] = latest
    quote["previous_close"] = previous_close
    quote["data_time"] = data_time.strftime("%Y-%m-%d %H:%M:%S")
    return quote


def detect_edition() -> str:
    manual = os.environ.get("EDITION", "evening").lower()
    if manual in ("evening", "market_watch_evening", "etf_evening", "auto"):
        return "market_watch_evening"
    if manual in ("morning", "market_watch_morning", "etf_morning"):
        raise RuntimeError("ETF 早间版已停用，仅生成工作日 18:00 日报")
    raise RuntimeError(f"不支持的 ETF 日报版本: {manual}")


def edition_label(edition: str) -> str:
    return "晚间版"


def to_optional_float(value: Any) -> Optional[float]:
    if value in (None, "", "-"):
        return None
    try:
        n = float(value)
        if n == -1:
            return None
        return n
    except (TypeError, ValueError):
        return None


def scaled(value: Any, divisor: float) -> Optional[float]:
    n = to_optional_float(value)
    return None if n is None else n / divisor


def fmt_number(value: Optional[float], digits: int = 3, suffix: str = "") -> str:
    if value is None:
        return "不可确认"
    return f"{value:.{digits}f}{suffix}"


def fmt_price(value: Optional[float]) -> str:
    return "不可确认" if value is None else f"{value:.3f} 元"


def fetch_quote_from_eastmoney(etf: dict[str, str]) -> dict[str, Any]:
    url = "https://push2.eastmoney.com/api/qt/stock/get"
    params = {
        "secid": etf["eastmoney_secid"],
        "fields": "f43,f44,f45,f46,f47,f48,f57,f58,f60,f86,f169,f170",
    }
    resp = http_get(url, params=params, timeout=12, headers={"User-Agent": "Mozilla/5.0"})
    resp.raise_for_status()
    data = resp.json().get("data") or {}
    if not data:
        raise RuntimeError("东方财富未返回行情数据")

    ts = data.get("f86")
    data_time = None
    if ts:
        try:
            data_time = datetime.fromtimestamp(int(ts), BEIJING_TZ).strftime("%Y-%m-%d %H:%M:%S")
        except (TypeError, ValueError):
            data_time = str(ts)

    return validate_quote({
        "name": data.get("f58") or etf["name"],
        "code": str(data.get("f57") or ""),
        "latest_price": scaled(data.get("f43"), 1000),
        "change_amount": scaled(data.get("f169"), 1000),
        "pct_change": scaled(data.get("f170"), 100),
        "open": scaled(data.get("f46"), 1000),
        "high": scaled(data.get("f44"), 1000),
        "low": scaled(data.get("f45"), 1000),
        "previous_close": scaled(data.get("f60"), 1000),
        "volume": data.get("f47"),
        "amount": scaled(data.get("f48"), 1),
        "data_time": data_time,
        "source": "东方财富",
    }, etf)


def fetch_quote_from_sina(etf: dict[str, str]) -> dict[str, Any]:
    url = f"https://hq.sinajs.cn/list={etf['sina_code']}"
    headers = {"User-Agent": "Mozilla/5.0", "Referer": "https://finance.sina.com.cn/"}
    resp = http_get(url, timeout=12, headers=headers)
    resp.raise_for_status()
    text = resp.content.decode("gbk", errors="replace")
    match = re.search(r'var\s+hq_str_([a-z]{2}\d+)="(.*)";', text, re.IGNORECASE)
    if not match:
        raise RuntimeError("新浪未返回行情数据")
    if match.group(1).lower() != etf["sina_code"].lower():
        raise RuntimeError(f"新浪行情代码不匹配: {match.group(1)}")
    parts = match.group(2).split(",")
    if len(parts) < 32 or not parts[0]:
        raise RuntimeError("新浪行情格式异常")

    def to_float(i: int) -> Optional[float]:
        try:
            return float(parts[i])
        except (IndexError, ValueError):
            return None

    latest = to_float(3)
    previous_close = to_float(2)
    change_amount = latest - previous_close if latest is not None and previous_close else None
    pct_change = change_amount / previous_close * 100 if change_amount is not None and previous_close else None

    return validate_quote({
        "name": parts[0],
        "code": etf["code"],
        "latest_price": latest,
        "change_amount": change_amount,
        "pct_change": pct_change,
        "open": to_float(1),
        "high": to_float(4),
        "low": to_float(5),
        "previous_close": previous_close,
        "volume": to_float(8),
        "amount": to_float(9),
        "data_time": f"{parts[30]} {parts[31]}" if len(parts) > 31 else None,
        "source": "新浪财经",
    }, etf)


def fetch_etf_quote(etf: dict[str, str]) -> dict[str, Any]:
    errors = []
    for fetcher in (fetch_quote_from_eastmoney, fetch_quote_from_sina):
        try:
            quote = fetcher(etf)
            print(f"  ✅ {etf['name']} 行情来自 {quote['source']}")
            return quote
        except Exception as e:
            errors.append(f"{getattr(fetcher, '__name__', '行情适配器')}: {e}")
            print(f"  ⚠️ {etf['name']} 行情源失败: {e}")
    raise RuntimeError(f"{etf['name']} 所有实时行情源均失败: {'; '.join(errors)}")


def normalize_daily_prices(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    today = now_beijing().date()
    by_date: dict[str, dict[str, Any]] = {}
    for item in items:
        trade_date = parse_iso_date(item.get("date") or item.get("tradeDate"))
        open_price = finite_positive(item.get("open"))
        close = finite_positive(item.get("close"))
        high = finite_positive(item.get("high"))
        low = finite_positive(item.get("low"))
        if trade_date is None or trade_date > today or not validate_ohlc(open_price, high, low, close):
            continue
        source = str(item.get("source") or "未知来源")
        adjustment = str(item.get("adjustmentType") or item.get("adjustment_type") or "").upper()
        if adjustment != PRICE_ADJUSTMENT_TYPE:
            continue
        by_date[trade_date.isoformat()] = {
            "date": trade_date.isoformat(),
            "open": open_price,
            "close": close,
            "high": high,
            "low": low,
            "source": source,
            "adjustmentType": PRICE_ADJUSTMENT_TYPE,
        }
    return [by_date[key] for key in sorted(by_date)][-PRICE_HISTORY_LIMIT:]


def fetch_etf_daily_prices_from_eastmoney(etf: dict[str, str]) -> list[dict[str, Any]]:
    resp = http_get(
        "https://push2his.eastmoney.com/api/qt/stock/kline/get",
        params={
            "secid": etf["eastmoney_secid"],
            "klt": 101,
            "fqt": 1,
            "lmt": PRICE_HISTORY_LIMIT,
            "end": "20500101",
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": "f51,f52,f53,f54,f55,f56",
        },
        timeout=(5, 15),
        headers={"User-Agent": "Mozilla/5.0"},
    )
    resp.raise_for_status()
    body = resp.json()
    data = body.get("data") or {}
    if str(data.get("code") or etf["code"]) != etf["code"]:
        raise RuntimeError("东方财富日线代码不匹配")
    raw_items = []
    for line in data.get("klines") or []:
        parts = line.split(",") if isinstance(line, str) else []
        if len(parts) >= 6:
            raw_items.append({
                "date": parts[0], "open": parts[1], "close": parts[2],
                "high": parts[3], "low": parts[4], "source": "东方财富前复权日线",
                "adjustmentType": PRICE_ADJUSTMENT_TYPE,
            })
    prices = normalize_daily_prices(raw_items)
    if len(prices) < 20 or not is_fresh_date(
        prices[-1]["date"], now_beijing().date(), PRICE_MAX_STALENESS_DAYS
    ):
        raise RuntimeError("东方财富 ETF 前复权日线数量不足或数据过旧")
    return prices


def _extract_tencent_rows(body: Any, code: str) -> list[Any]:
    data = body.get("data") if isinstance(body, dict) else None
    if not isinstance(data, dict):
        return []
    node = data.get(code) or data.get(f"sh{code}") or data.get(f"sz{code}")
    if not isinstance(node, dict):
        return []
    rows = node.get("qfqday")
    return rows if isinstance(rows, list) else []


def fetch_etf_daily_prices_from_tencent(etf: dict[str, str]) -> list[dict[str, Any]]:
    symbol = etf["sina_code"]
    resp = http_get(
        "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get",
        params={"param": f"{symbol},day,,,{PRICE_HISTORY_LIMIT},qfq"},
        timeout=(5, 15),
        headers={"User-Agent": "Mozilla/5.0", "Referer": "https://gu.qq.com/"},
    )
    resp.raise_for_status()
    body = resp.json()
    if not isinstance(body, dict) or str(body.get("code", 0)) not in ("0", "200"):
        raise RuntimeError(f"腾讯日线业务错误: {body.get('code') if isinstance(body, dict) else 'invalid'}")
    raw_items = []
    for row in _extract_tencent_rows(body, symbol):
        if isinstance(row, list) and len(row) >= 5:
            raw_items.append({
                "date": row[0], "open": row[1], "close": row[2],
                "high": row[3], "low": row[4], "source": "腾讯前复权日线",
                "adjustmentType": PRICE_ADJUSTMENT_TYPE,
            })
    prices = normalize_daily_prices(raw_items)
    if len(prices) < 20 or not is_fresh_date(
        prices[-1]["date"], now_beijing().date(), PRICE_MAX_STALENESS_DAYS
    ):
        raise RuntimeError("腾讯 ETF 前复权日线数量不足或数据过旧")
    return prices


def fetch_etf_daily_prices(etf: dict[str, str]) -> list[dict[str, Any]]:
    errors = []
    for fetcher in (fetch_etf_daily_prices_from_eastmoney, fetch_etf_daily_prices_from_tencent):
        try:
            return fetcher(etf)
        except Exception as e:
            errors.append(f"{getattr(fetcher, '__name__', '行情适配器')}: {e}")
            print(f"  ⚠️ {etf['name']} 历史源失败: {e}")
    raise RuntimeError("外部历史双源失败: " + "; ".join(errors))


def pct_return(current: Optional[float], baseline: Optional[float]) -> Optional[float]:
    if current is None or baseline is None or baseline <= 0:
        return None
    return (current / baseline - 1) * 100


def parse_iso_date(value: Any) -> Optional[date]:
    text = str(value or "")[:10]
    try:
        return datetime.strptime(text, "%Y-%m-%d").date()
    except ValueError:
        return None


def subtract_calendar_months(value: date, months: int) -> date:
    year = value.year
    month = value.month - months
    while month <= 0:
        month += 12
        year -= 1
    day = min(value.day, calendar.monthrange(year, month)[1])
    return date(year, month, day)


def subtract_calendar_month(value: date) -> date:
    return subtract_calendar_months(value, 1)


def is_fresh_date(value: Any, reference_date: date, max_staleness_days: int) -> bool:
    parsed = parse_iso_date(value)
    return (
        parsed is not None
        and parsed <= reference_date
        and (reference_date - parsed).days <= max_staleness_days
    )


def latest_observation_on_or_before(
    observations: list[dict[str, Any]],
    target_date: date,
    date_key: str = "date",
    max_staleness_days: Optional[int] = None,
) -> Optional[dict[str, Any]]:
    candidates = []
    for item in observations:
        item_date = parse_iso_date(item.get(date_key))
        if item_date is None or item_date > target_date:
            continue
        if max_staleness_days is not None and (target_date - item_date).days > max_staleness_days:
            continue
        candidates.append((item_date, item))
    return max(candidates, key=lambda pair: pair[0])[1] if candidates else None


def backend_headers() -> dict[str, str]:
    token = os.environ.get("REPORT_INGEST_TOKEN", "")
    return {"X-Ingest-Token": token} if token else {}


def fetch_cached_etf_prices(etf: dict[str, str]) -> list[dict[str, Any]]:
    backend_url = os.environ.get("BACKEND_API_URL", "").rstrip("/")
    if not backend_url:
        return []
    try:
        resp = http_get(
            f"{backend_url}/api/etf-prices/{etf['code']}/latest",
            params={"limit": PRICE_CACHE_QUERY_LIMIT, "adjustmentType": PRICE_ADJUSTMENT_TYPE},
            headers=backend_headers(),
            timeout=(4, 10),
        )
        body = backend_result(resp, f"{etf['name']}价格缓存查询")
        data = body.get("data") if body else []
        if not isinstance(data, list):
            raise RuntimeError("价格缓存响应 data 必须是数组")
        return data
    except Exception as e:
        print(f"  ⚠️ {etf['name']} 价格缓存查询失败: {e}")
        return []


def select_cached_price_series(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[str, list[dict[str, Any]]] = {}
    for item in items:
        source = str(item.get("source") or "后端价格缓存")
        groups.setdefault(source, []).append({**item, "source": source})
    candidates = []
    for source, values in groups.items():
        values = normalize_daily_prices(values)
        if not values:
            continue
        run = 1
        dates = [parse_iso_date(item["date"]) for item in values]
        for index in range(len(dates) - 1, 0, -1):
            gap = (dates[index] - dates[index - 1]).days
            if 1 <= gap <= 4:
                run += 1
            else:
                break
        candidates.append((values[-1]["date"], run, len(values), source, values))
    if not candidates:
        return []
    latest_date = max(candidate[0] for candidate in candidates)
    latest_candidates = [candidate for candidate in candidates if candidate[0] == latest_date]
    return max(latest_candidates, key=lambda candidate: (candidate[1], candidate[2]))[4]


def quote_from_cached_prices(
    etf: dict[str, str],
    cached_items: list[dict[str, Any]],
) -> Optional[dict[str, Any]]:
    prices = select_cached_price_series(cached_items)
    if len(prices) < 2:
        return None
    latest = prices[-1]
    latest_date = parse_iso_date(latest.get("date"))
    if (
        latest_date is None
        or (now_beijing().date() - latest_date).days > PRICE_MAX_STALENESS_DAYS
    ):
        return None
    previous_close = prices[-2]["close"] if len(prices) > 1 else None
    latest_close = latest["close"]
    return validate_quote({
        "name": etf["name"],
        "code": etf["code"],
        "latest_price": latest_close,
        "change_amount": (
            latest_close - previous_close if previous_close is not None else None
        ),
        "pct_change": pct_return(latest_close, previous_close),
        "open": latest["open"],
        "high": latest["high"],
        "low": latest["low"],
        "previous_close": previous_close,
        "volume": None,
        "amount": None,
        "data_time": f"{latest_date.isoformat()} 15:00:00",
        "source": f"{latest['source']}（后端缓存最近确认收盘）",
        "data_status": "cached_close",
    }, etf)


def push_etf_price_history(
    etf: dict[str, str],
    prices: list[dict[str, Any]],
    quote: dict[str, Any],
    cached_items: Optional[list[dict[str, Any]]] = None,
) -> bool:
    backend_url = os.environ.get("BACKEND_API_URL", "").rstrip("/")
    token = os.environ.get("REPORT_INGEST_TOKEN", "")
    quote_date = parse_iso_date(quote.get("data_time"))
    if not backend_url or not token or quote_date is None:
        return False
    cached_by_identity = {
        (str(item.get("tradeDate") or item.get("date")), str(item.get("source") or "")): item
        for item in (cached_items or [])
        if str(item.get("adjustmentType") or "").upper() == PRICE_ADJUSTMENT_TYPE
    }
    completed = []
    for item in prices:
        trade_date = parse_iso_date(item.get("date"))
        if trade_date is None or trade_date >= quote_date:
            continue
        cached = cached_by_identity.get((item["date"], item["source"]))
        if cached and all(
            to_optional_float(cached.get(field)) == to_optional_float(item.get(field))
            for field in ("open", "high", "low", "close")
        ):
            continue
        completed.append(item)
    if not completed:
        return True
    fetched_at = now_beijing().replace(tzinfo=None).isoformat(timespec="seconds")
    payload = [{
        "fundCode": etf["code"],
        "fundName": etf["name"],
        "tradeDate": item["date"],
        "open": item["open"],
        "close": item["close"],
        "high": item["high"],
        "low": item["low"],
        "source": item["source"],
        "adjustmentType": PRICE_ADJUSTMENT_TYPE,
        "fetchedAt": fetched_at,
    } for item in completed]
    try:
        for start in range(0, len(payload), PRICE_INGEST_BATCH_SIZE):
            batch = payload[start:start + PRICE_INGEST_BATCH_SIZE]
            resp = requests.post(
                f"{backend_url}/api/etf-prices/ingest",
                json=batch,
                headers=backend_headers(),
                timeout=(4, 12),
            )
            if backend_result(resp, f"{etf['name']}价格缓存同步") is None:
                return False
        return True
    except Exception as e:
        print(f"  ⚠️ {etf['name']} 价格缓存同步失败: {e}")
        return False


def build_price_context(
    quote: dict[str, Any],
    daily_prices: list[dict[str, Any]],
    data_status: str = "live",
    error: Optional[str] = None,
) -> dict[str, Any]:
    current = quote["latest_price"]
    quote_date_text = str(quote.get("data_time") or "")[:10]
    quote_date = parse_iso_date(quote_date_text)
    if quote_date is None:
        raise RuntimeError("实时行情日期格式异常")
    completed = [item for item in daily_prices if item["date"] < quote_date_text]
    current_values = [value for value in (current, quote.get("high"), quote.get("low")) if value is not None]
    if not current_values:
        raise RuntimeError("实时价格不可确认")
    recent_month = (completed + [{
        "date": quote_date_text,
        "close": current,
        "high": max(current_values),
        "low": min(current_values),
    }])[-20:]
    previous_item = latest_observation_on_or_before(
        completed, quote_date - timedelta(days=1), max_staleness_days=15
    )
    week_item = latest_observation_on_or_before(
        completed, quote_date - timedelta(days=7), max_staleness_days=15
    )
    month_item = latest_observation_on_or_before(
        completed, subtract_calendar_month(quote_date), max_staleness_days=15
    )
    half_year_item = latest_observation_on_or_before(
        completed, subtract_calendar_months(quote_date, 6), max_staleness_days=15
    )
    year_item = latest_observation_on_or_before(
        completed, subtract_years(quote_date, 1), max_staleness_days=15
    )
    three_year_item = latest_observation_on_or_before(
        completed, subtract_years(quote_date, 3), max_staleness_days=15
    )
    previous_close = to_optional_float(quote.get("previous_close"))
    if previous_close is None and previous_item:
        previous_close = previous_item["close"]
    week_baseline = week_item["close"] if week_item else None
    month_baseline = month_item["close"] if month_item else None
    half_year_baseline = half_year_item["close"] if half_year_item else None
    year_baseline = year_item["close"] if year_item else None
    three_year_baseline = three_year_item["close"] if three_year_item else None
    month_high = max((item["high"] for item in recent_month), default=None)
    month_low = min((item["low"] for item in recent_month), default=None)
    range_position = None
    if current is not None and month_high is not None and month_low is not None and month_high > month_low:
        range_position = (current - month_low) / (month_high - month_low) * 100
    return {
        "previous_close": previous_close,
        "previous_date": previous_item["date"] if previous_item else None,
        "week_baseline": week_baseline,
        "week_baseline_date": week_item["date"] if week_item else None,
        "month_baseline": month_baseline,
        "month_baseline_date": month_item["date"] if month_item else None,
        "half_year_baseline": half_year_baseline,
        "half_year_baseline_date": half_year_item["date"] if half_year_item else None,
        "year_baseline": year_baseline,
        "year_baseline_date": year_item["date"] if year_item else None,
        "three_year_baseline": three_year_baseline,
        "three_year_baseline_date": three_year_item["date"] if three_year_item else None,
        "week_pct_change": pct_return(current, week_baseline),
        "month_pct_change": pct_return(current, month_baseline),
        "half_year_pct_change": pct_return(current, half_year_baseline),
        "year_pct_change": pct_return(current, year_baseline),
        "three_year_pct_change": pct_return(current, three_year_baseline),
        "month_high": month_high,
        "month_low": month_low,
        "distance_from_month_high": pct_return(current, month_high),
        "month_range_position": range_position,
        "history_days": len(completed),
        "source": daily_prices[-1].get("source") if daily_prices else "不可确认",
        "adjustmentType": PRICE_ADJUSTMENT_TYPE,
        "data_status": data_status,
        "error": error,
        "as_of": completed[-1]["date"] if completed else None,
    }


def empty_price_context(
    error: str,
    previous_close: Optional[float] = None,
) -> dict[str, Any]:
    return {
        "previous_close": previous_close,
        "previous_date": None,
        "week_baseline": None,
        "week_baseline_date": None,
        "month_baseline": None,
        "month_baseline_date": None,
        "half_year_baseline": None,
        "half_year_baseline_date": None,
        "year_baseline": None,
        "year_baseline_date": None,
        "three_year_baseline": None,
        "three_year_baseline_date": None,
        "week_pct_change": None,
        "month_pct_change": None,
        "half_year_pct_change": None,
        "year_pct_change": None,
        "three_year_pct_change": None,
        "month_high": None,
        "month_low": None,
        "distance_from_month_high": None,
        "month_range_position": None,
        "history_days": 0,
        "source": "不可确认",
        "adjustmentType": PRICE_ADJUSTMENT_TYPE,
        "data_status": "unavailable",
        "error": error,
        "as_of": None,
    }


def fetch_price_context(
    etf: dict[str, str],
    quote: dict[str, Any],
    cached_items: Optional[list[dict[str, Any]]] = None,
) -> dict[str, Any]:
    if cached_items is None:
        cached_items = fetch_cached_etf_prices(etf)
    external_error = None
    try:
        prices = fetch_etf_daily_prices(etf)
        context = build_price_context(quote, prices, "external")
        if push_etf_price_history(etf, prices, quote, cached_items):
            context["data_status"] = "external_cached"
        print(f"  ✅ {etf['name']} 价格趋势来自 {context['source']}")
        return context
    except Exception as e:
        external_error = str(e)
        print(f"  ⚠️ {etf['name']} 外部价格历史失败: {e}")
    cached = select_cached_price_series(cached_items)
    if cached:
        context = build_price_context(quote, cached, "cache", external_error)
        context["source"] = f"{context['source']}（后端缓存）"
        print(f"  ✅ {etf['name']} 价格趋势使用单一来源后端缓存")
        return context
    return empty_price_context(
        f"外部历史失败且无可用单一来源缓存: {external_error}",
        to_optional_float(quote.get("previous_close")),
    )


def premium_level(premium_rate: Optional[float]) -> str:
    if premium_rate is None:
        return "不可确认"
    if premium_rate > 2:
        return "溢价偏高"
    if premium_rate > 0.5:
        return "小幅溢价"
    if premium_rate < -0.5:
        return "折价"
    return "接近净值"


def fetch_etf_premium(etf: dict[str, str], quote: dict[str, Any]) -> dict[str, Any]:
    try:
        resp = http_get(
            "https://push2.eastmoney.com/api/qt/stock/get",
            params={
                "secid": etf["eastmoney_secid"],
                "fields": "f2,f57,f58,f124,f402,f441",
            },
            timeout=(4, 8),
            headers={"User-Agent": "Mozilla/5.0", "Referer": "https://quote.eastmoney.com/"},
        )
        resp.raise_for_status()
        data = resp.json().get("data") or {}
        if str(data.get("f57") or "") != etf["code"]:
            raise RuntimeError("东方财富ETF溢价数据代码不匹配")
        ts = data.get("f124")
        data_time = "不可确认"
        premium_date = None
        if ts:
            premium_datetime = datetime.fromtimestamp(int(ts), BEIJING_TZ)
            premium_date = premium_datetime.date()
            data_time = premium_datetime.strftime("%Y-%m-%d %H:%M:%S")
        quote_date = parse_iso_date(quote.get("data_time"))
        if premium_date is None or quote_date is None or premium_date != quote_date:
            return {
                "premium_rate": None,
                "level": "IOPV未与行情同步",
                "estimated_nav": None,
                "data_time": data_time,
                "source": "东方财富ETF实时IOPV",
                "reference_only": etf_is_qdii(etf),
                "data_status": "stale_source",
                "error": "IOPV日期与行情日期不一致",
            }
        estimated_nav = to_optional_float(data.get("f441"))
        listed_rate = None
        try:
            raw_listed_rate = data.get("f402")
            listed_rate = float(raw_listed_rate) if raw_listed_rate not in (None, "", "-") else None
        except (TypeError, ValueError):
            listed_rate = None
        latest_price = to_optional_float(data.get("f2")) or to_optional_float(quote.get("latest_price"))
        calculated_rate = pct_return(latest_price, estimated_nav)
        premium_rate = calculated_rate if calculated_rate is not None else (
            -listed_rate if listed_rate is not None else None
        )
        if listed_rate is not None and calculated_rate is not None and abs(listed_rate + calculated_rate) > 0.2:
            print(
                f"  ⚠️ {etf['name']} 折价率字段与IOPV计算差异 "
                f"{abs(listed_rate + calculated_rate):.2f} 个百分点"
            )
        if premium_rate is None:
            raise RuntimeError("东方财富ETF溢价响应缺少有效IOPV和折价率")
        return {
            "premium_rate": premium_rate,
            "level": premium_level(premium_rate),
            "estimated_nav": estimated_nav,
            "data_time": data_time,
            "source": "东方财富ETF实时IOPV",
            "reference_only": etf_is_qdii(etf),
            "data_status": "available",
            "error": None,
        }
    except Exception as e:
        print(f"  ⚠️ {etf['name']} 溢价率抓取失败: {e}")
        return {
            "premium_rate": None,
            "level": "不可确认",
            "estimated_nav": None,
            "data_time": "不可确认",
            "source": "东方财富ETF实时IOPV",
            "reference_only": etf_is_qdii(etf),
            "data_status": "provider_error",
            "error": f"东方财富ETF实时IOPV不可用: {e}",
        }


def empty_premium_history_fields() -> dict[str, Any]:
    return {
        "display_rate": None,
        "display_date": None,
        "previous_rate": None,
        "previous_date": None,
        "week_rate": None,
        "week_date": None,
        "month_rate": None,
        "month_date": None,
        "half_year_rate": None,
        "half_year_date": None,
        "year_rate": None,
        "year_date": None,
        "three_year_rate": None,
        "three_year_date": None,
        "history_source": None,
        "history_error": None,
    }


def normalize_nav_rows(rows: list[Any]) -> list[dict[str, Any]]:
    today = now_beijing().date()
    by_date: dict[str, dict[str, Any]] = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        trade_date = parse_iso_date(row.get("FSRQ") or row.get("date"))
        nav = finite_positive(row.get("DWJZ") or row.get("nav"))
        if trade_date is None or trade_date > today or nav is None:
            continue
        by_date[trade_date.isoformat()] = {"date": trade_date.isoformat(), "nav": nav}
    return [by_date[key] for key in sorted(by_date)]


def normalize_unadjusted_closes(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    today = now_beijing().date()
    by_date: dict[str, dict[str, Any]] = {}
    for item in items:
        trade_date = parse_iso_date(item.get("date"))
        close = finite_positive(item.get("close"))
        if trade_date is None or trade_date > today or close is None:
            continue
        by_date[trade_date.isoformat()] = {
            "date": trade_date.isoformat(),
            "close": close,
            "source": str(item.get("source") or "不复权收盘"),
        }
    return [by_date[key] for key in sorted(by_date)]


def fetch_etf_nav_history_from_eastmoney(etf: dict[str, str]) -> list[dict[str, Any]]:
    rows: list[Any] = []
    for page in range(1, NAV_HISTORY_PAGES + 1):
        resp = http_get(
            "https://api.fund.eastmoney.com/f10/lsjz",
            params={"fundCode": etf["code"], "pageIndex": page, "pageSize": NAV_PAGE_SIZE},
            timeout=(5, 15),
            headers=EASTMONEY_FUND_HEADERS,
        )
        resp.raise_for_status()
        body = resp.json()
        data = body.get("Data") if isinstance(body, dict) else None
        page_rows = data.get("LSJZList") if isinstance(data, dict) else None
        if not isinstance(page_rows, list) or not page_rows:
            break
        rows.extend(page_rows)
    navs = normalize_nav_rows(rows)
    if len(navs) < 5:
        raise RuntimeError("东方财富单位净值数量不足")
    return navs


def fetch_etf_unadjusted_closes_from_eastmoney(etf: dict[str, str]) -> list[dict[str, Any]]:
    resp = http_get(
        "https://push2his.eastmoney.com/api/qt/stock/kline/get",
        params={
            "secid": etf["eastmoney_secid"],
            "klt": 101,
            "fqt": 0,
            "lmt": UNADJUSTED_CLOSE_LIMIT,
            "end": "20500101",
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": "f51,f52,f53,f54,f55,f56",
        },
        timeout=(5, 15),
        headers={"User-Agent": "Mozilla/5.0"},
    )
    resp.raise_for_status()
    data = resp.json().get("data") or {}
    if str(data.get("code") or etf["code"]) != etf["code"]:
        raise RuntimeError("东方财富不复权日线代码不匹配")
    raw_items = []
    for line in data.get("klines") or []:
        parts = line.split(",") if isinstance(line, str) else []
        if len(parts) >= 3:
            raw_items.append({
                "date": parts[0],
                "close": parts[2],
                "source": "东方财富不复权日线",
            })
    closes = normalize_unadjusted_closes(raw_items)
    if len(closes) < 10:
        raise RuntimeError("东方财富不复权收盘数量不足")
    return closes


def fetch_etf_unadjusted_closes_from_tencent(etf: dict[str, str]) -> list[dict[str, Any]]:
    symbol = etf["sina_code"]
    resp = http_get(
        "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get",
        params={"param": f"{symbol},day,,,{UNADJUSTED_CLOSE_LIMIT},"},
        timeout=(5, 15),
        headers={"User-Agent": "Mozilla/5.0", "Referer": "https://gu.qq.com/"},
    )
    resp.raise_for_status()
    body = resp.json()
    data = body.get("data") if isinstance(body, dict) else None
    node = (data.get(symbol) or data.get(f"sh{etf['code']}") or data.get(f"sz{etf['code']}")) if isinstance(data, dict) else None
    rows = node.get("day") if isinstance(node, dict) else None
    if not isinstance(rows, list):
        raise RuntimeError("腾讯不复权日线缺少 day 列表")
    raw_items = []
    for row in rows:
        if isinstance(row, list) and len(row) >= 3:
            raw_items.append({
                "date": row[0],
                "close": row[2],
                "source": "腾讯不复权日线",
            })
    closes = normalize_unadjusted_closes(raw_items)
    if len(closes) < 10:
        raise RuntimeError("腾讯不复权收盘数量不足")
    return closes


def fetch_etf_unadjusted_closes(etf: dict[str, str]) -> list[dict[str, Any]]:
    errors = []
    for fetcher in (fetch_etf_unadjusted_closes_from_eastmoney, fetch_etf_unadjusted_closes_from_tencent):
        try:
            return fetcher(etf)
        except Exception as e:
            errors.append(f"{getattr(fetcher, '__name__', '收盘适配器')}: {e}")
    raise RuntimeError("不复权收盘双源失败: " + "; ".join(errors))


def pair_premium_observations(
    closes: list[dict[str, Any]],
    navs: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    nav_by_date = {item["date"]: item["nav"] for item in navs}
    observations = []
    for item in closes:
        close = finite_positive(item.get("close"))
        nav = nav_by_date.get(item["date"])
        rate = pct_return(close, nav)
        if rate is None:
            continue
        observations.append({
            "date": item["date"],
            "close": close,
            "nav": nav,
            "premium_rate": rate,
        })
    return observations


def premium_history_anchor(premium: dict[str, Any], quote: dict[str, Any]) -> date:
    if premium.get("premium_rate") is not None:
        iopv_date = parse_iso_date(premium.get("data_time"))
        if iopv_date is not None:
            return iopv_date
    quote_date = parse_iso_date(quote.get("data_time"))
    if quote_date is not None:
        return quote_date
    return now_beijing().date()


def enrich_premium_with_history(
    etf: dict[str, str],
    premium: dict[str, Any],
    quote: dict[str, Any],
) -> dict[str, Any]:
    fields = empty_premium_history_fields()
    try:
        navs = fetch_etf_nav_history_from_eastmoney(etf)
        closes = fetch_etf_unadjusted_closes(etf)
        observations = pair_premium_observations(closes, navs)
        if not observations:
            raise RuntimeError("净值与不复权收盘没有同一天可配对")
        anchor = premium_history_anchor(premium, quote)
        latest = latest_observation_on_or_before(
            observations, anchor, max_staleness_days=PREMIUM_HISTORY_STALENESS_DAYS
        )
        history_anchor = parse_iso_date(latest["date"]) if latest and premium.get("premium_rate") is None else anchor
        if history_anchor is None:
            history_anchor = anchor
        previous = latest_observation_on_or_before(
            observations, history_anchor - timedelta(days=1), max_staleness_days=PREMIUM_HISTORY_STALENESS_DAYS
        )
        week = latest_observation_on_or_before(
            observations, history_anchor - timedelta(days=7), max_staleness_days=PREMIUM_HISTORY_STALENESS_DAYS
        )
        month = latest_observation_on_or_before(
            observations, subtract_calendar_month(history_anchor), max_staleness_days=PREMIUM_HISTORY_STALENESS_DAYS
        )
        half_year = latest_observation_on_or_before(
            observations, subtract_calendar_months(history_anchor, 6), max_staleness_days=PREMIUM_HISTORY_STALENESS_DAYS
        )
        year = latest_observation_on_or_before(
            observations, subtract_years(history_anchor, 1), max_staleness_days=PREMIUM_HISTORY_STALENESS_DAYS
        )
        three_year = latest_observation_on_or_before(
            observations, subtract_years(history_anchor, 3), max_staleness_days=PREMIUM_HISTORY_STALENESS_DAYS
        )
        fields.update({
            "display_rate": latest["premium_rate"] if latest else None,
            "display_date": latest["date"] if latest else None,
            "previous_rate": previous["premium_rate"] if previous else None,
            "previous_date": previous["date"] if previous else None,
            "week_rate": week["premium_rate"] if week else None,
            "week_date": week["date"] if week else None,
            "month_rate": month["premium_rate"] if month else None,
            "month_date": month["date"] if month else None,
            "half_year_rate": half_year["premium_rate"] if half_year else None,
            "half_year_date": half_year["date"] if half_year else None,
            "year_rate": year["premium_rate"] if year else None,
            "year_date": year["date"] if year else None,
            "three_year_rate": three_year["premium_rate"] if three_year else None,
            "three_year_date": three_year["date"] if three_year else None,
            "history_source": "东方财富单位净值+不复权收盘",
        })
    except Exception as e:
        fields["history_error"] = str(e)
        print(f"  ⚠️ {etf['name']} 历史溢价失败: {e}")
    return {**premium, **fields}


def valuation_level(percentile_value: Optional[float]) -> str:
    if percentile_value is None:
        return "已提供 PE，未提供分位"
    if percentile_value < 30:
        return "偏低"
    if percentile_value <= 70:
        return "合理"
    return "偏高"


def normalize_percentile(value: Any) -> Optional[float]:
    number = to_optional_float(value)
    return number if number is not None and math.isfinite(number) and 0 <= number <= 100 else None


def validate_valuation(
    valuation: dict[str, Any],
    expected_method: Optional[str] = None,
    reference_date: Optional[date] = None,
) -> dict[str, Any]:
    pe_value = finite_positive(valuation.get("pe_ttm"), 300)
    percentile = normalize_percentile(valuation.get("pe_percentile"))
    updated_at = parse_iso_date(valuation.get("updated_at"))
    method = valuation.get("percentile_method") or valuation.get("percentileMethod")
    reference = reference_date or now_beijing().date()
    if pe_value is None:
        raise RuntimeError("PE 必须是有限正数且不超过 300")
    if percentile is None:
        raise RuntimeError("PE 分位必须在 0-100")
    if updated_at is None or updated_at > reference:
        raise RuntimeError("估值日期无效或位于未来")
    if not method:
        raise RuntimeError("估值缺少 percentile_method")
    if expected_method and method != expected_method:
        raise RuntimeError(f"估值口径不匹配: {method} != {expected_method}")
    valuation["pe_ttm"] = pe_value
    valuation["pe_percentile"] = percentile
    valuation["percentile_method"] = method
    valuation["percentileMethod"] = method
    valuation["updated_at"] = updated_at.isoformat()
    return valuation


def danjuan_headers() -> dict[str, str]:
    return {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        ),
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer": "https://danjuanfunds.com/djmodule/value-center",
    }


def valuation_from_danjuan_item(
    etf: dict[str, str],
    item: dict[str, Any],
    source: str = "蛋卷基金指数估值",
) -> dict[str, Any]:
    pe_value = to_optional_float(item.get("pe"))
    raw_percentile = to_optional_float(item.get("pe_percentile"))
    percentile_value = raw_percentile * 100 if raw_percentile is not None and 0 <= raw_percentile <= 1 else None
    ts = item.get("ts")
    updated_at = None
    if ts:
        try:
            updated_at = datetime.fromtimestamp(int(ts) / 1000, BEIJING_TZ).strftime("%Y-%m-%d")
        except (TypeError, ValueError):
            updated_at = None
    if updated_at is None:
        raw_date = str(item.get("date") or "")
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", raw_date):
            updated_at = raw_date
    return validate_valuation({
        "index_name": item.get("name") or etf["index_name"],
        "pe_ttm": pe_value,
        "pe_percentile": percentile_value,
        "percentile_method": DANJUAN_PE_TTM_PROVIDER,
        "percentileMethod": DANJUAN_PE_TTM_PROVIDER,
        "valuation_level": valuation_level(percentile_value),
        "source": source,
        "updated_at": updated_at,
    }, DANJUAN_PE_TTM_PROVIDER)


def fetch_valuation_from_danjuan(etf: dict[str, str]) -> dict[str, Any]:
    resp = http_get(
        "https://danjuanfunds.com/djapi/index_eva/dj",
        timeout=15,
        headers=danjuan_headers(),
    )
    resp.raise_for_status()
    body = resp.json()
    if not isinstance(body, dict):
        raise RuntimeError("蛋卷估值响应格式异常")
    if str(body.get("result_code", 0)) not in ("0", "200"):
        raise RuntimeError(f"蛋卷估值业务错误: {body.get('result_code')} {body.get('result_msg', '')}")
    items = (body.get("data") or {}).get("items") or []
    target_code = etf["valuation_index_code"].upper()
    item = next(
        (item for item in items if str(item.get("index_code", "")).upper() == target_code),
        None,
    )
    if not item:
        raise RuntimeError(f"蛋卷估值未找到 {etf['valuation_index_code']}")
    valuation = valuation_from_danjuan_item(etf, item)
    if valuation["pe_ttm"] is None or valuation["pe_percentile"] is None or not valuation["updated_at"]:
        raise RuntimeError("蛋卷估值缺少PE、分位或有效日期")
    return valuation


def subtract_years(value: date, years: int) -> date:
    try:
        return value.replace(year=value.year - years)
    except ValueError:
        return value.replace(year=value.year - years, day=28)


def fetch_csi300_pe_history() -> list[dict[str, Any]]:
    resp = http_get(
        "https://www.csindex.com.cn/csindex-home/perf/indexCsiDsPe",
        params={"indexCode": "000300"},
        headers={"User-Agent": "Mozilla/5.0", "Referer": "https://www.csindex.com.cn/"},
        timeout=20,
    )
    resp.raise_for_status()
    body = resp.json()
    if str(body.get("code")) != "200" or not body.get("success"):
        raise RuntimeError(f"中证指数PE接口业务错误: {body.get('code')} {body.get('msg', '')}")

    points = []
    today = now_beijing().date()
    for item in body.get("data") or []:
        trade_date = str(item.get("tradeDate") or "")
        pe_value = finite_positive(item.get("peg"), 300)
        if not re.fullmatch(r"\d{8}", trade_date) or pe_value is None:
            continue
        parsed_date = datetime.strptime(trade_date, "%Y%m%d").date()
        if parsed_date <= today:
            points.append((parsed_date, pe_value))
    points.sort(key=lambda point: point[0])
    if not points:
        raise RuntimeError("中证指数未返回有效PE历史")

    history = []
    window_values: list[float] = []
    window_start_index = 0
    for trade_date, pe_value in points:
        minimum_date = subtract_years(trade_date, CSI300_PE_WINDOW_YEARS)
        while window_start_index < len(points) and points[window_start_index][0] < minimum_date:
            expired_value = points[window_start_index][1]
            value_index = bisect_left(window_values, expired_value)
            if value_index < len(window_values) and window_values[value_index] == expired_value:
                window_values.pop(value_index)
            window_start_index += 1
        insort(window_values, pe_value)
        percentile = bisect_right(window_values, pe_value) / len(window_values) * 100
        history.append({
            "tradeDate": trade_date.isoformat(),
            "peTtm": pe_value,
            "pePercentile": percentile,
            "percentileMethod": CSI_PE_TTM_ROLLING_10Y,
            "percentile_method": CSI_PE_TTM_ROLLING_10Y,
            "source": f"中证指数官网PE(TTM)，滚动{CSI300_PE_WINDOW_YEARS}年分位",
        })
    return history


_VALUATION_ARCHIVE_CACHE: dict[str, Optional[list[dict[str, Any]]]] = {}


def fetch_valuation_archive(snapshot_date: date) -> dict[str, Any]:
    date_text = snapshot_date.isoformat()
    if date_text in _VALUATION_ARCHIVE_CACHE:
        return {"status": "ok" if _VALUATION_ARCHIVE_CACHE[date_text] is not None else "missing", "items": _VALUATION_ARCHIVE_CACHE[date_text]}
    try:
        resp = http_get(
            VALUATION_ARCHIVE_URL.format(date=date_text),
            timeout=(4, 8),
            headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json"},
        )
        if resp.status_code == 404:
            _VALUATION_ARCHIVE_CACHE[date_text] = None
            return {"status": "missing", "items": None, "error": "HTTP 404"}
        resp.raise_for_status()
        body = resp.json()
        if isinstance(body, list):
            items = body
        elif isinstance(body, dict) and isinstance(body.get("data"), dict):
            items = body["data"].get("items") or []
        else:
            raise ValueError("估值快照响应格式异常")
        if not isinstance(items, list):
            raise ValueError("估值快照 items 格式异常")
        _VALUATION_ARCHIVE_CACHE[date_text] = items
        return {"status": "ok", "items": items}
    except (requests.RequestException, ValueError) as e:
        print(f"  ⚠️ {date_text} 蛋卷估值公开快照传输失败: {e}")
        return {"status": "transport_error", "items": None, "error": str(e)}


def fetch_archived_valuation_on_or_before(
    etf: dict[str, str],
    target_date: date,
    max_lookback_days: int = PE_LOOKBACK_DAYS,
) -> Optional[dict[str, Any]]:
    target_code = etf["valuation_index_code"].upper()
    best = None
    consecutive_transport_failures = 0
    for offset in range(max_lookback_days + 1):
        snapshot_date = target_date - timedelta(days=offset)
        result = fetch_valuation_archive(snapshot_date)
        if result.get("status") == "transport_error":
            consecutive_transport_failures += 1
            if consecutive_transport_failures >= 3:
                break
            continue
        consecutive_transport_failures = 0
        items = result.get("items") or []
        item = next(
            (item for item in items if str(item.get("index_code", "")).upper() == target_code),
            None,
        )
        if not item:
            continue
        try:
            valuation = valuation_from_danjuan_item(etf, item, "蛋卷估值每日公开快照")
        except RuntimeError as e:
            print(f"  ⚠️ {snapshot_date.isoformat()} 蛋卷估值快照字段无效: {e}")
            continue
        effective_date = parse_iso_date(valuation.get("updated_at"))
        if effective_date is not None and effective_date <= target_date:
            if best is None or effective_date > parse_iso_date(best["updated_at"]):
                best = valuation
        if best is not None and parse_iso_date(best["updated_at"]) == target_date:
            break
    return best


def valuation_to_history_item(valuation: dict[str, Any]) -> Optional[dict[str, Any]]:
    trade_date = str(valuation.get("updated_at") or "")
    if parse_iso_date(trade_date) is None:
        return None
    percentile = to_optional_float(valuation.get("pe_percentile"))
    if percentile is None:
        return None
    return {
        "tradeDate": trade_date,
        "peTtm": to_optional_float(valuation.get("pe_ttm")),
        "pePercentile": percentile,
        "percentileMethod": valuation.get("percentile_method") or valuation.get("percentileMethod"),
        "percentile_method": valuation.get("percentile_method") or valuation.get("percentileMethod"),
        "source": valuation.get("source"),
    }


def fetch_source_valuation(
    etf: dict[str, str],
    backend_history: Optional[list[dict[str, Any]]] = None,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    if etf["valuation_index_code"] == "SH000300":
        history = fetch_csi300_pe_history()
        latest = history[-1]
        percentile = latest["pePercentile"]
        valuation = validate_valuation({
            "index_name": etf["index_name"],
            "pe_ttm": latest["peTtm"],
            "pe_percentile": percentile,
            "percentile_method": CSI_PE_TTM_ROLLING_10Y,
            "percentileMethod": CSI_PE_TTM_ROLLING_10Y,
            "valuation_level": valuation_level(percentile),
            "source": latest["source"],
            "updated_at": latest["tradeDate"],
        }, CSI_PE_TTM_ROLLING_10Y)
        if not is_fresh_date(
            valuation["updated_at"],
            now_beijing().date(),
            CURRENT_VALUATION_MAX_STALENESS_DAYS,
        ):
            raise RuntimeError("中证指数PE数据过旧")
        return valuation, history

    valuation = None
    try:
        realtime = fetch_valuation_from_danjuan(etf)
        if (
            to_optional_float(realtime.get("pe_ttm")) is not None
            and to_optional_float(realtime.get("pe_percentile")) is not None
            and is_fresh_date(
                valuation_trade_date(realtime),
                now_beijing().date(),
                CURRENT_VALUATION_MAX_STALENESS_DAYS,
            )
        ):
            valuation = realtime
        else:
            print(f"  ⚠️ {etf['name']} 蛋卷实时估值数据过旧，改用公开快照")
    except Exception as e:
        print(f"  ⚠️ {etf['name']} 蛋卷实时估值抓取失败: {e}")
    if valuation is None:
        valuation = fetch_archived_valuation_on_or_before(etf, now_beijing().date())
        if valuation is None:
            raise RuntimeError(f"{etf['name']} 蛋卷实时估值和公开快照均不可用")
        if valuation.get("source"):
            valuation["source"] += "（最近有效值）"
    if (
        to_optional_float(valuation.get("pe_ttm")) is None
        or to_optional_float(valuation.get("pe_percentile")) is None
        or not is_fresh_date(
            valuation_trade_date(valuation),
            now_beijing().date(),
            CURRENT_VALUATION_MAX_STALENESS_DAYS,
        )
    ):
        raise RuntimeError(f"{etf['name']} 实时估值和公开快照均不可用或数据过旧")

    history = merge_pe_history(
        backend_history or [],
        percentile_method=valuation_percentile_method(etf),
    )
    current_date = parse_iso_date(valuation.get("updated_at"))
    if current_date is not None:
        targets = {
            current_date - timedelta(days=1),
            current_date - timedelta(days=7),
            subtract_calendar_month(current_date),
            subtract_calendar_months(current_date, 6),
            subtract_years(current_date, 1),
            subtract_years(current_date, 3),
        }
        for target in sorted(targets, reverse=True):
            if pe_observation_on_or_before(history, target) is not None:
                continue
            archived = fetch_archived_valuation_on_or_before(etf, target)
            item = valuation_to_history_item(archived) if archived else None
            if item:
                history.append(item)
    current_item = valuation_to_history_item(valuation)
    if current_item:
        history.append(current_item)
    return valuation, history


def fetch_valuation_from_env(etf: dict[str, str]) -> dict[str, Any]:
    prefix = etf["valuation_env_prefix"]
    pe = os.environ.get(f"{prefix}_PE")
    percentile = os.environ.get(f"{prefix}_PE_PERCENTILE")
    method = os.environ.get(f"{prefix}_PE_PERCENTILE_METHOD")
    valuation_date = os.environ.get(f"{prefix}_VALUATION_DATE")
    source = os.environ.get(f"{prefix}_VALUATION_SOURCE", "手动环境变量")
    expected_method = valuation_percentile_method(etf)

    try:
        return validate_valuation({
            "index_name": etf["index_name"],
            "pe_ttm": pe,
            "pe_percentile": percentile,
            "percentile_method": method,
            "percentileMethod": method,
            "valuation_level": valuation_level(normalize_percentile(percentile)),
            "source": source,
            "updated_at": valuation_date,
        }, expected_method)
    except RuntimeError as e:
        return {
            "index_name": etf["index_name"],
            "pe_ttm": None,
            "pe_percentile": None,
            "percentile_method": expected_method,
            "percentileMethod": expected_method,
            "valuation_level": "估值数据不可确认",
            "source": "未获取到稳定估值源",
            "updated_at": None,
            "error": f"环境变量估值无效: {e}",
        }


def fmt_signed_pct(value: Optional[float]) -> str:
    if value is None:
        return "不可确认"
    return f"{value:+.2f}%"


def fmt_compact_price(value: Optional[float]) -> Optional[str]:
    return None if value is None else f"{value:.3f}"


def fmt_compact_pct_change(value: Optional[float]) -> Optional[str]:
    if value is None:
        return None
    if abs(value) < 0.005:
        return "0.00%"
    return f"{value:+.2f}%"


def fmt_compact_pt_change(
    value: Optional[float], digits: int = 0, unit: str = "pt"
) -> Optional[str]:
    if value is None:
        return None
    if digits <= 0:
        rounded = int(round(value))
        return f"0{unit}" if rounded == 0 else f"{rounded:+d}{unit}"
    threshold = 0.5 * (10 ** -digits)
    if abs(value) < threshold:
        return f"{0:.{digits}f}{unit}"
    return f"{value:+.{digits}f}{unit}"


def fmt_plain_pct(value: Optional[float], digits: int = 2) -> Optional[str]:
    return None if value is None else f"{value:.{digits}f}%"


def join_compact_lookbacks(
    today_text: Optional[str],
    items: list[tuple[str, Optional[str], Optional[str]]],
) -> str:
    parts = [f"今 {today_text or '不可确认'}"]
    for label, value_text, change_text in items:
        if not value_text:
            continue
        parts.append(f"{label} {value_text} {change_text}" if change_text else f"{label} {value_text}")
    return "｜".join(parts)


def fmt_change_pct(value: Optional[float]) -> str:
    if value is None:
        return "不可确认"
    if value > 0:
        return f"↑ {value:+.2f}%"
    if value < 0:
        return f"↓ {value:.2f}%"
    return "— 0.00%"


def format_premium_rate(rate: Optional[float]) -> str:
    return "不可确认" if rate is None else f"{rate:+.2f}%"


def current_premium_rate(premium: dict[str, Any]) -> Optional[float]:
    rate = premium.get("premium_rate")
    if rate is not None:
        return rate
    return premium.get("display_rate")


def format_premium_amount(rate: Optional[float]) -> str:
    if rate is None:
        return "不可确认"
    if rate < 0:
        return f"折价 {abs(rate):.2f}%"
    return f"{rate:.2f}%"


def fmt_premium_vs_today(current: Optional[float], baseline: Optional[float]) -> str:
    if current is None or baseline is None:
        return ""
    diff = current - baseline
    if abs(diff) < 0.05:
        return "，和当天几乎一样"
    if diff > 0:
        return f"，当天比那天高 {diff:.2f} 个百分点"
    return f"，当天比那天低 {abs(diff):.2f} 个百分点"


def format_premium(premium: dict[str, Any]) -> str:
    rate = premium.get("premium_rate")
    if rate is not None:
        level = premium.get("level")
        if level and "IOPV" not in str(level) and level != "不可确认":
            return f"{rate:+.2f}%（{level}）"
        return f"{rate:+.2f}%"
    display = premium.get("display_rate")
    if display is not None:
        level = premium_level(display)
        return f"{display:+.2f}%{fmt_baseline_date(premium.get('display_date'))}（{level}）"
    return "不可确认"


def format_premium_with_lookbacks(premium: dict[str, Any], etf: dict[str, Any]) -> str:
    today = format_premium(premium)
    if not etf_is_qdii(etf):
        return today
    return (
        f"{today}；一天前{fmt_baseline_date(premium.get('previous_date'))} "
        f"{format_premium_rate(premium.get('previous_rate'))}；"
        f"一周前{fmt_baseline_date(premium.get('week_date'))} "
        f"{format_premium_rate(premium.get('week_rate'))}；"
        f"一月前{fmt_baseline_date(premium.get('month_date'))} "
        f"{format_premium_rate(premium.get('month_rate'))}"
    )


def valuation_trade_date(valuation: dict[str, Any]) -> Optional[str]:
    updated_at = str(valuation.get("updated_at") or "")
    return updated_at if parse_iso_date(updated_at) is not None else None


def history_field(item: dict[str, Any], camel: str, snake: str) -> Any:
    value = item.get(camel)
    return value if value is not None else item.get(snake)


def merge_pe_history(
    *histories: list[dict[str, Any]],
    percentile_method: Optional[str] = None,
) -> list[dict[str, Any]]:
    by_date: dict[str, dict[str, Any]] = {}
    for history in histories:
        for item in history:
            trade_date = str(history_field(item, "tradeDate", "trade_date") or "")
            pe_value = finite_positive(history_field(item, "peTtm", "pe_ttm"), 300)
            percentile = normalize_percentile(history_field(item, "pePercentile", "pe_percentile"))
            method = history_field(item, "percentileMethod", "percentile_method")
            parsed_date = parse_iso_date(trade_date)
            if (
                parsed_date is None or parsed_date > now_beijing().date()
                or pe_value is None or percentile is None or not method
                or (percentile_method and method != percentile_method)
            ):
                continue
            normalized = dict(item)
            normalized.update({
                "tradeDate": parsed_date.isoformat(),
                "peTtm": pe_value,
                "pePercentile": percentile,
                "percentileMethod": method,
                "percentile_method": method,
            })
            by_date[parsed_date.isoformat()] = normalized
    return [by_date[key] for key in sorted(by_date)]


def fmt_pe_percentile(value: Optional[float]) -> str:
    return "不可确认" if value is None else f"{value:.0f}"


def fmt_pe_rank(value: Optional[float]) -> Optional[str]:
    return None if value is None else f"{value:.0f}"


def fmt_baseline_date(value: Optional[str]) -> str:
    parsed = parse_iso_date(value)
    return f"（{parsed.strftime('%m-%d')}）" if parsed else ""


def fmt_pe_change(current: Optional[float], baseline: Optional[float]) -> str:
    if current is None or baseline is None:
        return "不可确认"
    diff = current - baseline
    if abs(diff) < 0.5:
        return "— 0 个百分点"
    if diff > 0:
        return f"↑ +{diff:.0f} 个百分点"
    return f"↓ {diff:.0f} 个百分点"


def pe_observation_on_or_before(
    history: list[dict[str, Any]],
    target_date: date,
) -> Optional[dict[str, Any]]:
    return latest_observation_on_or_before(
        history,
        target_date,
        "tradeDate",
        max_staleness_days=PE_LOOKBACK_DAYS,
    )


def build_pe_context(snapshot: dict[str, Any]) -> dict[str, Any]:
    valuation = snapshot["valuation"]
    current = to_optional_float(valuation.get("pe_percentile"))
    current_date_text = valuation_trade_date(valuation)
    current_date = parse_iso_date(current_date_text)
    if current is None or current_date is None:
        return {
            "current": current,
            "current_date": current_date_text,
            "previous": None,
            "previous_date": None,
            "week_baseline": None,
            "week_baseline_date": None,
            "month_baseline": None,
            "month_baseline_date": None,
            "half_year_baseline": None,
            "half_year_baseline_date": None,
            "year_baseline": None,
            "year_baseline_date": None,
            "three_year_baseline": None,
            "three_year_baseline_date": None,
        }

    method = valuation.get("percentile_method") or valuation.get("percentileMethod")
    history = merge_pe_history(snapshot.get("pe_history", []), percentile_method=method)
    previous = pe_observation_on_or_before(history, current_date - timedelta(days=1))
    week = pe_observation_on_or_before(history, current_date - timedelta(days=7))
    month = pe_observation_on_or_before(history, subtract_calendar_month(current_date))
    half_year = pe_observation_on_or_before(history, subtract_calendar_months(current_date, 6))
    year = pe_observation_on_or_before(history, subtract_years(current_date, 1))
    three_year = pe_observation_on_or_before(history, subtract_years(current_date, 3))

    def value(item: Optional[dict[str, Any]]) -> Optional[float]:
        return to_optional_float(item.get("pePercentile")) if item else None

    def trade_date(item: Optional[dict[str, Any]]) -> Optional[str]:
        return str(item.get("tradeDate")) if item else None

    return {
        "current": current,
        "current_date": current_date_text,
        "previous": value(previous),
        "previous_date": trade_date(previous),
        "week_baseline": value(week),
        "week_baseline_date": trade_date(week),
        "month_baseline": value(month),
        "month_baseline_date": trade_date(month),
        "half_year_baseline": value(half_year),
        "half_year_baseline_date": trade_date(half_year),
        "year_baseline": value(year),
        "year_baseline_date": trade_date(year),
        "three_year_baseline": value(three_year),
        "three_year_baseline_date": trade_date(three_year),
    }


def backend_result(
    resp: requests.Response,
    operation: str,
    parsed_body: Any = _JSON_UNSET,
) -> Optional[dict[str, Any]]:
    if resp.status_code != 200:
        print(f"  ⚠️ {operation}失败: HTTP {resp.status_code} {resp.text[:300]}")
        return None
    try:
        body = resp.json() if parsed_body is _JSON_UNSET else parsed_body
    except ValueError:
        print(f"  ⚠️ {operation}失败: 后端未返回JSON")
        return None
    if not isinstance(body, dict):
        print(f"  ⚠️ {operation}失败: 后端JSON不是对象")
        return None
    if body.get("code") != 200:
        print(f"  ⚠️ {operation}失败: 业务码 {body.get('code')} {body.get('message', '')}")
        return None
    return body


def valuation_percentile_method(etf: dict[str, str]) -> str:
    return etf["percentile_method"]


def fetch_valuation_history(etf: dict[str, str]) -> list[dict[str, Any]]:
    backend_url = os.environ.get("BACKEND_API_URL", "")
    ingest_token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url:
        print(f"  ⚠️ {etf['index_name']} 未配置 BACKEND_API_URL，跳过估值缓存查询")
        return []
    if not ingest_token:
        print(f"  ⚠️ {etf['index_name']} 未配置 REPORT_INGEST_TOKEN，跳过估值缓存查询")
        return []
    try:
        resp = http_get(
            f"{backend_url}/api/market-valuations/{etf['valuation_index_code']}/latest",
            params={
                "limit": VALUATION_HISTORY_LIMIT,
                "percentileMethod": valuation_percentile_method(etf),
            },
            headers={"X-Ingest-Token": ingest_token},
            timeout=20,
        )
        body = backend_result(resp, f"{etf['index_name']}估值历史查询")
        return (body.get("data") or []) if body else []
    except Exception as e:
        print(f"  ⚠️ {etf['index_name']} 估值历史查询失败: {e}")
        return []


def push_valuation_history(snapshot: dict[str, Any]) -> bool:
    backend_url = os.environ.get("BACKEND_API_URL", "")
    ingest_token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url or not ingest_token:
        return False
    etf = snapshot["etf"]
    valuation = snapshot["valuation"]
    current_date = valuation_trade_date(valuation)
    current_item = {
        "tradeDate": current_date,
        "peTtm": valuation.get("pe_ttm"),
        "pePercentile": valuation.get("pe_percentile"),
        "percentileMethod": valuation.get("percentile_method") or valuation.get("percentileMethod"),
        "source": valuation.get("source"),
    }
    context = build_pe_context(snapshot)
    wanted_dates = {
        current_date,
        context.get("previous_date"),
        context.get("week_baseline_date"),
        context.get("month_baseline_date"),
        context.get("half_year_baseline_date"),
        context.get("year_baseline_date"),
        context.get("three_year_baseline_date"),
    }
    method = valuation.get("percentile_method") or valuation.get("percentileMethod")
    source_items = merge_pe_history(
        snapshot.get("pe_history", []), [current_item], percentile_method=method
    )
    items = [item for item in source_items if item.get("tradeDate") in wanted_dates]
    if not items:
        print(f"  ⚠️ {etf['index_name']} 当前估值不完整，跳过历史写入")
        return False

    saved = 0
    for item in items:
        trade_date = str(item.get("tradeDate") or "")
        pe_ttm = to_optional_float(item.get("peTtm"))
        percentile = to_optional_float(item.get("pePercentile"))
        if parse_iso_date(trade_date) is None or pe_ttm is None or percentile is None:
            continue
        payload = {
            "indexCode": etf["valuation_index_code"],
            "indexName": valuation["index_name"],
            "peTtm": pe_ttm,
            "pePercentile": percentile,
            "percentileMethod": method,
            "valuationLevel": valuation_level(percentile),
            "tradeDate": trade_date,
            "source": item.get("source") or valuation["source"],
        }
        try:
            resp = requests.post(
                f"{backend_url}/api/market-valuations/ingest",
                json=payload,
                headers={"X-Ingest-Token": ingest_token},
                timeout=30,
            )
            if backend_result(resp, f"{etf['index_name']}估值历史同步"):
                saved += 1
        except Exception as e:
            print(f"  ⚠️ {etf['index_name']} 估值历史同步失败: {e}")
    if saved:
        print(f"  ✅ {etf['index_name']} 已同步 {saved} 个估值基准")
    return saved == len(items)


def build_snapshot(etf: dict[str, str]) -> dict[str, Any]:
    backend_history = fetch_valuation_history(etf)
    expected_method = valuation_percentile_method(etf)
    valuation_error = None
    try:
        valuation, source_history = fetch_source_valuation(etf, backend_history)
        valuation = validate_valuation(valuation, expected_method)
        valuation["data_status"] = "external"
        valuation["error"] = None
        print(f"  ✅ {etf['name']} 估值来自 {valuation['source']}")
    except Exception as e:
        valuation_error = str(e)
        print(f"  ⚠️ {etf['name']} 主估值源失败: {e}")
        source_history = []
        cached = merge_pe_history(backend_history, percentile_method=expected_method)
        latest = next((item for item in reversed(cached) if is_fresh_date(
            item.get("tradeDate"), now_beijing().date(), CURRENT_VALUATION_MAX_STALENESS_DAYS
        )), None)
        env_valuation = fetch_valuation_from_env(etf)
        if latest:
            percentile = latest["pePercentile"]
            valuation = validate_valuation({
                "index_name": etf["index_name"],
                "pe_ttm": latest["peTtm"],
                "pe_percentile": percentile,
                "percentile_method": latest["percentileMethod"],
                "valuation_level": valuation_level(percentile),
                "source": f"{latest.get('source') or '后端估值缓存'}（后端缓存）",
                "updated_at": latest["tradeDate"],
            }, expected_method)
            valuation["data_status"] = "cache"
            valuation["error"] = valuation_error
        elif env_valuation.get("pe_ttm") is not None and is_fresh_date(
            valuation_trade_date(env_valuation), now_beijing().date(), CURRENT_VALUATION_MAX_STALENESS_DAYS
        ):
            valuation = validate_valuation(env_valuation, expected_method)
            valuation["data_status"] = "environment"
            valuation["error"] = valuation_error
        else:
            env_error = env_valuation.get("error") or "环境变量不可用"
            valuation = {
                "index_name": etf["index_name"],
                "pe_ttm": None,
                "pe_percentile": None,
                "percentile_method": expected_method,
                "percentileMethod": expected_method,
                "valuation_level": "估值数据不可确认",
                "source": "不可确认",
                "updated_at": None,
                "data_status": "unavailable",
                "error": f"主源: {valuation_error}；后端无同口径有效缓存；{env_error}",
            }
    cached_prices = fetch_cached_etf_prices(etf)
    try:
        quote = fetch_etf_quote(etf)
        quote["data_status"] = "external"
        quote_error = None
    except Exception as e:
        quote_error = str(e)
        quote = quote_from_cached_prices(etf, cached_prices)
        if quote is None:
            raise RuntimeError(f"{etf['name']} 实时行情失败且无新鲜缓存收盘价: {e}") from e
        quote["error"] = quote_error
        print(f"  ✅ {etf['name']} 行情使用后端缓存最近确认收盘价")
    premium = (
        fetch_etf_premium(etf, quote)
        if quote.get("data_status") != "cached_close"
        else {
            "premium_rate": None,
            "level": "缓存收盘不计算实时溢价",
            "estimated_nav": None,
            "data_time": "不可确认",
            "source": "不可确认",
            "reference_only": etf_is_qdii(etf),
            "data_status": "stale_source",
            "error": "实时行情失败，缓存收盘不能与实时IOPV比较",
        }
    )
    if etf_is_qdii(etf):
        premium = enrich_premium_with_history(etf, premium, quote)
    return {
        "etf": etf,
        "quote": quote,
        "price_context": fetch_price_context(etf, quote, cached_prices),
        "premium": premium,
        "valuation": valuation,
        "pe_history": merge_pe_history(
            backend_history, source_history, percentile_method=expected_method
        ),
    }


def sanitize_report(report: str) -> str:
    report = report.replace(f"> {DISCLAIMER}", "").replace(DISCLAIMER, "")
    return re.sub(r"\n{3,}", "\n\n", report).rstrip()


def parse_eastmoney_clist(body: Any) -> list[dict[str, Any]]:
    if not isinstance(body, dict) or not isinstance(body.get("data"), dict):
        raise RuntimeError("东方财富A股候选响应缺少 data 对象")
    items = body["data"].get("diff")
    if not isinstance(items, list):
        raise RuntimeError("东方财富A股候选响应 diff 不是列表")
    return items


def summarize_a_share_error(error: Any) -> str:
    text = str(error or "").strip()
    if re.search(r"\b(502|Bad Gateway)\b", text, re.I):
        return "行情列表源网关繁忙（502）"
    if re.search(r"\b(429|503|504)\b", text):
        return "行情列表源暂时不可用"
    cleaned = re.sub(r"https?://\S+", "", text)
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" :;,.。")
    if len(cleaned) > 60:
        cleaned = cleaned[:57] + "..."
    return cleaned or "行情列表源暂不可用"


def _a_share_clist_params(fs: str, page_size: int) -> dict[str, Any]:
    return {
        "pn": 1,
        "pz": page_size,
        "po": 1,
        "np": 1,
        "ut": A_SHARE_EASTMONEY_UT,
        "fltt": 2,
        "invt": 2,
        "fid": "f6",
        "fs": fs,
        "fields": A_SHARE_CLIST_FIELDS,
    }


def _fetch_eastmoney_clist(url: str, fs: str, page_size: int) -> list[dict[str, Any]]:
    resp = http_get(
        url,
        params=_a_share_clist_params(fs, page_size),
        timeout=(3, 8),
        headers=A_SHARE_EASTMONEY_HEADERS,
    )
    resp.raise_for_status()
    return parse_eastmoney_clist(resp.json())


def _merge_clist_items(groups: list[list[dict[str, Any]]]) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    seen: set[str] = set()
    for items in groups:
        for item in items:
            code = str(item.get("f12") or "")
            if not code or code in seen:
                continue
            seen.add(code)
            merged.append(item)
    return merged


def fetch_a_share_candidates_from_eastmoney() -> list[dict[str, Any]]:
    errors: list[str] = []
    for url in A_SHARE_EASTMONEY_HOSTS:
        try:
            return _fetch_eastmoney_clist(url, A_SHARE_MARKET_FS, A_SHARE_PAGE_SIZE)
        except Exception as e:
            errors.append(f"{url}: {e}")
    board_groups: list[list[dict[str, Any]]] = []
    for fs in A_SHARE_MARKET_BOARDS:
        fetched = False
        for url in A_SHARE_EASTMONEY_HOSTS:
            try:
                board_groups.append(_fetch_eastmoney_clist(url, fs, A_SHARE_BOARD_PAGE_SIZE))
                fetched = True
                break
            except Exception as e:
                errors.append(f"{fs}@{url}: {e}")
        if not fetched:
            continue
    merged = _merge_clist_items(board_groups)
    if merged:
        return merged
    raise RuntimeError("东方财富A股列表不可用: " + "；".join(errors[:4]))


def sina_row_to_eastmoney_item(row: dict[str, Any]) -> dict[str, Any]:
    market_cap = finite_positive(row.get("mktcap"))
    return {
        "f12": str(row.get("code") or ""),
        "f14": str(row.get("name") or ""),
        "f2": row.get("trade"),
        "f3": row.get("changepercent"),
        "f6": row.get("amount"),
        "f8": row.get("turnoverratio"),
        "f9": row.get("per"),
        "f10": None,
        "f15": row.get("high"),
        "f16": row.get("low"),
        "f20": market_cap * A_SHARE_SINA_MARKET_CAP_UNIT if market_cap is not None else None,
        "f23": row.get("pb"),
        "f24": None,
        "f25": None,
        "f62": None,
    }


def fetch_a_share_candidates_from_sina() -> list[dict[str, Any]]:
    resp = http_get(
        "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData",
        params={
            "page": 1,
            "num": A_SHARE_PAGE_SIZE,
            "sort": "amount",
            "asc": 0,
            "node": "hs_a",
            "symbol": "",
            "_s_r_a": "page",
        },
        timeout=(3, 10),
        headers=A_SHARE_SINA_HEADERS,
    )
    resp.raise_for_status()
    try:
        body = resp.json()
    except ValueError:
        body = json.loads(resp.content.decode("gbk", errors="replace"))
    if not isinstance(body, list):
        raise RuntimeError("新浪A股候选响应不是列表")
    items = [sina_row_to_eastmoney_item(row) for row in body if isinstance(row, dict)]
    if body and not items:
        raise RuntimeError("新浪A股候选响应无法识别")
    return items


def fetch_a_share_candidate_pool() -> tuple[list[dict[str, Any]], str]:
    errors: list[str] = []
    for fetcher, source in (
        (fetch_a_share_candidates_from_eastmoney, "东方财富A股行情"),
        (fetch_a_share_candidates_from_sina, "新浪财经A股行情"),
    ):
        try:
            items = fetcher()
            print(f"  ✅ A股候选列表来自 {source}（{len(items)} 条）")
            return items, source
        except Exception as e:
            errors.append(f"{source}: {e}")
            print(f"  ⚠️ {source}失败: {e}")
    raise RuntimeError("；".join(errors) if errors else "A股候选数据源不可用")


def fetch_a_share_candidates() -> list[dict[str, Any]]:
    items, _source = fetch_a_share_candidate_pool()
    return items


def normalize_a_share(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "code": str(item.get("f12") or ""),
        "name": str(item.get("f14") or ""),
        "latest_price": finite_positive(item.get("f2")),
        "pct_change": to_optional_float(item.get("f3")),
        "amount": finite_positive(item.get("f6")),
        "turnover_rate": to_optional_float(item.get("f8")),
        "pe_dynamic": finite_positive(item.get("f9")),
        "volume_ratio": to_optional_float(item.get("f10")),
        "high": finite_positive(item.get("f15")),
        "low": finite_positive(item.get("f16")),
        "total_market_cap": finite_positive(item.get("f20")),
        "pb": finite_positive(item.get("f23")),
        "pct_change_60d": to_optional_float(item.get("f24")),
        "pct_change_ytd": to_optional_float(item.get("f25")),
        "main_net_inflow": to_optional_float(item.get("f62")),
    }


def is_a_share_candidate(stock: dict[str, Any]) -> bool:
    name = stock["name"].upper()
    return bool(
        stock["code"] and stock["name"]
        and not any(keyword in name for keyword in A_SHARE_BANNED_NAME_KEYWORDS)
        and stock["latest_price"] is not None and stock["latest_price"] > 2
        and stock["pct_change"] is not None and abs(stock["pct_change"]) <= A_SHARE_MAX_ABS_PCT_CHANGE
        and stock["amount"] is not None and stock["amount"] >= A_SHARE_MIN_AMOUNT
        and stock["total_market_cap"] is not None and stock["total_market_cap"] >= A_SHARE_MIN_MARKET_CAP
        and stock["pe_dynamic"] is not None and stock["pe_dynamic"] <= 80
        and stock["pb"] is not None and stock["pb"] <= 10
        and stock["turnover_rate"] is not None and 0.3 <= stock["turnover_rate"] <= 8
    )


def score_a_share(stock: dict[str, Any]) -> float:
    liquidity = min(stock["amount"] / 2_000_000_000, 1) * 30
    size = min(stock["total_market_cap"] / 80_000_000_000, 1) * 20
    stability = max(0, 1 - abs(stock["pct_change"]) / A_SHARE_MAX_ABS_PCT_CHANGE) * 15
    valuation = max(0, 1 - stock["pe_dynamic"] / 80) * 10 + max(0, 1 - stock["pb"] / 10) * 5
    volume = 10 if 0.8 <= (stock["volume_ratio"] or 0) <= 2.5 else 3
    inflow = 5 if (stock["main_net_inflow"] or 0) > 0 else 0
    trend_60d = stock["pct_change_60d"]
    trend = 10 if trend_60d is not None and -10 <= trend_60d <= 30 else 3
    return liquidity + size + stability + valuation + volume + inflow + trend


def a_share_observation(stock: dict[str, Any]) -> dict[str, str]:
    reasons = [
        f"成交额 {stock['amount'] / 100_000_000:.1f} 亿元",
        f"动态PE {stock['pe_dynamic']:.1f}、PB {stock['pb']:.1f}",
    ]
    if (stock["main_net_inflow"] or 0) > 0:
        reasons.append(f"主力净流入 {stock['main_net_inflow'] / 10_000:.0f} 万元")
    day_change = stock["pct_change"] or 0
    trend_60d = stock["pct_change_60d"]
    if day_change > 0 and trend_60d is not None and trend_60d > 0:
        trend = "量价与中期方向偏强；若后续成交额维持且不跌破当日低点，强势可能延续。"
    elif day_change < 0 and trend_60d is not None and trend_60d < 0:
        trend = "短中期仍偏弱；只有放量企稳并收复当日高点后，趋势才可能改善。"
    else:
        trend = "短期更可能维持震荡；需观察后续量能和当日高低点突破方向。"
    risks = []
    if stock["pe_dynamic"] > 50 or stock["pb"] > 6:
        risks.append("估值偏高")
    if abs(day_change) >= 4:
        risks.append("单日波动较大")
    if (stock["main_net_inflow"] or 0) < 0:
        risks.append("主力资金净流出")
    if trend_60d is not None and trend_60d > 30:
        risks.append("近60日涨幅较大")
    if not risks:
        risks.append("机械筛选未覆盖基本面、公告和行业事件")
    return {
        "name": stock["name"],
        "code": stock["code"],
        "reason": "；".join(reasons) + "。",
        "trend": trend,
        "risk": "；".join(risks) + "。",
    }


def build_a_share_observations() -> AShareObservationResult:
    try:
        raw_items, source = fetch_a_share_candidate_pool()
        stocks = [normalize_a_share(item) for item in raw_items]
        candidates = [stock for stock in stocks if is_a_share_candidate(stock)]
        candidates.sort(key=score_a_share, reverse=True)
        picks = candidates[:A_SHARE_PICK_COUNT]
        status = "available" if picks else "empty"
        print(f"  ✅ A股观察候选筛选完成：{len(picks)} 只，来源 {source}")
        return {
            "status": status,
            "items": [a_share_observation(stock) for stock in picks],
            "source": source,
            "error": None,
        }
    except Exception as e:
        print(f"  ⚠️ A股观察候选抓取失败: {e}")
        return {
            "status": "provider_error",
            "items": [],
            "source": "不可确认",
            "error": summarize_a_share_error(e),
        }


def etf_short_name(snapshot: dict[str, Any]) -> str:
    return snapshot["etf"]["name"].split()[0]


def data_status_label(status: Optional[str]) -> str:
    return {
        "external": "外部数据已校验",
        "live": "外部数据已校验",
        "external_cached": "外部数据已校验并写入缓存",
        "cache": "后端缓存",
        "cached_close": "后端缓存最近确认收盘",
        "environment": "显式配置数据",
        "unavailable": "不可确认",
    }.get(status or "", status or "未知")


def percentile_method_label(method: Optional[str]) -> str:
    return {
        CSI_PE_TTM_ROLLING_10Y: "中证 PE(TTM) 滚动10年分位",
        DANJUAN_PE_TTM_PROVIDER: "蛋卷提供方 PE(TTM) 分位",
    }.get(method or "", method or "不可确认")


def valuation_revision_note(snapshot: dict[str, Any]) -> Optional[str]:
    valuation = snapshot["valuation"]
    current_date = parse_iso_date(valuation_trade_date(valuation))
    current_pe = finite_positive(valuation.get("pe_ttm"), 300)
    current_percentile = normalize_percentile(valuation.get("pe_percentile"))
    method = valuation.get("percentile_method") or valuation.get("percentileMethod")
    if current_date is None or current_pe is None or current_percentile is None or not method:
        return None
    history = merge_pe_history(snapshot.get("pe_history", []), percentile_method=method)
    previous = pe_observation_on_or_before(history, current_date - timedelta(days=1))
    if not previous:
        return None
    previous_pe = finite_positive(previous.get("peTtm"), 300)
    previous_percentile = normalize_percentile(previous.get("pePercentile"))
    if previous_pe is None or previous_percentile is None:
        return None
    pe_change = (current_pe / previous_pe - 1) * 100
    percentile_change = current_percentile - previous_percentile
    if abs(pe_change) < 10 and abs(percentile_change) < 20:
        return None
    return (
        f"相较 {previous['tradeDate']}，PE变化 {pe_change:+.1f}%、分位变化 "
        f"{percentile_change:+.0f} 个百分点；幅度较大，可能包含数据源成分、盈利或历史样本修订，"
        "需结合后续同口径数据复核。"
    )


def snapshot_data_issues(snapshot: dict[str, Any]) -> list[str]:
    issues = []
    for label, item in (
        ("行情", snapshot["quote"]),
        ("价格历史", snapshot["price_context"]),
        ("估值", snapshot["valuation"]),
        ("溢折价", snapshot["premium"]),
    ):
        if item.get("error"):
            issues.append(f"{label}：{item['error']}")
        elif item.get("data_status") == "unavailable":
            issues.append(f"{label}：不可确认")
    revision = valuation_revision_note(snapshot)
    if revision:
        issues.append(f"估值异常：{revision}")
    return issues


def format_conclusion_line(snapshot: dict[str, Any], memo: Any) -> str:
    quote = snapshot["quote"]
    valuation = snapshot["valuation"]
    note = ""
    if memo["action"] == "无法判断":
        note = "，主数据不足不下动作"
    elif memo["vetoes"]:
        note = f"，{memo['vetoes'][0]}"
    return (
        f"- {etf_short_name(snapshot)}：{memo['action']}｜"
        f"{fmt_compact_price(quote.get('latest_price')) or '不可确认'}｜"
        f"PE {fmt_number(valuation.get('pe_ttm'), 2)}｜"
        f"分位 {fmt_pe_percentile(valuation.get('pe_percentile'))}{note}"
    )


def format_lead_conclusion(snapshots: list[dict[str, Any]], memos: list[Any]) -> list[str]:
    lines = ["## 先看结论"]
    lines.extend(format_conclusion_line(snapshot, memo) for snapshot, memo in zip(snapshots, memos))
    return lines


def format_price_change_section(snapshots: list[dict[str, Any]]) -> list[str]:
    lines = ["## ETF变化"]
    for snapshot in snapshots:
        quote = snapshot["quote"]
        context = snapshot["price_context"]
        current = quote.get("latest_price")
        lines.extend([
            f"### {etf_short_name(snapshot)}",
            "- " + join_compact_lookbacks(fmt_compact_price(current), [
                ("昨", fmt_compact_price(context.get("previous_close")), fmt_compact_pct_change(
                    quote.get("pct_change") if quote.get("pct_change") is not None
                    else pct_return(current, context.get("previous_close"))
                )),
                ("周", fmt_compact_price(context.get("week_baseline")), fmt_compact_pct_change(context.get("week_pct_change"))),
                ("月", fmt_compact_price(context.get("month_baseline")), fmt_compact_pct_change(context.get("month_pct_change"))),
                ("半年", fmt_compact_price(context.get("half_year_baseline")), fmt_compact_pct_change(context.get("half_year_pct_change"))),
                ("一年", fmt_compact_price(context.get("year_baseline")), fmt_compact_pct_change(context.get("year_pct_change"))),
                ("三年", fmt_compact_price(context.get("three_year_baseline")), fmt_compact_pct_change(context.get("three_year_pct_change"))),
            ]),
        ])
    return lines


def _pt_delta(
    current: Optional[float],
    baseline: Optional[float],
    digits: int = 0,
    unit: str = "pt",
) -> Optional[str]:
    if current is None or baseline is None:
        return None
    return fmt_compact_pt_change(current - baseline, digits, unit=unit)


def format_pe_change_section(snapshots: list[dict[str, Any]]) -> list[str]:
    lines = ["## PE分位变化"]
    for snapshot in snapshots:
        pe_context = build_pe_context(snapshot)
        current = pe_context["current"]
        lines.extend([
            f"### {etf_short_name(snapshot)}",
            "- " + join_compact_lookbacks(fmt_pe_percentile(current), [
                ("昨", fmt_pe_rank(pe_context.get("previous")), _pt_delta(current, pe_context.get("previous"), unit="点")),
                ("周", fmt_pe_rank(pe_context.get("week_baseline")), _pt_delta(current, pe_context.get("week_baseline"), unit="点")),
                ("月", fmt_pe_rank(pe_context.get("month_baseline")), _pt_delta(current, pe_context.get("month_baseline"), unit="点")),
                ("半年", fmt_pe_rank(pe_context.get("half_year_baseline")), _pt_delta(current, pe_context.get("half_year_baseline"), unit="点")),
                ("一年", fmt_pe_rank(pe_context.get("year_baseline")), _pt_delta(current, pe_context.get("year_baseline"), unit="点")),
                ("三年", fmt_pe_rank(pe_context.get("three_year_baseline")), _pt_delta(current, pe_context.get("three_year_baseline"), unit="点")),
            ]),
        ])
    return lines


def format_premium_change_section(snapshots: list[dict[str, Any]]) -> list[str]:
    qdii_snapshots = [snapshot for snapshot in snapshots if etf_is_qdii(snapshot["etf"])]
    if not qdii_snapshots:
        return []
    lines = ["## 溢价变化"]
    for snapshot in qdii_snapshots:
        premium = snapshot["premium"]
        current = current_premium_rate(premium)
        lines.extend([
            f"### {etf_short_name(snapshot)}",
            "- " + join_compact_lookbacks(fmt_plain_pct(current), [
                ("昨", fmt_plain_pct(premium.get("previous_rate")), _pt_delta(current, premium.get("previous_rate"), 2)),
                ("周", fmt_plain_pct(premium.get("week_rate")), _pt_delta(current, premium.get("week_rate"), 2)),
                ("月", fmt_plain_pct(premium.get("month_rate")), _pt_delta(current, premium.get("month_rate"), 2)),
                ("半年", fmt_plain_pct(premium.get("half_year_rate")), _pt_delta(current, premium.get("half_year_rate"), 2)),
                ("一年", fmt_plain_pct(premium.get("year_rate")), _pt_delta(current, premium.get("year_rate"), 2)),
                ("三年", fmt_plain_pct(premium.get("three_year_rate")), _pt_delta(current, premium.get("three_year_rate"), 2)),
            ]),
        ])
    return lines


def report_needs_iopv_refresh(snapshots: list[dict[str, Any]]) -> bool:
    for snapshot in snapshots:
        premium = snapshot.get("premium") or {}
        if premium.get("error"):
            return True
        if premium.get("data_status") in ("provider_error", "stale_source", "unavailable"):
            return True
        if "IOPV" in str(premium.get("level") or ""):
            return True
    return False


def format_a_share_section(stock_observations: Optional[AShareObservationResult]) -> list[str]:
    lines = ["## A股观察候选"]
    observation_result = stock_observations or {
        "status": "provider_error", "items": [], "source": "不可确认", "error": "未取得候选数据"
    }
    if observation_result.get("status") == "available":
        for stock in observation_result.get("items", []):
            lines.append(
                f"- **{stock['name']}（{stock['code']}）**：{stock['reason']}"
                f"趋势：{stock['trend']}风险：{stock['risk']}"
            )
    elif observation_result.get("status") == "empty":
        lines.append("- 数据源正常，按当前公开量价与估值规则未筛出合格候选。")
    else:
        error = summarize_a_share_error(observation_result.get("error") or "候选数据源不可用")
        lines.append(f"- 候选数据源异常，本次无法确认股票观察名单：{error}。")
    lines.append("- 候选基于公开量价与估值机械筛选，仅作研究线索，不代表推荐或确定性预测。")
    return lines


def report_title(edition: str) -> str:
    today = now_beijing().strftime("%Y-%m-%d")
    return f"> **ETF 行情日报 · {today}（{edition_label(edition)}）**"


def build_programmatic_report(
    snapshots: list[dict[str, Any]],
    edition: str,
    stock_observations: Optional[AShareObservationResult] = None,
) -> str:
    memos = [analyze_snapshot(snapshot) for snapshot in snapshots]
    premium_section = format_premium_change_section(snapshots)
    lines = [
        report_title(edition),
        "",
        *format_lead_conclusion(snapshots, memos),
        "",
        *format_price_change_section(snapshots),
        "",
        *format_pe_change_section(snapshots),
    ]
    if premium_section:
        lines.extend(["", *premium_section])
    lines.extend(["", *format_a_share_section(stock_observations)])
    body = "\n".join(lines)
    if report_needs_iopv_refresh(snapshots):
        body += f"\n\n{ETF_REFRESH_MARKER}"
    return sanitize_report(body)


def build_wechat_report(snapshots: list[dict[str, Any]], edition: str) -> str:
    memos = [analyze_snapshot(snapshot) for snapshot in snapshots]
    premium_section = format_premium_change_section(snapshots)
    lines = [
        report_title(edition),
        "",
        *format_lead_conclusion(snapshots, memos),
        "",
        *format_price_change_section(snapshots),
        "",
        *format_pe_change_section(snapshots),
    ]
    if premium_section:
        lines.extend(["", *premium_section])
    return sanitize_report("\n".join(lines))


def build_fallback_report(snapshots: list[dict[str, Any]], edition: str, reason: str) -> str:
    degraded = [dict(snapshot) for snapshot in snapshots]
    if degraded:
        degraded[0] = dict(degraded[0])
        degraded[0]["price_context"] = dict(degraded[0]["price_context"])
        degraded[0]["price_context"]["error"] = reason
    return build_programmatic_report(degraded, edition)


def colorize_wework_changes(text: str) -> str:
    def paint(match: re.Match[str]) -> str:
        token = match.group(1)
        if token.startswith("+"):
            return f'<font color="warning">{token}</font>'
        if token.startswith("-"):
            return f'<font color="info">{token}</font>'
        return f'<font color="comment">{token}</font>'
    return SIGNED_CHANGE_TOKEN.sub(paint, text)


def convert_to_wework_markdown(md_text: str) -> str:
    md_text = re.sub(r"<!--.*?-->", "", md_text, flags=re.S)
    out = []
    for line in md_text.split("\n"):
        stripped = line.strip()
        if not stripped:
            out.append("")
            continue
        if stripped.startswith("### "):
            converted = f"**{stripped[4:]}**"
        elif stripped.startswith("## "):
            converted = f"> **{stripped[3:]}**"
        elif stripped.startswith("# "):
            converted = f"> **{stripped[2:]}**"
        elif stripped.startswith("|") and stripped.endswith("|"):
            continue
        else:
            converted = stripped
        out.append(colorize_wework_changes(converted))

    result = "\n".join(out)
    max_bytes = 3800
    if len(result.encode("utf-8")) <= max_bytes:
        return result

    marker = "> ...(内容已截断)"
    prefix = list(out)
    truncated = "\n".join([*prefix, "", marker])
    while len(truncated.encode("utf-8")) > max_bytes and prefix:
        prefix.pop()
        truncated = "\n".join([*prefix, "", marker] if prefix else [marker])
    return truncated


def push_to_wechat(content: str, webhook_url: str, max_attempts: int = 3) -> bool:
    payload = {"msgtype": "markdown", "markdown": {"content": content}}
    headers = {"Content-Type": "application/json; charset=utf-8"}
    for attempt in range(max_attempts):
        try:
            resp = requests.post(webhook_url, json=payload, headers=headers, timeout=15)
            try:
                data = resp.json()
            except ValueError:
                data = None
            errcode = data.get("errcode") if isinstance(data, dict) else None
            if resp.status_code == 200 and errcode == 0:
                print(f"✅ ETF 企业微信推送成功 ({len(content.encode('utf-8'))} bytes)")
                return True
            print(f"❌ ETF 企业微信推送失败: HTTP {resp.status_code}, errcode={errcode}")
            if resp.status_code not in RETRYABLE_STATUS_CODES and errcode not in WECHAT_RETRYABLE_ERRCODES:
                return False
        except (requests.ConnectionError, requests.Timeout) as e:
            print(f"⚠️ ETF 企业微信推送失败: {e}")
        except requests.RequestException as e:
            print(f"❌ ETF 企业微信推送失败且不可重试: {e}")
            return False
        if attempt < max_attempts - 1:
            time.sleep(attempt + 1)
    return False


def record_ops_delivery(edition, report_date, success=True, channel_type="wechat", error_message=None):
    backend_url = os.environ.get("BACKEND_API_URL", "")
    ingest_token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url or not ingest_token:
        return False
    try:
        resp = requests.post(
            f"{backend_url.rstrip('/')}/api/reports/record-delivery",
            json={
                "edition": edition,
                "reportDate": report_date,
                "channelType": channel_type,
                "success": success,
                "errorMessage": error_message,
            },
            headers={"X-Ingest-Token": ingest_token},
            timeout=(5, 15),
        )
        try:
            body = resp.json()
        except ValueError:
            body = None
        ok = resp.status_code == 200 and isinstance(body, dict) and body.get("code") == 200
        if ok:
            print(f"  📝 已记入投递记录 {body.get('data')}")
        else:
            print(f"  ⚠️ 投递记录写入失败: HTTP {resp.status_code}")
        return ok
    except requests.RequestException as e:
        print(f"  ⚠️ 投递记录写入失败: {e}")
        return False


def push_to_backend(
    edition: str,
    report_date: str,
    title: str,
    content: str,
    summary: str,
    run_id: str,
) -> bool:
    backend_url = os.environ.get("BACKEND_API_URL", "")
    ingest_token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url:
        print("  ⚠️ 未配置 BACKEND_API_URL，跳过后端存储")
        return False
    if not ingest_token:
        print("  ⚠️ 未配置 REPORT_INGEST_TOKEN，跳过后端存储")
        return False

    payload = {
        "edition": edition,
        "reportDate": report_date,
        "title": title,
        "content": content[:30000],
        "summary": summary,
        "runId": run_id,
    }
    for attempt in range(3):
        try:
            resp = requests.post(
                f"{backend_url}/api/reports/ingest",
                json=payload,
                headers={"X-Ingest-Token": ingest_token},
                timeout=(4, 15),
            )
            try:
                parsed_body = resp.json()
            except ValueError:
                parsed_body = None
            body = backend_result(resp, "ETF报告同步", parsed_body)
            if body is not None:
                print(f"  ✅ ETF 报告已同步到后端（第 {attempt + 1} 次尝试）")
                return True
            business_code = parsed_body.get("code") if isinstance(parsed_body, dict) else None
            retryable = resp.status_code in RETRYABLE_STATUS_CODES or (
                resp.status_code == 200 and isinstance(business_code, int) and business_code >= 500
            )
            if not retryable:
                return False
        except (requests.ConnectionError, requests.Timeout) as e:
            print(f"  ⚠️ 后端 API 同步失败: {e}")
        except requests.RequestException as e:
            print(f"  ⚠️ 后端 API 同步失败且不可重试: {e}")
            return False
        if attempt < 2:
            time.sleep(attempt + 1)
    return False


def build_summary(snapshots: list[dict[str, Any]]) -> str:
    parts = []
    for snapshot in snapshots:
        quote = snapshot["quote"]
        valuation = snapshot["valuation"]
        memo = analyze_snapshot(snapshot)
        parts.append(
            f"{etf_short_name(snapshot)}截至{quote.get('data_time') or '不可确认'} "
            f"{fmt_change_pct(quote.get('pct_change'))}，PE {fmt_number(valuation.get('pe_ttm'), 2)}，"
            f"分位 {fmt_pe_percentile(valuation.get('pe_percentile'))}，"
            f"估值状态 {data_status_label(valuation.get('data_status'))}，"
            f"仓位备忘 {memo['action']}"
        )
    return "；".join(parts) + "。"


def unavailable_snapshot(etf: dict[str, str], error: str) -> dict[str, Any]:
    method = valuation_percentile_method(etf)
    return {
        "etf": etf,
        "quote": {
            "latest_price": None,
            "previous_close": None,
            "pct_change": None,
            "data_time": "不可确认",
            "source": "不可确认",
            "data_status": "unavailable",
            "error": error,
        },
        "price_context": empty_price_context(error),
        "premium": {
            "premium_rate": None,
            "level": "不可确认",
            "estimated_nav": None,
            "data_time": "不可确认",
            "source": "不可确认",
            "reference_only": etf_is_qdii(etf),
            "data_status": "unavailable",
            "error": error,
        },
        "valuation": {
            "index_name": etf["index_name"],
            "pe_ttm": None,
            "pe_percentile": None,
            "percentile_method": method,
            "percentileMethod": method,
            "valuation_level": "估值数据不可确认",
            "source": "不可确认",
            "updated_at": None,
            "data_status": "unavailable",
            "error": error,
        },
        "pe_history": [],
    }


def sync_price_history() -> bool:
    if not os.environ.get("BACKEND_API_URL") or not os.environ.get("REPORT_INGEST_TOKEN"):
        print("❌ sync_only 需要 BACKEND_API_URL 和 REPORT_INGEST_TOKEN")
        return False
    success = True
    completed_cutoff = {"data_time": now_beijing().strftime("%Y-%m-%d %H:%M:%S")}
    for etf in ETF_LIST:
        try:
            prices = fetch_etf_daily_prices(etf)
            if not push_etf_price_history(etf, prices, completed_cutoff):
                raise RuntimeError("后端批量写入失败")
            cached = select_cached_price_series(fetch_cached_etf_prices(etf))
            completed = [
                item for item in prices
                if parse_iso_date(item.get("date")) < now_beijing().date()
            ]
            expected_dates = {item["date"] for item in completed}
            cached_dates = {item["date"] for item in cached}
            if (
                not completed
                or not cached
                or cached[-1]["date"] != completed[-1]["date"]
                or cached[-1]["source"] != completed[-1]["source"]
                or not expected_dates.issubset(cached_dates)
            ):
                raise RuntimeError("回读缓存未覆盖本次同来源回填数据")
            print(
                f"  ✅ {etf['name']} 已回填并确认 {len(completed)} 条价格，"
                f"截至 {cached[-1]['date']}，来源 {cached[-1]['source']}"
            )
        except Exception as e:
            success = False
            print(f"  ❌ {etf['name']} 价格历史回填失败: {e}")
    return success


def env_enabled(name: str) -> bool:
    return os.environ.get(name, "").lower() in ("1", "true", "yes")


def should_skip_weekend_report(current_time: datetime, dry_run: bool) -> bool:
    return current_time.weekday() >= 5 and not dry_run and not env_enabled("ETF_FORCE_RUN")


def main() -> None:
    current_time = now_beijing()
    today = current_time.strftime("%Y-%m-%d")
    edition = detect_edition()
    label = edition_label(edition)
    report_file = f"ETF市场数据简报_{today}（{label}）.md"

    print(f"\n{'=' * 50}")
    print(f"📈 ETF 市场数据简报 · {today}（{label}）")
    print(f"{'=' * 50}\n")

    webhook_url = os.environ.get("ETF_WECHAT_WEBHOOK", "")
    backend_configured = bool(os.environ.get("BACKEND_API_URL") and os.environ.get("REPORT_INGEST_TOKEN"))
    dry_run = env_enabled("ETF_DRY_RUN")
    sync_only = env_enabled("ETF_SYNC_ONLY")
    if sync_only:
        print("📡 正在回填 ETF 价格历史...")
        if not sync_price_history():
            sys.exit(1)
        print(f"\n✅ ETF 价格历史回填完成！({now_beijing().strftime('%H:%M:%S')})")
        return
    if should_skip_weekend_report(current_time, dry_run):
        print("ℹ️ 周末无交易，默认跳过 ETF 日报抓取与推送")
        return
    if not webhook_url and not backend_configured and not dry_run:
        print("❌ 缺少 ETF_WECHAT_WEBHOOK，且未配置后端入库")
        sys.exit(1)

    print("📡 正在抓取 ETF 行情...")
    snapshots = []
    for etf in ETF_LIST:
        try:
            snapshots.append(build_snapshot(etf))
        except Exception as e:
            print(f"  ❌ {etf['name']} 数据不可确认，保留降级报告: {e}")
            snapshots.append(unavailable_snapshot(etf, str(e)))

    if dry_run:
        print("🧪 ETF_DRY_RUN 已开启，跳过估值历史写入")
    else:
        print("📡 正在同步估值历史...")
        for snapshot in snapshots:
            if push_valuation_history(snapshot):
                snapshot["pe_history"] = merge_pe_history(
                    snapshot.get("pe_history", []),
                    fetch_valuation_history(snapshot["etf"]),
                )

    print("📡 正在筛选两只 A 股观察候选...")
    stock_observations = build_a_share_observations()
    report = build_programmatic_report(snapshots, edition, stock_observations)

    if dry_run:
        print("🧪 ETF_DRY_RUN 已开启，跳过本地报告文件写入")
    else:
        with open(report_file, "w", encoding="utf-8") as f:
            f.write(report)
        print(f"💾 已保存: {report_file}")

    wx_content = convert_to_wework_markdown(build_wechat_report(snapshots, edition))
    run_id = os.environ.get("GITHUB_RUN_ID", "local")
    title = f"【ETF市场数据简报{label}】沪深300ETF / 纳指100ETF / 标普500ETF {today}"
    if dry_run:
        print("🧪 ETF_DRY_RUN 已开启，跳过后端报告存储和企业微信推送")
        print(wx_content)
    else:
        if not push_to_backend(edition, today, title, report, build_summary(snapshots), run_id):
            print("❌ ETF 报告同步到后端失败，本次不继续推送")
            sys.exit(1)
        if backend_configured:
            print("📬 推送由后端订阅渠道负责，跳过脚本直推企业微信")
        elif not push_to_wechat(wx_content, webhook_url):
            print("❌ ETF 企业微信推送失败，报告已入库")
            sys.exit(1)
        else:
            record_ops_delivery(edition, today, success=True)

    print(f"\n✅ 市场观察完成！({now_beijing().strftime('%H:%M:%S')})")


def generate_and_ingest() -> bool:
    """poller 入口：生成并入库公共 ETF 报。有后端时不直推企业微信。"""
    try:
        main()
    except SystemExit as exc:
        return exc.code in (0, None)
    return True


if __name__ == "__main__":
    main()
