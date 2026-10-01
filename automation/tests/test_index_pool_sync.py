import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

SCRIPTS_DIR = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

MODULE_PATH = SCRIPTS_DIR / "index_pool_sync.py"
SPEC = importlib.util.spec_from_file_location("index_pool_sync", MODULE_PATH)
sync = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sync)

report = sync.report


def csindex_fund(code, index_code=None, csindex_code=None, etf=None):
    return {
        "indexCode": index_code or f"SH{code}",
        "indexName": f"指数{code}",
        "category": "broad",
        "valuationSource": "csindex",
        "percentileMethod": report.CSI_PE_TTM_ROLLING_10Y,
        "csindexCode": csindex_code if csindex_code is not None else code,
        "danjuanCode": None,
        "etfCode": etf,
        "market": 1 if etf else None,
        "etfName": f"{code}ETF" if etf else None,
        "tracking": None,
    }


def danjuan_fund(code, index_code, danjuan_code, etf=None):
    return {
        "indexCode": index_code,
        "indexName": f"指数{code}",
        "category": "broad",
        "valuationSource": "danjuan",
        "percentileMethod": report.DANJUAN_PE_TTM_PROVIDER,
        "csindexCode": None,
        "danjuanCode": danjuan_code,
        "etfCode": etf,
        "market": 1 if etf else None,
        "etfName": f"{code}ETF" if etf else None,
        "tracking": None,
    }


def history(trade_date="2026-09-30", pe=13.5, percentile=48.8, rows=1200):
    return [{
        "tradeDate": trade_date,
        "peTtm": pe,
        "pePercentile": percentile,
        "percentileMethod": report.CSI_PE_TTM_ROLLING_10Y,
        "source": f"{report.CSI_PE_SOURCE_LABEL}，滚动10年分位",
    } for _ in range(rows)]


def bars(count=300):
    return [{
        "date": f"2026-{1 + day // 28:02d}-{1 + day % 28:02d}",
        "open": 4.0,
        "close": 4.1,
        "high": 4.2,
        "low": 3.9,
        "source": "腾讯前复权日线",
        "adjustmentType": "QFQ",
    } for day in range(count)]


BACKEND_ENV = {"BACKEND_API_URL": "http://backend:8080", "REPORT_INGEST_TOKEN": "secret"}


class CsindexSegmentTests(unittest.TestCase):
    def test_a_failure_midway_abandons_the_whole_segment(self):
        # 一条 403/429 之后继续跑只会把封禁加深，而且写出一个「一部分今天的、
        # 一部分昨天的」池子——那种池子从页面上看不出任何问题
        calls = []

        def fetcher(code):
            calls.append(code)
            if len(calls) == 2:
                raise report.requests.HTTPError("403 Client Error")
            return history()

        funds = [csindex_fund("000300"), csindex_fund("000905"), csindex_fund("000852")]
        self.assertIsNone(sync.csindex_valuations(funds, fetcher=fetcher))

    def test_each_index_asks_for_its_own_code(self):
        seen = {}

        def fetcher(code):
            seen[code] = history()
            return seen[code]

        funds = [csindex_fund("000300"), csindex_fund("000905"), csindex_fund("000688")]
        rows = sync.csindex_valuations(funds, fetcher=fetcher)

        self.assertEqual(sorted(seen), ["000300", "000688", "000905"])
        self.assertEqual(
            sorted(row["indexCode"] for row in rows), ["SH000300", "SH000688", "SH000905"]
        )

    def test_a_csindex_source_without_its_code_is_an_error_not_a_guess(self):
        funds = [csindex_fund("000300", csindex_code=None, index_code="SH000300")]
        funds[0]["csindexCode"] = ""
        self.assertIsNone(sync.csindex_valuations(funds, fetcher=lambda code: history()))

    def test_the_row_carries_its_source_and_method(self):
        rows = sync.csindex_valuations([csindex_fund("000300")], fetcher=lambda code: history())
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["percentileMethod"], report.CSI_PE_TTM_ROLLING_10Y)
        self.assertEqual(rows[0]["tradeDate"], "2026-09-30")
        self.assertEqual(rows[0]["pePercentile"], 48.8)
        self.assertIn(report.CSI_PE_SOURCE_LABEL, rows[0]["source"])

    def test_only_the_latest_point_is_written_not_the_whole_series(self):
        rows = sync.csindex_valuations([csindex_fund("000300")], fetcher=lambda code: history(rows=1))
        self.assertEqual(len(rows), 1)

    @patch("index_pool_sync.report.now_beijing")
    def test_a_stale_row_is_skipped_instead_of_written(self, now):
        from datetime import datetime
        now.return_value = datetime(2026, 10, 1, 17, 0, tzinfo=report.BEIJING_TZ)

        rows = sync.csindex_valuations(
            [csindex_fund("000300")], fetcher=lambda code: history(trade_date="2026-08-01")
        )
        self.assertEqual(rows, [])

    def test_the_budget_caps_how_many_indices_are_fetched(self):
        seen = []

        def fetcher(code):
            seen.append(code)
            return history()

        funds = [csindex_fund(f"00030{i}") for i in range(4)]
        with patch.dict(os.environ, {"INDEX_POOL_CSINDEX_MAX": "2"}):
            rows = sync.csindex_valuations(funds, fetcher=fetcher)

        self.assertEqual(len(seen), 2)
        self.assertEqual(len(rows), 2)

    def test_indices_without_a_valuation_source_are_not_touched(self):
        fund = csindex_fund("000300")
        fund["valuationSource"] = None
        fund["percentileMethod"] = None
        fund["csindexCode"] = None
        self.assertEqual(sync.csindex_valuations([fund], fetcher=lambda code: history()), [])


