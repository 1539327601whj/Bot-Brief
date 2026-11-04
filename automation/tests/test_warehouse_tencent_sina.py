# -*- coding: utf-8 -*-
import json
import unittest
from datetime import date

import warehouse_fixtures

warehouse_fixtures.ensure_path()

from warehouse.config import Config  # noqa: E402
from warehouse.sources.sina import SinaClient  # noqa: E402
from warehouse.sources.sina import parse_etf_catalog_row  # noqa: E402
from warehouse.sources.sina import parse_quote_text as parse_sina_text  # noqa: E402
from warehouse.sources.tencent import parse_kline_rows, parse_quote_text  # noqa: E402

FakeHttp = warehouse_fixtures.FakeHttp


def tencent_quote_line(symbol="sh600519", code="600519", name="贵州茅台",
                       overrides=None) -> str:
    """按实测下标拼一条腾讯行情：3 现价、4 昨收、5 今开、6 量、30 时间……"""
    parts = [""] * 50
    values = {
        0: "1",
        1: name,
        2: code,
        3: "1522.50",
        4: "1510.00",
        5: "1512.00",
        6: "32000",
        30: "20250930150000",
        31: "12.50",
        32: "0.83",
        33: "1530.00",
        34: "1505.00",
        37: "487000.00",
        38: "0.26",
        39: "22.50",
        43: "1.65",
        44: "1550.00",
        45: "1550.00",
        46: "7.80",
    }
    values.update(overrides or {})
    for index, value in values.items():
        parts[index] = value
    return f'v_{symbol}="{"~".join(parts)}";'


def sina_quote_line(**overrides) -> str:
    """0 名称、1 今开、2 昨收、3 现价、4 高、5 低、8 量(股)、9 额、30/31 日期时间。"""
    parts = [""] * 33
    values = {
        0: "贵州茅台",
        1: "1512.00",
        2: "1510.00",
        3: "1522.50",
        4: "1530.00",
        5: "1505.00",
        8: "3200000",
        9: "4870000000.000",
        30: "2025-09-30",
        31: "15:00:00",
    }
    values.update(overrides)
    for index, value in values.items():
        parts[index] = value
    return 'var hq_str_sh600519="' + ",".join(parts) + '";'


class TencentQuoteTest(unittest.TestCase):
    def test_quote_fields(self):
        rows = parse_quote_text(tencent_quote_line())
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(row["raw_code"], "600519")
        self.assertEqual(row["name"], "贵州茅台")
        self.assertAlmostEqual(row["price"], 1522.5)
        self.assertAlmostEqual(row["prev_close"], 1510.0)
        self.assertAlmostEqual(row["volume"], 32000.0)
        self.assertAlmostEqual(row["amount"], 4870000000.0)  # 万元 → 元
        self.assertAlmostEqual(row["pe_ttm"], 22.5)
        self.assertAlmostEqual(row["pb"], 7.8)
        self.assertAlmostEqual(row["total_market_cap"], 1.55e11)  # 1550 亿
        self.assertEqual(row["quote_time"].date(), date(2025, 9, 30))

    def test_missing_values_are_none(self):
        rows = parse_quote_text(tencent_quote_line(overrides={3: "-", 39: "-", 46: ""}))
        self.assertIsNone(rows[0]["price"])
        self.assertIsNone(rows[0]["pe_ttm"])
        self.assertIsNone(rows[0]["pb"])

    def test_short_payload_is_ignored(self):
        self.assertEqual(parse_quote_text('v_sh600519="1~贵州茅台~600519";'), [])

    def test_multiple_symbols(self):
        text = tencent_quote_line() + tencent_quote_line(
            symbol="sz300274", code="300274", name="阳光电源", overrides={3: "45.60"},
        )
        rows = parse_quote_text(text)
        self.assertEqual([row["raw_code"] for row in rows], ["600519", "300274"])
        self.assertAlmostEqual(rows[1]["price"], 45.6)


