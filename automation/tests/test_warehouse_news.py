# -*- coding: utf-8 -*-
import unittest
from datetime import datetime

import warehouse_fixtures

warehouse_fixtures.ensure_path()

from warehouse.rate_limit import SourceError  # noqa: E402
from warehouse.sources.news import (  # noqa: E402
    event_row,
    news_search_param,
    parse_news_jsonp,
    parse_notices,
    parse_policy_rss,
    policy_query,
    strip_jsonp,
)

load_json = warehouse_fixtures.load_json
load_text = warehouse_fixtures.load_text


class NewsParamTest(unittest.TestCase):
    def test_chinese_is_not_escaped(self):
        param = news_search_param("贵州茅台")
        self.assertIn("贵州茅台", param)
        self.assertNotIn("\\u", param)
        self.assertIn('"pageSize":20', param)

    def test_strip_jsonp(self):
        self.assertTrue(strip_jsonp("cb({\"a\":1});").startswith("{"))
        with self.assertRaises(SourceError):
            strip_jsonp("not-jsonp")


class NewsParseTest(unittest.TestCase):
    def test_news_items(self):
        items = parse_news_jsonp(load_text("eastmoney_news.jsonp"))
        self.assertEqual(len(items), 1)  # 缺 url 的条目被丢弃
        item = items[0]
        self.assertEqual(item["title"], "贵州茅台：上半年营收同比增长 12.5%")
        self.assertEqual(item["media"], "证券时报")
        self.assertEqual(item["category"], "NEWS")
        self.assertEqual(item["published_at"], datetime(2025, 9, 30, 10, 0))

    def test_missing_result_section_is_an_error(self):
        with self.assertRaises(SourceError):
            parse_news_jsonp('cb({"code":0,"result":{}});')


class NoticeParseTest(unittest.TestCase):
    def test_notice_items(self):
        items = parse_notices("600519", load_json("eastmoney_notices.json"))
        self.assertEqual(len(items), 2)  # 缺 art_code 的条目被丢弃
        first = items[0]
        self.assertEqual(first["category"], "NOTICE")
        self.assertEqual(first["event_type"], "回购、临时公告")
        self.assertIn("600519", first["url"])
        self.assertIn("AN20250930001", first["url"])
        self.assertEqual(items[1]["title"], "2025 年半年度报告")

    def test_missing_list_is_an_error(self):
        with self.assertRaises(SourceError):
            parse_notices("600519", {"data": {}})


class PolicyParseTest(unittest.TestCase):
    def test_query_has_time_window(self):
        self.assertEqual(policy_query("光伏设备"), "光伏设备 政策 when:30d")

    def test_rss_items(self):
        items = parse_policy_rss(load_text("google_news.xml"))
        self.assertEqual(len(items), 2)  # 缺 pubDate 的条目被丢弃
        self.assertEqual(items[0]["category"], "POLICY")
        self.assertEqual(items[0]["media"], "中国政府网")
        self.assertEqual(items[0]["published_at"], datetime(2025, 9, 29, 16, 0))
        self.assertEqual(items[1]["title"], "带标签的政策标题")

    def test_invalid_xml_is_an_error(self):
        with self.assertRaises(SourceError):
            parse_policy_rss("<rss><channel>")


class EventRowTest(unittest.TestCase):
    def item(self, title="关于回购股份的公告"):
        return {
            "category": "NOTICE",
            "event_type": "回购",
            "title": title,
            "summary": None,
            "url": "https://example.com/a",
            "published_at": datetime(2025, 9, 30, 8, 0),
            "source": "eastmoney-notice",
            "media": "东财公告",
        }

    def test_hash_is_deterministic(self):
        stamp = datetime(2025, 10, 1, 18, 0)
        first = event_row(self.item(), "STOCK:600519", stamp)
        second = event_row(self.item(), "STOCK:600519", stamp)
        self.assertEqual(first["content_hash"], second["content_hash"])
        self.assertEqual(first["polarity"], "NEUTRAL")
        self.assertEqual(first["importance"], "NORMAL")
        self.assertEqual(first["instrument_code"], "STOCK:600519")

    def test_hash_changes_with_title(self):
        stamp = datetime(2025, 10, 1, 18, 0)
        self.assertNotEqual(
            event_row(self.item("A"), None, stamp)["content_hash"],
            event_row(self.item("B"), None, stamp)["content_hash"],
        )


if __name__ == "__main__":
    unittest.main()
