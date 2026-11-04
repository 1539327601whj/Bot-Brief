# -*- coding: utf-8 -*-
import json
import tempfile
import unittest
from datetime import date, datetime
from pathlib import Path
from unittest.mock import patch

import warehouse_fixtures

warehouse_fixtures.ensure_path()

from warehouse.config import Config  # noqa: E402
from warehouse.rate_limit import RateLimited, SourceUnavailable  # noqa: E402
from warehouse.sources.eastmoney import parse_quote_row  # noqa: E402
from warehouse.sources.news import SOURCE_NEWS  # noqa: E402
from warehouse.sync import SyncOutcome  # noqa: E402
from warehouse.sync.events import sync_events  # noqa: E402
from warehouse.sync.etf_catalog import etf_rows, sync_etf_catalog  # noqa: E402
from warehouse.sync.index_profile import sync_csindex_detail  # noqa: E402
from warehouse.sync.instruments import (  # noqa: E402
    _pool_items,
    index_market,
    load_index_pool,
    pool_rows,
)
from warehouse.sync.prices import sync_price_series  # noqa: E402
from warehouse.sync.snapshot import sync_market_snapshot  # noqa: E402
from warehouse.sync.stock import sync_stock_detail  # noqa: E402
from warehouse.sync.stock_catalog import sync_stock_catalog  # noqa: E402

FakeDb = warehouse_fixtures.FakeDb
load_json = warehouse_fixtures.load_json
STAMP = datetime(2025, 10, 1, 18, 0)


class FakeEast:
    def __init__(self, rows=None, error=None, quotes=None):
        self.rows = rows or []
        self.error = error
        self.quotes = quotes or []
        self.kline_calls = 0
        self.quote_calls = 0

    def fetch_kline(self, asset_type, raw_code, market=None, *, limit=None, adj_type="QFQ"):
        self.kline_calls += 1
        if self.error:
            raise self.error
        return self.rows

    def fetch_universe(self, max_count=None):
        return self.quotes

    def fetch_quotes(self, secids):
        self.quote_calls += 1
        return self.quotes


class FakeTencent:
    def __init__(self, rows=None, error=None):
        self.rows = rows or []
        self.error = error
        self.calls = 0

    def fetch_kline(self, raw_code, market=None, *, limit=None, adj_type="QFQ"):
        self.calls += 1
        if self.error:
            raise self.error
        return self.rows


def kline_row(day=date(2025, 9, 30), close=1522.5):
    return {
        "trade_date": day,
        "adj_type": "QFQ",
        "open_price": 1510.0,
        "high_price": 1530.0,
        "low_price": 1505.0,
        "close_price": close,
        "volume": 32000.0,
        "amount": 4870000000.0,
        "turnover_rate": 0.26,
        "pct_change": 0.83,
    }


