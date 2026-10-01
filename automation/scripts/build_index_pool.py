#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按「指数 → 代表 ETF」提案，**只产候选，绝不覆写正式池子**。

正式池子是 `backend/src/main/resources/screener/index-pool.json`，只有人能改它。
这个脚本的产物是 `automation/agents/index-pool-candidates.json`，人看着往里搬。

为什么必须分开：

* **没有公开接口能给出 ETF → 跟踪指数 的映射。** 蛋卷指数详情不给基金名单（`result_code
  600001`）、天天基金概况页返回 12 字节。所以对应关系只能是人工维护的表，这里的「匹配」
  是给人工省事的初筛，不是权威绑定。
* **同名 ETF 不等于精确跟踪。** 510500 是中证500、159915 是创业板指、510880 是上证红利，
  名字经常对不上；名字对得上的也可能跟踪的是另一个近似指数。所以每条候选都带 `why`，
  人确认过才算数。

数据来源（都是免鉴权、一次请求拿全量）：

* 蛋卷目录：63 个指数，用来核 `danjuanCode` 是否还存在、以及提示漏填的蛋卷代码。
* 新浪 ETF 全量：15 页 × 100，用来找代表 ETF 与规模。
  规模**只认 `nmc`**（与腾讯的「总市值」一致）；`mktcap` 不是 ETF 规模
  （159915：`mktcap` 339 亿 vs 实际 654 亿）——它只出现在 verify_index_pool.py 的反证里。

跑法：

    py -3 automation/scripts/build_index_pool.py          # 产候选
    py -3 automation/scripts/build_index_pool.py --print  # 只看表，不写文件
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from datetime import datetime
from pathlib import Path

_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

import etf_report as report  # noqa: E402

from logging_setup import setup_logging  # noqa: E402

logger = setup_logging(__name__)

_ROOT = Path(__file__).resolve().parents[2]
SEED_PATH = _ROOT / "automation" / "agents" / "index_pool_seed.json"
OUTPUT_PATH = _ROOT / "automation" / "agents" / "index-pool-candidates.json"
POOL_PATH = _ROOT / "backend" / "src" / "main" / "resources" / "screener" / "index-pool.json"

SINA_ETF_URL = (
    "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
    "Market_Center.getHQNodeData"
)
SINA_ETF_PAGES = 40
SINA_ETF_PAGE_SIZE = 100
# 新浪的 nmc 单位是万元（22138.23584 → 2.21 亿）。换算成「亿」只为打印好看。
NMC_WAN_PER_YI = 10000
# 名称匹配出多少个才算「还看得出是哪一个」
TOO_MANY_MATCHES = 5
CATEGORIES = ("broad", "strategy", "sector", "theme", "overseas", "other")


def load_seed(path: Path = SEED_PATH) -> list[dict]:
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    seed = data.get("seed")
    if not isinstance(seed, list) or not seed:
        raise RuntimeError(f"{path} 里没有 seed 数组")
    for entry in seed:
        name = entry.get("indexName")
        if not name:
            raise RuntimeError(f"{path} 有缺 indexName 的条目: {entry}")
        if entry.get("category") not in CATEGORIES:
            raise RuntimeError(f"{name} 的 category 非法: {entry.get('category')!r}")
        if not entry.get("csindexCode") and not entry.get("danjuanCode"):
            raise RuntimeError(f"{name} 既没有 csindexCode 也没有 danjuanCode，它不会有任何估值")
    return seed


def fetch_sina_etfs(pages: int = SINA_ETF_PAGES) -> list[dict]:
    """新浪的场内 ETF 全量清单，按代码去重。

    `pages` 只是**上限**，真正的结束条件是空页。实测 1693 只、第 18 页才空：
    写死 15 页会把深市那一段（159xxx 之后）整段截掉，于是 159915 这种老牌 ETF
    在清单里「找不到」——而它明明在。这类截断不会报错，只会让提案少一半。
    """
    by_code: dict[str, dict] = {}
    for page in range(1, pages + 1):
        resp = report.http_get(
            SINA_ETF_URL,
            params={
                "page": page,
                "num": SINA_ETF_PAGE_SIZE,
                "sort": "symbol",
                "asc": 1,
                "node": "etf_hq_fund",
                "symbol": "",
            },
            headers=report.A_SHARE_SINA_HEADERS,
            timeout=(5, 20),
        )
        resp.raise_for_status()
        rows = resp.json()
        if not isinstance(rows, list):
            raise RuntimeError(f"新浪 ETF 清单第 {page} 页不是数组")
        if not rows:
            break
        for row in rows:
            code = str(row.get("code") or "")
            if code and code not in by_code:
                by_code[code] = row
    if not by_code:
        raise RuntimeError("新浪 ETF 清单为空")
    logger.info("📡 新浪 ETF 清单 %s 只", len(by_code))
    return sorted(by_code.values(), key=lambda row: str(row.get("code")))


