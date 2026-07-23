# -*- coding: utf-8 -*-
"""
市场观察脚本
用于生成 ETF 与 A 股市场观察早晚报，并推送到独立企业微信机器人。
"""

import calendar
import json
import os
import re
import sys
import time
from bisect import bisect_left, bisect_right, insort
from datetime import date, datetime, timezone, timedelta
from typing import Any, Optional

import requests

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
    },
    {
        "name": "纳指100ETF 国泰",
        "code": "513100",
        "eastmoney_secid": "1.513100",
        "sina_code": "sh513100",
        "index_name": "纳斯达克100指数",
        "valuation_index_code": "NDX",
        "valuation_env_prefix": "NASDAQ100",
    },
]

LLM_MODELS = [
    {
        "name": "deepseek-chat",
        "base_url": "https://api.deepseek.com/",
        "api_key_env": "DEEPSEEK_API_KEY",
        "description": "DeepSeek V3 - 主用模型",
    },
]

A_SHARE_PICK_COUNT = 2
A_SHARE_PAGE_SIZE = 100
A_SHARE_MARKET_FS = "m:1+t:2,m:0+t:6,m:0+t:80"
A_SHARE_MIN_AMOUNT = 300000000
A_SHARE_MIN_MARKET_CAP = 10000000000
A_SHARE_MAX_ABS_PCT_CHANGE = 6
A_SHARE_BANNED_NAME_KEYWORDS = ["ST", "*ST", "退"]
ADD_POSITION_LINE = 60
CSI300_PE_WINDOW_YEARS = 10
CURRENT_VALUATION_MAX_STALENESS_DAYS = 15
VALUATION_ARCHIVE_URL = "https://raw.githubusercontent.com/caibingcheng/djeva/master/json/{date}.json"

BANNED_WORDS = [
    "稳赚", "必涨", "满仓", "梭哈", "强烈买入", "无脑买入", "一定上涨",
    "推荐买入", "买入推荐", "卖出建议", "目标价", "抄底", "翻倍", "牛股", "稳赚不赔",
    "确定性机会", "闭眼买", "无风险", "马上买入", "卖出所有", "重仓", "投资顾问",
    "稳健收益", "保本收益", "稳赚策略", " All in", "all in",
]
DISCLAIMER = "风险提示：本报告由公开数据和 AI 辅助生成，仅作市场观察、分析、提醒和风险提示，不构成任何投资建议、证券推荐、投资顾问意见或买卖依据。基金和股票均有风险，投资需谨慎。"


def now_beijing() -> datetime:
    return datetime.now(BEIJING_TZ)


def detect_edition() -> str:
    manual = os.environ.get("EDITION", "auto").lower()
    mapping = {
        "morning": "market_watch_morning",
        "evening": "market_watch_evening",
        "market_watch_morning": "market_watch_morning",
        "market_watch_evening": "market_watch_evening",
        "etf_morning": "market_watch_morning",
        "etf_evening": "market_watch_evening",
    }
    if manual in mapping:
        return mapping[manual]
    return "market_watch_morning" if now_beijing().hour < 12 else "market_watch_evening"


def edition_label(edition: str) -> str:
    return "早间版" if edition.endswith("morning") else "晚间版"


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
        return "暂不可用"
    return f"{value:.{digits}f}{suffix}"


def fmt_price(value: Optional[float]) -> str:
    return "暂不可用" if value is None else f"{value:.3f} 元"


def fmt_amount(value: Optional[float]) -> str:
    if value is None:
        return "暂不可用"
    if value >= 100000000:
        return f"{value / 100000000:.2f} 亿"
    if value >= 10000:
        return f"{value / 10000:.2f} 万"
    return f"{value:.0f}"


def fetch_quote_from_eastmoney(etf: dict[str, str]) -> dict[str, Any]:
    url = "https://push2.eastmoney.com/api/qt/stock/get"
    params = {
        "secid": etf["eastmoney_secid"],
        "fields": "f43,f44,f45,f46,f47,f48,f57,f58,f60,f86,f169,f170",
    }
    resp = requests.get(url, params=params, timeout=12, headers={"User-Agent": "Mozilla/5.0"})
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

    return {
        "name": data.get("f58") or etf["name"],
        "code": data.get("f57") or etf["code"],
        "latest_price": scaled(data.get("f43"), 1000),
        "change_amount": scaled(data.get("f169"), 1000),
        "pct_change": scaled(data.get("f170"), 100),
        "open": scaled(data.get("f46"), 1000),
        "high": scaled(data.get("f44"), 1000),
        "low": scaled(data.get("f45"), 1000),
        "previous_close": scaled(data.get("f60"), 1000),
        "volume": data.get("f47"),
        "amount": scaled(data.get("f48"), 1),
        "data_time": data_time or now_beijing().strftime("%Y-%m-%d %H:%M:%S"),
        "source": "东方财富",
    }


def fetch_quote_from_sina(etf: dict[str, str]) -> dict[str, Any]:
    url = f"https://hq.sinajs.cn/list={etf['sina_code']}"
    headers = {"User-Agent": "Mozilla/5.0", "Referer": "https://finance.sina.com.cn/"}
    resp = requests.get(url, timeout=12, headers=headers)
    resp.raise_for_status()
    text = resp.content.decode("gbk", errors="replace")
    match = re.search(r'="(.+)";', text)
    if not match:
        raise RuntimeError("新浪未返回行情数据")
    parts = match.group(1).split(",")
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

    return {
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
        "data_time": f"{parts[30]} {parts[31]}" if len(parts) > 31 else now_beijing().strftime("%Y-%m-%d %H:%M:%S"),
        "source": "新浪财经",
    }


def fetch_etf_quote(etf: dict[str, str]) -> dict[str, Any]:
    for fetcher in (fetch_quote_from_eastmoney, fetch_quote_from_sina):
        try:
            quote = fetcher(etf)
            print(f"  ✅ {etf['name']} 行情来自 {quote['source']}")
            return quote
        except Exception as e:
            print(f"  ⚠️ {etf['name']} 行情源失败: {e}")
    raise RuntimeError(f"{etf['name']} 所有行情源均失败")