class PricesTest(unittest.TestCase):
    def run_sync(self, *, sources=None, east=None, tencent=None):
        db = FakeDb(sources=sources)
        east = east or FakeEast(rows=[kline_row()])
        tencent = tencent or FakeTencent(rows=[kline_row()])
        outcome = sync_price_series(
            db, east, tencent,
            instrument_code="STOCK:600519", asset_type="STOCK",
            raw_code="600519", market="SH", fetched_at=STAMP,
        )
        return outcome, db, east, tencent

    def test_new_series_prefers_eastmoney(self):
        outcome, db, east, tencent = self.run_sync()
        self.assertEqual(outcome.status, "OK")
        self.assertEqual(outcome.source, "eastmoney-kline")
        self.assertEqual(tencent.calls, 0)
        rows = db.fetch_upsert("md_price_daily")[0]["rows"]
        self.assertEqual(rows[0]["source"], "eastmoney-kline")
        self.assertEqual(rows[0]["instrument_code"], "STOCK:600519")
        self.assertEqual(rows[0]["trade_date"], date(2025, 9, 30))

    def test_rate_limited_does_not_fall_back_to_tencent(self):
        east = FakeEast(error=RateLimited("eastmoney:batch"))
        with self.assertRaises(RateLimited):
            self.run_sync(east=east)
        # 没有换成腾讯再发一次：降级会把缺字段的序列永久钉死。
        # run_sync 的 tencent 对象不可见，这里单独构造验证。
        db = FakeDb()
        tencent = FakeTencent(rows=[kline_row()])
        with self.assertRaises(RateLimited):
            sync_price_series(
                db, FakeEast(error=RateLimited("p")), tencent,
                instrument_code="STOCK:600519", asset_type="STOCK", raw_code="600519",
                fetched_at=STAMP,
            )
        self.assertEqual(tencent.calls, 0)
        self.assertEqual(db.upserts, [])

    def test_eastmoney_error_falls_back_to_tencent(self):
        _, db, _, tencent = self.run_sync(
            east=FakeEast(error=SourceUnavailable("down")),
            tencent=FakeTencent(rows=[kline_row()]),
        )
        self.assertEqual(tencent.calls, 1)
        self.assertEqual(db.fetch_upsert("md_price_daily")[0]["rows"][0]["source"],
                         "tencent-kline")

    def test_existing_tencent_series_is_not_replaced(self):
        outcome, db, east, tencent = self.run_sync(sources=["tencent-kline"])
        self.assertEqual(east.kline_calls, 0)
        self.assertEqual(tencent.calls, 1)
        self.assertEqual(outcome.source, "tencent-kline")

    def test_existing_eastmoney_series_is_not_replaced(self):
        _, _, east, tencent = self.run_sync(sources=["eastmoney-kline"])
        self.assertEqual(east.kline_calls, 1)
        self.assertEqual(tencent.calls, 0)

    def test_rows_without_close_are_dropped(self):
        db = FakeDb()
        outcome = sync_price_series(
            db, FakeEast(rows=[kline_row(close=None), kline_row(close=0)]), FakeTencent(),
            instrument_code="STOCK:600519", asset_type="STOCK", raw_code="600519",
            fetched_at=STAMP,
        )
        self.assertEqual(outcome.status, "EMPTY")
        self.assertEqual(db.upserts, [])


class SnapshotTest(unittest.TestCase):
    def quotes(self):
        rows = load_json("eastmoney_quote.json")["data"]["diff"]
        return [parse_quote_row(row) for row in rows]

    def test_snapshot_writes_instruments_and_valuations(self):
        db = FakeDb()
        outcome = sync_market_snapshot(db, FakeEast(quotes=self.quotes()), fetched_at=STAMP)
        self.assertEqual(outcome.status, "OK")
        self.assertEqual(outcome.detail["tradeDate"], "2025-09-30")
        valuations = db.fetch_upsert("md_valuation_daily")[0]["rows"]
        # 夹具里第一只有完整估值，第二、三只的价格/PE 都是 "-"，不写估值行。
        self.assertEqual(len(valuations), 1)
        self.assertEqual(valuations[0]["trade_date"], date(2025, 9, 30))
        self.assertEqual(valuations[0]["source"], "eastmoney-quote-snapshot")
        self.assertEqual(valuations[0]["instrument_code"], "STOCK:600519")
        self.assertEqual(db.fetch_upsert("md_instrument")[0]["rows"][0]["industry"], "酿酒行业")

    def test_snapshot_without_timestamps_fails(self):
        quotes = self.quotes()
        for quote in quotes:
            quote["quote_time"] = None
        db = FakeDb()
        outcome = sync_market_snapshot(db, FakeEast(quotes=quotes), fetched_at=STAMP)
        self.assertEqual(outcome.status, "FAILED")
        self.assertEqual(db.upserts, [])

    def test_empty_universe(self):
        db = FakeDb()
        outcome = sync_market_snapshot(db, FakeEast(quotes=[]), fetched_at=STAMP)
        self.assertEqual(outcome.status, "EMPTY")


