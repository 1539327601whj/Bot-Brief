# -*- coding: utf-8 -*-
import unittest

import warehouse_fixtures

warehouse_fixtures.ensure_path()

from warehouse.config import DbConfig, RateLimitConfig  # noqa: E402
from warehouse.schema import (  # noqa: E402
    SchemaMismatch,
    assert_schema,
    check_schema,
    load_manifest,
)

FakeDb = warehouse_fixtures.FakeDb


def manifest() -> dict:
    return {
        "tables": {
            "md_instrument": {"requiredColumns": ["instrument_code", "asset_type", "fetched_at"]},
            "md_price_daily": {"requiredColumns": ["instrument_code", "trade_date", "close_price"]},
        }
    }


def fake_db(tables, columns, database="brief"):
    return FakeDb(
        query_results=[
            {"db": database},
            [{"table_name": name} for name in tables],
            [{"table_name": table, "column_name": column} for table, cols in columns.items()
             for column in cols],
        ]
    )


class CheckSchemaTest(unittest.TestCase):
    def test_all_present(self):
        report = check_schema(fake_db(
            tables=["md_instrument", "md_price_daily"],
            columns={
                "md_instrument": ["instrument_code", "asset_type", "fetched_at"],
                "md_price_daily": ["instrument_code", "trade_date", "close_price"],
            },
        ), manifest())
        self.assertTrue(report.ok)
        self.assertEqual(report.checked_tables, 2)
        self.assertEqual(report.database, "brief")
        self.assertEqual(report.missing_tables, [])
        self.assertEqual(report.missing_columns, {})

    def test_missing_table(self):
        report = check_schema(fake_db(
            tables=["md_instrument"],
            columns={"md_instrument": ["instrument_code", "asset_type", "fetched_at"]},
        ), manifest())
        self.assertFalse(report.ok)
        self.assertEqual(report.missing_tables, ["md_price_daily"])
        with self.assertRaises(SchemaMismatch):
            assert_schema(FakeDb(), manifest())

    def test_missing_column(self):
        report = check_schema(fake_db(
            tables=["md_instrument", "md_price_daily"],
            columns={
                "md_instrument": ["instrument_code", "fetched_at"],
                "md_price_daily": ["instrument_code", "trade_date", "close_price"],
            },
        ), manifest())
        self.assertEqual(report.missing_columns, {"md_instrument": ["asset_type"]})

    def test_uppercase_column_names_are_accepted(self):
        db = FakeDb(query_results=[
            {"db": "brief"},
            [{"TABLE_NAME": "md_instrument"}, {"TABLE_NAME": "md_price_daily"}],
            [
                {"TABLE_NAME": "md_instrument", "COLUMN_NAME": "instrument_code"},
                {"TABLE_NAME": "md_instrument", "COLUMN_NAME": "asset_type"},
                {"TABLE_NAME": "md_instrument", "COLUMN_NAME": "fetched_at"},
                {"TABLE_NAME": "md_price_daily", "COLUMN_NAME": "instrument_code"},
                {"TABLE_NAME": "md_price_daily", "COLUMN_NAME": "trade_date"},
                {"TABLE_NAME": "md_price_daily", "COLUMN_NAME": "close_price"},
            ],
        ])
        self.assertTrue(check_schema(db, manifest()).ok)


class ManifestTest(unittest.TestCase):
    def test_default_manifest_covers_all_warehouse_tables(self):
        data = load_manifest()
        expected = {
            "md_instrument", "md_price_daily", "md_valuation_daily", "md_financial_report",
            "md_moneyflow_daily", "md_index_profile", "md_index_industry_weight",
            "md_index_constituent", "md_industry_board", "md_industry_constituent",
            "md_event", "md_analysis_cache", "md_sync_job", "md_source_state",
            # V15 还给已有的指数估值表补了 pb / dividend_yield 两列，这里一并校验。
            "market_valuation_history",
        }
        self.assertEqual(set((data.get("tables") or {}).keys()), expected)
        for table, spec in data["tables"].items():
            self.assertTrue(spec.get("requiredColumns"), table)


class DbConfigTest(unittest.TestCase):
    def test_empty_warehouse_user_falls_back_to_app_account(self):
        config = DbConfig.from_env({
            "DB_HOST": "db", "DB_NAME": "brief", "DB_USER": "app", "DB_PASSWORD": "pw",
            "WAREHOUSE_DB_USER": "",
            "WAREHOUSE_DB_PASSWORD": "",
        })
        self.assertEqual(config.user, "app")
        self.assertEqual(config.password, "pw")
        self.assertTrue(config.using_app_account)

    def test_explicit_warehouse_user(self):
        config = DbConfig.from_env({
            "DB_USER": "app", "DB_PASSWORD": "pw",
            "WAREHOUSE_DB_USER": "warehouse", "WAREHOUSE_DB_PASSWORD": "secret",
        })
        self.assertEqual(config.user, "warehouse")
        self.assertEqual(config.password, "secret")
        self.assertFalse(config.using_app_account)

    def test_defaults(self):
        config = DbConfig.from_env({})
        self.assertEqual(config.host, "127.0.0.1")
        self.assertEqual(config.port, 3306)
        self.assertEqual(config.name, "brief")

    def test_bad_port_falls_back(self):
        self.assertEqual(DbConfig.from_env({"DB_PORT": "not-a-number"}).port, 3306)


class RateLimitConfigTest(unittest.TestCase):
    def test_batch_lane_is_slower_than_interactive(self):
        config = RateLimitConfig()
        self.assertEqual(config.interval_ms("eastmoney:batch"), 500)
        self.assertEqual(config.interval_ms("eastmoney:interactive"), 150)
        self.assertEqual(config.interval_ms("unknown"), 300)

    def test_defaults_are_sane(self):
        config = RateLimitConfig()
        self.assertGreaterEqual(config.max_wait_ms, 1000)
        self.assertGreaterEqual(config.cooldown_seconds, 60)
        self.assertGreater(config.cooldown_cache_ms, 0)


if __name__ == "__main__":
    unittest.main()