def fetch_etf_daily_prices(etf: dict[str, str]) -> list[dict[str, Any]]:
    resp = requests.get(
        "https://push2his.eastmoney.com/api/qt/stock/kline/get",
        params={
            "secid": etf["eastmoney_secid"],
            "klt": 101,
            "fqt": 1,
            "lmt": 30,
            "end": "20500101",
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": "f51,f52,f53,f54,f55,f56",
        },
        timeout=15,
        headers={"User-Agent": "Mozilla/5.0"},
    )
    resp.raise_for_status()
    klines = (resp.json().get("data") or {}).get("klines") or []
    prices = []
    for line in klines:
        parts = line.split(",")
        if len(parts) < 6:
            continue
        close = to_optional_float(parts[2])
        high = to_optional_float(parts[3])
        low = to_optional_float(parts[4])
        if close is None or high is None or low is None:
            continue
        prices.append({"date": parts[0], "close": close, "high": high, "low": low})
    if not prices:
        raise RuntimeError("东方财富未返回 ETF 日线数据")
    return prices


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


def subtract_calendar_month(value: date) -> date:
    year = value.year - (1 if value.month == 1 else 0)
    month = 12 if value.month == 1 else value.month - 1
    day = min(value.day, calendar.monthrange(year, month)[1])
    return date(year, month, day)


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


def build_price_context(quote: dict[str, Any], daily_prices: list[dict[str, Any]]) -> dict[str, Any]:
    current = quote["latest_price"]
    quote_date_text = str(quote.get("data_time") or "")[:10]
    quote_date = parse_iso_date(quote_date_text)
    if quote_date is None:
        raise RuntimeError("实时行情日期格式异常")
    completed = [item for item in daily_prices if item["date"] < quote_date_text]
    current_values = [value for value in (current, quote.get("high"), quote.get("low")) if value is not None]
    if not current_values:
        raise RuntimeError("实时价格暂不可用")
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
    previous_close = to_optional_float(quote.get("previous_close"))
    if previous_close is None and previous_item:
        previous_close = previous_item["close"]
    week_baseline = week_item["close"] if week_item else None
    month_baseline = month_item["close"] if month_item else None
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
        "week_pct_change": pct_return(current, week_baseline),
        "month_pct_change": pct_return(current, month_baseline),
        "month_high": month_high,
        "month_low": month_low,
        "distance_from_month_high": pct_return(current, month_high),
        "month_range_position": range_position,
        "history_days": len(completed),
        "source": "东方财富前复权日线",
    }


def fetch_price_context(etf: dict[str, str], quote: dict[str, Any]) -> dict[str, Any]:
    try:
        context = build_price_context(quote, fetch_etf_daily_prices(etf))
        print(f"  ✅ {etf['name']} 价格趋势来自 {context['source']}")
        return context
    except Exception as e:
        print(f"  ⚠️ {etf['name']} 价格趋势抓取失败: {e}")
        return {
            "previous_close": to_optional_float(quote.get("previous_close")),
            "previous_date": None,
            "week_baseline": None,
            "week_baseline_date": None,
            "month_baseline": None,
            "month_baseline_date": None,
            "week_pct_change": None,
            "month_pct_change": None,
            "month_high": None,
            "month_low": None,
            "distance_from_month_high": None,
            "month_range_position": None,
            "history_days": 0,
            "source": "暂不可用",
        }


def premium_level(premium_rate: Optional[float]) -> str:
    if premium_rate is None:
        return "暂不可用"
    if premium_rate > 2:
        return "溢价偏高"
    if premium_rate > 0.5:
        return "小幅溢价"
    if premium_rate < -0.5:
        return "折价"
    return "接近净值"


def fetch_etf_premium(etf: dict[str, str], quote: dict[str, Any]) -> dict[str, Any]:
    board = "b:MK0023" if etf["code"] == "513100" else "b:MK0021"
    try:
        data = None
        for page in range(1, 16):
            resp = requests.get(
                "https://push2.eastmoney.com/api/qt/clist/get",
                params={
                    "pn": page,
                    "pz": 100,
                    "po": 1,
                    "np": 1,
                    "fltt": 2,
                    "invt": 2,
                    "fid": "f12",
                    "fs": board,
                    "fields": "f2,f12,f14,f124,f402,f441",
                },
                timeout=15,
                headers={"User-Agent": "Mozilla/5.0", "Referer": "https://quote.eastmoney.com/"},
            )
            resp.raise_for_status()
            response_data = resp.json().get("data") or {}
            items = response_data.get("diff") or []
            data = next((item for item in items if str(item.get("f12")) == etf["code"]), None)
            if data:
                break
            if page * 100 >= int(response_data.get("total") or 0):
                break
        if not data:
            raise RuntimeError("东方财富ETF列表未找到目标基金")
        ts = data.get("f124")
        data_time = "暂不可用"
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
                "reference_only": etf["code"] == "513100",
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
        return {
            "premium_rate": premium_rate,
            "level": premium_level(premium_rate),
            "estimated_nav": estimated_nav,
            "data_time": data_time,
            "source": "东方财富ETF实时IOPV",
            "reference_only": etf["code"] == "513100",
        }
    except Exception as e:
        print(f"  ⚠️ {etf['name']} 溢价率抓取失败: {e}")
        return {
            "premium_rate": None,
            "level": "暂不可用",
            "estimated_nav": None,
            "data_time": "暂不可用",
            "source": "暂不可用",
            "reference_only": False,
        }


def valuation_level(percentile_value: Optional[float]) -> str:
    if percentile_value is None:
        return "已提供 PE，未提供分位"
    if percentile_value < 30:
        return "偏低"
    if percentile_value <= 70:
        return "合理"
    return "偏高"


def normalize_percentile(value: Any) -> Optional[float]:
    try:
        n = float(value)
    except (TypeError, ValueError):
        return None
    return n * 100 if n <= 1 else n


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
    percentile_value = normalize_percentile(item.get("pe_percentile"))
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
    return {
        "index_name": item.get("name") or etf["index_name"],
        "pe_ttm": pe_value,
        "pe_percentile": percentile_value,
        "valuation_level": valuation_level(percentile_value),
        "source": source,
        "updated_at": updated_at,
    }