class FakeNews:
    def __init__(self, news=None, notice_error=None, policy=None, notice=None):
        self.news = news or []
        self.notice = notice or []
        self.policy = policy or []
        self.notice_error = notice_error
        self.calls = []

    def search_news(self, keyword):
        self.calls.append(("news", keyword))
        return self.news

    def fetch_notices(self, stock_code):
        self.calls.append(("notice", stock_code))
        if self.notice_error:
            raise self.notice_error
        return self.notice

    def search_policy(self, subject):
        self.calls.append(("policy", subject))
        return self.policy


def news_item(title="贵州茅台发布公告", url="https://example.com/1"):
    return {
        "category": "NEWS",
        "event_type": None,
        "title": title,
        "summary": None,
        "url": url,
        "published_at": datetime(2025, 9, 30, 10, 0),
        "source": SOURCE_NEWS,
        "media": "证券时报",
    }


class EventsTest(unittest.TestCase):
    def test_one_route_failure_does_not_drop_other_events(self):
        db = FakeDb()
        news = FakeNews(news=[news_item()], notice_error=RuntimeError("notice down"))
        outcome = sync_events(
            db, news, instrument_code="STOCK:600519", keyword="贵州茅台",
            stock_code="600519", policy_subject="酿酒行业", fetched_at=STAMP,
        )
        self.assertEqual(outcome.status, "FAILED")  # 公告段失败
        self.assertEqual(outcome.detail["news"]["status"], "OK")
        self.assertEqual(outcome.detail["policy"]["status"], "EMPTY")
        self.assertEqual(len(db.fetch_upsert("md_event")[0]["rows"]), 1)

    def test_rate_limit_is_raised_after_writing_successes(self):
        db = FakeDb()
        news = FakeNews(
            news=[news_item()],
            notice_error=RateLimited("eastmoney-notice"),
            policy=[news_item(title="政策", url="https://example.com/2")],
        )
        with self.assertRaises(RateLimited):
            sync_events(
                db, news, instrument_code="STOCK:600519", keyword="贵州茅台",
                stock_code="600519", policy_subject="酿酒行业", fetched_at=STAMP,
            )
        self.assertEqual(len(db.fetch_upsert("md_event")[0]["rows"]), 2)

    def test_duplicate_url_title_is_written_once(self):
        db = FakeDb()
        item = news_item()
        outcome = sync_events(
            db, FakeNews(news=[item, dict(item)]),
            instrument_code="STOCK:600519", keyword="贵州茅台", fetched_at=STAMP,
        )
        self.assertEqual(outcome.rows, 1)
        self.assertEqual(outcome.detail["news"]["rows"], 2)

    def test_no_routes_means_no_write(self):
        db = FakeDb()
        outcome = sync_events(db, FakeNews(), instrument_code=None, fetched_at=STAMP)
        self.assertEqual(outcome.status, "SKIPPED")
        self.assertEqual(db.upserts, [])


