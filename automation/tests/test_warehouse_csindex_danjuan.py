# -*- coding: utf-8 -*-
import unittest
from datetime import date

import warehouse_fixtures

warehouse_fixtures.ensure_path()

from warehouse.config import Config  # noqa: E402
from warehouse.rate_limit import SourceError  # noqa: E402
from warehouse.sources.csindex import (  # noqa: E402
    industry_code_for,
    parse_basic_info,
    parse_industry_weight,
    parse_pe_history,
    parse_top10,
)
from warehouse.sources.danjuan import (  # noqa: E402
    PERCENTILE_METHOD,
    DanjuanClient,
    parse_estimate_items,
)

FakeHttp = warehouse_fixtures.FakeHttp
load_json = warehouse_fixtures.load_json


class CsindexPeTest(unittest.TestCase):
    def test_peg_field_is_pe_ttm(self):
        rows = parse_pe_history(load_json("csindex_pe.json"))
        self.assertEqual([row["trade_date"] for row in rows],
                         [date(2025, 9, 29), date(2025, 9, 30), date(2099, 1, 1)])
        self.assertAlmostEqual(rows[1]["pe_ttm"], 12.6)

    def test_future_rows_are_filtered_when_today_given(self):
        rows = parse_pe_history(load_json("csindex_pe.json"), today=date(2025, 10, 1))
        self.assertEqual(len(rows), 2)

    def test_business_error(self):
        with self.assertRaises(SourceError):
            parse_pe_history({"code": 500, "success": False, "msg": "boom"})
        with self.assertRaises(SourceError):
            parse_pe_history({"code": 200, "success": True, "data": None})


class CsindexBasicTest(unittest.TestCase):
    def test_null_constituent_count_is_none(self):
        info = parse_basic_info(load_json("csindex_basic.json"))
        self.assertEqual(info["name"], "沪深300")
        self.assertEqual(info["full_name"], "沪深300指数")
        self.assertIsNone(info["constituent_count"])
        self.assertEqual(info["publish_date"], date(2005, 4, 8))


class CsindexTop10Test(unittest.TestCase):
    def test_rows_and_weights(self):
        parsed = parse_top10(load_json("csindex_top10.json"))
        self.assertEqual(parsed["as_of"], date(2025, 9, 30))
        self.assertAlmostEqual(parsed["top10_weight"], 22.95)
        self.assertEqual(len(parsed["rows"]), 2)
        self.assertEqual(parsed["rows"][0]["stock_code"], "600519")
        self.assertAlmostEqual(parsed["rows"][1]["weight"], 2.73)
        self.assertEqual(parsed["rows"][0]["industry_name"], "主要消费")

    def test_sum_is_computed_when_provider_omits_it(self):
        body = load_json("csindex_top10.json")
        body["data"].pop("top10Sum")
        parsed = parse_top10(body)
        self.assertAlmostEqual(parsed["top10_weight"], 3.51 + 2.73)


class CsindexIndustryTest(unittest.TestCase):
    def test_rows_sorted_and_named(self):
        parsed = parse_industry_weight(load_json("csindex_industry.json"))
        self.assertEqual(parsed["as_of"], date(2025, 9, 30))
        self.assertEqual([row["weight"] for row in parsed["rows"]], [21.86, 18.2, 5.0])
        self.assertEqual(parsed["rows"][0]["industry_code"], "cics1-financials")
        self.assertEqual(parsed["rows"][0]["industry_name"], "金融")

    def test_industry_code_is_stable_and_never_collides_with_eastmoney(self):
        self.assertEqual(industry_code_for("Financials", "金融"), "cics1-financials")
        self.assertEqual(industry_code_for("Financials", None), "cics1-financials")
        fallback = industry_code_for(None, "无英文")
        self.assertTrue(fallback.startswith("cics1-"))
        self.assertEqual(fallback, industry_code_for("", "无英文"))


class DanjuanTest(unittest.TestCase):
    def test_percentile_scale_is_normalized(self):
        rows = parse_estimate_items(load_json("danjuan_estimates.json"))
        self.assertEqual(len(rows), 2)
        self.assertAlmostEqual(rows[0]["pe_percentile"], 42.0)
        self.assertAlmostEqual(rows[1]["pe_percentile"], 37.5)
        self.assertIsNone(rows[1]["pe_ttm"])
        self.assertEqual(rows[0]["trade_date"], date(2025, 9, 30))
        self.assertEqual(rows[0]["percentile_method"], PERCENTILE_METHOD)

    def test_business_error(self):
        with self.assertRaises(SourceError):
            parse_estimate_items({"result_code": 500, "result_msg": "boom"})

    def test_single_request_is_cached_by_beijing_date(self):
        http = FakeHttp({"index_eva/dj": load_json("danjuan_estimates.json")})
        client = DanjuanClient(http, Config.from_env())
        first = client.fetch_estimates()
        second = client.fetch_estimates()
        self.assertEqual(len(http.calls), 1)
        self.assertIs(first, second)

    def test_empty_snapshot_is_an_error(self):
        http = FakeHttp({"index_eva/dj": {"result_code": 0, "data": {"items": []}}})
        with self.assertRaises(SourceError):
            DanjuanClient(http, Config.from_env()).fetch_estimates()


if __name__ == "__main__":
    unittest.main()