class TencentKlineTest(unittest.TestCase):
    def test_close_is_third_element(self):
        rows = parse_kline_rows([
            ["2025-09-30", "1510.00", "1522.50", "1530.00", "1505.00", "32000"],
        ])
        self.assertAlmostEqual(rows[0]["close_price"], 1522.5)
        self.assertAlmostEqual(rows[0]["open_price"], 1510.0)
        self.assertAlmostEqual(rows[0]["high_price"], 1530.0)
        self.assertAlmostEqual(rows[0]["low_price"], 1505.0)
        self.assertEqual(rows[0]["adj_type"], "QFQ")

    def test_invalid_rows_and_ohl(self):
        rows = parse_kline_rows([
            ["2025-09-30", "1510.00", "0", "1530.00", "1505.00", "1"],
            ["2025-09-30", "1510.00", "1522.50", "1500.00", "1530.00", "1"],
            ["bad-date", "1", "2", "3", "4", "5"],
        ])
        self.assertEqual(len(rows), 1)
        self.assertIsNone(rows[0]["high_price"])
        self.assertIsNone(rows[0]["low_price"])

    def test_sorted_by_date(self):
        rows = parse_kline_rows([
            ["2025-09-30", "1", "2", "3", "1", "1"],
            ["2025-09-29", "1", "2", "3", "1", "1"],
        ])
        self.assertEqual([row["trade_date"] for row in rows], [date(2025, 9, 29), date(2025, 9, 30)])


class SinaQuoteTest(unittest.TestCase):
    def test_volume_is_converted_from_shares_to_lots(self):
        rows = parse_sina_text(sina_quote_line())
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(row["raw_code"], "600519")
        self.assertAlmostEqual(row["price"], 1522.5)
        self.assertAlmostEqual(row["volume"], 32000.0)
        self.assertAlmostEqual(row["amount"], 4870000000.0)
        self.assertEqual(row["quote_time"].date(), date(2025, 9, 30))

    def test_short_payload_is_ignored(self):
        self.assertEqual(parse_sina_text('var hq_str_sh600519="贵州茅台,1,2";'), [])


class SinaEtfCatalogTest(unittest.TestCase):
    def test_row_carries_market_and_normalised_code(self):
        row = parse_etf_catalog_row(
            {"symbol": "sz159915", "code": "159915", "name": "创业板ETF"})
        self.assertEqual(row, {"raw_code": "159915", "name": "创业板ETF", "market": "SZ"})

    def test_rows_without_a_market_or_code_are_dropped(self):
        self.assertIsNone(parse_etf_catalog_row({"symbol": "bj510300", "name": "x"}))
        self.assertIsNone(parse_etf_catalog_row({"symbol": "shABC", "name": "x"}))
        self.assertIsNone(parse_etf_catalog_row({"symbol": "sh510300", "name": ""}))
        self.assertIsNone(parse_etf_catalog_row("not a row"))

    def test_fetch_pages_until_a_short_page(self):
        first_page = [
            {"symbol": f"sh{510000 + i}", "code": str(510000 + i), "name": f"ETF{i}"}
            for i in range(100)
        ]
        second_page = [
            {"symbol": "sz159915", "code": "159915", "name": "创业板ETF"},
            {"symbol": "sh588000", "code": "588000", "name": "科创50ETF"},
        ]
        pages = [first_page, second_page]

        def next_page():
            return json.dumps(pages.pop(0))

        http = FakeHttp(text_by_url={"Market_Center.getHQNodeData": next_page})
        client = SinaClient(http, Config.from_env())
        rows = client.fetch_etf_catalog()

        self.assertEqual(len(rows), 102)
        self.assertEqual(len(http.calls), 2)
        self.assertEqual(rows[-1]["raw_code"], "588000")
        self.assertEqual(rows[-1]["market"], "SH")

    def test_duplicate_codes_across_pages_are_kept_once(self):
        first_page = [
            {"symbol": f"sh{510000 + i}", "code": str(510000 + i), "name": f"ETF{i}"}
            for i in range(100)
        ]
        pages = [
            first_page,
            [{"symbol": "sh510000", "code": "510000", "name": "重复ETF"}],
        ]

        def next_page():
            return json.dumps(pages.pop(0))

        http = FakeHttp(text_by_url={"Market_Center.getHQNodeData": next_page})
        rows = SinaClient(http, Config.from_env()).fetch_etf_catalog()
        self.assertEqual(len(rows), 100)
        self.assertEqual(len(http.calls), 2)

    def test_non_list_payload_is_a_contract_error(self):
        http = FakeHttp(text_by_url={
            "Market_Center.getHQNodeData": lambda: '{"datas":[],"pageIndex":"1"}',
        })
        with self.assertRaises(ValueError):
            SinaClient(http, Config.from_env()).fetch_etf_catalog()


if __name__ == "__main__":
    unittest.main()