def fund_scale_yi(row: dict) -> float | None:
    value = report.to_optional_float(row.get("nmc"))
    return round(value / NMC_WAN_PER_YI, 2) if value is not None else None


def match_etfs(entry: dict, etfs: list[dict]) -> list[tuple[int, dict]]:
    """按名称 token 找代表 ETF，返回 `(命中的 token 长度, ETF 行)`，长的在前。

    两条规则都在防**看着像、其实是另一个指数**：

    * **前缀匹配，不是包含匹配。** 境内 ETF 的名字是「主题 + ETF + 公司」
      （`红利低波ETF华泰柏瑞`、`军工ETF国泰`），主题一定在开头。用「包含」会让
      `300红利` 把 `300红利低波ETF嘉实` 也认下来，而那是 930740 那个指数。
    * **命中的 token 越长越可信。** `中证500` 会把 `中证500低波动ETF华安` 也匹配上，
      但 `500低波` 那一组（`中证500低波动`）命中的前缀更长，胜出。

    长度只在归属同一个 ETF 时用来决胜，见 `propose_representative_etfs`。
    """
    tokens = [token for token in [entry["indexName"], *entry.get("aliases", [])] if token]
    matched = []
    for row in etfs:
        name = str(row.get("name") or "")
        best = max((len(token) for token in tokens if name.startswith(token)), default=0)
        if best:
            matched.append((best, row))
    matched.sort(key=lambda pair: (pair[0], fund_scale_yi(pair[1]) or 0), reverse=True)
    return matched


def propose_representative_etfs(seed: list[dict], etfs: list[dict]) -> dict[int, dict]:
    """seed 下标 → 提案（ETF、置信度、依据、警告）。**一只 ETF 最多归一个指数。**

    没有这条互斥，「一个主题名字里含另一个主题」时会同时给两个指数配上同一只 ETF，
    而卡片上两个指数于是显示同一份价格位置——看不出是错的。
    """
    proposals: dict[int, dict] = {}
    by_index: dict[int, list[tuple[int, dict]]] = {}
    claims: dict[str, tuple[int, int]] = {}

    for index, entry in enumerate(seed):
        confirmed = (entry.get("etfCode") or "").strip()
        if not confirmed:
            by_index[index] = match_etfs(entry, etfs)
            continue
        row = next((item for item in etfs if str(item.get("code")) == confirmed), None)
        proposals[index] = {
            "etf": row,
            "confidence": "confirmed",
            "why": [f"seed 里人工确认的代表 ETF：{confirmed}"],
            "warnings": [] if row else [
                f"seed 写死的 ETF {confirmed} 在新浪 ETF 清单（{len(etfs)} 只）里找不到，可能已退市或改了代码"
            ],
        }
        if row:
            # 人工确认的不参与争夺，也不会被自动匹配抢走
            claims[str(row["code"])] = (10 ** 6, index)

    # 全局按「命中的 token 长度」从长到短分配，长的先挑
    ordered = sorted(
        ((length, index, row) for index, pairs in by_index.items() for length, row in pairs),
        key=lambda item: item[0],
        reverse=True,
    )
    for length, index, row in ordered:
        code = str(row["code"])
        if code in claims:
            continue
        claims[code] = (length, index)

    for index, pairs in by_index.items():
        mine = [(length, row) for length, row in pairs if claims.get(str(row["code"])) == (length, index)]
        # 归属由 token 长度定，**挑哪一个由规模定**：同一个指数常有「深证100 / 深100」两种叫法，
        # 长度只说明谁更具体，不代表它后面那只 ETF 更值得当代表（小到 0.57 亿的也在名单里）
        mine.sort(key=lambda pair: (fund_scale_yi(pair[1]) or 0, pair[0]), reverse=True)
        warnings = []
        etf = mine[0][1] if mine else None
        if not etf:
            if pairs:
                other = pairs[0][1]
                stolen = claims.get(str(other["code"]))
                owner = seed[stolen[1]]["indexName"] if stolen else "另一个指数"
                warnings.append(
                    f"唯一按名称匹配上的 {other['code']} {other['name']} 归给了「{owner}」"
                    "（它的命名前缀更长）；这个指数需要人工补 etfCode"
                )
            else:
                warnings.append("没有按名称找到代表 ETF；没有 ETF 的指数只出估值分位，价格与规模全空")
            proposals[index] = {"etf": None, "confidence": "none", "why": [], "warnings": warnings}
            continue
        length = mine[0][0]
        confidence = ("high" if len(mine) == 1
                      else "medium" if len(mine) <= TOO_MANY_MATCHES
                      else "low")
        why = [f"名称以「{etf['name'][:length]}」开头（命中 {length} 字），共 {len(mine)} 只，取规模最大的"]
        if len(mine) > 1:
            why.append("其它候选：" + "、".join(
                f"{row.get('code')} {row.get('name')}" for _, row in mine[1:4]
            ))
        if confidence == "low":
            warnings.append(f"匹配到 {len(mine)} 只，名称太泛，必须人工确认")
        proposals[index] = {"etf": etf, "confidence": confidence, "why": why, "warnings": warnings}
    return proposals


