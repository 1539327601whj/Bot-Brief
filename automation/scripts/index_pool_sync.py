# -*- coding: utf-8 -*-
"""指数池每日同步：估值（中证官网 / 蛋卷）与代表 ETF 的前复权日线。

这个脚本回答的是「点击 /stock-pick 时那一堆空格从哪来」：

1. **估值分位**（PE / PE 分位）不是点击时去外网现取，而是**每天 16:30 后写进库**，
   点击时只读库。每小时 200 个指数 × 一次外呼，等于把按 IP 限流的源自己封掉。
2. **价格位置**（一年价格分位 / 距最高 / 最大回撤 / 年化波动）需要日线。池子里的
   ETF 由这里预取前复权日线入 `etf_price_history`，点击时批量读库、不再逐只外呼。

两条纪律，都在章程 `automation/agents/stock_screening_rules.md` §5 里：

* **每个指数只用一个估值来源**，来源由池子里的 `valuationSource` 钉死。中证官网与蛋卷
  是两套口径（中证500 差 22%、科创50 差 75%），同一个指数在两个源之间来回换，页面上
  只会看到一个变了的分位，没有任何报错。
* **任何一段取数失败就整段放弃，绝不写半截。**「一部分指数是今天的、一部分还是昨天的」
  这种池子从页面上看不出问题，比空白更危险。失败时库里保留昨日值，卡片上的
  `valuationTradeDate` 自然显示昨天。

**不要在这里 import `fetch_valuation_archive`**：归档是给叙事日报补历史基线用的，
池子只要最新一行。取数成本上，中证官网一次约 320KB、0.5–1.2 秒，跑偏了真会被封。
"""

import os
import random
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any, Optional

import requests

_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

import etf_report as report  # noqa: E402

from logging_setup import setup_logging  # noqa: E402

logger = setup_logging(__name__)

# 中证官网按 IP 限流，并发 3 + 每条 100–300ms 抖动是控频手段，不是性能调优。
# 「反正有兜底」不是调大它的理由：真被封了就整个池子一起空。
CSINDEX_CONCURRENCY = 3
CSINDEX_JITTER_SECONDS = (0.1, 0.3)
# 一轮最多取多少个中证指数。真到 200+ 条时按 INDEX_POOL_CSINDEX_MAX 往下压。
INDEX_POOL_CSINDEX_MAX = 250
# 池子里每只 ETF 推多少根日线。点击时按最近 250 根算价格位置，留一点余量。
POOL_PRICE_BARS = 260
# 与后端 MarketValuationHistoryServiceImpl.MAX_BATCH / 控制器 @Size 一致。
VALUATION_INGEST_BATCH_SIZE = 250
# 「本机今天已跑过」的硬哨兵。防的是调试时反复跑把中证官网惹毛，
# 真正的幂等门在 poll_loop 里（看库里 SH000300 的最新 tradeDate）。
SYNC_MARKER_NAME = ".index_pool_sync_marker"
MARKER_PATH = os.path.join(os.path.dirname(_SCRIPTS_DIR), SYNC_MARKER_NAME)
# 幂等门探针：池子里第一个有估值来源的指数（现为沪深300）。
PROBE_INDEX_CODE = "SH000300"


def csindex_budget() -> int:
    raw = (os.environ.get("INDEX_POOL_CSINDEX_MAX") or "").strip()
    if not raw:
        return INDEX_POOL_CSINDEX_MAX
    try:
        value = int(raw)
    except ValueError:
        logger.warning("⚠️ INDEX_POOL_CSINDEX_MAX=%r 不是整数，按 %s 处理", raw, INDEX_POOL_CSINDEX_MAX)
        return INDEX_POOL_CSINDEX_MAX
    return max(0, value)


def fetch_index_pool() -> list[dict[str, Any]]:
    """池子只有一份持有者：后端 classpath 里的 `screener/index-pool.json`。

    Python 侧**不存第二份**——两边各存一份必然漂移，而漂移的后果是某个指数悄悄没有数据。
    """
    backend_url = os.environ.get("BACKEND_API_URL", "").rstrip("/")
    if not backend_url:
        raise RuntimeError("未配置 BACKEND_API_URL，无法读取指数池")
    resp = report.http_get(
        f"{backend_url}/api/index-pool",
        headers=report.backend_headers(),
        timeout=(4, 15),
    )
    body = report.backend_result(resp, "指数池读取")
    if body is None:
        raise RuntimeError("指数池读取失败")
    indices = body.get("data")
    if not isinstance(indices, list) or not indices:
        raise RuntimeError("指数池响应 data 必须是非空数组")
    return indices


