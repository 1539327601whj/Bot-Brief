#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""代码查询 v2 数据源探针。

用途：在真正写 warehouse ETL 之前，先确认每个免费源「现在」还能不能取、取回什么字段。
东财的 reportName / 字段 / 域名会漂移，中证的权重接口也不是文档化的——
这些假设一旦写死进代码，坏掉时只会表现为页面少一块，不会报错。

纪律（与后端一致）：
- 一个源一个目标只发一次，**不重试、不换关键词**。
- 任何 429 / 限流信号：把该源标记为 BANNED，后续目标不再向它外呼。
- --noproxy 时 requests 完全忽略系统代理（本机 7890 代理会让国内行情域名失败）。
- 输出 JSON，退出码只在「所有源都不可用」时为 1；部分失败仍是 0，便于反复跑。

用法：
    py -3 automation/scripts/verify_warehouse_sources.py --noproxy
    py -3 automation/scripts/verify_warehouse_sources.py \
        --code 300274 --code 600519 --code 510300 --code 000300 --noproxy
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from typing import Any, Callable

import requests

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)
TIMEOUT = 12
MIN_INTERVAL_SECONDS = 0.3

# 每个 provider 最后一次外呼时刻，用于把突发抹匀。
_last_call: dict[str, float] = {}
# 被限流的源：后续目标直接跳过，零外呼。
_banned: dict[str, str] = {}


def _throttle(provider: str) -> None:
    now = time.monotonic()
    prior = _last_call.get(provider)
    if prior is not None:
        wait = MIN_INTERVAL_SECONDS - (now - prior)
        if wait > 0:
            time.sleep(wait)
    _last_call[provider] = time.monotonic()


def _is_rate_limited(status: int, text: str) -> bool:
    if status == 429:
        return True
    lowered = (text or "").lower()
    return any(
        marker in lowered
        for marker in ("too many requests", "访问频率", "请求过于频繁", "rate limit")
    )


def _request(
    provider: str,
    url: str,
    *,
    headers: dict[str, str] | None = None,
    params: dict[str, Any] | None = None,
    session: requests.Session,
) -> dict[str, Any]:
    """发一次请求并归一成探针结果。**绝不重试。**"""
    if provider in _banned:
        return {
            "status": "SKIPPED",
            "note": f"该源本次已被限流，跳过：{_banned[provider]}",
            "elapsedMs": 0,
        }
    _throttle(provider)
    started = time.monotonic()
    merged_headers = {
        "User-Agent": UA,
        "Accept": "application/json, text/plain, */*",
    }
    if headers:
        merged_headers.update(headers)
    try:
        response = session.get(
            url, headers=merged_headers, params=params, timeout=TIMEOUT
        )
    except requests.RequestException as exc:
        return {
            "status": "ERROR",
            "elapsedMs": int((time.monotonic() - started) * 1000),
            "note": f"{type(exc).__name__}: {exc}",
        }
    elapsed = int((time.monotonic() - started) * 1000)
    text = response.text or ""
    if _is_rate_limited(response.status_code, text):
        _banned[provider] = f"HTTP {response.status_code}"
        return {
            "status": "RATE_LIMITED",
            "httpStatus": response.status_code,
            "elapsedMs": elapsed,
            "rateLimited": True,
            "note": "已记录并停止对该源的外呼；不重试",
        }
    result: dict[str, Any] = {
        "status": "HTTP_OK" if 200 <= response.status_code < 300 else "HTTP_ERROR",
        "httpStatus": response.status_code,
        "elapsedMs": elapsed,
        "contentType": response.headers.get("Content-Type"),
        "bytes": len(response.content),
    }
    try:
        result["json"] = response.json()
    except ValueError:
        result["textHead"] = text[:300]
    return result


def _shape(value: Any, depth: int = 0) -> Any:
    """只保留结构，不把整包数据写进报告。"""
    if depth >= 4:
        return "..."
    if isinstance(value, dict):
        return {str(k): _shape(v, depth + 1) for k, v in list(value.items())[:20]}
    if isinstance(value, list):
        if not value:
            return []
        return [_shape(value[0], depth + 1)]
    return type(value).__name__


