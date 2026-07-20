# -*- coding: utf-8 -*-
"""
市场观察脚本
用于生成 ETF 与 A 股市场观察早晚报，并推送到独立企业微信机器人。
"""

import os
import re
import sys
import time
from datetime import datetime, timezone, timedelta
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


def fetch_valuation_from_danjuan(etf: dict[str, str]) -> dict[str, Any]:
    url = "https://danjuanfunds.com/djapi/index_eva/dj"
    headers = {
        "User-Agent": "Mozilla/5.0",
        "Accept": "application/json, text/plain, */*",
        "Referer": "https://danjuanfunds.com/djmodule/value-center",
    }
    resp = requests.get(url, timeout=12, headers=headers)
    resp.raise_for_status()
    items = resp.json().get("data", {}).get("items", [])
    target_code = etf["valuation_index_code"].upper()
    for item in items:
        if str(item.get("index_code", "")).upper() != target_code:
            continue
        pe_value = item.get("pe")
        try:
            pe_value = float(pe_value) if pe_value not in (None, "") else None
        except (TypeError, ValueError):
            pe_value = None
        percentile_value = normalize_percentile(item.get("pe_percentile"))
        ts = item.get("ts")
        updated_at = None
        if ts:
            try:
                updated_at = datetime.fromtimestamp(int(ts) / 1000, BEIJING_TZ).strftime("%Y-%m-%d")
            except (TypeError, ValueError):
                updated_at = str(ts)
        return {
            "index_name": item.get("name") or etf["index_name"],
            "pe_ttm": pe_value,
            "pe_percentile": percentile_value,
            "valuation_level": valuation_level(percentile_value),
            "source": "蛋卷基金指数估值",
            "updated_at": updated_at,
        }
    raise RuntimeError(f"蛋卷估值未找到 {etf['valuation_index_code']}")


def fetch_valuation_from_env(etf: dict[str, str]) -> dict[str, Any]:
    prefix = etf["valuation_env_prefix"]
    pe = os.environ.get(f"{prefix}_PE")
    percentile = os.environ.get(f"{prefix}_PE_PERCENTILE")
    source = os.environ.get(f"{prefix}_VALUATION_SOURCE", "手动环境变量")

    try:
        pe_value = float(pe) if pe else None
    except ValueError:
        pe_value = None
    percentile_value = normalize_percentile(percentile) if percentile else None

    if pe_value is None:
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
        "updated_at": now_beijing().strftime("%Y-%m-%d"),
    }


def fetch_valuation(etf: dict[str, str]) -> dict[str, Any]:
    try:
        valuation = fetch_valuation_from_danjuan(etf)
        print(f"  ✅ {etf['name']} 估值来自 {valuation['source']}")
        return valuation
    except Exception as e:
        print(f"  ⚠️ {etf['name']} 蛋卷估值抓取失败: {e}")
        return fetch_valuation_from_env(etf)


def valuation_action(percentile_value: Optional[float]) -> str:
    if percentile_value is None:
        return "维持观察，估值依据不足"
    if percentile_value < 30:
        return "低估观察，可关注额外资金"
    if percentile_value < ADD_POSITION_LINE:
        return "正常定投，可继续观察"
    if percentile_value <= 70:
        return "正常定投，暂不额外加仓"
    return "正常定投，不额外加仓"


def distance_to_add_line(percentile_value: Optional[float]) -> str:
    if percentile_value is None:
        return "估值数据不足"
    diff = percentile_value - ADD_POSITION_LINE
    if diff > 0:
        return f"还差 {diff:.0f} 个百分点"
    if diff < 0:
        return f"已低于加仓线 {abs(diff):.0f} 个百分点"
    return "已到加仓观察线"


def format_pe_history(history: list[dict[str, Any]], current_percentile: Optional[float]) -> str:
    values = []
    seen_dates = set()
    for item in history:
        trade_date = str(item.get("tradeDate") or item.get("trade_date") or "")
        percentile = to_optional_float(item.get("pePercentile") or item.get("pe_percentile"))
        if percentile is None:
            continue
        values.append(f"{percentile:.0f}%")
        if trade_date:
            seen_dates.add(trade_date)
    today = now_beijing().strftime("%Y-%m-%d")
    if current_percentile is not None and today not in seen_dates:
        values.insert(0, f"{current_percentile:.0f}%")
    return " / ".join(values[:7]) if values else "历史数据累计中"