# 号段 → indexCode 前缀。**表驱动，不许按「看着像哪个市场」推。**
SEGMENT_PREFIX = (
    (("399", "980"), "SZ"),   # 深证 / 国证自编
    (("000", "950"), "SH"),   # 上证 / 中证交易所公布
)
# 其余（930/931/716/H30/H11…）是中证自编指数，不是沪深交易所公布的代码。
DEFAULT_PREFIX = "CSI"


def index_code_prefix(code: str) -> str:
    for segments, prefix in SEGMENT_PREFIX:
        if code.startswith(segments):
            return prefix
    return DEFAULT_PREFIX


def derived_index_code(entry: dict) -> str:
    """池子里的 `indexCode` —— 项目库的键，对应 `market_valuation_history.index_code`。

    **不能直接照抄蛋卷的代码。** 蛋卷对同一个号段有两种写法：`SH930901`（动漫游戏）
    和 `CSI930740`（红利低波）都是 93 开头的中证自编指数。照抄的话，同一个指数
    先按蛋卷写一条、再按中证写一条，库里会同时躺着两个 key 的两份估值，
    而两张卡在页面上看起来都正常——这是搬条目时最容易静默出错的地方。

    规则：前缀由号段定（见 `index_code_prefix`），后缀是 6 位中证/深证代码。
    现有 7 条按这条规则算出来一字不差（000300→SH、399006→SZ），老数据不用迁移。
    海外指数（港股/美股）没有沪深代码，用它自己的稳定标识。
    """
    danjuan_code = str(entry.get("danjuanCode") or "")
    if entry.get("category") == "overseas" and danjuan_code:
        return danjuan_code
    code = str(entry.get("csindexCode") or "") or re.sub(r"\D", "", danjuan_code)
    return index_code_prefix(code) + code


def build_candidate(entry: dict, proposal: dict, danjuan: dict, in_pool: dict[str, str]) -> dict:
    warnings: list[str] = list(proposal["warnings"])
    why: list[str] = list(proposal["why"])

    csindex_code = entry.get("csindexCode") or None
    danjuan_code = entry.get("danjuanCode") or None
    if csindex_code:
        valuation_source, method = report.VALUATION_SOURCE_CSINDEX, report.CSI_PE_TTM_ROLLING_10Y
        if not entry.get("csindexVerifiedOn"):
            warnings.append("csindexCode 还没验过（csindexVerifiedOn 为空），搬进池子前先跑 verify_index_pool.py")
    elif danjuan_code:
        valuation_source, method = report.VALUATION_SOURCE_DANJUAN, report.DANJUAN_PE_TTM_PROVIDER
    else:
        valuation_source, method = None, None
        warnings.append("没有估值来源：进池子会长出 PE / PE 分位空白")

    index_code = derived_index_code(entry)
    if index_code in in_pool and in_pool[index_code] != entry["indexName"]:
        warnings.append(
            f"池子里 {index_code} 叫「{in_pool[index_code]}」，这条叫「{entry['indexName']}」——"
            "同一个 code 两个名字会写出两份估值，先确认是不是同一个指数"
        )

    if danjuan_code and danjuan_code not in danjuan["byCode"]:
        warnings.append(f"蛋卷目录里没有 {danjuan_code} 这个代码，可能已经下架或改了名")
    elif not danjuan_code and entry["indexName"] in danjuan["byName"]:
        # 只是提示，不自动填：名字相同不代表是同一个指数
        warnings.append(f"蛋卷目录里有同名指数 {danjuan['byName'][entry['indexName']]}，可以考虑补 danjuanCode")

    etf = proposal["etf"]

    candidate = {
        "indexCode": index_code,
        "indexName": entry["indexName"],
        "category": entry["category"],
        "valuationSource": valuation_source,
        "percentileMethod": method,
        "csindexCode": csindex_code,
        "danjuanCode": danjuan_code,
        "etfCode": str(etf.get("code")) if etf else None,
        "market": (1 if str(etf.get("symbol", "")).startswith("sh") else 0) if etf else None,
        "etfName": str(etf.get("name")) if etf else None,
        "tracking": entry["indexName"],
        "confidence": proposal["confidence"],
        "etfScaleYi": fund_scale_yi(etf) if etf else None,
        "alreadyInPool": index_code in in_pool,
        "why": why,
        "warnings": warnings,
    }
    return candidate