class DanjuanSegmentTests(unittest.TestCase):
    def test_danjuan_is_fetched_once_for_the_whole_pool(self):
        items = {
            "SH000905": {"index_code": "SH000905", "name": "中证500", "pe": 31.9,
                         "pe_percentile": 0.73, "ts": 1759219200000},
            "SH000016": {"index_code": "SH000016", "name": "上证50", "pe": 10.7,
                         "pe_percentile": 0.55, "ts": 1759219200000},
        }
        fetcher = Mock(return_value=items)
        funds = [
            danjuan_fund("510500", "SH000905", "SH000905"),
            danjuan_fund("510050", "SH000016", "SH000016"),
        ]
        with patch.object(report, "fetch_danjuan_items", fetcher):
            rows = sync.danjuan_valuations(funds)

        self.assertEqual(fetcher.call_count, 1)
        self.assertEqual(len(rows), 2)
        self.assertEqual({row["percentileMethod"] for row in rows}, {report.DANJUAN_PE_TTM_PROVIDER})

    def test_a_danjuan_fetch_failure_abandons_the_segment(self):
        funds = [danjuan_fund("510500", "SH000905", "SH000905")]
        with patch.object(report, "fetch_danjuan_items", Mock(side_effect=RuntimeError("超时"))):
            self.assertIsNone(sync.danjuan_valuations(funds))

    def test_an_index_missing_from_danjuan_is_skipped_not_invented(self):
        funds = [danjuan_fund("510500", "SH000905", "SH999999")]
        self.assertEqual(sync.danjuan_valuations(funds, items={"SH000016": {}}), [])


class SentinelTests(unittest.TestCase):
    def setUp(self):
        handle, path = tempfile.mkstemp()
        os.close(handle)
        os.unlink(path)
        self.path = path
        self.addCleanup(lambda: os.path.exists(self.path) and os.unlink(self.path))
        patcher = patch.object(sync, "MARKER_PATH", path)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_the_marker_is_written_once_then_read_back(self):
        self.assertFalse(sync.already_synced_today())
        sync.mark_synced()
        self.assertTrue(sync.already_synced_today())

    def test_a_missing_marker_file_is_not_an_error(self):
        self.assertFalse(sync.already_synced_today())

    def test_a_read_only_marker_does_not_break_the_run(self):
        with patch("builtins.open", side_effect=OSError("read-only")):
            sync.mark_synced()


