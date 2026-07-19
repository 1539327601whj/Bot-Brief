# -*- coding: utf-8 -*-
"""
ETF 每日观察脚本
用于生成沪深300ETF/纳指100ETF早晚报，并推送到独立企业微信机器人。
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

BANNED_WORDS = ["稳赚", "必涨", "满仓", "梭哈", "强烈买入", "无脑买入", "一定上涨"]
DISCLAIMER = "风险提示：本报告由公开数据和 AI 辅助生成，仅供信息参考，不构成任何投资建议或买卖依据。基金有风险，投资需谨慎。"


def now_beijing() -> datetime:
    return datetime.now(BEIJING_TZ)


def detect_edition() -> str:
    manual = os.environ.get("EDITION", "auto").lower()
    mapping = {
        "morning": "etf_morning",
        "evening": "etf_evening",
        "etf_morning": "etf_morning",
        "etf_evening": "etf_evening",
    }
    if manual in mapping:
        return mapping[manual]
    return "etf_morning" if now_beijing().hour < 12 else "etf_evening"


def edition_label(edition: str) -> str:
    return "早间版" if edition == "etf_morning" else "晚间版"


def scaled(value: Any, divisor: float) -> Optional[float]:
    try:
        n = float(value)
        if n == -1:
            return None
        return n / divisor
    except (TypeError, ValueError):
        return None


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


def build_snapshot(etf: dict[str, str]) -> dict[str, Any]:
    return {
        "etf": etf,
        "quote": fetch_etf_quote(etf),
        "valuation": fetch_valuation(etf),
    }


def snapshot_to_text(snapshot: dict[str, Any]) -> str:
    etf = snapshot["etf"]
    quote = snapshot["quote"]
    valuation = snapshot["valuation"]
    return "\n".join([
        f"标的：{etf['name']}（{etf['code']}）",
        f"跟踪指数：{valuation['index_name']}",
        f"最新价：{fmt_number(quote['latest_price'])}",
        f"涨跌额：{fmt_number(quote['change_amount'])}",
        f"涨跌幅：{fmt_number(quote['pct_change'], 2, '%')}",
        f"开盘/最高/最低/昨收：{fmt_number(quote['open'])} / {fmt_number(quote['high'])} / {fmt_number(quote['low'])} / {fmt_number(quote['previous_close'])}",
        f"成交额：{fmt_amount(quote['amount'])}",
        f"行情时间：{quote['data_time']}（{quote['source']}）",
        f"跟踪指数 PE：{fmt_number(valuation['pe_ttm'], 2)}",
        f"PE 分位：{fmt_number(valuation['pe_percentile'], 1, '%')}",
        f"估值状态：{valuation['valuation_level']}",
        f"估值来源：{valuation['source']}",
    ])


def build_etf_prompt(snapshots: list[dict[str, Any]], edition: str) -> str:
    today = now_beijing().strftime("%Y-%m-%d")
    label = edition_label(edition)
    source_text = "\n\n---\n\n".join(snapshot_to_text(s) for s in snapshots)
    return f"""你是一位谨慎、保守、重视风险控制的 ETF 投研助手。请基于我提供的行情和估值数据，生成一份企业微信 ETF 每日观察。

【重要约束】
1. 只能使用下方提供的数据，不允许编造价格、涨跌幅、PE 或分位数。
2. 如果 PE 或分位数是“暂不可用”，必须明确说明估值数据暂不可用，并降低判断置信度。
3. 不得输出“稳赚、必涨、满仓、梭哈、强烈买入、无脑买入、一定上涨”等激进或承诺性表达。
4. “是否值得加仓”只能使用保守口径，例如：可小额分批、适合定投、暂不追高、等待回调、维持观察、控制仓位。
5. 趋势预测只能写成情景判断，必须包含风险因素。
6. 结尾必须附上风险提示：{DISCLAIMER}
7. 总字数控制在 900 个中文字符以内，只输出最终报告，不解释生成过程。

【输出格式】
> **ETF 每日观察 · {today}（{label}）**

## 1. 沪深300ETF 华泰柏瑞（510300）
- 最新价：...
- 涨跌幅：...
- 跟踪指数 PE：...
- 估值位置：...
- 加仓判断：...
- 趋势判断：...

## 2. 纳指100ETF 国泰（513100）
- 最新价：...
- 涨跌幅：...
- 跟踪指数 PE：...
- 估值位置：...
- 加仓判断：...
- 趋势判断：...

## 今日结论
- 沪深300ETF：...
- 纳指100ETF：...

> 风险提示：...

【原始数据】
{source_text}
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


def build_fallback_report(snapshots: list[dict[str, Any]], edition: str, reason: str) -> str:
    today = now_beijing().strftime("%Y-%m-%d")
    lines = [f"> **ETF 每日观察 · {today}（{edition_label(edition)}）**", "", f"> AI 分析暂不可用：{reason}", ""]
    for index, snapshot in enumerate(snapshots, 1):
        etf = snapshot["etf"]
        quote = snapshot["quote"]
        valuation = snapshot["valuation"]
        lines.extend([
            f"## {index}. {etf['name']}（{etf['code']}）",
            f"- 最新价：{fmt_number(quote['latest_price'])}",
            f"- 涨跌幅：{fmt_number(quote['pct_change'], 2, '%')}",
            f"- 跟踪指数 PE：{fmt_number(valuation['pe_ttm'], 2)}",
            f"- 估值位置：{valuation['valuation_level']}",
            "- 加仓判断：AI 分析暂不可用，建议先维持观察，避免仅凭单日波动追涨杀跌。",
            "- 趋势判断：暂不生成趋势预测，请结合更长周期走势和自身仓位判断。",
            "",
        ])
    lines.append(f"> {DISCLAIMER}")
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
    report_file = f"ETF日报_{today}（{label}）.md"

    print(f"\n{'=' * 50}")
    print(f"📈 ETF 每日观察 · {today}（{label}）")
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

    prompt = build_etf_prompt(snapshots, edition)
    try:
        report = call_llm_with_retry(prompt)
    except Exception as e:
        print(f"⚠️ AI 分析失败，改用基础行情报告: {e}")
        report = build_fallback_report(snapshots, edition, str(e))

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
    title = f"【ETF{label}】沪深300ETF / 纳指100ETF 每日观察 {today}"
    push_to_backend(edition, title, report, build_summary(report), run_id)

    print(f"\n✅ ETF 每日观察完成！({now_beijing().strftime('%H:%M:%S')})")


if __name__ == "__main__":
    main()