def _json_status(payload: dict[str, Any]) -> str:
    if payload["status"] not in ("HTTP_OK",):
        return payload["status"]
    body = payload.get("json")
    if body is None:
        return "NON_JSON"
    return "OK"


def secid(code: str) -> str:
    """东财 secid。指数代码有歧义，这里按常见形态钉死，探针不追求覆盖全部。"""
    code = code.strip().upper()
    if code.startswith(("SH", "SZ")):
        prefix, digits = code[:2], code[2:]
        return f"{'1' if prefix == 'SH' else '0'}.{digits}"
    if code in {"000001", "000016", "000300", "000688", "000852", "000905", "000906"}:
        return f"1.{code}"
    if code.startswith("399"):
        return f"0.{code}"
    if code.startswith(("93", "H3", "h3")) or code.startswith("H"):
        return f"2.{code}"
    if code.startswith(("6", "5", "9")):
        return f"1.{code}"
    return f"0.{code}"


def normalize_code(code: str) -> str:
    code = code.strip().upper()
    if code.startswith(("SH", "SZ")):
        return code[2:]
    return code


def probe_eastmoney_quote(code: str, session: requests.Session) -> dict[str, Any]:
    sid = secid(code)
    payload = _request(
        "eastmoney:interactive",
        "https://push2.eastmoney.com/api/qt/ulist.np/get",
        params={
            "fltt": "2",
            "invt": "2",
            "fields": "f12,f14,f2,f3,f6,f8,f9,f20,f21,f23,f115,f133",
            "secids": sid,
        },
        headers={"Referer": "https://quote.eastmoney.com/"},
        session=session,
    )
    payload["secid"] = sid
    body = payload.get("json") or {}
    data = body.get("data") or {}
    diff = data.get("diff")
    if isinstance(diff, dict):
        payload["rowCount"] = len(diff)
        payload["sampleKeys"] = sorted(next(iter(diff.values())).keys()) if diff else []
    elif isinstance(diff, list):
        payload["rowCount"] = len(diff)
        payload["sampleKeys"] = sorted(diff[0].keys()) if diff else []
    payload["status"] = _json_status(payload)
    return payload


def probe_eastmoney_kline(code: str, session: requests.Session) -> dict[str, Any]:
    sid = secid(code)
    payload = _request(
        "eastmoney:interactive",
        "https://push2his.eastmoney.com/api/qt/stock/kline/get",
        params={
            "secid": sid,
            "klt": "101",
            "fqt": "1",
            "lmt": "40",
            "end": "20500101",
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
        },
        headers={"Referer": "https://quote.eastmoney.com/"},
        session=session,
    )
    payload["secid"] = sid
    body = payload.get("json") or {}
    data = body.get("data") or {}
    klines = data.get("klines") or []
    payload["rowCount"] = len(klines)
    payload["firstBar"] = klines[0] if klines else None
    payload["lastBar"] = klines[-1] if klines else None
    payload["status"] = _json_status(payload)
    return payload


def probe_valuation_history(code: str, session: requests.Session) -> dict[str, Any]:
    payload = _request(
        "eastmoney:interactive",
        "https://datacenter-web.eastmoney.com/api/data/v1/get",
        params={
            "reportName": "RPT_VALUEANALYSIS_DET",
            "columns": "ALL",
            "filter": f'(SECURITY_CODE="{normalize_code(code)}")',
            "pageNumber": "1",
            "pageSize": "5",
            "sortColumns": "TRADE_DATE",
            "sortTypes": "-1",
            "source": "WEB",
            "client": "WEB",
        },
        headers={"Referer": "https://data.eastmoney.com/"},
        session=session,
    )
    body = payload.get("json") or {}
    result = body.get("result") or {}
    rows = result.get("data") or []
    payload["rowCount"] = len(rows)
    payload["total"] = result.get("count")
    payload["columns"] = sorted(rows[0].keys()) if rows else []
    payload["status"] = _json_status(payload)
    return payload


FINANCIAL_REPORTS = [
    "RPT_DMSK_FN_BALANCE",
    "RPT_DMSK_FN_INCOME",
    "RPT_DMSK_FN_CASHFLOW",
    "RPT_F10_FINANCE_MAINFINADATA",
    "RPT_F10_FINANCE_MAINFINADATA_NEW",
    "RPT_DMSK_FN_MAIN",
]


