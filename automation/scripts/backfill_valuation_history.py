# -*- coding: utf-8 -*-
"""一次性回填中证系指数的 PE 分位**历史**，让「代码查询」页的 5 年 / 10 年档能落上数。

## 为什么需要它

每日同步（`index_pool_sync.py`）**只推 `history[-1]` 那一行**——它回答的是「今天这个指数
的 PE 分位是多少」，池子里有一个最新值就够了。所以库里的历史是从项目上线那天开始
一天长一行的，只有今天往后，没有过去。

而「5 年前那天的分位」需要 5 年前那一行。中证官网的 `indexCsiDsPe` 其实是**回全量日频
历史的**（000300 实测 3787 个点、回溯到 2011-06-28），这些点每天都在被取回来、
算好分位、然后**只留最后一个扔掉其余**。这个脚本就是去把它们捡回来。

## 边界（都是在能联网的机器上实测过的，不是推测）

* **只回填 csindex 系**。蛋卷那个接口只给「当前 PE + 当前分位」，**没有历史序列**，
  想回填也没有数，所以直接跳过并打印原因。蛋卷指数的 5/10 年两档在页面上写「不可用」。
* **池外指数不回填**。它们不在池子里、不定源，按需实时取，不进库。
* **只留 `today - 10 年 - 30 天` 之后的点**。更早的永远不会被读到（窗口就是 10 年），
  留着只是白占空间。30 天缓冲是为了让「10 年前当天或之前」那一行能落上。
* **窗口口径不在这里重写**：分位一律来自 `report.fetch_csindex_pe_history`，
  它是这套口径的单一真源（滚动 `min(10年, 该指数实际可用长度)`）。这个脚本只搬运。

## 与每日同步的关系

**不碰** `poll_loop.py`，也不改每日路径。10 年的数据写一次就不变，每天重推约 8 万行
没有任何意义，只会把按 IP 限流的中证官网自己封掉。每日同步继续照常只推最新一行。

## 跑法

    # 先干跑：取数、算好条数，**一条都不写**
    py -3 automation/scripts/backfill_valuation_history.py --dry-run
    # 再真跑
    py -3 automation/scripts/backfill_valuation_history.py

需要 `BACKEND_API_URL` 与 `REPORT_INGEST_TOKEN`（干跑只要前者）。
幂等：DB 层 `upsert` 按 `(index_code, trade_date, percentile_method)` 落唯一键，
重跑不会写重。哨兵（`.valuation_backfill_marker`）按**池子里 csindex 代码集合的哈希**
记住「这批已经回填过」，往池子新加中证指数会让它自动失效重跑；`--force` 无视哨兵。

**失败即整体放弃**：任何一只取数失败都在写库**之前**中止，一条都不写——「一部分指数
有十年历史、一部分没有」这种库从页面上看不出来。若中止发生在写库中途（某一批 POST
失败），会明确报出**已经写进去多少行**再退出，重跑即可收敛（upsert 幂等）。
"""

import hashlib
import os
import random
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, timedelta
from typing import Any, Callable, Optional

import requests

_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

import etf_report as report  # noqa: E402
import index_pool_sync as sync  # noqa: E402

from logging_setup import setup_logging  # noqa: E402

logger = setup_logging(__name__)

# 与每日同步同一套控频：中证官网按 IP 限流，并发 3 + 抖动是控频手段，不是性能调优。
CSINDEX_CONCURRENCY = sync.CSINDEX_CONCURRENCY
CSINDEX_JITTER_SECONDS = sync.CSINDEX_JITTER_SECONDS

# 批量上限沿用后端既有的 250（MarketValuationHistoryServiceImpl.MAX_BATCH）。
VALUATION_INGEST_BATCH_SIZE = sync.VALUATION_INGEST_BATCH_SIZE

# 比窗口再多留一点：让「10 年前当天或之前」那一行能落上，不至于因为差几天取不到基线。
CUTOFF_BUFFER_DAYS = 30