def fetch_valuation_from_danjuan(etf: dict[str, str]) -> dict[str, Any]:
    resp = requests.get(
        "https://danjuanfunds.com/djapi/index_eva/dj",
        timeout=15,
        headers=danjuan_headers(),
    )
    resp.raise_for_status()
    body = resp.json()
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
    resp = requests.get(
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
    for item in body.get("data") or []:
        trade_date = str(item.get("tradeDate") or "")
        pe_value = to_optional_float(item.get("peg"))
        if not re.fullmatch(r"\d{8}", trade_date) or pe_value is None or pe_value <= 0:
            continue
        points.append((datetime.strptime(trade_date, "%Y%m%d").date(), pe_value))
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
            "source": f"中证指数官网PE(TTM)，滚动{CSI300_PE_WINDOW_YEARS}年分位",
        })
    return history


_VALUATION_ARCHIVE_CACHE: dict[str, Optional[list[dict[str, Any]]]] = {}
_VALUATION_ARCHIVE_UNAVAILABLE = False


def fetch_valuation_archive(snapshot_date: date) -> Optional[list[dict[str, Any]]]:
    global _VALUATION_ARCHIVE_UNAVAILABLE
    date_text = snapshot_date.isoformat()
    if date_text in _VALUATION_ARCHIVE_CACHE:
        return _VALUATION_ARCHIVE_CACHE[date_text]
    if _VALUATION_ARCHIVE_UNAVAILABLE:
        return None
    try:
        resp = requests.get(
            VALUATION_ARCHIVE_URL.format(date=date_text),
            timeout=8,
            headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json"},
        )
        if resp.status_code == 404:
            _VALUATION_ARCHIVE_CACHE[date_text] = None
            return None
        resp.raise_for_status()
        body = resp.json()
    except (requests.RequestException, ValueError) as e:
        _VALUATION_ARCHIVE_UNAVAILABLE = True
        print(f"  ⚠️ 蛋卷估值公开快照不可用，本次运行改用后端缓存: {e}")
        return None
    items = body if isinstance(body, list) else ((body.get("data") or {}).get("items") or [])
    _VALUATION_ARCHIVE_CACHE[date_text] = items
    return items


def fetch_archived_valuation_on_or_before(
    etf: dict[str, str],
    target_date: date,
    max_lookback_days: int = 10,
) -> Optional[dict[str, Any]]:
    target_code = etf["valuation_index_code"].upper()
    best = None
    for offset in range(max_lookback_days + 1):
        snapshot_date = target_date - timedelta(days=offset)
        items = fetch_valuation_archive(snapshot_date)
        if not items:
            continue
        item = next(
            (item for item in items if str(item.get("index_code", "")).upper() == target_code),
            None,
        )
        if not item:
            continue
        valuation = valuation_from_danjuan_item(etf, item, "蛋卷估值每日公开快照")
        effective_date = parse_iso_date(valuation.get("updated_at"))
        if (
            effective_date is not None
            and effective_date <= target_date
            and valuation["pe_ttm"] is not None
            and valuation["pe_percentile"] is not None
        ):
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
        "source": valuation.get("source"),
    }