def probe_financial_reports(code: str, session: requests.Session) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    stock = normalize_code(code)
    for report_name in FINANCIAL_REPORTS:
        payload = _request(
            "eastmoney:interactive",
            "https://datacenter-web.eastmoney.com/api/data/v1/get",
            params={
                "reportName": report_name,
                "columns": "ALL",
                "filter": f'(SECURITY_CODE="{stock}")',
                "pageNumber": "1",
                "pageSize": "3",
                "source": "WEB",
                "client": "WEB",
            },
            headers={"Referer": "https://data.eastmoney.com/"},
            session=session,
        )
        body = payload.get("json") or {}
        result = body.get("result") or {}
        data = result.get("data") or []
        payload["reportName"] = report_name
        payload["rowCount"] = len(data)
        payload["total"] = result.get("count")
        payload["columns"] = sorted(data[0].keys()) if data else []
        payload["status"] = _json_status(payload)
        rows.append(payload)
        if payload["status"] == "RATE_LIMITED":
            break
    return rows


def probe_moneyflow(code: str, session: requests.Session) -> dict[str, Any]:
    sid = secid(code)
    payload = _request(
        "eastmoney:interactive",
        "https://push2.eastmoney.com/api/qt/stock/fflow/daykline/get",
        params={
            "lmt": "0",
            "klt": "101",
            "secid": sid,
            "fields1": "f1,f2,f3,f7",
            "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65",
        },
        headers={"Referer": "https://quote.eastmoney.com/"},
        session=session,
    )
    payload["secid"] = sid
    body = payload.get("json") or {}
    data = body.get("data") or {}
    klines = data.get("klines") or []
    payload["rowCount"] = len(klines)
    payload["firstBar"] = klines[0] if klines else None
    payload["lastBar"] = klines[-1] if klines else None
    payload["status"] = _json_status(payload)
    return payload


def probe_industry_boards(session: requests.Session) -> dict[str, Any]:
    payload = _request(
        "eastmoney:interactive",
        "https://push2.eastmoney.com/api/qt/clist/get",
        params={
            "pn": "1",
            "pz": "10",
            "po": "1",
            "np": "1",
            "fltt": "2",
            "invt": "2",
            "fid": "f3",
            "fs": "m:90+t:2",
            "fields": "f12,f14,f3,f62,f104,f105,f128,f136",
        },
        headers={"Referer": "https://quote.eastmoney.com/center/gridlist.html"},
        session=session,
    )
    body = payload.get("json") or {}
    data = body.get("data") or {}
    diff = data.get("diff") or []
    if isinstance(diff, dict):
        diff = list(diff.values())
    payload["rowCount"] = len(diff)
    payload["sample"] = diff[:2]
    payload["status"] = _json_status(payload)
    return payload


def probe_universe(session: requests.Session) -> dict[str, Any]:
    payload = _request(
        "eastmoney:interactive",
        "https://push2.eastmoney.com/api/qt/clist/get",
        params={
            "pn": "1",
            "pz": "20",
            "po": "1",
            "np": "1",
            "fltt": "2",
            "invt": "2",
            "fid": "f20",
            "fs": "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23",
            "fields": "f12,f14,f2,f3,f6,f8,f20,f21,f23,f115,f133",
        },
        headers={"Referer": "https://quote.eastmoney.com/center/gridlist.html"},
        session=session,
    )
    body = payload.get("json") or {}
    data = body.get("data") or {}
    diff = data.get("diff") or []
    if isinstance(diff, dict):
        diff = list(diff.values())
    payload["rowCount"] = len(diff)
    payload["total"] = data.get("total")
    payload["sample"] = diff[:2]
    payload["status"] = _json_status(payload)
    return payload


def probe_tencent(code: str, session: requests.Session) -> dict[str, Any]:
    stock = normalize_code(code)
    market = "sh" if secid(code).startswith("1.") else "sz"
    payload = _request(
        "tencent",
        f"https://qt.gtimg.cn/q={market}{stock}",
        headers={"Referer": "https://gu.qq.com/"},
        session=session,
    )
    text = payload.get("textHead") or ""
    if "json" in payload:
        text = json.dumps(payload["json"], ensure_ascii=False)[:300]
    payload["head"] = text[:200]
    payload["status"] = _json_status(payload) if "json" in payload else (
        "OK" if text.startswith("v_") else "NON_JSON"
    )
    return payload


