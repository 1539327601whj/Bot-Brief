# -*- coding: utf-8 -*-
import unittest

import warehouse_fixtures

warehouse_fixtures.ensure_path()

from warehouse.config import DbConfig  # noqa: E402
from warehouse.db import WarehouseDb, chunked  # noqa: E402


def db_config() -> DbConfig:
    return DbConfig(host="127.0.0.1", port=3306, name="brief", user="w", password="")


class RecordingDb(WarehouseDb):
    def __init__(self, chunk_size=2):
        super().__init__(db_config(), connect_factory=lambda config: None, chunk_size=chunk_size)
        self.calls = []

    def execute_many(self, sql, rows):
        self.calls.append((sql, list(rows)))
        return len(rows)


def rows(count=5):
    return [
        {"instrument_code": f"STOCK:{index}", "trade_date": "2025-09-30", "close_price": 1.0,
         "source": "s", "fetched_at": "2025-10-01 18:00:00"}
        for index in range(count)
    ]


class UpsertSqlTest(unittest.TestCase):
    def test_guard_and_coalesce(self):
        sql = WarehouseDb(db_config()).upsert_sql(
            "md_price_daily",
            ["instrument_code", "trade_date", "close_price", "source", "fetched_at"],
            ["instrument_code", "trade_date", "source"],
        )
        self.assertTrue(sql.startswith("INSERT INTO `md_price_daily`"))
        self.assertIn("IF(VALUES(`fetched_at`) >= `fetched_at`", sql)
        self.assertIn("COALESCE(VALUES(`close_price`), `close_price`)", sql)
        self.assertIn("`fetched_at` = GREATEST(`fetched_at`, VALUES(`fetched_at`))", sql)
        # 唯一键列不能被 UPDATE 覆盖。
        self.assertNotIn("`instrument_code` =", sql)

    def test_identifier_validation(self):
        with self.assertRaises(ValueError):
            WarehouseDb(db_config()).upsert_sql(
                "md_price_daily; DROP TABLE x",
                ["instrument_code", "fetched_at"],
                ["instrument_code"],
            )

    def test_guard_column_is_required(self):
        with self.assertRaises(ValueError):
            WarehouseDb(db_config()).upsert_sql(
                "md_price_daily", ["instrument_code", "close_price"], ["instrument_code"]
            )


class UpsertTest(unittest.TestCase):
    def test_rows_are_chunked(self):
        db = RecordingDb(chunk_size=2)
        written = db.upsert(
            "md_price_daily", rows(5),
            ["instrument_code", "trade_date", "source"],
        )
        self.assertEqual(written, 5)
        self.assertEqual([len(call[1]) for call in db.calls], [2, 2, 1])

    def test_empty_rows_write_nothing(self):
        db = RecordingDb()
        self.assertEqual(db.upsert("md_price_daily", [], ["instrument_code"]), 0)
        self.assertEqual(db.calls, [])

    def test_inconsistent_columns_are_rejected(self):
        db = RecordingDb()
        bad = rows(2)
        bad[1]["extra"] = 1
        with self.assertRaises(ValueError):
            db.upsert("md_price_daily", bad, ["instrument_code", "trade_date", "source"])

    def test_missing_guard_column_is_rejected(self):
        db = RecordingDb()
        with self.assertRaises(ValueError):
            db.upsert(
                "md_price_daily",
                [{"instrument_code": "STOCK:1"}],
                ["instrument_code"],
            )


class ChunkedTest(unittest.TestCase):
    def test_chunks(self):
        self.assertEqual(list(chunked([1, 2, 3, 4, 5], 2)), [[1, 2], [3, 4], [5]])
        with self.assertRaises(ValueError):
            list(chunked([1], 0))


if __name__ == "__main__":
    unittest.main()