BACKFILL_MARKER_NAME = ".valuation_backfill_marker"
MARKER_PATH = os.path.join(os.path.dirname(_SCRIPTS_DIR), BACKFILL_MARKER_NAME)


def backfill_cutoff(today: Optional[date] = None) -> date:
    """保留 `today - 10 年 - 30 天` 之后的点。窗口口径直接来自章程用的那个常量。"""
    current = today or report.now_beijing().date()
    return report.subtract_years(current, report.CSI300_PE_WINDOW_YEARS) - timedelta(days=CUTOFF_BUFFER_DAYS)


def marker_key(csindex_codes: list[str]) -> str:
    """哨兵的键：池子里 csindex 代码集合的哈希（带上窗口年数）。

    **刻意不含日期**：回填是一次性的，数据写进去就不变；把日期放进键里会让它每天
    失效、每天重推 8 万行——那正是这个脚本要避免的事。往池子新加中证指数时集合变了，
    键自然失效、自动重跑一次，这才是需要重跑的唯一情形。
    """
    joined = "\n".join(sorted(set(csindex_codes)))
    digest = hashlib.sha256(f"w{report.CSI300_PE_WINDOW_YEARS}:{joined}".encode("utf-8"))
    return digest.hexdigest()[:16]


def already_backfilled(key: str) -> bool:
    try:
        with open(MARKER_PATH, encoding="utf-8") as handle:
            lines = handle.read().strip().splitlines()
    except OSError:
        return False
    return bool(lines) and lines[0] == key


def mark_backfilled(key: str, today: Optional[date] = None) -> None:
    current = (today or report.now_beijing().date()).isoformat()
    try:
        with open(MARKER_PATH, "w", encoding="utf-8") as handle:
            handle.write(f"{key}\n{current}\n")
    except OSError as e:
        # 只读文件系统不该让回填失败：哨兵是防手滑的，不是数据正确性的一部分
        logger.warning("⚠️ 无法写入回填哨兵 %s: %s", MARKER_PATH, e)