class StockDetailTest(unittest.TestCase):
    def test_section_isolation(self):
        db = FakeDb()
        east = FakeEast(quotes=[])
        with patch(
            "warehouse.sync.stock.sync_stock_financials",
            side_effect=RuntimeError("financials down"),
        ), patch(
            "warehouse.sync.stock.sync_stock_moneyflow",
            return_value=SyncOutcome("OK", rows=3),
        ), patch(
            "warehouse.sync.stock.sync_events",
            return_value=SyncOutcome("OK", rows=1),
        ):
            outcome = sync_stock_detail(
                db, east, FakeNews(), "600519", fetched_at=STAMP,
            )
        self.assertEqual(outcome.status, "FAILED")
        self.assertEqual(outcome.detail["financials"]["status"], "FAILED")
        self.assertEqual(outcome.detail["moneyflow"]["status"], "OK")
        self.assertEqual(outcome.rows, 4)

    def test_rate_limit_is_reraised_after_other_sections(self):
        db = FakeDb()
        with patch(
            "warehouse.sync.stock.sync_stock_financials",
            side_effect=RateLimited("eastmoney:datacenter-web"),
        ), patch(
            "warehouse.sync.stock.sync_stock_moneyflow",
            return_value=SyncOutcome("OK", rows=3),
        ), patch(
            "warehouse.sync.stock.sync_events",
            return_value=SyncOutcome("OK", rows=1),
        ):
            with self.assertRaises(RateLimited):
                sync_stock_detail(
                    db, FakeEast(quotes=[]), FakeNews(), "600519", fetched_at=STAMP,
                )

    def test_quote_empty_is_not_a_failure(self):
        db = FakeDb()
        with patch(
            "warehouse.sync.stock.sync_stock_financials",
            return_value=SyncOutcome("OK", rows=1),
        ), patch(
            "warehouse.sync.stock.sync_stock_moneyflow",
            return_value=SyncOutcome("OK", rows=1),
        ), patch(
            "warehouse.sync.stock.sync_events",
            return_value=SyncOutcome("OK", rows=1),
        ):
            outcome = sync_stock_detail(db, FakeEast(quotes=[]), FakeNews(), "600519",
                                        fetched_at=STAMP)
        # 没有任何 FAILED；EMPTY 是「行情没返回这只票」，属于正常结果。
        self.assertEqual(outcome.status, "EMPTY")
        self.assertEqual(outcome.detail["instrument"]["status"], "EMPTY")


class InstrumentsTest(unittest.TestCase):
    def pool(self):
        return [{
            "indexCode": "SH000300",
            "indexName": "沪深300",
            "csindexCode": "000300",
            "etfCode": "510300",
            "market": 1,
            "etfName": "沪深300ETF",
        }]

    def test_pool_rows_link_etf_to_index(self):
        rows = {row["instrument_code"]: row for row in pool_rows(self.pool(), STAMP)}
        self.assertIn("INDEX:SH000300", rows)
        self.assertIn("ETF:510300", rows)
        self.assertEqual(rows["ETF:510300"]["tracking_index_code"], "INDEX:SH000300")
        self.assertEqual(rows["INDEX:SH000300"]["market"], "SH")
        self.assertEqual(rows["ETF:510300"]["market"], "SH")
        self.assertEqual(rows["INDEX:SH000300"]["source"], "index-pool")

    def test_index_market(self):
        self.assertEqual(index_market("SH000300"), "SH")
        self.assertEqual(index_market("SZ399006"), "SZ")
        self.assertEqual(index_market("CSI930901"), "CSI")
        self.assertEqual(index_market("NDX"), "OFFSHORE")

    def test_load_index_pool_from_local_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pool.json"
            path.write_text(json.dumps({"indices": self.pool()}, ensure_ascii=False),
                            encoding="utf-8")
            config = Config.from_env().with_pool_path(path)
            pool = load_index_pool(config)
        self.assertEqual(pool[0]["indexCode"], "SH000300")

    def test_empty_pool_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pool.json"
            path.write_text(json.dumps({"indices": []}), encoding="utf-8")
            config = Config.from_env().with_pool_path(path)
            with self.assertRaises(RuntimeError):
                load_index_pool(config)

    def test_backend_result_wrapper_is_accepted(self):
        # 后端 /api/index-pool 走统一 Result 包装：{"code":200,"data":[...]}。
        payload = {"code": 200, "message": "success", "data": self.pool()}
        self.assertEqual(_pool_items(payload, "test")[0]["indexCode"], "SH000300")

    def test_backend_error_is_reported_with_code_and_message(self):
        payload = {"code": 401, "message": "查询 token 无效", "data": None}
        with self.assertRaises(RuntimeError) as ctx:
            _pool_items(payload, "backend:/api/index-pool")
        self.assertIn("401", str(ctx.exception))
        self.assertIn("查询 token 无效", str(ctx.exception))


