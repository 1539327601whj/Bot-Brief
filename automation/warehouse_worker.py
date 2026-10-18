# -*- coding: utf-8 -*-
"""warehouse 独立进程入口。

与 poller 共镜像、**独立进程、独立 DB 凭据、独立重启**。启动时先做 schema 自检：
缺表/缺列直接退出（exit 2），不带着半个 schema 跑——那种情况下写库错误会被
「单段失败不影响其他段」吞掉，最后表现成页面长期空白，很难查。

用法::

    python -u warehouse_worker.py              # 常驻；每日任务按时间窗自动入队
    python -u warehouse_worker.py --once       # 跑一轮：补任务 + 处理一条
    python -u warehouse_worker.py --drain      # 处理完当前所有可执行任务后退出
    python -u warehouse_worker.py --check-schema
"""

from __future__ import annotations

import argparse
import logging
import sys
import time
from datetime import datetime
from typing import Any

from warehouse import jobs
from warehouse.clients import Clients, build_clients
from warehouse.config import Config
from warehouse.normalize import now_beijing
from warehouse.rate_limit import RateLimited, SourceCooling
from warehouse.schema import SchemaMismatch, assert_schema, check_schema
from warehouse.sync import SyncOutcome, combine
from warehouse.sync.events import sync_events
from warehouse.sync.index_profile import sync_index_detail
from warehouse.sync.industry import sync_industry_boards
from warehouse.sync.instruments import load_index_pool
from warehouse.sync.snapshot import sync_market_snapshot
from warehouse.sync.stock import sync_stock_detail
from warehouse.logs import configure as configure_logging

logger = logging.getLogger("warehouse.worker")

NEWS_POLICY_SUBJECT_LIMIT = 30


def _stock_code(job: dict[str, Any]) -> str | None:
    payload = job.get("payload") or {}
    code = payload.get("stock_code")
    if code:
        return str(code)
    instrument = job.get("instrument_code") or ""
    if instrument.upper().startswith("STOCK:"):
        return instrument.split(":", 1)[1]
    return None


def _index_payload(job: dict[str, Any]) -> dict[str, Any]:
    payload = dict(job.get("payload") or {})
    instrument = job.get("instrument_code") or ""
    if instrument.upper().startswith("INDEX:") and not payload.get("index_code"):
        payload["index_code"] = instrument.split(":", 1)[1]
    return payload


def _run_index_profile(clients: Clients, job: dict[str, Any]) -> SyncOutcome:
    pool = load_index_pool(clients.config)
    cursor = job.get("cursor")
    resume = bool(cursor)
    parts: dict[str, SyncOutcome] = {}
    last_done = cursor

    for entry in pool:
        index_code = str(entry.get("indexCode") or "")
        if not index_code:
            continue
        if resume:
            if index_code == cursor:
                resume = False
            continue
        outcome = sync_index_detail(
            clients.db, clients.csindex, clients.danjuan,
            index_code=index_code,
            csindex_code=entry.get("csindexCode"),
            danjuan_code=entry.get("danjuanCode"),
        )
        parts[index_code] = outcome
        last_done = index_code
        jobs.set_cursor(clients.db, job, last_done)
        # 单只指数没有可用来源是正常情况（例如场外指数），不阻塞整批。
        logger.info("index profile %s -> %s rows=%s", index_code, outcome.status, outcome.rows)

    if not parts:
        return SyncOutcome("SKIPPED", detail={"reason": "没有剩余指数"})
    combined = combine("DAILY_INDEX_PROFILE", parts)
    combined.detail["lastCursor"] = last_done
    return combined


def _run_news_refresh(clients: Clients) -> SyncOutcome:
    rows = clients.db.query_all(
        "SELECT DISTINCT industry FROM md_instrument "
        "WHERE industry IS NOT NULL AND industry <> '' "
        f"LIMIT {NEWS_POLICY_SUBJECT_LIMIT}"
    )
    subjects = [str(row.get("industry")) for row in rows if row.get("industry")]
    if not subjects:
        return SyncOutcome("EMPTY", detail={"reason": "还没有行业名，跳过政策刷新"})
    parts: dict[str, SyncOutcome] = {}
    for subject in subjects:
        try:
            parts[subject] = sync_events(
                clients.db, clients.news,
                instrument_code=None, policy_subject=subject,
            )
        except RateLimited:
            raise
        except Exception as exc:  # noqa: BLE001
            parts[subject] = SyncOutcome("FAILED", error=str(exc))
    return combine("NEWS_REFRESH", parts)


def dispatch(clients: Clients, job: dict[str, Any]) -> SyncOutcome:
    task = job.get("task")
    payload = job.get("payload") or {}

    if task == jobs.TASK_STOCK_DETAIL:
        code = _stock_code(job)
        if not code:
            return SyncOutcome("FAILED", error="STOCK_DETAIL 缺少 stock_code")
        return sync_stock_detail(
            clients.db, clients.eastmoney, clients.news, code,
            name=payload.get("name"),
        )

    if task == jobs.TASK_INDEX_DETAIL:
        data = _index_payload(job)
        index_code = data.get("index_code")
        if not index_code:
            return SyncOutcome("FAILED", error="INDEX_DETAIL 缺少 index_code")
        return sync_index_detail(
            clients.db, clients.csindex, clients.danjuan,
            index_code=index_code,
            csindex_code=data.get("csindex_code"),
            danjuan_code=data.get("danjuan_code"),
        )

    if task == jobs.TASK_DAILY_MARKET_SNAPSHOT:
        return sync_market_snapshot(clients.db, clients.eastmoney)

    if task == jobs.TASK_DAILY_INDEX_PROFILE:
        return _run_index_profile(clients, job)

    if task == jobs.TASK_DAILY_INDUSTRY:
        return sync_industry_boards(clients.db, clients.eastmoney)

    if task == jobs.TASK_NEWS_REFRESH:
        return _run_news_refresh(clients)

    return SyncOutcome("FAILED", error=f"未知任务类型: {task}")