class SyncIndexPoolTests(unittest.TestCase):
    def setUp(self):
        patcher = patch.dict(os.environ, BACKEND_ENV)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_sync(self, indices, csindex_rows, danjuan_rows=None, price_failures=None, **extra):
        patches = {
            "fetch_index_pool": Mock(return_value=indices),
            "csindex_valuations": Mock(return_value=csindex_rows),
            "danjuan_valuations": Mock(return_value=danjuan_rows or []),
            "push_valuation_batch": Mock(return_value=extra.get("push_ok", True)),
            "sync_pool_prices": Mock(return_value=price_failures or []),
            "already_synced_today": Mock(return_value=False),
            "mark_synced": Mock(),
        }
        mocks = {}
        for name, mock in patches.items():
            p = patch.object(sync, name, mock)
            p.start()
            self.addCleanup(p.stop)
            mocks[name] = mock
        result = sync.sync_index_pool(**extra.get("sync_kwargs", {}))
        return result, mocks

    def test_an_aborted_csindex_segment_writes_nothing_at_all(self):
        result, mocks = self.run_sync([csindex_fund("000300")], csindex_rows=None)

        self.assertFalse(result)
        mocks["push_valuation_batch"].assert_not_called()
        mocks["sync_pool_prices"].assert_not_called()
        mocks["mark_synced"].assert_not_called()

    def test_an_aborted_danjuan_segment_writes_nothing_at_all(self):
        result, mocks = self.run_sync([danjuan_fund("510500", "SH000905", "SH000905")],
                                      csindex_rows=[], danjuan_rows=None)

        self.assertFalse(result)
        mocks["push_valuation_batch"].assert_not_called()

    def test_nothing_to_write_is_a_failure_not_a_silent_success(self):
        result, mocks = self.run_sync([csindex_fund("000300")], csindex_rows=[])

        self.assertFalse(result)
        mocks["push_valuation_batch"].assert_not_called()

    def test_a_batch_write_failure_keeps_the_day_open_for_a_retry(self):
        result, mocks = self.run_sync([csindex_fund("000300")], csindex_rows=[{"indexCode": "SH000300"}],
                                      push_ok=False)

        self.assertFalse(result)
        mocks["mark_synced"].assert_not_called()

    def test_a_successful_valuation_run_writes_every_index_once(self):
        rows = [{"indexCode": "SH000300"}, {"indexCode": "SH000905"}]
        result, mocks = self.run_sync([csindex_fund("000300"), csindex_fund("000905")],
                                      csindex_rows=rows)

        self.assertTrue(result)
        written = mocks["push_valuation_batch"].call_args[0][0]
        codes = [row["indexCode"] for row in written]
        self.assertEqual(len(codes), len(set(codes)))
        mocks["mark_synced"].assert_called_once()

    def test_a_price_backfill_failure_still_leaves_the_marker_but_reports_failure(self):
        # 中证那 64MB 才是会被封的部分：日线没成不该让下一分钟再去跑一遍中证段
        result, mocks = self.run_sync([csindex_fund("000300", etf="510300")],
                                      csindex_rows=[{"indexCode": "SH000300"}],
                                      price_failures=["510300"])

        self.assertFalse(result)
        mocks["mark_synced"].assert_called_once()

    def test_the_marker_is_respected_by_default(self):
        with patch.object(sync, "already_synced_today", Mock(return_value=True)), \
                patch.object(sync, "fetch_index_pool", Mock()) as fetch:
            self.assertTrue(sync.sync_index_pool())
            fetch.assert_not_called()

    def test_force_ignores_the_marker(self):
        indices = [csindex_fund("000300", etf=None)]
        with patch.object(sync, "already_synced_today", Mock(return_value=True)), \
                patch.object(sync, "fetch_index_pool", Mock(return_value=indices)) as fetch, \
                patch.object(sync, "csindex_valuations", Mock(return_value=[{"indexCode": "SH000300"}])), \
                patch.object(sync, "danjuan_valuations", Mock(return_value=[])), \
                patch.object(sync, "push_valuation_batch", Mock(return_value=True)), \
                patch.object(sync, "sync_pool_prices", Mock(return_value=[])), \
                patch.object(sync, "mark_synced", Mock()):
            self.assertTrue(sync.sync_index_pool(force=True))
            fetch.assert_called_once()


class PriceBackfillTests(unittest.TestCase):
    def test_only_the_most_recent_bars_are_pushed(self):
        fund = csindex_fund("000300", etf="510300")
        pushed = {}

        def fake_push(etf, prices, quote, cached=None):
            pushed["prices"] = prices
            pushed["etf"] = etf
            return True

        with patch.object(report, "fetch_etf_daily_prices_from_tencent", Mock(return_value=bars(400))), \
                patch.object(report, "fetch_cached_etf_prices", Mock(return_value=[])), \
                patch.object(report, "push_etf_price_history", fake_push):
            failures = sync.sync_pool_prices([fund])

        self.assertEqual(failures, [])
        self.assertEqual(len(pushed["prices"]), sync.POOL_PRICE_BARS)
        self.assertEqual(pushed["prices"][-1]["date"], bars(400)[-1]["date"])

    def test_the_shanghai_shenzhen_prefix_comes_from_the_pool_market_field(self):
        self.assertEqual(sync.etf_identity(csindex_fund("000300", etf="510300"))["sina_code"], "sh510300")
        shenzhen = csindex_fund("000300", etf="159915")
        shenzhen["market"] = 0
        self.assertEqual(sync.etf_identity(shenzhen)["sina_code"], "sz159915")

    def test_an_index_without_an_etf_skips_the_price_step(self):
        with patch.object(report, "fetch_etf_daily_prices_from_tencent", Mock()) as fetch:
            self.assertEqual(sync.sync_pool_prices([csindex_fund("000300")]), [])
            fetch.assert_not_called()

    def test_a_failed_etf_is_reported_by_code(self):
        fund = csindex_fund("000300", etf="510300")
        with patch.object(report, "fetch_etf_daily_prices_from_tencent",
                          Mock(side_effect=RuntimeError("腾讯日线数量不足"))):
            self.assertEqual(sync.sync_pool_prices([fund]), ["510300"])


class NoArchiveTests(unittest.TestCase):
    def test_the_archive_is_never_touched_by_the_pool_sync(self):
        """归档是给叙事日报补历史基线的；池子只要最新一行，取它等于白打六次外网。"""
        archive = Mock(side_effect=AssertionError("指数池同步不该碰估值归档"))
        funds = [csindex_fund("000300"), danjuan_fund("510500", "SH000905", "SH000905")]
        with patch.object(report, "fetch_valuation_archive", archive), \
                patch.object(report, "fetch_danjuan_items", Mock(return_value={})):
            sync.csindex_valuations(funds, fetcher=lambda code: history())
            sync.danjuan_valuations(funds)

        archive.assert_not_called()

    def test_the_module_does_not_even_import_the_archive_helper(self):
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertNotIn("fetch_valuation_archive(", source)


if __name__ == "__main__":
    unittest.main()