# -*- coding: utf-8 -*-
import unittest
from datetime import date

import warehouse_fixtures

warehouse_fixtures.ensure_path()

from warehouse.config import Config  # noqa: E402
from warehouse.sources.eastmoney import (  # noqa: E402
    KLINE_URL,
    MONEYFLOW_URL,
    QUOTE_URL,
    UNIVERSE_FIELDS,
    EastmoneyClient,
    parse_financial_payloads,
    parse_kline_line,
    parse_quote_row,
    quote_to_instrument,
    quote_trade_date,
)

FakeHttp = warehouse_fixtures.FakeHttp
load_json = warehouse_fixtures.load_json


def client(http) -> EastmoneyClient:
    return EastmoneyClient(http, Config.from_env())


class QuoteParseTest(unittest.TestCase):
    def test_quote_row(self):
        row = load_json("eastmoney_quote.json")["data"]["diff"][0]
        parsed = parse_quote_row(row)
        self.assertEqual(parsed["raw_code"], "600519")
        self.assertEqual(parsed["name"], "贵州茅台")
        self.assertAlmostEqual(parsed["price"], 12.34)
        self.assertAlmostEqual(parsed["pe_ttm"], 22.5)
        self.assertAlmostEqual(parsed["pb"], 7.8)
        self.assertAlmostEqual(parsed["dividend_yield"], 2.15)
        self.assertEqual(parsed["industry"], "酿酒行业")
        self.assertEqual(parsed["quote_time"].date(), date(2025, 9, 30))

    def test_missing_markers_are_none(self):
        row = load_json("eastmoney_quote.json")["data"]["diff"][1]
        parsed = parse_quote_row(row)
        self.assertIsNone(parsed["price"])
        self.assertIsNone(parsed["pe_ttm"])
        self.assertIsNone(parsed["pb"])

    def test_row_without_code_is_dropped(self):
        self.assertIsNone(parse_quote_row({"f12": "-", "f14": "无代码"}))

    def test_quote_trade_date(self):
        quotes = [parse_quote_row(row) for row in load_json("eastmoney_quote.json")["data"]["diff"]]
        self.assertEqual(quote_trade_date(quotes), date(2025, 9, 30))
        self.assertIsNone(quote_trade_date([{"quote_time": None}]))

    def test_quote_to_instrument(self):
        parsed = parse_quote_row(load_json("eastmoney_quote.json")["data"]["diff"][0])
        instrument = quote_to_instrument(parsed)
        self.assertEqual(instrument["instrument_code"], "STOCK:600519")
        self.assertEqual(instrument["market"], "SH")
        self.assertEqual(instrument["industry"], "酿酒行业")
        self.assertEqual(instrument["asset_type"], "STOCK")


class KlineParseTest(unittest.TestCase):
    def test_valid_line(self):
        parsed = parse_kline_line(
            "2025-09-30,1510.00,1522.50,1530.00,1505.00,32000,4870000000.00,1.65,0.83,12.50,0.26"
        )
        self.assertEqual(parsed["trade_date"], date(2025, 9, 30))
        self.assertAlmostEqual(parsed["close_price"], 1522.5)
        self.assertAlmostEqual(parsed["amount"], 4870000000.0)
        self.assertAlmostEqual(parsed["pct_change"], 0.83)
        self.assertAlmostEqual(parsed["turnover_rate"], 0.26)

    def test_zero_ohl_is_nulled_but_close_kept(self):
        parsed = parse_kline_line(
            "2025-10-08,0.00,1515.00,0.00,0.00,1000,15000000.00,0.00,0.00,0.00,0.01"
        )
        self.assertAlmostEqual(parsed["close_price"], 1515.0)
        self.assertIsNone(parsed["open_price"])
        self.assertIsNone(parsed["high_price"])
        self.assertIsNone(parsed["low_price"])

    def test_broken_ohl_relation_is_nulled(self):
        parsed = parse_kline_line(
            "2025-10-09,1520.00,1510.00,1500.00,1530.00,100,1.0,0,0,0,0"
        )
        # high < close、low > close 都不成立，置空；收盘价仍然有效。
        self.assertIsNone(parsed["high_price"])
        self.assertIsNone(parsed["low_price"])
        self.assertAlmostEqual(parsed["close_price"], 1510.0)

    def test_short_line_and_bad_close(self):
        self.assertIsNone(parse_kline_line("2025-10-09,1,2"))
        self.assertIsNone(parse_kline_line("2025-10-09,,,0.00,0.00,0,0,0,0,0,0"))