def fetch_source_valuation(etf: dict[str, str]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    if etf["valuation_index_code"] == "SH000300":
        history = fetch_csi300_pe_history()
        latest = history[-1]
        percentile = latest["pePercentile"]
        valuation = {
            "index_name": etf["index_name"],
            "pe_ttm": latest["peTtm"],
            "pe_percentile": percentile,
            "valuation_level": valuation_level(percentile),
            "source": latest["source"],
            "updated_at": latest["tradeDate"],
        }
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
            valuation = fetch_valuation_from_env(etf)
        elif valuation.get("source"):
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

    history = []
    current_date = parse_iso_date(valuation.get("updated_at"))
    if current_date is not None:
        targets = {
            current_date - timedelta(days=1),
            current_date - timedelta(days=7),
            subtract_calendar_month(current_date),
        }
        for target in sorted(targets, reverse=True):
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
    valuation_date = os.environ.get(f"{prefix}_VALUATION_DATE")
    source = os.environ.get(f"{prefix}_VALUATION_SOURCE", "手动环境变量")

    try:
        pe_value = float(pe) if pe else None
    except ValueError:
        pe_value = None
    percentile_value = normalize_percentile(percentile) if percentile else None

    if pe_value is None or percentile_value is None or parse_iso_date(valuation_date) is None:
        return {
            "index_name": etf["index_name"],
            "pe_ttm": None,
            "pe_percentile": None,
            "valuation_level": "估值数据暂不可用",
            "source": "未获取到稳定估值源",
            "updated_at": None,
        }

    return {
        "index_name": etf["index_name"],
        "pe_ttm": pe_value,
        "pe_percentile": percentile_value,
        "valuation_level": valuation_level(percentile_value),
        "source": source,
        "updated_at": valuation_date,
    }


def valuation_decision(percentile_value: Optional[float]) -> dict[str, str]:
    if percentile_value is None:
        return {"category": "insufficient", "action": "维持观察，估值依据不足"}
    if percentile_value < 30:
        return {"category": "low", "action": "低估观察，可关注额外资金"}
    if percentile_value < ADD_POSITION_LINE:
        return {"category": "watch", "action": "正常定投，继续观察"}
    if percentile_value <= 70:
        return {"category": "hold", "action": "正常定投，暂不额外加仓"}
    return {"category": "high", "action": "正常定投，不额外加仓"}


def valuation_action(percentile_value: Optional[float]) -> str:
    return valuation_decision(percentile_value)["action"]


def distance_to_watch_line(percentile_value: Optional[float]) -> str:
    if percentile_value is None:
        return "估值数据不足"
    diff = percentile_value - ADD_POSITION_LINE
    if diff > 0:
        return f"高于观察线 {diff:.0f} 个百分点"
    if diff < 0:
        return f"低于观察线 {abs(diff):.0f} 个百分点"
    return "位于观察线"


def fmt_signed_pct(value: Optional[float]) -> str:
    if value is None:
        return "暂不可用"
    return f"{value:+.2f}%"


def fmt_change_pct(value: Optional[float]) -> str:
    if value is None:
        return "暂不可用"
    if value > 0:
        return f"↑ {value:+.2f}%"
    if value < 0:
        return f"↓ {value:.2f}%"
    return "— 0.00%"


def format_premium(premium: dict[str, Any]) -> str:
    rate = premium.get("premium_rate")
    if rate is None:
        return premium.get("level") or "暂不可用"
    note = "，跨境IOPV仅供参考" if premium.get("reference_only") else ""
    return f"{rate:+.2f}%（{premium['level']}{note}）"


def valuation_trade_date(valuation: dict[str, Any]) -> Optional[str]:
    updated_at = str(valuation.get("updated_at") or "")
    return updated_at if parse_iso_date(updated_at) is not None else None


def history_field(item: dict[str, Any], camel: str, snake: str) -> Any:
    value = item.get(camel)
    return value if value is not None else item.get(snake)


def merge_pe_history(*histories: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_date: dict[str, dict[str, Any]] = {}
    for history in histories:
        for item in history:
            trade_date = str(history_field(item, "tradeDate", "trade_date") or "")
            percentile = to_optional_float(history_field(item, "pePercentile", "pe_percentile"))
            if parse_iso_date(trade_date) is None or percentile is None:
                continue
            normalized = dict(item)
            normalized["tradeDate"] = trade_date
            normalized["pePercentile"] = percentile
            if to_optional_float(normalized.get("peTtm")) is None:
                normalized["peTtm"] = to_optional_float(normalized.get("pe_ttm"))
            by_date[trade_date] = normalized
    return [by_date[key] for key in sorted(by_date)]


def pe_history_values(
    history: list[dict[str, Any]],
    current_percentile: Optional[float],
    current_trade_date: Optional[str],
    limit: int = 7,
) -> list[float]:
    by_date: dict[str, float] = {}
    for item in history:
        trade_date = str(history_field(item, "tradeDate", "trade_date") or "")
        percentile = to_optional_float(history_field(item, "pePercentile", "pe_percentile"))
        if parse_iso_date(trade_date) is not None and percentile is not None:
            by_date[trade_date] = percentile
    if current_percentile is not None and current_trade_date and parse_iso_date(current_trade_date):
        by_date[current_trade_date] = current_percentile
    return [by_date[date] for date in sorted(by_date, reverse=True)[:limit]]


def format_pe_history(snapshot: dict[str, Any]) -> str:
    valuation = snapshot["valuation"]
    values = pe_history_values(
        snapshot.get("pe_history", []),
        valuation["pe_percentile"],
        valuation_trade_date(valuation),
    )
    if not values:
        return "历史数据累计中"
    return " / ".join(f"{value:.0f}%" for value in values)


def format_pe_trend(snapshot: dict[str, Any]) -> str:
    valuation = snapshot["valuation"]
    values = pe_history_values(
        snapshot.get("pe_history", []),
        valuation["pe_percentile"],
        valuation_trade_date(valuation),
    )
    count = len(values)
    if count < 2:
        return f"历史数据累计中（已有 {count} 个交易日）"
    diff = values[0] - values[-1]
    if abs(diff) < 0.5:
        return f"近 {count} 个交易日基本持平"
    direction = "上升" if diff > 0 else "下降"
    return f"近 {count} 个交易日{direction} {abs(diff):.0f} 个百分点"


def fmt_pe_percentile(value: Optional[float]) -> str:
    return "暂不可用" if value is None else f"{value:.0f}%"


def fmt_baseline_date(value: Optional[str]) -> str:
    parsed = parse_iso_date(value)
    return f"（{parsed.strftime('%m-%d')}）" if parsed else ""


def fmt_pe_change(current: Optional[float], baseline: Optional[float]) -> str:
    if current is None or baseline is None:
        return "暂不可用"
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
        max_staleness_days=10,
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
        }

    history = merge_pe_history(snapshot.get("pe_history", []))
    previous = pe_observation_on_or_before(history, current_date - timedelta(days=1))
    week = pe_observation_on_or_before(history, current_date - timedelta(days=7))
    month = pe_observation_on_or_before(history, subtract_calendar_month(current_date))

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
    }


def backend_result(resp: requests.Response, operation: str) -> Optional[dict[str, Any]]:
    if resp.status_code != 200:
        print(f"  ⚠️ {operation}失败: HTTP {resp.status_code} {resp.text[:300]}")
        return None
    try:
        body = resp.json()
    except ValueError:
        print(f"  ⚠️ {operation}失败: 后端未返回JSON")
        return None
    if body.get("code") != 200:
        print(f"  ⚠️ {operation}失败: 业务码 {body.get('code')} {body.get('message', '')}")
        return None
    return body


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
        resp = requests.get(
            f"{backend_url}/api/market-valuations/{etf['valuation_index_code']}/latest",
            params={"limit": 365},
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
        "source": valuation.get("source"),
    }
    context = build_pe_context(snapshot)
    wanted_dates = {
        current_date,
        context.get("previous_date"),
        context.get("week_baseline_date"),
        context.get("month_baseline_date"),
    }
    source_items = merge_pe_history(snapshot.get("pe_history", []), [current_item])
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
    try:
        valuation, source_history = fetch_source_valuation(etf)
        print(f"  ✅ {etf['name']} 估值来自 {valuation['source']}")
    except Exception as e:
        print(f"  ⚠️ {etf['name']} 主估值源失败: {e}")
        source_history = []
        cached = merge_pe_history(backend_history)
        latest = next(
            (
                item
                for item in reversed(cached)
                if to_optional_float(item.get("peTtm")) is not None
                and to_optional_float(item.get("pePercentile")) is not None
                and is_fresh_date(
                    item.get("tradeDate"),
                    now_beijing().date(),
                    CURRENT_VALUATION_MAX_STALENESS_DAYS,
                )
            ),
            None,
        )
        env_valuation = fetch_valuation_from_env(etf)
        if latest:
            percentile = to_optional_float(latest.get("pePercentile"))
            valuation = {
                "index_name": etf["index_name"],
                "pe_ttm": to_optional_float(latest.get("peTtm")),
                "pe_percentile": percentile,
                "valuation_level": valuation_level(percentile),
                "source": f"{latest.get('source') or '后端估值缓存'}（最近有效值）",
                "updated_at": latest.get("tradeDate"),
            }
        elif (
            to_optional_float(env_valuation.get("pe_ttm")) is not None
            and to_optional_float(env_valuation.get("pe_percentile")) is not None
            and is_fresh_date(
                valuation_trade_date(env_valuation),
                now_beijing().date(),
                CURRENT_VALUATION_MAX_STALENESS_DAYS,
            )
        ):
            valuation = env_valuation
        else:
            raise RuntimeError(f"{etf['name']} 无可用实时估值、公开快照或后端缓存")
    quote = fetch_etf_quote(etf)
    return {
        "etf": etf,
        "quote": quote,
        "price_context": fetch_price_context(etf, quote),
        "premium": fetch_etf_premium(etf, quote),
        "valuation": valuation,
        "pe_history": merge_pe_history(backend_history, source_history),
    }


