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
    EastmoneyClient,
    parse_financial_payloads,
    parse_kline_line,
    parse_quote_row,
    quote_to_instrument,
    quote_trade_date,
    universe_list_query,
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

    def test_universe_list_query_keeps_filter_raw(self):
        """filter 不能百分号编码：服务端用 ANTLR 解析它且不做 URL 解码。"""
        url = universe_list_query(300, "TOTAL_MARKET_CAP", -1, "(TRADE_DATE='2025-09-30')")
        self.assertIn("filter=(TRADE_DATE='2025-09-30')", url)
        self.assertNotIn("%", url)
        self.assertIn("sortTypes=-1", url)

    def test_fetch_universe_uses_datacenter_list_then_batched_quotes(self):
        """清单走 datacenter-web，基本面走 ulist；北交所不进池子。"""
        http = FakeHttp({
            # 第一问：最新交易日。必须排在清单前面，否则同一个 URL 会先命中清单夹具。
            "columns=TRADE_DATE": {
                "result": {"data": [{"TRADE_DATE": "2025-09-30 00:00:00"}], "count": 1},
            },
            "reportName=RPT_VALUEANALYSIS_DET": {
                "result": {
                    "data": [
                        {"SECURITY_CODE": "600519", "SECUCODE": "600519.SH",
                         "SECURITY_NAME_ABBR": "贵州茅台", "TOTAL_MARKET_CAP": 1.5e12},
                        {"SECURITY_CODE": "300274", "SECUCODE": "300274.SZ",
                         "SECURITY_NAME_ABBR": "阳光电源", "TOTAL_MARKET_CAP": 1.0e11},
                        {"SECURITY_CODE": "000001", "SECUCODE": "000001.SZ",
                         "SECURITY_NAME_ABBR": "平安银行", "TOTAL_MARKET_CAP": 8.0e10},
                        {"SECURITY_CODE": "830001", "SECUCODE": "830001.BJ",
                         "SECURITY_NAME_ABBR": "北交标的", "TOTAL_MARKET_CAP": 5.0e9},
                    ],
                    "count": 4,
                },
            },
            "qt/ulist.np/get": load_json("eastmoney_universe.json"),
        })

        rows = client(http).fetch_universe(10)

        self.assertEqual([row["raw_code"] for row in rows], ["600519", "300274", "000001"])
        self.assertIn("columns=TRADE_DATE", http.calls[0][1])
        list_url = http.calls[1][1]
        self.assertIn("filter=(TRADE_DATE='2025-09-30')", list_url)
        self.assertNotIn("%28", list_url)
        # 北交所（830001）必须被剔掉，不能进 secids
        self.assertEqual(http.calls[2][2]["secids"], "1.600519,0.300274,0.000001")

    def test_fetch_universe_pages_when_server_truncates_the_page_size(self):
        """服务端把 pageSize=4 截成 2 条时，按实际条数继续翻，不能静默少扫。"""
        def list_row(code, secucode):
            return {"SECURITY_CODE": code, "SECUCODE": secucode,
                    "SECURITY_NAME_ABBR": code, "TOTAL_MARKET_CAP": 1.0}

        http = FakeHttp({
            "columns=TRADE_DATE": {
                "result": {"data": [{"TRADE_DATE": "2025-09-30 00:00:00"}], "count": 1},
            },
            # 第二页：同一个 reportName，靠 pageNumber 区分，所以必须排在它前面
            "pageNumber=2": {
                "result": {"data": [list_row("000001", "000001.SZ"),
                                    list_row("600000", "600000.SH")], "count": 4},
            },
            "reportName=RPT_VALUEANALYSIS_DET": {
                "result": {"data": [list_row("600519", "600519.SH"),
                                    list_row("300274", "300274.SZ")], "count": 4},
            },
            "qt/ulist.np/get": load_json("eastmoney_universe.json"),
        })

        client(http).fetch_universe(4)

        second_page_url = http.calls[2][1]
        self.assertIn("pageNumber=2", second_page_url)
        # 页大小用服务端实际返回的 2，而不是某个配置里的默认页大小
        self.assertIn("pageSize=2", second_page_url)
        self.assertEqual(http.calls[3][2]["secids"],
                         "1.600519,0.300274,0.000001,1.600000")


if __name__ == "__main__":
    unittest.main()
