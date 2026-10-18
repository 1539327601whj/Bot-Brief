# -*- coding: utf-8 -*-
"""同步任务队列（``md_sync_job``）。

与 Java ``WarehouseRepository`` 的 SQL 语义保持一致，这样两边谁先写都行：

* ``job_key`` 唯一，重复入队不产生新行；
* ``priority`` **数字越大越先跑**（用户任务 100，批量任务 10）；
* ``DONE`` 的任务不会被重新入队；``RUNNING`` 不会被抢走；
* 失败写 ``retry_after``，冷却期内不再被取到；
* 429 不消耗重试次数——冷却结束后还会再来；解析类永久错误重试 3 次后标记 ``SKIPPED``。

批量任务用「日期 + 任务」做 ``job_key``，每天只会有一行，重启不会重跑。
"""

from __future__ import annotations

import json
import logging
from datetime import date, datetime
from typing import Any

from .config import Config
from .db import WarehouseDb, WarehouseDbError

logger = logging.getLogger(__name__)

TASK_STOCK_DETAIL = "STOCK_DETAIL"
TASK_INDEX_DETAIL = "INDEX_DETAIL"
TASK_DAILY_MARKET_SNAPSHOT = "DAILY_MARKET_SNAPSHOT"
TASK_DAILY_INDEX_PROFILE = "DAILY_INDEX_PROFILE"
TASK_DAILY_INDUSTRY = "DAILY_INDUSTRY"
TASK_NEWS_REFRESH = "NEWS_REFRESH"

PRIORITY_USER = 100
PRIORITY_INDEX = 80
PRIORITY_BATCH = 10
PRIORITY_NEWS = 5

STATUS_PENDING = "PENDING"
STATUS_RUNNING = "RUNNING"
STATUS_DONE = "DONE"
STATUS_FAILED = "FAILED"
STATUS_SKIPPED = "SKIPPED"