def fetch_a_share_candidates_from_eastmoney() -> list[dict[str, Any]]:
    url = "https://push2.eastmoney.com/api/qt/clist/get"
    params = {
        "pn": 1,
        "pz": A_SHARE_PAGE_SIZE,
        "po": 1,
        "np": 1,
        "fltt": 2,
        "invt": 2,
        "fid": "f6",
        "fs": A_SHARE_MARKET_FS,
        "fields": "f12,f14,f2,f3,f4,f5,f6,f8,f9,f10,f15,f16,f17,f18,f20,f21,f23,f24,f25,f62",
    }
    resp = requests.get(url, params=params, timeout=12, headers={"User-Agent": "Mozilla/5.0"})
    resp.raise_for_status()
    return resp.json().get("data", {}).get("diff", []) or []


def normalize_a_share_item(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "code": str(item.get("f12") or ""),
        "name": str(item.get("f14") or ""),
        "latest_price": to_optional_float(item.get("f2")),
        "pct_change": to_optional_float(item.get("f3")),
        "change_amount": to_optional_float(item.get("f4")),
        "volume": to_optional_float(item.get("f5")),
        "amount": to_optional_float(item.get("f6")),
        "turnover_rate": to_optional_float(item.get("f8")),
        "pe_dynamic": to_optional_float(item.get("f9")),
        "volume_ratio": to_optional_float(item.get("f10")),
        "high": to_optional_float(item.get("f15")),
        "low": to_optional_float(item.get("f16")),
        "open": to_optional_float(item.get("f17")),
        "previous_close": to_optional_float(item.get("f18")),
        "total_market_cap": to_optional_float(item.get("f20")),
        "float_market_cap": to_optional_float(item.get("f21")),
        "pb": to_optional_float(item.get("f23")),
        "pct_change_60d": to_optional_float(item.get("f24")),
        "pct_change_ytd": to_optional_float(item.get("f25")),
        "main_net_inflow": to_optional_float(item.get("f62")),
        "source": "东方财富",
    }


def is_a_share_candidate(stock: dict[str, Any]) -> bool:
    name = stock["name"].upper()
    if not stock["code"] or not stock["name"]:
        return False
    if any(keyword in name for keyword in A_SHARE_BANNED_NAME_KEYWORDS):
        return False
    latest = stock["latest_price"]
    pct = stock["pct_change"]
    amount = stock["amount"]
    market_cap = stock["total_market_cap"]
    pe = stock["pe_dynamic"]
    pb = stock["pb"]
    turnover = stock["turnover_rate"]
    if latest is None or latest <= 2:
        return False
    if pct is None or abs(pct) > A_SHARE_MAX_ABS_PCT_CHANGE:
        return False
    if amount is None or amount < A_SHARE_MIN_AMOUNT:
        return False
    if market_cap is None or market_cap < A_SHARE_MIN_MARKET_CAP:
        return False
    if pe is None or pe <= 0 or pe > 80:
        return False
    if pb is None or pb <= 0 or pb > 10:
        return False
    if turnover is None or turnover < 0.3 or turnover > 8:
        return False
    return True


def score_a_share_candidate(stock: dict[str, Any]) -> float:
    amount_score = min((stock["amount"] or 0) / 2000000000, 1.0) * 30
    market_cap_score = min((stock["total_market_cap"] or 0) / 80000000000, 1.0) * 20
    pct = abs(stock["pct_change"] or 0)
    stability_score = max(0, 1 - pct / A_SHARE_MAX_ABS_PCT_CHANGE) * 18
    pe = stock["pe_dynamic"] or 80
    pe_score = max(0, 1 - pe / 80) * 12
    pb = stock["pb"] or 10
    pb_score = max(0, 1 - pb / 10) * 8
    ratio = stock["volume_ratio"] or 0
    volume_ratio_score = 7 if 0.8 <= ratio <= 2.5 else 2
    inflow_score = 5 if (stock["main_net_inflow"] or 0) > 0 else 0
    trend_60d = stock["pct_change_60d"]
    trend_score = 0
    if trend_60d is not None:
        if -10 <= trend_60d <= 25:
            trend_score = 8
        elif 25 < trend_60d <= 45:
            trend_score = 3
    return amount_score + market_cap_score + stability_score + pe_score + pb_score + volume_ratio_score + inflow_score + trend_score


def build_a_share_watchlist() -> list[dict[str, Any]]:
    try:
        raw_items = fetch_a_share_candidates_from_eastmoney()
        stocks = [normalize_a_share_item(item) for item in raw_items]
        candidates = [stock for stock in stocks if is_a_share_candidate(stock)]
        for stock in candidates:
            stock["score"] = score_a_share_candidate(stock)
        candidates.sort(key=lambda x: x["score"], reverse=True)
        picks = candidates[:A_SHARE_PICK_COUNT]
        print(f"  ✅ A 股自动筛选完成：{len(picks)} 只候选")
        return picks
    except Exception as e:
        print(f"  ⚠️ A 股候选抓取失败: {e}")
        return []


def a_share_to_text(stock: dict[str, Any]) -> str:
    return "\n".join([
        f"股票：{stock['name']}（{stock['code']}）",
        f"最新价：{fmt_number(stock['latest_price'])}",
        f"涨跌额：{fmt_number(stock['change_amount'])}",
        f"涨跌幅：{fmt_number(stock['pct_change'], 2, '%')}",
        f"成交额：{fmt_amount(stock['amount'])}",
        f"换手率：{fmt_number(stock['turnover_rate'], 2, '%')}",
        f"动态 PE：{fmt_number(stock['pe_dynamic'], 2)}",
        f"PB：{fmt_number(stock['pb'], 2)}",
        f"量比：{fmt_number(stock['volume_ratio'], 2)}",
        f"60 日涨跌幅：{fmt_number(stock['pct_change_60d'], 2, '%')}",
        f"年初至今涨跌幅：{fmt_number(stock['pct_change_ytd'], 2, '%')}",
        f"主力净流入：{fmt_amount(stock['main_net_inflow'])}",
        f"机械筛选分：{fmt_number(stock.get('score'), 1)}",
        f"数据来源：{stock['source']}",
    ])