def latest_valuation_trade_date(index_code: str) -> Optional[str]:
    """库里该指数最新一条估值的 tradeDate，用作「今天是否已同步过」的幂等门。"""
    backend_url = os.environ.get("BACKEND_API_URL", "").rstrip("/")
    token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url or not token:
        return None
    try:
        resp = report.http_get(
            f"{backend_url}/api/market-valuations/{index_code}/latest",
            params={"limit": 1, "percentileMethod": report.CSI_PE_TTM_ROLLING_10Y},
            headers=report.backend_headers(),
            timeout=(4, 10),
        )
        body = report.backend_result(resp, "指数池同步状态查询")
        rows = (body or {}).get("data") or []
        if not isinstance(rows, list) or not rows:
            return None
        return str(rows[0].get("tradeDate") or "")[:10] or None
    except Exception as e:
        logger.warning("  ⚠️ %s 同步状态查询失败: %s", index_code, e)
        return None


def already_synced_today(today: Optional[Any] = None) -> bool:
    current = (today or report.now_beijing().date()).isoformat()
    try:
        with open(MARKER_PATH, encoding="utf-8") as handle:
            return handle.read().strip() == current
    except OSError:
        return False


def mark_synced(today: Optional[Any] = None) -> None:
    current = (today or report.now_beijing().date()).isoformat()
    try:
        with open(MARKER_PATH, "w", encoding="utf-8") as handle:
            handle.write(current)
    except OSError as e:
        # 只读文件系统不该让整轮同步失败：哨兵是防手滑的，不是数据正确性的一部分
        logger.warning("⚠️ 无法写入同步哨兵 %s: %s", MARKER_PATH, e)


def csindex_latest_payload(fund: dict[str, Any], history: list[dict[str, Any]]) -> Optional[dict[str, Any]]:
    """只取中证 PE 历史的最后一个点——池子只要最新一行，不要历史序列。

    分位由项目按滚动窗口自算（`fetch_csindex_pe_history` 的说明里写了窗口是
    min(10 年, 该指数实际可用长度)，科创50 只有约 6 年）。
    """
    if not history:
        return None
    latest = history[-1]
    trade_date = str(latest.get("tradeDate") or "")
    if not report.is_fresh_date(
        trade_date, report.now_beijing().date(), report.CURRENT_VALUATION_MAX_STALENESS_DAYS
    ):
        # 超出项目统一的 15 天新鲜度阈值就跳过：写进去的旧值看上去和数据正常时一模一样
        logger.warning("  ⚠️ %s 的中证 PE 最新日期 %s 过旧，跳过", fund.get("indexName"), trade_date or "空")
        return None
    percentile = latest.get("pePercentile")
    return {
        "indexCode": fund["indexCode"],
        "indexName": fund["indexName"],
        "peTtm": latest.get("peTtm"),
        "pePercentile": percentile,
        "percentileMethod": report.CSI_PE_TTM_ROLLING_10Y,
        "valuationLevel": report.valuation_level(report.to_optional_float(percentile)),
        "tradeDate": trade_date,
        "source": latest.get("source") or report.CSI_PE_SOURCE_LABEL,
    }