def handle_job(clients: Clients, job: dict[str, Any]) -> SyncOutcome:
    started = time.monotonic()
    logger.info("job start key=%s task=%s attempt=%s", job["job_key"], job["task"], job["attempts"])
    try:
        outcome = dispatch(clients, job)
    except RateLimited as exc:
        seconds = max(clients.config.rate.cooldown_seconds, exc.retry_after_seconds)
        jobs.mark_failed(
            clients.db, job, f"限流: {exc}",
            retry_after_seconds=seconds, permanent=False,
        )
        logger.warning("job rate-limited key=%s retryAfter=%ss", job["job_key"], seconds)
        return SyncOutcome("FAILED", error=str(exc))
    except SourceCooling as exc:
        seconds = max(1, exc.remaining_ms // 1000 + 1)
        jobs.mark_failed(
            clients.db, job, f"源冷却: {exc}",
            retry_after_seconds=seconds, permanent=False,
        )
        logger.warning("job cooling key=%s retryAfter=%ss", job["job_key"], seconds)
        return SyncOutcome("FAILED", error=str(exc))
    except Exception as exc:  # noqa: BLE001 - 未知错误按永久错误累计重试
        attempts = job["attempts"] + 1
        delay = jobs.retry_delay_seconds(attempts)
        status = jobs.mark_failed(
            clients.db, job, f"{type(exc).__name__}: {exc}",
            retry_after_seconds=delay, permanent=True,
            max_attempts=clients.config.job_max_attempts,
        )
        logger.exception("job failed key=%s status=%s retryAfter=%ss", job["job_key"], status, delay)
        return SyncOutcome("FAILED", error=str(exc))

    elapsed_ms = int((time.monotonic() - started) * 1000)
    if outcome.status == "FAILED":
        attempts = job["attempts"] + 1
        delay = jobs.retry_delay_seconds(attempts)
        status = jobs.mark_failed(
            clients.db, job, outcome.error or "未知失败",
            retry_after_seconds=delay, permanent=True,
            max_attempts=clients.config.job_max_attempts,
        )
        logger.warning(
            "job failed key=%s status=%s error=%s elapsed=%sms",
            job["job_key"], status, outcome.error, elapsed_ms,
        )
    else:
        jobs.mark_done(clients.db, job, outcome.rows)
        logger.info(
            "job done key=%s task=%s status=%s rows=%s elapsed=%sms",
            job["job_key"], job["task"], outcome.status, outcome.rows, elapsed_ms,
        )
    return outcome


def _schedule(clients: Clients, now: datetime) -> None:
    try:
        keys = jobs.ensure_scheduled_jobs(clients.db, clients.config, now)
        if keys:
            logger.debug("scheduled: %s", ", ".join(keys))
    except Exception:  # noqa: BLE001 - 调度失败不该杀死 worker
        logger.exception("每日任务入队失败")


def _yield_to_interactive(clients: Clients) -> None:
    """批量任务之间给交互链路让路：最近 30 秒用户查询过，就缓一秒。"""
    try:
        if clients.gate.recently_active("eastmoney:interactive", 30):
            time.sleep(1.0)
    except Exception:  # noqa: BLE001
        pass


def run_loop(clients: Clients, *, drain: bool = False, max_jobs: int | None = None) -> int:
    processed = 0
    while True:
        now = now_beijing()
        _schedule(clients, now)
        job = jobs.claim_next(clients.db)
        if job is None:
            if drain or max_jobs is not None:
                return 0
            time.sleep(clients.config.job_poll_seconds)
            continue
        _yield_to_interactive(clients)
        handle_job(clients, job)
        processed += 1
        if max_jobs is not None and processed >= max_jobs:
            return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="investment warehouse worker")
    parser.add_argument("--once", action="store_true", help="跑一轮：补任务 + 处理一条")
    parser.add_argument("--drain", action="store_true", help="处理完当前可执行任务后退出")
    parser.add_argument("--check-schema", action="store_true", help="只做 schema 自检")
    args = parser.parse_args(argv)

    config = Config.from_env()
    configure_logging(config.log_level)

    if config.db.using_app_account:
        logger.warning(
            "未配置 WAREHOUSE_DB_USER/PASSWORD，退回应用账号 %s；生产环境应使用只授 md_* 的独立账号",
            config.db.user,
        )

    clients = build_clients(config)

    try:
        report = check_schema(clients.db) if args.check_schema else assert_schema(clients.db)
    except SchemaMismatch as exc:
        logger.error("%s", exc)
        return 2
    except Exception as exc:  # noqa: BLE001 - DB 不可达
        logger.error("schema 自检无法完成: %s", exc)
        return 2

    if args.check_schema:
        logger.info("schema ok tables=%s database=%s", report.checked_tables, report.database)
        return 0

    if args.once:
        return run_loop(clients, max_jobs=1)
    if args.drain:
        return run_loop(clients, drain=True)

    logger.info("warehouse worker started (batch start %02d:00)", config.batch_start_hour)
    try:
        return run_loop(clients)
    except KeyboardInterrupt:
        logger.info("worker stopped")
        return 0


if __name__ == "__main__":
    sys.exit(main())