def snapshot_to_text(snapshot: dict[str, Any]) -> str:
    etf = snapshot["etf"]
    quote = snapshot["quote"]
    valuation = snapshot["valuation"]
    percentile = valuation["pe_percentile"]
    return "\n".join([
        f"标的：{etf['name']}（{etf['code']}）",
        f"最新价：{fmt_number(quote['latest_price'])}",
        f"涨跌幅：{fmt_number(quote['pct_change'], 2, '%')}",
        f"PE 分位：{fmt_number(percentile, 0, '%')}",
        f"状态：{valuation['valuation_level']}",
        f"今日动作：{valuation_action(percentile)}",
        f"近一周 PE 分位：{format_pe_history(snapshot)}",
        f"趋势：{format_pe_trend(snapshot)}",
        f"距离观察线 {ADD_POSITION_LINE}%：{distance_to_watch_line(percentile)}",
    ])


def build_etf_prompt(snapshots: list[dict[str, Any]], edition: str, stock_picks: list[dict[str, Any]]) -> str:
    today = now_beijing().strftime("%Y-%m-%d")
    label = edition_label(edition)
    etf_text = "\n\n---\n\n".join(snapshot_to_text(s) for s in snapshots)
    stock_text = "\n\n---\n\n".join(a_share_to_text(s) for s in stock_picks) if stock_picks else "今日 A 股候选数据暂不可用，暂不生成观察名单。"
    edition_strategy = "早间偏今日观察，避免盘中早段下定论。" if edition.endswith("morning") else "晚间偏全天复盘和明日观察点。"
    return f"""你是一位谨慎、保守、重视风险控制的市场观察助手。请基于我提供的数据，生成一份 500-700 字以内的企业微信每日市场观察。

【版本策略】
{edition_strategy}

【硬性约束】
1. 只能使用下方提供的数据，不允许编造价格、涨跌幅、PE、PB 或分位数。
2. 结论先行，避免重复表达；每个 ETF 必须包含：状态、今日动作、PE 分位、近一周 PE、趋势、距离观察线。
3. “近一周 PE”必须保持输入中的顺序：今天 / 昨天 / 前天 / 3天前 / 4天前 / 5天前 / 6天前。
4. A 股只能称为“观察候选”，每只只写一句观察理由和一句主要风险。
5. 不得输出“稳赚、必涨、满仓、梭哈、强烈买入、推荐买入、买入推荐、卖出建议、目标价、抄底、翻倍、牛股、确定性机会、投资顾问”等表达。
6. 不给具体仓位比例，不给目标价，不给确定性预测。
7. 结尾必须附上风险提示：{DISCLAIMER}

【输出格式】
> **市场观察 · {today}（{label}）**

## 今日总览
- 沪深300ETF：状态，今日动作。
- 纳指100ETF：状态，今日动作。
- A股观察：只观察，不追涨。

## 沪深300ETF
- 状态：...
- 今日动作：...
- PE分位：...
- 近一周 PE：... / ... / ...
- 趋势：...
- 距离观察线 60%：...

## 纳指100ETF
- 状态：...
- 今日动作：...
- PE分位：...
- 近一周 PE：... / ... / ...
- 趋势：...
- 距离观察线 60%：...

## A股观察
- 股票A：观察理由；主要风险。
- 股票B：观察理由；主要风险。

> 风险提示：...

【ETF 数据】
{etf_text}

【A 股候选数据】
{stock_text}
"""


def sanitize_model_text(text: str) -> str:
    for word in BANNED_WORDS:
        text = text.replace(word, "谨慎评估")
    return text.strip()


def sanitize_report(report: str) -> str:
    report = report.replace(f"> {DISCLAIMER}", "").replace(DISCLAIMER, "")
    report = sanitize_model_text(report)
    return report.rstrip() + f"\n\n> {DISCLAIMER}"


def call_llm_with_retry(prompt: str, max_retries: int = 3, ensure_disclaimer: bool = True) -> str:
    from openai import OpenAI

    for model_index, model_config in enumerate(LLM_MODELS):
        model_name = model_config["name"]
        base_url = model_config["base_url"]
        api_key_env = model_config["api_key_env"]
        description = model_config.get("description", model_name)
        api_key = os.environ.get(api_key_env, "")
        if not api_key:
            print(f"⚠️ 未配置 {api_key_env}，跳过 {description}")
            continue

        client = OpenAI(api_key=api_key, base_url=base_url)
        retries = max_retries if model_index == 0 else max(1, max_retries - 1)
        for attempt in range(retries + 1):
            try:
                print(f"🤖 正在调用 {description} (尝试 {attempt + 1}/{retries + 1})...")
                response = client.chat.completions.create(
                    model=model_name,
                    messages=[{"role": "user", "content": prompt}],
                    temperature=0.2,
                    max_tokens=1800,
                )
                content = response.choices[0].message.content.strip()
                if content.startswith("```"):
                    content = re.sub(r"^```(?:markdown)?\n?", "", content)
                    content = re.sub(r"\n?```$", "", content)
                print(f"✅ 成功使用模型: {description}")
                return sanitize_report(content) if ensure_disclaimer else sanitize_model_text(content)
            except Exception as e:
                error_str = str(e).lower()
                retryable = any(k in error_str for k in ("503", "rate limit", "unavailable", "timeout", "connection"))
                if retryable and attempt < retries:
                    wait_time = (attempt + 1) * 3
                    print(f"⚠️ {description} 暂不可用，等待 {wait_time} 秒后重试...")
                    time.sleep(wait_time)
                    continue
                if attempt >= retries:
                    if model_index < len(LLM_MODELS) - 1:
                        print(f"❌ {description} 不可用，尝试备用模型...")
                        break
                    raise

    raise RuntimeError("所有 LLM 模型均不可用，请检查 API 配置或稍后重试")


def build_stock_observation_prompt(stock_picks: list[dict[str, Any]]) -> str:
    stock_text = "\n\n---\n\n".join(a_share_to_text(stock) for stock in stock_picks)
    return f"""请基于下方公开行情指标，为每只股票写一行简短观察。

硬性要求：
1. 每只严格一行，格式：- 股票名（代码）：观察理由；主要风险。
2. 只使用给定数据，不得编造基本面、新闻、行业或价格。
3. 只能称为观察候选，不输出买入、卖出、目标价、仓位或确定性预测。
4. 每行不超过 55 个中文字符，只输出列表，不加标题和结尾。

【候选数据】
{stock_text}
"""