class FinancialParseTest(unittest.TestCase):
    def test_four_reports_are_merged(self):
        rows = parse_financial_payloads(load_json("eastmoney_financial.json"))
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(row["report_date"], date(2025, 6, 30))
        self.assertEqual(row["report_type"], "H1")
        self.assertEqual(row["announce_date"], date(2025, 8, 20))
        self.assertEqual(row["available_from"], date(2025, 8, 20))
        self.assertAlmostEqual(row["revenue"], 500.0)
        self.assertAlmostEqual(row["revenue_yoy"], 12.5)
        self.assertAlmostEqual(row["net_profit"], 80.0)
        self.assertAlmostEqual(row["net_profit_yoy"], 9.9)
        self.assertAlmostEqual(row["roe"], 15.2)
        self.assertAlmostEqual(row["gross_margin"], 40.0)
        self.assertAlmostEqual(row["net_margin"], 16.0)
        self.assertAlmostEqual(row["ocf"], 70.0)
        self.assertAlmostEqual(row["total_assets"], 1000.0)
        self.assertAlmostEqual(row["total_equity"], 600.0)
        self.assertAlmostEqual(row["total_liabilities"], 400.0)
        self.assertAlmostEqual(row["interest_expense"], 5.0)
        self.assertAlmostEqual(row["ebit"], 105.0)
        self.assertAlmostEqual(row["accounts_receivable"], 50.0)
        self.assertIsNone(row["goodwill"])
        self.assertIsNone(row["current_assets"])

    def test_net_margin_is_computed_only_when_absent(self):
        payloads = {
            "RPT_DMSK_FN_INCOME": {
                "result": {
                    "data": [{
                        "REPORT_DATE": "2025-06-30",
                        "TOTAL_OPERATE_INCOME": 100.0,
                        "PARENT_NETPROFIT": 10.0,
                    }]
                }
            }
        }
        row = parse_financial_payloads(payloads)[0]
        self.assertAlmostEqual(row["net_margin"], 10.0)

    def test_missing_report_does_not_drop_others(self):
        payloads = load_json("eastmoney_financial.json")
        payloads.pop("RPT_DMSK_FN_BALANCE")
        row = parse_financial_payloads(payloads)[0]
        self.assertAlmostEqual(row["revenue"], 500.0)
        # 总资产还能从主要指标报表补到；负债只有资产负债表有。
        self.assertAlmostEqual(row["total_assets"], 1000.0)
        self.assertIsNone(row["total_liabilities"])

    def test_non_report_dates_are_skipped(self):
        payloads = {
            "RPT_DMSK_FN_BALANCE": {
                "result": {"data": [{"REPORT_DATE": "2025-05-20", "TOTAL_ASSETS": 1.0}]}
            }
        }
        self.assertEqual(parse_financial_payloads(payloads), [])


class ClientTest(unittest.TestCase):
    def test_fetch_quotes_handles_diff_dict(self):
        http = FakeHttp({QUOTE_URL: load_json("eastmoney_universe.json")})
        quotes = client(http).fetch_quotes(["1.600519", "0.300274", "0.000001"])
        self.assertEqual(len(quotes), 3)
        self.assertEqual(quotes[0]["raw_code"], "600519")

    def test_fetch_quotes_without_data_is_empty(self):
        http = FakeHttp({QUOTE_URL: {"rc": 0, "data": None}})
        self.assertEqual(client(http).fetch_quotes(["1.600519"]), [])

    def test_fetch_kline_filters_bad_rows(self):
        http = FakeHttp({KLINE_URL: load_json("eastmoney_kline.json")})
        rows = client(http).fetch_kline("STOCK", "600519", "SH")
        self.assertEqual([row["trade_date"] for row in rows], [
            date(2025, 9, 29), date(2025, 9, 30), date(2025, 10, 8),
        ])
        self.assertEqual(rows[0]["adj_type"], "QFQ")

    def test_fetch_kline_without_data_is_empty(self):
        http = FakeHttp({KLINE_URL: {"data": None}})
        self.assertEqual(client(http).fetch_kline("STOCK", "600519"), [])

    def test_fetch_moneyflow(self):
        http = FakeHttp({MONEYFLOW_URL: load_json("eastmoney_moneyflow.json")})
        rows = client(http).fetch_moneyflow("600519")
        self.assertEqual(len(rows), 2)
        self.assertAlmostEqual(rows[0]["main_net"], 1200000.0)
        self.assertAlmostEqual(rows[1]["main_ratio"], -1.7)

    def test_fetch_universe_passes_expected_fields(self):
        http = FakeHttp({"qt/clist/get": load_json("eastmoney_universe.json")})
        rows = client(http).fetch_universe(10)
        self.assertEqual(len(rows), 3)
        _, _, params = http.calls[0]
        self.assertEqual(params["fields"], UNIVERSE_FIELDS)


if __name__ == "__main__":
    unittest.main()