def probe_tencent_kline(code: str, session: requests.Session) -> dict[str, Any]:
    stock = normalize_code(code)
    market = "sh" if secid(code).startswith("1.") else "sz"
    payload = _request(
        "tencent",
        "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get",
        params={
            "param": f"{market}{stock},day,,,800,qfq",
            "_var": "kline_dayqfq",
        },
        headers={"Referer": "https://gu.qq.com/"},
        session=session,
    )
    text = payload.get("textHead") or ""
    payload["head"] = text[:200]
    payload["status"] = "OK" if "kline_dayqfq" in text else "NON_JSON"
    return payload


def probe_sina(code: str, session: requests.Session) -> dict[str, Any]:
    stock = normalize_code(code)
    market = "sh" if secid(code).startswith("1.") else "sz"
    payload = _request(
        "sina",
        f"https://hq.sinajs.cn/list={market}{stock}",
        headers={"Referer": "https://finance.sina.com.cn/"},
        session=session,
    )
    text = payload.get("textHead") or ""
    payload["head"] = text[:200]
    payload["status"] = "OK" if "var hq_str_" in text else "NON_JSON"
    return payload


def _csindex_common_headers() -> dict[str, str]:
    return {"Referer": "https://www.csindex.com.cn/"}


def probe_csindex(code: str, session: requests.Session) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    cs_code = normalize_code(code)
    # PE 历史：已由 OffPoolIndexClient 验证过的接口
    pe = _request(
        "csindex",
        "https://www.csindex.com.cn/csindex-home/perf/indexCsiDsPe",
        params={"indexCode": cs_code},
        headers=_csindex_common_headers(),
        session=session,
    )
    body = pe.get("json") or {}
    data = body.get("data") or []
    if isinstance(data, dict):
        data = data.get("data") or []
    pe["probe"] = "pe-history"
    pe["rowCount"] = len(data) if isinstance(data, list) else 0
    pe["sample"] = data[:2] if isinstance(data, list) else None
    pe["status"] = _json_status(pe)
    rows.append(pe)

    # 下面三个是候选接口，探针确认它们的真实形状；失败不视为致命。
    for label, path in (
        ("basic-info", f"/csindex-home/indexInfo/index-basic-info/{cs_code}"),
        ("top10", f"/csindex-home/indexInfo/weight/top10new/{cs_code}"),
        ("industry-weight", f"/csindex-home/indexInfo/weight/industry-weight/{cs_code}"),
    ):
        item = _request(
            "csindex",
            "https://www.csindex.com.cn" + path,
            headers=_csindex_common_headers(),
            session=session,
        )
        body = item.get("json")
        item["probe"] = label
        item["status"] = _json_status(item)
        item["shape"] = _shape(body) if body is not None else None
        rows.append(item)
        if item.get("status") == "RATE_LIMITED":
            break
    return rows


def probe_danjuan(session: requests.Session) -> dict[str, Any]:
    payload = _request(
        "danjuan",
        "https://danjuanfunds.com/djapi/index_eva/dj",
        headers={"Referer": "https://danjuanfunds.com/"},
        session=session,
    )
    body = payload.get("json") or {}
    data = body.get("data") or {}
    items = data.get("items") if isinstance(data, dict) else None
    payload["rowCount"] = len(items) if isinstance(items, list) else 0
    payload["status"] = _json_status(payload)
    return payload


def probe_google_news(session: requests.Session) -> dict[str, Any]:
    payload = _request(
        "google-news",
        "https://news.google.com/rss/search",
        params={"q": "A股 政策", "hl": "zh-CN", "gl": "CN", "ceid": "CN:zh-Hans"},
        headers={"Referer": "https://news.google.com/"},
        session=session,
    )
    text = payload.get("textHead") or ""
    payload["hasItem"] = "<item>" in text
    payload["status"] = "OK" if payload["hasItem"] else payload["status"]
    return payload