def read_pool_index_codes(path: Path = POOL_PATH) -> dict[str, str]:
    """读正式池子（**只读**）→ `indexCode -> indexName`。池子只有一份持有者，这里不做第二份。"""
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        return {
            str(item.get("indexCode")): str(item.get("indexName") or "")
            for item in data.get("indices") or []
        }
    except (OSError, ValueError) as e:
        logger.warning("⚠️ 读不到正式池子（%s），alreadyInPool 一律按 false 处理: %s", path, e)
        return {}


def summarize(candidates: list[dict]) -> dict:
    return {
        "candidates": len(candidates),
        "withEtf": sum(1 for item in candidates if item["etfCode"]),
        "confirmed": sum(1 for item in candidates if item["confidence"] == "confirmed"),
        "alreadyInPool": sum(1 for item in candidates if item["alreadyInPool"]),
        "noValuationSource": sum(1 for item in candidates if not item["valuationSource"]),
        "withWarnings": sum(1 for item in candidates if item["warnings"]),
    }


def print_table(candidates: list[dict]) -> None:
    for item in candidates:
        mark = "✔" if item["confidence"] == "confirmed" else " "
        etf = f"{item['etfCode']} {item['etfName']} {item['etfScaleYi']}亿" if item["etfCode"] else "（无 ETF）"
        logger.info(
            "%s %-9s %-10s %-7s %-9s %s",
            mark, item["indexCode"], item["indexName"], item["category"],
            item["valuationSource"] or "无来源", etf,
        )
        for warning in item["warnings"]:
            logger.warning("      ⚠️ %s", warning)


def main() -> None:
    parser = argparse.ArgumentParser(description="按「指数 → 代表 ETF」提案，不写正式池子")
    parser.add_argument("--print", dest="print_only", action="store_true", help="只打印，不写候选文件")
    args = parser.parse_args()

    if os.path.abspath(OUTPUT_PATH) == os.path.abspath(POOL_PATH):  # pragma: no cover - 防手滑
        raise RuntimeError("候选文件不能就是正式池子")

    seed = load_seed()
    etfs = fetch_sina_etfs()
    items = report.fetch_danjuan_items()
    danjuan = {
        "byCode": items,
        "byName": {str(item.get("name")): code for code, item in items.items()},
    }
    logger.info("📡 蛋卷目录 %s 个指数，seed %s 条", len(items), len(seed))

    in_pool = read_pool_index_codes()
    proposals = propose_representative_etfs(seed, etfs)
    candidates = sorted(
        (build_candidate(entry, proposals[index], danjuan, in_pool)
         for index, entry in enumerate(seed)),
        key=lambda item: (item["category"], item["indexCode"]),
    )

    print_table(candidates)
    summary = summarize(candidates)
    logger.info("📊 %s", summary)

    if args.print_only:
        return
    payload = {
        "generatedAt": report.now_beijing().isoformat(timespec="seconds"),
        "sources": {"danjuanIndices": len(items), "sinaEtfs": len(etfs), "seedEntries": len(seed)},
        "summary": summary,
        "howToUse": [
            "人看着把条目搬进 backend/src/main/resources/screener/index-pool.json，",
            "csindexVerifiedOn 为空的先跑一遍 verify_index_pool.py；",
            "有 warnings 的条目搬进去之前先解决，否则会长出空白。",
        ],
        "candidates": candidates,
    }
    with open(OUTPUT_PATH, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    logger.info("💾 候选已写入 %s（正式池子未被改动）", OUTPUT_PATH)


if __name__ == "__main__":
    main()