def csindex_valuations(
    indices: list[dict[str, Any]],
    fetcher: Optional[Any] = None,
) -> Optional[list[dict[str, Any]]]:
    """中证段的估值行；**任一条失败就返回 None 表示整段放弃**。

    为什么不像其它地方那样「跳过这一条继续」：中证官网是同一个 IP 限流，一条 403/429
    之后继续跑只会让封禁更深；而且写出来的会是「一部分今天的、一部分昨天的」的池子。
    返回 None 而不是抛异常，是为了让调用方能明确区分「这段没成」与「这段成了一条没有」。
    """
    fetch = fetcher or report.fetch_csindex_pe_history
    wanted = [f for f in indices if f.get("valuationSource") == report.VALUATION_SOURCE_CSINDEX]
    if not wanted:
        return []

    budget = csindex_budget()
    if len(wanted) > budget:
        logger.warning(
            "  ⚠️ 中证段本轮只取前 %s 个（共 %s 个），其余等下一天：INDEX_POOL_CSINDEX_MAX=%s",
            budget, len(wanted), budget,
        )
        wanted = wanted[:budget]

    def one(fund: dict[str, Any]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        time.sleep(random.uniform(*CSINDEX_JITTER_SECONDS))
        code = fund.get("csindexCode")
        if not code:
            raise RuntimeError(
                f"{fund.get('indexName') or fund.get('indexCode')} 的 valuationSource=csindex 但缺 csindexCode"
            )
        return fund, fetch(code)

    payloads: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=CSINDEX_CONCURRENCY) as executor:
        futures = {executor.submit(one, fund): fund for fund in wanted}
        for future in as_completed(futures):
            fund = futures[future]
            try:
                _, history = future.result()
            except Exception as e:
                logger.error(
                    "  ❌ 中证 PE 取数失败（%s %s）: %s；本轮中证段整体放弃，库里保留昨日值",
                    fund.get("indexName"), fund.get("csindexCode"), e,
                )
                for pending in futures:
                    pending.cancel()
                return None
            payload = csindex_latest_payload(fund, history)
            if payload:
                payloads.append(payload)

    # 完成顺序是不确定的，排一下让日志和入库顺序稳定
    payloads.sort(key=lambda item: item["indexCode"])
    return payloads


def danjuan_valuations(
    indices: list[dict[str, Any]],
    items: Optional[dict[str, dict[str, Any]]] = None,
) -> Optional[list[dict[str, Any]]]:
    """蛋卷段的估值行；**那一次请求失败就整段放弃**（返回 None）。

    蛋卷那个接口一次就回全部指数，所以这里**只发一次请求**（`fetch_danjuan_items`
    模块级按日期缓存）。逐个指数各发一次，池子一扩就是几十次无谓请求。
    """
    wanted = [f for f in indices if f.get("valuationSource") == report.VALUATION_SOURCE_DANJUAN]
    if not wanted:
        return []

    if items is None:
        try:
            items = report.fetch_danjuan_items()
        except Exception as e:
            logger.error("  ❌ 蛋卷估值取数失败: %s；本轮蛋卷段整体放弃", e)
            return None

    payloads: list[dict[str, Any]] = []
    for fund in wanted:
        code = str(fund.get("danjuanCode") or "").upper()
        item = items.get(code)
        if not item:
            logger.warning("  ⚠️ 蛋卷估值里没有 %s(%s)，该指数本轮无分位", fund.get("indexName"), code)
            continue
        try:
            valuation = report.valuation_from_danjuan_item(
                {"index_name": fund.get("indexName") or code}, item
            )
        except Exception as e:
            logger.warning("  ⚠️ %s 的蛋卷估值不可用: %s", fund.get("indexName"), e)
            continue
        payloads.append({
            "indexCode": fund["indexCode"],
            "indexName": fund["indexName"],
            "peTtm": valuation["pe_ttm"],
            "pePercentile": valuation["pe_percentile"],
            "percentileMethod": report.DANJUAN_PE_TTM_PROVIDER,
            "valuationLevel": valuation["valuation_level"],
            "tradeDate": valuation["updated_at"],
            "source": valuation["source"],
        })
    payloads.sort(key=lambda item: item["indexCode"])
    return payloads


def push_valuation_batch(payloads: list[dict[str, Any]]) -> bool:
    backend_url = os.environ.get("BACKEND_API_URL", "").rstrip("/")
    token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url or not token:
        return False
    try:
        for start in range(0, len(payloads), VALUATION_INGEST_BATCH_SIZE):
            chunk = payloads[start:start + VALUATION_INGEST_BATCH_SIZE]
            resp = requests.post(
                f"{backend_url}/api/market-valuations/ingest-batch",
                json=chunk,
                headers={"X-Ingest-Token": token},
                timeout=(4, 20),
            )
            if report.backend_result(resp, "指数池估值批量入库") is None:
                return False
        logger.info("  ✅ 已写入 %s 个指数的估值", len(payloads))
        return True
    except Exception as e:
        logger.warning("  ⚠️ 指数池估值批量入库失败: %s", e)
        return False


