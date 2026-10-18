# -*- coding: utf-8 -*-
import json
import unittest
from datetime import datetime

import warehouse_fixtures

warehouse_fixtures.ensure_path()

from warehouse import jobs  # noqa: E402
from warehouse.config import Config  # noqa: E402

FakeDb = warehouse_fixtures.FakeDb


class Cursor:
    def __init__(self, row=None):
        self.row = row
        self.executed = []

    def execute(self, sql, params=None):
        self.executed.append((sql, params))

    def fetchone(self):
        return self.row

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False


class Connection:
    def __init__(self, row=None):
        self.cursor_obj = Cursor(row)
        self.commits = 0
        self.rollbacks = 0
        self.begins = 0

    def begin(self):
        self.begins += 1

    def commit(self):
        self.commits += 1

    def rollback(self):
        self.rollbacks += 1

    def cursor(self):
        return self.cursor_obj


class ClaimDb(FakeDb):
    def __init__(self, row=None):
        super().__init__()
        self.conn_obj = Connection(row)

    def connection(self):
        return self.conn_obj


def config(**overrides) -> Config:
    base = Config.from_env()
    return Config(**{**base.__dict__, **overrides})


class ScheduleTest(unittest.TestCase):
    def scheduled(self, when, **overrides):
        db = FakeDb()
        jobs.ensure_scheduled_jobs(db, config(**overrides), when)
        return [(params[0], params[1]) for _, params in db.executed]

    def test_nothing_before_batch_window(self):
        self.assertEqual(self.scheduled(datetime(2025, 9, 30, 16, 59)), [])

    def test_batch_tasks_at_17(self):
        keys = self.scheduled(datetime(2025, 9, 30, 17, 0))
        self.assertEqual(keys, [
            ("DAILY_MARKET_SNAPSHOT:2025-09-30", "DAILY_MARKET_SNAPSHOT"),
            ("DAILY_INDEX_PROFILE:2025-09-30", "DAILY_INDEX_PROFILE"),
        ])

    def test_industry_only_after_1730(self):
        self.assertEqual(self.scheduled(datetime(2025, 9, 30, 17, 29)), [
            ("DAILY_MARKET_SNAPSHOT:2025-09-30", "DAILY_MARKET_SNAPSHOT"),
            ("DAILY_INDEX_PROFILE:2025-09-30", "DAILY_INDEX_PROFILE"),
        ])
        keys = self.scheduled(datetime(2025, 9, 30, 17, 30))
        self.assertIn(("DAILY_INDUSTRY:2025-09-30", "DAILY_INDUSTRY"), keys)

    def test_news_is_hourly_and_optional(self):
        self.assertEqual(
            [key for key, _ in self.scheduled(datetime(2025, 9, 30, 10, 0))], []
        )
        keys = self.scheduled(datetime(2025, 9, 30, 10, 0), news_refresh_enabled=True)
        self.assertEqual(keys, [("NEWS_REFRESH:2025-09-30-10", "NEWS_REFRESH")])

    def test_job_key_is_date_scoped(self):
        first = self.scheduled(datetime(2025, 9, 30, 17, 0))
        second = self.scheduled(datetime(2025, 10, 1, 17, 0))
        self.assertNotEqual(first[0][0], second[0][0])


class EnqueueSqlTest(unittest.TestCase):
    def test_done_jobs_are_not_reopened_and_priority_only_grows(self):
        sql = jobs.ENQUEUE_SQL
        self.assertIn("GREATEST(priority, VALUES(priority))", sql)
        self.assertIn("WHEN status = 'DONE' THEN status", sql)
        self.assertIn("WHEN status = 'RUNNING' THEN status", sql)

    def test_payload_roundtrip(self):
        raw = jobs.encode_payload({"name": "贵州茅台", "attempts": 2})
        self.assertIn("贵州茅台", raw)
        self.assertEqual(jobs.decode_payload(raw), {"name": "贵州茅台", "attempts": 2})
        self.assertEqual(jobs.decode_payload("not json"), {})
        self.assertEqual(jobs.decode_payload(None), {})


class ClaimTest(unittest.TestCase):
    def test_claim_marks_running_and_decodes_payload(self):
        row = {
            "id": 7,
            "job_key": "STOCK_DETAIL:600519",
            "task": "STOCK_DETAIL",
            "priority": 100,
            "instrument_code": "STOCK:600519",
            "payload_json": json.dumps({"stock_code": "600519", "attempts": 1}),
            "cursor": None,
            "error": None,
        }
        db = ClaimDb(row)
        job = jobs.claim_next(db)
        self.assertEqual(job["id"], 7)
        self.assertEqual(job["attempts"], 1)
        self.assertEqual(job["payload"]["stock_code"], "600519")
        self.assertEqual(db.conn_obj.commits, 1)
        update_sql = db.conn_obj.cursor_obj.executed[-1][0]
        self.assertIn("status='RUNNING'", update_sql)

    def test_no_job_returns_none(self):
        db = ClaimDb(None)
        self.assertIsNone(jobs.claim_next(db))
        self.assertEqual(db.conn_obj.commits, 1)


class FailureTest(unittest.TestCase):
    def job(self, attempts=0):
        return {"job_key": "k", "attempts": attempts, "payload": {"attempts": attempts}}

    def test_retry_delay(self):
        self.assertEqual(jobs.retry_delay_seconds(1), 60)
        self.assertEqual(jobs.retry_delay_seconds(2), 120)
        self.assertEqual(jobs.retry_delay_seconds(3), 240)
        self.assertEqual(jobs.retry_delay_seconds(20), 3600)

    def test_permanent_failure_increments_attempts(self):
        db = FakeDb()
        status = jobs.mark_failed(db, self.job(), "boom", retry_after_seconds=60, permanent=True)
        self.assertEqual(status, "FAILED")
        params = db.executed[0][1]
        payload = json.loads(params[2])
        self.assertEqual(payload["attempts"], 1)
        self.assertEqual(params[3], 60)

    def test_rate_limit_does_not_consume_attempts(self):
        db = FakeDb()
        jobs.mark_failed(db, self.job(), "429", retry_after_seconds=600, permanent=False)
        payload = json.loads(db.executed[0][1][2])
        self.assertEqual(payload["attempts"], 0)

    def test_skipped_after_max_attempts(self):
        db = FakeDb()
        status = jobs.mark_failed(
            db, self.job(attempts=2), "boom", retry_after_seconds=60,
            permanent=True, max_attempts=3,
        )
        self.assertEqual(status, "SKIPPED")
        self.assertIn("重试 3 次仍失败", db.executed[0][1][1])

    def test_error_is_truncated(self):
        db = FakeDb()
        jobs.mark_failed(db, self.job(), "x" * 5000, retry_after_seconds=60, permanent=True)
        self.assertLessEqual(len(db.executed[0][1][1]), 990)


if __name__ == "__main__":
    unittest.main()