def fallback_stock_observations(stock_picks: list[dict[str, Any]]) -> str:
    if not stock_picks:
        return "- 今日 A 股候选数据暂不可用，暂不生成观察名单。"
    return "\n".join(
        f"- {stock['name']}（{stock['code']}）：流动性和估值过滤后进入观察；风险是未做基本面深度尽调。"
        for stock in stock_picks
    )


def etf_short_name(snapshot: dict[str, Any]) -> str:
    return snapshot["etf"]["name"].split()[0]


def build_add_position_conclusion(snapshots: list[dict[str, Any]]) -> str:
    low_names = []
    insufficient_names = []
    for snapshot in snapshots:
        decision = valuation_decision(snapshot["valuation"]["pe_percentile"])
        name = etf_short_name(snapshot)
        if decision["category"] == "low":
            low_names.append(name)
        elif decision["category"] == "insufficient":
            insufficient_names.append(name)

    if low_names:
        conclusion = f"{'、'.join(low_names)}进入低估观察，可关注额外资金"
        if insufficient_names:
            conclusion += f"；{'、'.join(insufficient_names)}估值依据不足"
        return conclusion + "。"
    if insufficient_names:
        return f"今日暂不新增资金；{'、'.join(insufficient_names)}估值依据不足。"
    return "今日不额外加仓，两只ETF维持正常定投或观察。"


def build_market_direction_summary(snapshots: list[dict[str, Any]]) -> str:
    changes = [to_optional_float(snapshot["quote"].get("pct_change")) for snapshot in snapshots]
    if any(value is None for value in changes):
        return "部分行情数据暂不可用，暂不归纳短期方向。"
    if all(value > 0 for value in changes):
        return "今日两只ETF同步上涨。"
    if all(value < 0 for value in changes):
        return "今日两只ETF同步回落。"
    if changes[0] * changes[1] < 0:
        return "今日两只ETF表现分化。"
    return "今日两只ETF整体波动有限。"


def build_next_watch_point(snapshots: list[dict[str, Any]]) -> str:
    categories = {
        valuation_decision(snapshot["valuation"]["pe_percentile"])["category"]
        for snapshot in snapshots
    }
    if "low" in categories:
        return "继续观察低估状态能否延续，以及场内溢价是否扩大。"
    if categories & {"hold", "high"}:
        return f"关注PE分位是否回落到{ADD_POSITION_LINE}%观察线以下，以及场内溢价是否扩大。"
    if "watch" in categories:
        return "关注PE分位是否进一步回落到30%以下低估区。"
    return "等待估值数据恢复，并继续观察场内溢价。"


def build_programmatic_report(
    snapshots: list[dict[str, Any]],
    edition: str,
    stock_observations: str,
    ai_note: Optional[str] = None,
) -> str:
    today = now_beijing().strftime("%Y-%m-%d")
    lines = [
        f"> **市场观察 · {today}（{edition_label(edition)}）**",
        "",
        "## 先看结论",
        f"- **额外加仓判断：{build_add_position_conclusion(snapshots)}**",
    ]
    for snapshot in snapshots:
        valuation = snapshot["valuation"]
        percentile = valuation["pe_percentile"]
        lines.append(
            f"- {etf_short_name(snapshot)}：{valuation_action(percentile)}（PE分位 {fmt_number(percentile, 0, '%')}）。"
        )

    lines.extend(["", "## 两只ETF变化"])
    for snapshot in snapshots:
        quote = snapshot["quote"]
        context = snapshot["price_context"]
        current_price = fmt_price(quote["latest_price"])
        lines.extend([
            f"### {etf_short_name(snapshot)}",
            f"- 当前价格 {current_price}｜昨日收盘{fmt_baseline_date(context.get('previous_date'))} "
            f"{fmt_price(context.get('previous_close'))}｜今日　 {fmt_change_pct(quote['pct_change'])}",
            f"- 当前价格 {current_price}｜一周前价{fmt_baseline_date(context.get('week_baseline_date'))} "
            f"{fmt_price(context.get('week_baseline'))}｜近一周 {fmt_change_pct(context['week_pct_change'])}",
            f"- 当前价格 {current_price}｜一月前价{fmt_baseline_date(context.get('month_baseline_date'))} "
            f"{fmt_price(context.get('month_baseline'))}｜近一月 {fmt_change_pct(context['month_pct_change'])}",
        ])

    lines.extend(["", "## PE分位变化"])
    for snapshot in snapshots:
        premium = snapshot["premium"]
        valuation = snapshot["valuation"]
        pe_context = build_pe_context(snapshot)
        current_pe = fmt_pe_percentile(pe_context["current"])
        lines.extend([
            f"### {etf_short_name(snapshot)}（{valuation['valuation_level']}）",
            f"- 当前PE分位 {current_pe}｜昨日分位{fmt_baseline_date(pe_context['previous_date'])} "
            f"{fmt_pe_percentile(pe_context['previous'])}｜今日　 "
            f"{fmt_pe_change(pe_context['current'], pe_context['previous'])}",
            f"- 当前PE分位 {current_pe}｜一周前分位{fmt_baseline_date(pe_context['week_baseline_date'])} "
            f"{fmt_pe_percentile(pe_context['week_baseline'])}｜近一周 "
            f"{fmt_pe_change(pe_context['current'], pe_context['week_baseline'])}",
            f"- 当前PE分位 {current_pe}｜一月前分位{fmt_baseline_date(pe_context['month_baseline_date'])} "
            f"{fmt_pe_percentile(pe_context['month_baseline'])}｜近一月 "
            f"{fmt_pe_change(pe_context['current'], pe_context['month_baseline'])}",
            f"- 场内参考溢价率：{format_premium(premium)}；估值日期："
            f"{valuation_trade_date(valuation) or '暂不可用'}；估值源：{valuation['source']}。",
        ])

    lines.extend(["", "## A股观察", stock_observations])
    if ai_note:
        lines.extend(["", f"> A股分析暂不可用：{ai_note}"])

    lines.extend([
        "",
        "## 今日总结",
        f"- {build_market_direction_summary(snapshots)}",
        f"- 下一观察点：{build_next_watch_point(snapshots)}",
    ])
    return sanitize_report("\n".join(lines))