def etf_identity(fund: dict[str, Any]) -> dict[str, str]:
    """池子条目 → 现成取数函数要的那种 ETF 字典。

    前缀由 `market` 推出来（1=沪、0=深），与东财 secid 同一套约定；
    腾讯/新浪用的是 `sh`/`sz` 前缀。
    """
    code = str(fund["etfCode"])
    prefix = "sh" if str(fund.get("market")) == "1" else "sz"
    return {
        "code": code,
        "name": fund.get("etfName") or code,
        "sina_code": f"{prefix}{code}",
        "index_name": fund.get("indexName") or code,
    }


def sync_pool_prices(indices: list[dict[str, Any]]) -> list[str]:
    """预取池内每只代表 ETF 的前复权日线，返回失败的 ETF 代码。

    只走腾讯、不落东财：东财的 `push2his` 在部署机器上本来就被限流，而池子里几十只一起
    打过去只会把 IP 封得更深。日线链的兜底留给点击时那一层（Java 侧，且有次数上限）。
    """
    cutoff = {"data_time": report.now_beijing().strftime("%Y-%m-%d %H:%M:%S")}
    failures: list[str] = []
    for fund in indices:
        if not fund.get("etfCode"):
            continue
        etf = etf_identity(fund)
        try:
            bars = report.fetch_etf_daily_prices_from_tencent(etf)
            # 传库里的缓存行进去，未变动的旧行就不用再写一遍
            cached = report.fetch_cached_etf_prices(etf)
            if not report.push_etf_price_history(etf, bars[-POOL_PRICE_BARS:], cutoff, cached):
                raise RuntimeError("后端批量写入失败")
            logger.info("  ✅ %s 日线已回填（%s 根，截至 %s）", etf["name"], len(bars), bars[-1]["date"])
        except Exception as e:
            failures.append(etf["code"])
            logger.warning("  ⚠️ %s 日线回填失败: %s", etf["name"], e)
    return failures


def sync_index_pool(force: bool = False) -> bool:
    """同步一次指数池的估值与日线。全部成功才返回 True。"""
    if not os.environ.get("BACKEND_API_URL") or not os.environ.get("REPORT_INGEST_TOKEN"):
        logger.error("❌ 指数池同步需要 BACKEND_API_URL 和 REPORT_INGEST_TOKEN")
        return False
    if not force and already_synced_today():
        logger.info("ℹ️ 本机今天已同步过指数池，跳过（要重跑设 INDEX_POOL_FORCE=1）")
        return True

    try:
        indices = fetch_index_pool()
    except Exception as e:
        logger.error("❌ 指数池读取失败: %s", e)
        return False

    with_etf = sum(1 for fund in indices if fund.get("etfCode"))
    logger.info("📡 指数池共 %s 个指数，其中 %s 个有代表 ETF", len(indices), with_etf)

    csindex_rows = csindex_valuations(indices)
    if csindex_rows is None:
        logger.error("❌ 中证段中止，本轮不写任何估值（库里保留昨日值）")
        return False
    danjuan_rows = danjuan_valuations(indices)
    if danjuan_rows is None:
        logger.error("❌ 蛋卷段中止，本轮不写任何估值（库里保留昨日值）")
        return False

    valuations = csindex_rows + danjuan_rows
    if not valuations:
        logger.error("❌ 指数池没有任何可写估值")
        return False
    if not push_valuation_batch(valuations):
        logger.error("❌ 估值入库失败（库里保留昨日值）")
        return False

    price_failures = sync_pool_prices(indices)

    # 先落哨兵再去判断日线：中证那 64MB 才是真正会被封的部分，
    # 日线没成不该让下一分钟的轮询再去把中证段跑一遍。
    mark_synced()
    if price_failures:
        logger.error("❌ %s 只 ETF 的日线未回填: %s", len(price_failures), ", ".join(price_failures))
        return False
    logger.info("✅ 指数池同步完成：%s 个指数估值 + %s 只 ETF 日线", len(valuations), with_etf)
    return True


def main() -> None:
    if not sync_index_pool(force=report.env_enabled("INDEX_POOL_FORCE")):
        sys.exit(1)


if __name__ == "__main__":
    main()