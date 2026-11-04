#!/usr/bin/env python3
"""校验新浪 ETF 目录接口仍然可用，并且仍然覆盖沪深两市。

为什么需要这个脚本：ETF 目录是「搜索池外 ETF」的本地兜底。它不在指数池里，
也没有别的免费源能一次给出全量清单（东财 ``clist`` 会按 IP 封禁，项目禁用）。
新浪这个节点是唯一在用的来源，接口一旦改字段、改分页上限或只回单市场，
页面表现会是「某些 ETF 搜不到」，而不是报错——所以要用脚本把契约钉住。

钉住四件事：

1. 返回体是 JSON 数组（不是 ``{"datas": ...}`` 外壳）；
2. 每页最多 100 条，短页即最后一页；
3. 同时包含 ``sh`` 与 ``sz`` 代码；
4. 代码是 6 位数字、名称非空，且去重后数量与计数接口大致相符
   （允许少量差异：计数接口包含当日停牌/未上市条目，两者口径不完全一致）。

只读：不写库。退出码 0 = 通过；1 = 契约变了，先把目录同步停掉再查。

跑法（Windows 上 python 是商店占位符，用完整路径或 py -3）::

    py -3 automation/scripts/verify_etf_catalog.py
"""

from __future__ import annotations

import json
import sys
import urllib.request

COUNT_URL = (
    "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
    "Market_Center.getHQNodeStockCount?node=etf_hq_fund"
)
LIST_URL = (
    "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
    "Market_Center.getHQNodeData"
)
HEADERS = {
    "Referer": "https://finance.sina.com.cn/",
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    ),
}
PAGE_SIZE = 100
MAX_PAGES = 40
# 计数接口与目录接口口径不完全一致，只要求目录不少于计数的 90%。
MIN_COVERAGE = 0.9


def fetch_text(url: str) -> str:
    # 国内行情域名不走系统代理：本机 7890 会让请求直接失败。
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request(url, headers=HEADERS)
    with opener.open(request, timeout=20) as response:
        return response.read().decode("utf-8")


def fetch_count() -> int | None:
    try:
        payload = json.loads(fetch_text(COUNT_URL))
    except (ValueError, OSError) as exc:
        print(f"[WARN] 计数接口不可用：{type(exc).__name__}: {exc}")
        return None
    try:
        return int(payload)
    except (TypeError, ValueError):
        print(f"[WARN] 计数接口返回了非数字：{payload!r}")
        return None


def fetch_catalog() -> list[dict]:
    rows: list[dict] = []
    seen: set[str] = set()
    for page in range(1, MAX_PAGES + 1):
        url = (f"{LIST_URL}?page={page}&num={PAGE_SIZE}"
               f"&sort=symbol&asc=1&node=etf_hq_fund")
        payload = json.loads(fetch_text(url))
        if not isinstance(payload, list):
            raise AssertionError(
                f"第 {page} 页返回体不是数组（{type(payload).__name__}），契约已变")
        if len(payload) > PAGE_SIZE:
            raise AssertionError(
                f"第 {page} 页返回 {len(payload)} 条，超过宣称的每页 {PAGE_SIZE} 条")
        for item in payload:
            symbol = str(item.get("symbol") or "")
            code = str(item.get("code") or "")
            name = str(item.get("name") or "")
            if symbol[:2].lower() not in ("sh", "sz"):
                continue
            if len(code) != 6 or not code.isdigit() or not name:
                raise AssertionError(f"目录行不合约：{item!r}")
            if code not in seen:
                seen.add(code)
                rows.append(item)
        if len(payload) < PAGE_SIZE:
            return rows
    raise AssertionError(f"翻到 {MAX_PAGES} 页仍是满页，分页可能已经失效")


def main() -> int:
    count = fetch_count()
    try:
        rows = fetch_catalog()
    except (AssertionError, ValueError, OSError) as exc:
        print(f"[FAIL] ETF 目录校验失败：{exc}")
        return 1

    markets = {str(row.get("symbol") or "")[:2].lower() for row in rows}
    if not {"sh", "sz"} <= markets:
        print(f"[FAIL] 目录只覆盖了 {sorted(markets)}，沪深至少缺一个")
        return 1
    if count is not None and len(rows) < count * MIN_COVERAGE:
        print(f"[FAIL] 目录 {len(rows)} 条，低于计数接口 {count} 条的 "
              f"{MIN_COVERAGE:.0%}；可能少翻了页")
        return 1

    print(f"[OK] ETF 目录 {len(rows)} 条（计数接口 {count if count is not None else '不可用'}）")
    print(f"     市场覆盖：{', '.join(sorted(markets))}")
    for row in rows[:5]:
        print(f"     {row.get('symbol')}  {row.get('code')}  {row.get('name')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