def build_fallback_report(snapshots: list[dict[str, Any]], edition: str, reason: str, stock_picks: Optional[list[dict[str, Any]]] = None) -> str:
    return build_programmatic_report(
        snapshots,
        edition,
        fallback_stock_observations(stock_picks or []),
        reason,
    )


def colorize_wework_changes(text: str) -> str:
    text = re.sub(
        r"↑ \+\d+(?:\.\d+)?(?:%| 个百分点)",
        lambda match: f'<font color="warning">{match.group(0)}</font>',
        text,
    )
    text = re.sub(
        r"↓ -\d+(?:\.\d+)?(?:%| 个百分点)",
        lambda match: f'<font color="info">{match.group(0)}</font>',
        text,
    )
    return re.sub(
        r"— 0(?:\.0+)?(?:%| 个百分点)",
        lambda match: f'<font color="comment">{match.group(0)}</font>',
        text,
    )


def convert_to_wework_markdown(md_text: str) -> str:
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

    summary_index = next(
        (index for index, line in enumerate(out) if line == "> **今日总结**"),
        len(out),
    )
    tail = out[summary_index:]
    marker = "> ...(内容已截断)"
    suffix = [marker, "", *tail]
    suffix_bytes = len("\n".join(suffix).encode("utf-8"))
    prefix = []
    current_bytes = 0
    for line in out[:summary_index]:
        line_bytes = len(line.encode("utf-8")) + 1
        if current_bytes + line_bytes + suffix_bytes > max_bytes:
            break
        prefix.append(line)
        current_bytes += line_bytes

    truncated = "\n".join([*prefix, *suffix])
    while len(truncated.encode("utf-8")) > max_bytes and prefix:
        prefix.pop()
        truncated = "\n".join([*prefix, *suffix])
    return truncated


def push_to_wechat(content: str, webhook_url: str) -> bool:
    payload = {"msgtype": "markdown", "markdown": {"content": content}}
    headers = {"Content-Type": "application/json; charset=utf-8"}
    resp = requests.post(webhook_url, json=payload, headers=headers, timeout=15)
    data = resp.json()
    if data.get("errcode") == 0:
        print(f"✅ ETF 企业微信推送成功 ({len(content.encode('utf-8'))} bytes)")
        return True
    print(f"❌ ETF 企业微信推送失败: {data}")
    return False


def push_to_backend(edition: str, title: str, content: str, summary: str, run_id: str) -> bool:
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
        "title": title,
        "content": content[:30000],
        "summary": summary,
        "runId": run_id,
    }
    for attempt in range(5):
        try:
            resp = requests.post(
                f"{backend_url}/api/reports/ingest",
                json=payload,
                headers={"X-Ingest-Token": ingest_token},
                timeout=60,
            )
            if resp.status_code == 200:
                print(f"  ✅ ETF 报告已同步到后端（第 {attempt + 1} 次尝试）")
                return True
            print(f"  ⚠️ 后端 API 返回 {resp.status_code}: {resp.text}")
        except Exception as e:
            print(f"  ⚠️ 后端 API 同步失败: {e}")
        if attempt < 4:
            time.sleep((attempt + 1) * 15)
    return False


def build_summary(snapshots: list[dict[str, Any]]) -> str:
    changes = "；".join(
        f"{etf_short_name(snapshot)}今日 {fmt_change_pct(snapshot['quote']['pct_change'])}"
        for snapshot in snapshots
    )
    return f"额外加仓判断：{build_add_position_conclusion(snapshots)}{changes}。"


def main() -> None:
    today = now_beijing().strftime("%Y-%m-%d")
    edition = detect_edition()
    label = edition_label(edition)
    report_file = f"市场观察_{today}（{label}）.md"

    print(f"\n{'=' * 50}")
    print(f"📈 市场观察 · {today}（{label}）")
    print(f"{'=' * 50}\n")

    webhook_url = os.environ.get("ETF_WECHAT_WEBHOOK", "")
    dry_run = os.environ.get("ETF_DRY_RUN", "").lower() in ("1", "true", "yes")
    if not webhook_url and not dry_run:
        print("❌ 缺少 ETF_WECHAT_WEBHOOK 环境变量")
        sys.exit(1)

    print("📡 正在抓取 ETF 行情...")
    try:
        snapshots = [build_snapshot(etf) for etf in ETF_LIST]
    except Exception as e:
        print(f"❌ ETF 行情抓取失败: {e}")
        sys.exit(1)

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

    print("📡 正在筛选 A 股观察候选...")
    stock_picks = build_a_share_watchlist()

    ai_note = None
    stock_observations = fallback_stock_observations(stock_picks)
    if dry_run and stock_picks:
        print("🧪 ETF_DRY_RUN 已开启，跳过 A 股 LLM 分析")
    elif stock_picks:
        try:
            stock_observations = call_llm_with_retry(
                build_stock_observation_prompt(stock_picks),
                ensure_disclaimer=False,
            )
        except Exception as e:
            ai_note = str(e)
            print(f"⚠️ A 股 AI 分析失败，改用基础观察: {e}")
    report = build_programmatic_report(snapshots, edition, stock_observations, ai_note)

    if dry_run:
        print("🧪 ETF_DRY_RUN 已开启，跳过本地报告文件写入")
    else:
        with open(report_file, "w", encoding="utf-8") as f:
            f.write(report)
        print(f"💾 已保存: {report_file}")

    wx_content = convert_to_wework_markdown(report)
    if dry_run:
        print("🧪 ETF_DRY_RUN 已开启，跳过企业微信推送")
        print(wx_content)
    else:
        push_to_wechat(wx_content, webhook_url)

    run_id = os.environ.get("GITHUB_RUN_ID", "local")
    title = f"【市场观察{label}】沪深300ETF / 纳指100ETF / A股观察 {today}"
    if dry_run:
        print("🧪 ETF_DRY_RUN 已开启，跳过后端报告存储")
    else:
        push_to_backend(edition, title, report, build_summary(snapshots), run_id)

    print(f"\n✅ 市场观察完成！({now_beijing().strftime('%H:%M:%S')})")


if __name__ == "__main__":
    main()