def format_pe_trend(history: list[dict[str, Any]], current_percentile: Optional[float]) -> str:
    values = []
    seen_dates = set()
    for item in history:
        trade_date = str(item.get("tradeDate") or item.get("trade_date") or "")
        percentile = to_optional_float(item.get("pePercentile") or item.get("pe_percentile"))
        if percentile is None:
            continue
        values.append(percentile)
        if trade_date:
            seen_dates.add(trade_date)
    today = now_beijing().strftime("%Y-%m-%d")
    if current_percentile is not None and today not in seen_dates:
        values.insert(0, current_percentile)
    values = values[:7]
    if len(values) < 2:
        return "历史数据累计中"
    diff = values[0] - values[-1]
    if abs(diff) < 0.5:
        return "一周基本持平"
    direction = "上升" if diff > 0 else "下降"
    return f"一周{direction} {abs(diff):.0f} 个百分点"


def fetch_valuation_history(etf: dict[str, str]) -> list[dict[str, Any]]:
    backend_url = os.environ.get("BACKEND_API_URL", "")
    ingest_token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url or not ingest_token:
        return []
    try:
        resp = requests.get(
            f"{backend_url}/api/market-valuations/{etf['valuation_index_code']}/latest",
            params={"limit": 7},
            headers={"X-Ingest-Token": ingest_token},
            timeout=20,
        )
        if resp.status_code != 200:
            print(f"  ⚠️ {etf['index_name']} 估值历史查询失败: {resp.status_code}")
            return []
        return resp.json().get("data") or []
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
    payload = {
        "indexCode": etf["valuation_index_code"],
        "indexName": valuation["index_name"],
        "peTtm": valuation["pe_ttm"],
        "pePercentile": valuation["pe_percentile"],
        "valuationLevel": valuation["valuation_level"],
        "tradeDate": now_beijing().strftime("%Y-%m-%d"),
        "source": valuation["source"],
    }
    try:
        resp = requests.post(
            f"{backend_url}/api/market-valuations/ingest",
            json=payload,
            headers={"X-Ingest-Token": ingest_token},
            timeout=30,
        )
        if resp.status_code == 200:
            print(f"  ✅ {etf['index_name']} 估值历史已同步")
            return True
        print(f"  ⚠️ {etf['index_name']} 估值历史同步失败: {resp.status_code} {resp.text}")
    except Exception as e:
        print(f"  ⚠️ {etf['index_name']} 估值历史同步失败: {e}")
    return False


