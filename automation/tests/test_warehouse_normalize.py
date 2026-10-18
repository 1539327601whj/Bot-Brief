# -*- coding: utf-8 -*-
import unittest
from datetime import date, datetime

import warehouse_fixtures

warehouse_fixtures.ensure_path()

from warehouse.normalize import (  # noqa: E402
    asset_instrument_code,
    clean_text,
    eastmoney_secid,
    guess_market,
    parse_date,
    parse_datetime,
    report_type_of,
    tencent_symbol,
    to_number,
)


class ToNumberTest(unittest.TestCase):
    def test_missing_markers_become_none(self):
        for value in (None, "", "-", "--", "—", "None", "nan", "N/A"):
            self.assertIsNone(to_number(value), value)

    def test_boolean_is_not_a_number(self):
        self.assertIsNone(to_number(True))
        self.assertIsNone(to_number(False))

    def test_percent_and_thousands_separators(self):
        self.assertAlmostEqual(to_number("12.5%"), 12.5)
        self.assertAlmostEqual(to_number("1,234.50"), 1234.5)
        # 全角负号不是数字，识别不了就当缺失，不要猜成正数。
        self.assertIsNone(to_number("－3.2"))

    def test_nan_and_inf_become_none(self):
        self.assertIsNone(to_number(float("nan")))
        self.assertIsNone(to_number(float("inf")))


class CleanTextTest(unittest.TestCase):
    def test_missing_markers(self):
        self.assertIsNone(clean_text(" "))
        self.assertIsNone(clean_text("—"))
        self.assertEqual(clean_text("  贵州茅台 "), "贵州茅台")


class ParseDateTest(unittest.TestCase):
    def test_supported_formats(self):
        self.assertEqual(parse_date("2025-09-30"), date(2025, 9, 30))
        self.assertEqual(parse_date("20250930"), date(2025, 9, 30))
        self.assertEqual(parse_date("2025/09/30"), date(2025, 9, 30))
        self.assertEqual(parse_date("2025-09-30 15:00:00"), date(2025, 9, 30))
        self.assertEqual(parse_date(datetime(2025, 9, 30, 15, 0)), date(2025, 9, 30))

    def test_epoch_seconds_and_milliseconds(self):
        self.assertEqual(parse_date(1759215600), date(2025, 9, 30))
        self.assertEqual(parse_date(1759215600000), date(2025, 9, 30))

    def test_invalid(self):
        self.assertIsNone(parse_date("2025-13-40"))
        self.assertIsNone(parse_date("-"))


class ParseDateTimeTest(unittest.TestCase):
    def test_iso_with_timezone_is_converted_to_beijing_naive(self):
        parsed = parse_datetime("2025-09-30T07:00:00Z")
        self.assertEqual(parsed, datetime(2025, 9, 30, 15, 0, 0))
        self.assertIsNone(parsed.tzinfo)


class MarketTest(unittest.TestCase):
    def test_guess_market(self):
        self.assertEqual(guess_market("600519", "STOCK"), "SH")
        self.assertEqual(guess_market("300274", "STOCK"), "SZ")
        self.assertEqual(guess_market("830799", "STOCK"), "BJ")
        self.assertEqual(guess_market("510300", "ETF"), "SH")
        self.assertEqual(guess_market("159915", "ETF"), "SZ")
        self.assertEqual(guess_market("399006", "INDEX"), "SZ")
        self.assertEqual(guess_market("930901", "INDEX"), "CSI")
        self.assertEqual(guess_market("SH000300", "INDEX"), "SH")

    def test_eastmoney_secid(self):
        self.assertEqual(eastmoney_secid("STOCK", "600519"), "1.600519")
        self.assertEqual(eastmoney_secid("STOCK", "300274"), "0.300274")
        self.assertEqual(eastmoney_secid("INDEX", "000300"), "1.000300")
        self.assertEqual(eastmoney_secid("INDEX", "399006"), "0.399006")
        self.assertEqual(eastmoney_secid("INDEX", "930901"), "2.930901")

    def test_tencent_symbol(self):
        self.assertEqual(tencent_symbol("600519"), "sh600519")
        self.assertEqual(tencent_symbol("300274"), "sz300274")
        self.assertEqual(tencent_symbol("000300", "SH"), "sh000300")


class ReportTypeTest(unittest.TestCase):
    def test_quarter_mapping(self):
        self.assertEqual(report_type_of(date(2025, 3, 31)), "Q1")
        self.assertEqual(report_type_of(date(2025, 6, 30)), "H1")
        self.assertEqual(report_type_of(date(2025, 9, 30)), "Q3")
        self.assertEqual(report_type_of(date(2025, 12, 31)), "ANNUAL")
        self.assertIsNone(report_type_of(date(2025, 5, 20)))
        self.assertIsNone(report_type_of(None))


class InstrumentCodeTest(unittest.TestCase):
    def test_prefix_and_uppercase(self):
        self.assertEqual(asset_instrument_code("stock", "600519"), "STOCK:600519")
        self.assertEqual(asset_instrument_code("ETF", "510300"), "ETF:510300")
        with self.assertRaises(ValueError):
            asset_instrument_code("", "600519")


if __name__ == "__main__":
    unittest.main()