def csindex_funds(indices: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """只挑 csindex 系。蛋卷与其它来源**逐条打印跳过原因**，不做静默过滤。"""
    wanted: list[dict[str, Any]] = []
    for fund in indices:
        source = fund.get("valuationSource")
        if source == report.VALUATION_SOURCE_CSINDEX:
            if not fund.get("csindexCode"):
                # 池子校验本该拦住这个（csindex 源必须有 csindexCode），
                # 真漏出来就在这里明说，别让它变成一个静默的空档
                logger.error("❌ %s 标了 csindex 源却没有 csindexCode，无法回填",
                             fund.get("indexName") or fund.get("indexCode"))
                continue
            wanted.append(fund)
        elif source == report.VALUATION_SOURCE_DANJUAN:
            logger.info("  ⏭️ %s：蛋卷源只给当前值、没有历史序列，无法回填（页面按需实时取）",
                        fund.get("indexName") or fund.get("indexCode"))
        else:
            logger.info("  ⏭️ %s：估值来源 %r 不在回填范围内",
                        fund.get("indexName") or fund.get("indexCode"), source)
    return wanted


def backfill_rows(fund: dict[str, Any], history: list[dict[str, Any]], cutoff: date) -> tuple[list[dict], int]:
    """把一只指数的全量历史裁成入库行，返回 (行, 被裁掉的条数)。

    被裁掉的条数要返回出来而不是丢掉不提：它说明「这个指数的 10 年窗口是否真的满了」。
    """
    rows: list[dict[str, Any]] = []
    dropped = 0
    for item in history:
        trade_date = str(item.get("tradeDate") or "")
        try:
            parsed = date.fromisoformat(trade_date)
        except ValueError:
            # fetch_csindex_pe_history 已经保证是 ISO 日期；真出现别的形态是上游变了
            logger.warning("  ⚠️ %s 的历史里有无法解析的 tradeDate %r，跳过该行",
                           fund.get("indexName"), trade_date)
            dropped += 1
            continue
        if parsed < cutoff:
            dropped += 1
            continue
        percentile = report.to_optional_float(item.get("pePercentile"))
        rows.append({
            "indexCode": fund["indexCode"],
            "indexName": fund["indexName"],
            "peTtm": report.to_optional_float(item.get("peTtm")),
            "pePercentile": percentile,
            "percentileMethod": report.CSI_PE_TTM_ROLLING_10Y,
            "valuationLevel": report.valuation_level(percentile),
            "tradeDate": trade_date,
            "source": item.get("source") or report.CSI_PE_SOURCE_LABEL,
        })
    return rows, dropped


def fetch_histories(
    wanted: list[dict[str, Any]],
    fetcher: Optional[Callable[[str], list[dict[str, Any]]]] = None,
) -> Optional[dict[str, list[dict[str, Any]]]]:
    """并发取回每只指数的全量历史；**任何一只失败就返回 None（整体放弃）**。

    返回 None 而不是抛异常，是为了让调用方能把「这段没成」与「这段成了一条没有」
    分开处理——和 `index_pool_sync.csindex_valuations` 同一套约定。
    """
    fetch = fetcher or report.fetch_csindex_pe_history

    def one(code: str) -> list[dict[str, Any]]:
        time.sleep(random.uniform(*CSINDEX_JITTER_SECONDS))
        return fetch(code)

    histories: dict[str, list[dict[str, Any]]] = {}
    with ThreadPoolExecutor(max_workers=CSINDEX_CONCURRENCY) as executor:
        futures = {executor.submit(one, fund["csindexCode"]): fund for fund in wanted}
        for future in as_completed(futures):
            fund = futures[future]
            try:
                histories[fund["indexCode"]] = future.result()
            except Exception as e:
                logger.error("❌ %s(%s) 的中证 PE 历史取数失败: %s；本次回填整体放弃，一条都不写",
                             fund.get("indexName"), fund.get("csindexCode"), e)
                for pending in futures:
                    pending.cancel()
                return None
    return histories


def post_batch(chunk: list[dict[str, Any]]) -> bool:
    """推一批（≤250 条）。单独成函数是为了让测试能把网络层整个换掉。"""
    backend_url = os.environ.get("BACKEND_API_URL", "").rstrip("/")
    token = os.environ.get("REPORT_INGEST_TOKEN", "")
    if not backend_url or not token:
        return False
    try:
        resp = requests.post(
            f"{backend_url}/api/market-valuations/ingest-batch",
            json=chunk,
            headers={"X-Ingest-Token": token},
            timeout=(4, 30),
        )
    except Exception as e:
        logger.warning("  ⚠️ 批量入库请求失败: %s", e)
        return False
    return report.backend_result(resp, "估值历史回填入库") is not None


def push_rows(
    rows: list[dict[str, Any]],
    poster: Optional[Callable[[list[dict]], bool]] = None,
) -> tuple[bool, int]:
    """分批推完，返回 (是否全部成功, 已成功写入的行数)。

    已写入的行数必须返回：中途失败时若只说「失败」，用户不知道库里现在是半截的。
    """
    post = poster or post_batch
    written = 0
    for start in range(0, len(rows), VALUATION_INGEST_BATCH_SIZE):
        chunk = rows[start:start + VALUATION_INGEST_BATCH_SIZE]
        if not post(chunk):
            logger.error("❌ 第 %s 批（第 %s–%s 行）入库失败",
                         start // VALUATION_INGEST_BATCH_SIZE + 1, start + 1, start + len(chunk))
            return False, written
        written += len(chunk)
        if written % (VALUATION_INGEST_BATCH_SIZE * 20) == 0:
            logger.info("  … 已写入 %s/%s 行", written, len(rows))
    return True, written


def run(
    force: bool = False,
    dry_run: bool = False,
    fetcher: Optional[Callable[[str], list[dict[str, Any]]]] = None,
    poster: Optional[Callable[[list[dict]], bool]] = None,
    indices: Optional[list[dict[str, Any]]] = None,
) -> bool:
    """回填一次。返回是否成功（干跑成功即 True）。

    `fetcher` / `poster` / `indices` 只为可测性而留：外网取数与入库都是副作用，
    测试要把它们整个换掉才能验证「失败时一条都不写」这类约定。
    """
    if not os.environ.get("BACKEND_API_URL"):
        logger.error("❌ 回填需要 BACKEND_API_URL")
        return False
    if not dry_run and not os.environ.get("REPORT_INGEST_TOKEN"):
        logger.error("❌ 真跑需要 REPORT_INGEST_TOKEN（干跑不需要）")
        return False

    try:
        pool = indices if indices is not None else sync.fetch_index_pool()
    except Exception as e:
        logger.error("❌ 指数池读取失败: %s", e)
        return False
    indices = pool

    wanted = csindex_funds(indices)
    if not wanted:
        logger.info("ℹ️ 池子里没有中证系指数，无需回填")
        return True

    key = marker_key([f["csindexCode"] for f in wanted])
    if not force and not dry_run and already_backfilled(key):
        logger.info("ℹ️ 这批中证指数（%s 个）已回填过，跳过；要重跑加 --force", len(wanted))
        return True

    cutoff = backfill_cutoff()
    logger.info("📡 待回填 %s 个中证指数，保留 %s 之后的点（10 年窗口 + %s 天缓冲）",
                len(wanted), cutoff.isoformat(), CUTOFF_BUFFER_DAYS)

    histories = fetch_histories(wanted, fetcher=fetcher)
    if histories is None:
        return False

    all_rows: list[dict[str, Any]] = []
    for fund in sorted(wanted, key=lambda f: f["indexCode"]):
        rows, dropped = backfill_rows(fund, histories.get(fund["indexCode"], []), cutoff)
        if not rows:
            # 这个指数在窗口内一行都没有：要么它太新，要么上游变了。两种都要说清。
            logger.warning("  ⚠️ %s 在 %s 之后没有任何点（历史太短或取数为空）",
                           fund.get("indexName"), cutoff.isoformat())
            continue
        oldest = rows[0]["tradeDate"]
        span_years = (date.fromisoformat(rows[-1]["tradeDate"]) - date.fromisoformat(oldest)).days / 365.25
        logger.info("  ✅ %s：%s 行（%s ~ %s，约 %.1f 年，裁掉 %s 行）",
                    fund.get("indexName"), len(rows), oldest, rows[-1]["tradeDate"],
                    span_years, dropped)
        all_rows.extend(rows)

    if not all_rows:
        logger.error("❌ 没有任何可回填的行")
        return False

    # 顺序固定，便于重跑时对照同一份 payload
    all_rows.sort(key=lambda r: (r["indexCode"], r["tradeDate"]))
    logger.info("📦 合计 %s 行，将分 %s 批推入库",
                len(all_rows), (len(all_rows) + VALUATION_INGEST_BATCH_SIZE - 1) // VALUATION_INGEST_BATCH_SIZE)

    if dry_run:
        logger.info("🧪 干跑结束：一条都没写。去掉 --dry-run 才会真写。")
        return True

    ok, written = push_rows(all_rows, poster=poster)
    if not ok:
        logger.error("❌ 回填未完成：已写入 %s/%s 行。库里现在是半截状态，"
                     "重跑即可收敛（upsert 按唯一键幂等）。", written, len(all_rows))
        return False

    mark_backfilled(key)
    logger.info("✅ 回填完成：%s 行，%s 个中证指数", written, len(wanted))
    return True


def main(argv: Optional[list[str]] = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    for name in args:
        if name not in ("--force", "--dry-run"):
            print(f"未知参数 {name!r}；可用：--force、--dry-run", file=sys.stderr)
            return 2
    # --force 也认环境变量，与本仓库其它脚本的开关保持一致
    force = "--force" in args or report.env_enabled("VALUATION_BACKFILL_FORCE")
    return 0 if run(force=force, dry_run="--dry-run" in args) else 1


if __name__ == "__main__":
    sys.exit(main())