def probe_eastmoney_news(code: str, session: requests.Session) -> dict[str, Any]:
    # 资讯检索用名称，探针里先用代码；形状对了就说明接口可达。
    payload = _request(
        "eastmoney-news",
        "https://search-api-web.eastmoney.com/search/jsonp",
        params={
            "cb": "jQuery",
            "param": json.dumps(
                {
                    "uid": "",
                    "keyword": normalize_code(code),
                    "type": ["cmsArticleWebOld"],
                    "client": "web",
                    "clientType": "web",
                    "clientVersion": "curr",
                    "param": {
                        "cmsArticleWebOld": {
                            "searchScope": "default",
                            "sort": "default",
                            "pageIndex": 1,
                            "pageSize": 5,
                            "preTag": "<em>",
                            "postTag": "</em>",
                        }
                    },
                },
                ensure_ascii=False,
            ),
        },
        headers={"Referer": "https://so.eastmoney.com/"},
        session=session,
    )
    text = payload.get("textHead") or ""
    payload["head"] = text[:160]
    payload["status"] = "OK" if "jQuery" in text else "NON_JSON"
    return payload


def probe_notices(code: str, session: requests.Session) -> dict[str, Any]:
    payload = _request(
        "eastmoney-notice",
        "https://np-anotice-stock.eastmoney.com/api/security/ann",
        params={
            "sr": "-1",
            "page_size": "5",
            "page_index": "1",
            "ann_type": "A",
            "client_source": "web",
            "stock_list": normalize_code(code),
        },
        headers={"Referer": "https://data.eastmoney.com/notices/"},
        session=session,
    )
    body = payload.get("json") or {}
    data = body.get("data") or {}
    items = data.get("list") or []
    payload["rowCount"] = len(items)
    payload["status"] = _json_status(payload)
    return payload


def main() -> int:
    parser = argparse.ArgumentParser(description="代码查询 v2 免费数据源探针")
    parser.add_argument(
        "--code",
        action="append",
        default=None,
        help="待测代码，可重复；默认 300274/600519/510300/000300",
    )
    parser.add_argument(
        "--noproxy",
        action="store_true",
        help="忽略系统代理（本机 7890 代理会让国内行情域名失败）",
    )
    parser.add_argument("--output", default=None, help="JSON 输出路径；默认 stdout")
    args = parser.parse_args()

    codes = args.code or ["300274", "600519", "510300", "000300"]
    session = requests.Session()
    if args.noproxy:
        session.trust_env = False

    report: dict[str, Any] = {
        "generatedAt": datetime.now(timezone.utc).astimezone().isoformat(),
        "noproxy": args.noproxy,
        "codes": codes,
        "results": [],
        "banned": {},
    }

    def add(source: str, target: str, payload: dict[str, Any]) -> None:
        report["results"].append(
            {"source": source, "target": target, **payload}
        )

    # 与代码无关的源先跑一次。
    add("eastmoney", "*", {**probe_industry_boards(session), "probe": "industry-boards"})
    add("eastmoney", "*", {**probe_universe(session), "probe": "universe"})
    add("danjuan", "*", probe_danjuan(session))
    add("google-news", "*", probe_google_news(session))

    for code in codes:
        add("eastmoney", code, {**probe_eastmoney_quote(code, session), "probe": "quote"})
        add("eastmoney", code, {**probe_eastmoney_kline(code, session), "probe": "kline"})
        add("eastmoney", code, {**probe_valuation_history(code, session), "probe": "valuation-history"})
        for item in probe_financial_reports(code, session):
            add("eastmoney", code, {**item, "probe": "financial"})
        add("eastmoney", code, {**probe_moneyflow(code, session), "probe": "moneyflow"})
        add("tencent", code, probe_tencent(code, session))
        add("tencent", code, probe_tencent_kline(code, session))
        add("sina", code, probe_sina(code, session))
        for item in probe_csindex(code, session):
            add("csindex", code, item)
        add("eastmoney-news", code, probe_eastmoney_news(code, session))
        add("eastmoney-notice", code, probe_notices(code, session))

    report["banned"] = dict(_banned)

    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            handle.write(rendered)
        print(f"written {args.output}", file=sys.stderr)
    else:
        print(rendered)

    usable = [
        item for item in report["results"]
        if item.get("status") in ("OK", "HTTP_OK")
    ]
    return 0 if usable else 1


if __name__ == "__main__":
    sys.exit(main())