def encode_payload(payload: dict[str, Any] | None) -> str | None:
    if payload is None:
        return None
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def decode_payload(raw: Any) -> dict[str, Any]:
    if not raw:
        return {}
    if isinstance(raw, dict):
        return raw
    try:
        parsed = json.loads(raw)
    except (TypeError, ValueError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


ENQUEUE_SQL = """
INSERT INTO md_sync_job (job_key, task, status, priority, instrument_code, payload_json)
VALUES (%s, %s, 'PENDING', %s, %s, %s)
ON DUPLICATE KEY UPDATE
    task = VALUES(task),
    priority = GREATEST(priority, VALUES(priority)),
    instrument_code = COALESCE(VALUES(instrument_code), instrument_code),
    payload_json = COALESCE(VALUES(payload_json), payload_json),
    status = CASE
        WHEN status = 'RUNNING' THEN status
        WHEN status = 'DONE' THEN status
        -- SKIPPED 是「今天不再试了」：调度器每几秒重入队一次，
        -- 若把它重置回 PENDING，重试上限就形同虚设，被封的域名会被整晚反复撞。
        WHEN status = 'SKIPPED' THEN status
        WHEN retry_after IS NOT NULL AND retry_after > NOW(3) THEN status
        ELSE 'PENDING'
    END
"""


def enqueue(
    db: WarehouseDb,
    job_key: str,
    task: str,
    *,
    priority: int = PRIORITY_BATCH,
    instrument_code: str | None = None,
    payload: dict[str, Any] | None = None,
) -> None:
    db.execute(ENQUEUE_SQL, (job_key, task, priority, instrument_code, encode_payload(payload)))


def claim_next(db: WarehouseDb) -> dict[str, Any] | None:
    """原子取一条可执行任务并置为 RUNNING。没有任务返回 None。"""
    conn = db.connection()
    try:
        conn.begin()
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT id, job_key, task, priority, instrument_code, payload_json, `cursor`, error
                FROM md_sync_job
                WHERE status = 'PENDING'
                   OR (status = 'FAILED' AND (retry_after IS NULL OR retry_after <= NOW(3)))
                ORDER BY priority DESC, id ASC
                LIMIT 1
                FOR UPDATE
                """
            )
            row = cursor.fetchone()
            if not row:
                conn.commit()
                return None
            cursor.execute(
                "UPDATE md_sync_job SET status='RUNNING', started_at=NOW(3), error=NULL "
                "WHERE id = %s",
                (row["id"],),
            )
        conn.commit()
    except Exception as exc:
        try:
            conn.rollback()
        except Exception:  # pragma: no cover
            pass
        raise WarehouseDbError(f"领取任务失败: {type(exc).__name__}: {exc}") from exc

    payload = decode_payload(row.get("payload_json"))
    return {
        "id": int(row["id"]),
        "job_key": row["job_key"],
        "task": row["task"],
        "priority": int(row["priority"]),
        "instrument_code": row.get("instrument_code"),
        "payload": payload,
        "attempts": int(payload.get("attempts") or 0),
        "cursor": row.get("cursor"),
    }


def mark_done(db: WarehouseDb, job: dict[str, Any], rows: int) -> None:
    db.execute(
        "UPDATE md_sync_job SET status='DONE', rows_affected=%s, finished_at=NOW(3), "
        "retry_after=NULL, error=NULL WHERE job_key=%s",
        (max(0, rows), job["job_key"]),
    )


def mark_failed(
    db: WarehouseDb,
    job: dict[str, Any],
    error: str,
    *,
    retry_after_seconds: int,
    permanent: bool = False,
    max_attempts: int = 3,
) -> str:
    """写失败状态，返回最终状态（``FAILED`` 或 ``SKIPPED``）。

    ``permanent=False`` 用于限流/网络：不增加 attempts，冷却后继续重试。
    ``permanent=True`` 用于解析失败/参数错误：累计 attempts，超过上限标记 SKIPPED。
    """
    attempts = job.get("attempts", 0)
    if permanent:
        attempts += 1
    payload = dict(job.get("payload") or {})
    payload["attempts"] = attempts
    truncated = (error or "")[:990]
    skipped = permanent and attempts >= max_attempts
    status = STATUS_SKIPPED if skipped else STATUS_FAILED
    db.execute(
        "UPDATE md_sync_job SET status=%s, error=%s, payload_json=%s, finished_at=NOW(3), "
        "retry_after=TIMESTAMPADD(SECOND, %s, NOW(3)) WHERE job_key=%s",
        (
            status,
            truncated if not skipped else f"{truncated}（重试 {attempts} 次仍失败）"[:990],
            encode_payload(payload),
            max(0, retry_after_seconds),
            job["job_key"],
        ),
    )
    return status


def set_cursor(db: WarehouseDb, job: dict[str, Any], cursor: str | None) -> None:
    db.execute("UPDATE md_sync_job SET `cursor`=%s WHERE job_key=%s", (cursor, job["job_key"]))


def retry_delay_seconds(attempts: int) -> int:
    return min(3600, 60 * (2 ** max(0, attempts - 1)))


# ----------------------------------------------------------------------
# 调度
# ----------------------------------------------------------------------
def _daily_key(task: str, day: date) -> str:
    return f"{task}:{day.isoformat()}"


def ensure_scheduled_jobs(db: WarehouseDb, config: Config, now: datetime) -> list[str]:
    """按时间窗补每日任务。重复调用是幂等的（DONE 不会被重置）。"""
    created: list[str] = []
    day = now.date()

    if now.hour >= config.batch_start_hour:
        for task in (TASK_DAILY_MARKET_SNAPSHOT, TASK_DAILY_INDEX_PROFILE):
            key = _daily_key(task, day)
            enqueue(db, key, task, priority=PRIORITY_BATCH)
            created.append(key)

    if (now.hour, now.minute) >= (config.industry_start_hour, config.industry_start_minute):
        key = _daily_key(TASK_DAILY_INDUSTRY, day)
        # 注意：不要复用上面的循环变量 task，否则会被写成 DAILY_INDEX_PROFILE。
        enqueue(db, key, TASK_DAILY_INDUSTRY, priority=PRIORITY_BATCH)
        created.append(key)

    if config.news_refresh_enabled:
        key = f"{TASK_NEWS_REFRESH}:{now.strftime('%Y-%m-%d-%H')}"
        enqueue(db, key, TASK_NEWS_REFRESH, priority=PRIORITY_NEWS)
        created.append(key)

    return created


__all__ = [
    "ENQUEUE_SQL",
    "PRIORITY_BATCH",
    "PRIORITY_INDEX",
    "PRIORITY_NEWS",
    "PRIORITY_USER",
    "STATUS_DONE",
    "STATUS_FAILED",
    "STATUS_PENDING",
    "STATUS_RUNNING",
    "STATUS_SKIPPED",
    "TASK_DAILY_INDEX_PROFILE",
    "TASK_DAILY_INDUSTRY",
    "TASK_DAILY_MARKET_SNAPSHOT",
    "TASK_INDEX_DETAIL",
    "TASK_NEWS_REFRESH",
    "TASK_STOCK_DETAIL",
    "claim_next",
    "decode_payload",
    "encode_payload",
    "enqueue",
    "ensure_scheduled_jobs",
    "mark_done",
    "mark_failed",
    "retry_delay_seconds",
    "set_cursor",
]