class EtfCatalogTest(unittest.TestCase):
    class FakeSina:
        def __init__(self, items=None, error=None):
            self.items = items or []
            self.error = error

        def fetch_etf_catalog(self):
            if self.error is not None:
                raise self.error
            return list(self.items)

    def test_etf_rows_keep_identity_only(self):
        rows = etf_rows([
            {"raw_code": "510300", "name": "沪深300ETF", "market": "SH"},
            {"raw_code": "159915", "name": "创业板ETF", "market": "SZ"},
        ], STAMP)
        self.assertEqual([row["instrument_code"] for row in rows],
                         ["ETF:510300", "ETF:159915"])
        self.assertEqual(rows[0]["asset_type"], "ETF")
        self.assertEqual(rows[0]["source"], "sina-etf-list")
        self.assertIsNone(rows[0]["tracking_index_code"])
        self.assertIsNone(rows[0]["aliases"])

    def test_sync_only_adds_unknown_codes(self):
        db = FakeDb(query_results=[[{"raw_code": "510300"}]])
        sina = self.FakeSina([
            {"raw_code": "510300", "name": "沪深300ETF", "market": "SH"},
            {"raw_code": "588000", "name": "科创50ETF", "market": "SH"},
        ])

        outcome = sync_etf_catalog(db, sina, fetched_at=STAMP)

        self.assertEqual(outcome.status, "OK")
        self.assertEqual(outcome.rows, 1)
        self.assertEqual(outcome.detail["fetched"], 2)
        self.assertEqual(outcome.detail["known"], 1)
        rows = db.fetch_upsert("md_instrument")[0]["rows"]
        self.assertEqual([row["raw_code"] for row in rows], ["588000"])

    def test_empty_catalog_is_not_reported_as_success(self):
        outcome = sync_etf_catalog(FakeDb(), self.FakeSina([]), fetched_at=STAMP)
        self.assertEqual(outcome.status, "EMPTY")
        self.assertIn("为空", outcome.error)


class StockCatalogTest(unittest.TestCase):
    class FakeEast:
        def __init__(self, items=None, error=None):
            self.items = items or []
            self.error = error

        def fetch_stock_catalog(self):
            if self.error is not None:
                raise self.error
            return list(self.items)

    def test_sync_only_adds_unknown_codes(self):
        db = FakeDb(query_results=[[{"raw_code": "600519"}]])
        east = self.FakeEast([
            {"raw_code": "600519", "name": "贵州茅台", "market": "SH"},
            {"raw_code": "830799", "name": "艾融软件", "market": "BJ"},
            {"raw_code": "002050", "name": "三花智控", "market": "SZ"},
        ])

        outcome = sync_stock_catalog(db, east, fetched_at=STAMP)

        self.assertEqual(outcome.status, "OK")
        self.assertEqual(outcome.rows, 1)
        self.assertEqual(outcome.detail["new"], 1)
        rows = db.fetch_upsert("md_instrument")[0]["rows"]
        self.assertEqual([row["raw_code"] for row in rows], ["002050"])
        self.assertEqual(rows[0]["instrument_code"], "STOCK:002050")

    def test_empty_catalog_is_not_reported_as_success(self):
        outcome = sync_stock_catalog(FakeDb(), self.FakeEast([]), fetched_at=STAMP)
        self.assertEqual(outcome.status, "EMPTY")


class IndexProfileRateLimitTest(unittest.TestCase):
    class LimitedCsindex:
        def fetch_basic_info(self, code):
            raise RateLimited("csindex", 0, "HTTP 403")

        def fetch_top10(self, code):
            raise AssertionError("限流后不应继续打同一个源")

        def fetch_industry_weight(self, code):
            raise AssertionError("限流后不应继续打同一个源")

    def test_rate_limit_is_not_swallowed_by_section_isolation(self):
        with self.assertRaises(RateLimited):
            sync_csindex_detail(
                FakeDb(), self.LimitedCsindex(), "SZ399975", "399975",
            )


if __name__ == "__main__":
    unittest.main()