def build_snapshot(etf: dict[str, str]) -> dict[str, Any]:
    valuation = fetch_valuation(etf)
    return {
        "etf": etf,
        "quote": fetch_etf_quote(etf),
        "valuation": valuation,
        "pe_history": fetch_valuation_history(etf),
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
        f"近一周 PE：{format_pe_history(snapshot.get('pe_history', []), percentile)}",
        f"趋势：{format_pe_trend(snapshot.get('pe_history', []), percentile)}",
        f"距离加仓线 {ADD_POSITION_LINE}%：{distance_to_add_line(percentile)}",
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
2. 结论先行，避免重复表达；每个 ETF 必须包含：状态、今日动作、PE 分位、近一周 PE、趋势、距离加仓线。
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
- 距离加仓线 60%：...

## 纳指100ETF
- 状态：...
- 今日动作：...
- PE分位：...
- 近一周 PE：... / ... / ...
- 趋势：...
- 距离加仓线 60%：...

## A股观察
- 股票A：观察理由；主要风险。
- 股票B：观察理由；主要风险。

> 风险提示：...

【ETF 数据】
{etf_text}

【A 股候选数据】
{stock_text}
"""


def sanitize_report(report: str) -> str:
    for word in BANNED_WORDS:
        report = report.replace(word, "谨慎评估")
    if DISCLAIMER not in report:
        report = report.rstrip() + f"\n\n> {DISCLAIMER}"
    return report


def call_llm_with_retry(prompt: str, max_retries: int = 3) -> str:
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
                return sanitize_report(content)
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


def build_fallback_report(snapshots: list[dict[str, Any]], edition: str, reason: str, stock_picks: Optional[list[dict[str, Any]]] = None) -> str:
    today = now_beijing().strftime("%Y-%m-%d")
    lines = [f"> **市场观察 · {today}（{edition_label(edition)}）**", "", f"> AI 分析暂不可用：{reason}", "", "## 今日总览"]
    for snapshot in snapshots:
        etf = snapshot["etf"]
        valuation = snapshot["valuation"]
        percentile = valuation["pe_percentile"]
        lines.append(f"- {etf['name']}：{valuation['valuation_level']}，{valuation_action(percentile)}。")
    lines.append("- A股观察：只观察，不追涨。")

    for snapshot in snapshots:
        etf = snapshot["etf"]
        quote = snapshot["quote"]
        valuation = snapshot["valuation"]
        percentile = valuation["pe_percentile"]
        lines.extend([
            "",
            f"## {etf['name']}",
            f"- 状态：{valuation['valuation_level']}",
            f"- 今日动作：{valuation_action(percentile)}",
            f"- PE分位：{fmt_number(percentile, 0, '%')}",
            f"- 近一周 PE：{format_pe_history(snapshot.get('pe_history', []), percentile)}",
            f"- 趋势：{format_pe_trend(snapshot.get('pe_history', []), percentile)}",
            f"- 距离加仓线 {ADD_POSITION_LINE}%：{distance_to_add_line(percentile)}",
            f"- 涨跌幅：{fmt_number(quote['pct_change'], 2, '%')}",
        ])

    lines.extend(["", "## A股观察"])
    if stock_picks:
        for stock in stock_picks:
            lines.append(f"- {stock['name']}（{stock['code']}）：流动性和估值过滤后进入观察；风险是未做基本面深度尽调。")
    else:
        lines.append("- 今日 A 股候选数据暂不可用，暂不生成观察名单。")

    lines.append(f"\n> {DISCLAIMER}")
    return "\n".join(lines)


def convert_to_wework_markdown(md_text: str) -> str:
    lines = md_text.split("\n")
    out = []
    for line in lines:
        stripped = line.strip()
        if not stripped:
            out.append("")
            continue
        if stripped.startswith("### "):
            out.append(f"**{stripped[4:]}**")
        elif stripped.startswith("## "):
            out.append(f"> **{stripped[3:]}**")
        elif stripped.startswith("# "):
            out.append(f"> **{stripped[2:]}**")
        elif stripped.startswith("|") and stripped.endswith("|"):
            continue
        else:
            out.append(stripped)

    result = "\n".join(out)
    max_bytes = 3800
    encoded = result.encode("utf-8")
    if len(encoded) <= max_bytes:
        return result

    current_bytes = 0
    truncated = []
    for line in out:
        line_bytes = len(line.encode("utf-8")) + 1
        if current_bytes + line_bytes > max_bytes - 120:
            break
        truncated.append(line)
        current_bytes += line_bytes
    return "\n".join(truncated) + f"\n\n> ...(内容已截断)\n> {DISCLAIMER}"


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


def build_summary(report: str) -> str:
    text = re.sub(r"[#>*`\-]", "", report)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:100] + "..." if len(text) > 100 else text


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

    print("📡 正在同步估值历史...")
    for snapshot in snapshots:
        push_valuation_history(snapshot)

    print("📡 正在筛选 A 股观察候选...")
    stock_picks = build_a_share_watchlist()

    prompt = build_etf_prompt(snapshots, edition, stock_picks)
    try:
        report = call_llm_with_retry(prompt)
    except Exception as e:
        print(f"⚠️ AI 分析失败，改用基础行情报告: {e}")
        report = build_fallback_report(snapshots, edition, str(e), stock_picks)

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
    push_to_backend(edition, title, report, build_summary(report), run_id)

    print(f"\n✅ 市场观察完成！({now_beijing().strftime('%H:%M:%S')})")


if __name__ == "__main__":
    main()
