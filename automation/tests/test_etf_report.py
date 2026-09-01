import importlib.util
import os
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import Mock, patch

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "etf_report.py"
SPEC = importlib.util.spec_from_file_location("etf_report", MODULE_PATH)
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)

ETF = report.ETF_LIST[0]
NOW = datetime(2026, 7, 27, 15, 0, tzinfo=report.BEIJING_TZ)


def response(json_body=None, status=200, content=b""):
    result = Mock()
    result.status_code = status
    result.json.return_value = json_body
    result.content = content
    result.text = ""
    result.raise_for_status.side_effect = None if status < 400 else report.requests.HTTPError(str(status))
    return result


def prices(source="测试源", count=25):
    return [{
        "date": f"2026-06-{day:02d}",
        "open": 4.0,
        "close": 4.1,
        "high": 4.2,
        "low": 3.9,
        "source": source,
        "adjustmentType": "QFQ",
    } for day in range(1, min(count, 30) + 1)]


def complete_snapshot(etf=ETF):
    method = etf["percentile_method"]
    return {
        "etf": etf,
        "quote": {
            "latest_price": 4.1,
            "previous_close": 4.0,
            "pct_change": 2.5,
            "data_time": "2026-07-27 15:00:00",
            "source": "行情测试源",
            "data_status": "external",
            "error": None,
        },
        "price_context": {
            "previous_close": 4.0,
            "previous_date": "2026-07-24",
            "week_baseline": 3.9,
            "week_baseline_date": "2026-07-20",
            "week_pct_change": 5.13,
            "month_baseline": 3.8,
            "month_baseline_date": "2026-06-27",
            "month_pct_change": 7.89,
            "month_high": 4.30,
            "month_low": 3.80,
            "distance_from_month_high": -4.65,
            "month_range_position": 60.0,
            "source": "前复权测试源",
            "data_status": "external_cached",
            "as_of": "2026-07-24",
            "error": None,
        },
        "premium": {
            "premium_rate": 0.2,
            "level": "轻微溢价",
            "source": "溢价测试源",
            "data_time": "2026-07-27 15:00:00",
            "reference_only": report.etf_is_qdii(etf),
            "display_rate": 1.2,
            "display_date": "2026-07-24",
            "previous_rate": 1.0,
            "previous_date": "2026-07-24",
            "week_rate": 0.8,
            "week_date": "2026-07-20",
            "month_rate": 0.5,
            "month_date": "2026-06-27",
            "history_source": "溢价历史测试源",
            "error": None,
        },
        "valuation": {
            "pe_ttm": 12.5,
            "pe_percentile": 45.0,
            "percentile_method": method,
            "valuation_level": "估值适中",
            "source": "估值测试源",
            "updated_at": "2026-07-27",
            "data_status": "external",
            "error": None,
        },
        "pe_history": [{
            "tradeDate": "2026-07-24",
            "peTtm": 12.0,
            "pePercentile": 40.0,
            "percentileMethod": method,
        }],
    }


class HttpTests(unittest.TestCase):
    def test_retry_session_limits_get_to_three_attempts(self):
        session = report.build_http_session()
        retry = session.get_adapter("https://").max_retries
        self.assertEqual(retry.total, 2)
        self.assertEqual(set(retry.status_forcelist), set(report.RETRYABLE_STATUS_CODES))
        self.assertEqual(retry.allowed_methods, frozenset(("GET",)))


class QuoteTests(unittest.TestCase):
    @patch.object(report, "now_beijing", return_value=NOW)
    def test_quote_validation_rejects_future_and_bad_ohlc(self, _):
        base = {
            "code": "510300", "latest_price": 4.1, "previous_close": 4.0,
            "open": 4.0, "high": 4.2, "low": 3.9,
            "data_time": "2026-07-27 14:00:00",
        }
        self.assertEqual(report.validate_quote(dict(base), ETF)["latest_price"], 4.1)
        with self.assertRaisesRegex(RuntimeError, "未来"):
            report.validate_quote({**base, "data_time": "2026-07-28 14:00:00"}, ETF)
        with self.assertRaisesRegex(RuntimeError, "OHLC"):
            report.validate_quote({**base, "high": 4.05}, ETF)
        with self.assertRaisesRegex(RuntimeError, "过旧"):
            report.validate_quote({**base, "data_time": "2026-07-11 14:00:00"}, ETF)

    @patch.object(report, "fetch_quote_from_sina")
    @patch.object(report, "fetch_quote_from_eastmoney", side_effect=RuntimeError("字段无效"))
    def test_quote_switches_to_sina_on_semantic_failure(self, _, sina):
        sina.return_value = {"source": "新浪财经"}
        self.assertEqual(report.fetch_etf_quote(ETF)["source"], "新浪财经")
        sina.assert_called_once_with(ETF)

    @patch.object(report, "http_get")
    def test_premium_uses_stock_get_code_field(self, get):
        get.return_value = response({"data": {
            "f57": ETF["code"],
            "f124": int(NOW.timestamp()),
            "f2": 4.1,
            "f441": 4.0,
            "f402": -2.5,
        }})
        quote = {"latest_price": 4.1, "data_time": "2026-07-27 15:00:00"}
        premium = report.fetch_etf_premium(ETF, quote)
        self.assertAlmostEqual(premium["premium_rate"], 2.5)
        self.assertIsNone(premium["error"])
        self.assertIn("f57", get.call_args.kwargs["params"]["fields"])


class PremiumHistoryTests(unittest.TestCase):
    def test_pairs_only_same_date_nav_and_close(self):
        closes = [
            {"date": "2026-07-24", "close": 2.20},
            {"date": "2026-07-25", "close": 2.22},
            {"date": "2026-07-27", "close": 2.30},
        ]
        navs = [
            {"date": "2026-07-24", "nav": 2.00},
            {"date": "2026-07-25", "nav": 2.00},
            {"date": "2026-07-26", "nav": 2.10},
        ]
        pairs = report.pair_premium_observations(closes, navs)
        self.assertEqual([item["date"] for item in pairs], ["2026-07-24", "2026-07-25"])
        self.assertAlmostEqual(pairs[0]["premium_rate"], 10.0)

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_enrich_keeps_today_iopv_and_fills_lookbacks(self, _):
        observations = [
            {"date": "2026-06-27", "close": 2.01, "nav": 2.00, "premium_rate": 0.5},
            {"date": "2026-07-20", "close": 2.016, "nav": 2.00, "premium_rate": 0.8},
            {"date": "2026-07-24", "close": 2.02, "nav": 2.00, "premium_rate": 1.0},
            {"date": "2026-07-27", "close": 2.024, "nav": 2.00, "premium_rate": 1.2},
        ]
        premium = {
            "premium_rate": 0.2,
            "level": "接近净值",
            "data_time": "2026-07-27 15:00:00",
            "reference_only": True,
        }
        quote = {"data_time": "2026-07-27 15:00:00"}
        with patch.object(report, "fetch_etf_nav_history_from_eastmoney", return_value=[
            {"date": item["date"], "nav": item["nav"]} for item in observations
        ]), patch.object(report, "fetch_etf_unadjusted_closes", return_value=[
            {"date": item["date"], "close": item["close"]} for item in observations
        ]):
            result = report.enrich_premium_with_history(report.ETF_LIST[1], premium, quote)
        self.assertEqual(result["premium_rate"], 0.2)
        self.assertAlmostEqual(result["display_rate"], 1.2)
        self.assertEqual(result["previous_date"], "2026-07-24")
        self.assertEqual(result["week_date"], "2026-07-20")
        self.assertEqual(result["month_date"], "2026-06-27")

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_format_premium_falls_back_to_nav_close(self, _):
        text = report.format_premium({
            "premium_rate": None,
            "level": "IOPV未与行情同步",
            "display_rate": 11.21,
            "display_date": "2026-08-27",
            "reference_only": True,
        })
        self.assertIn("+11.21%", text)
        self.assertIn("08-27", text)
        self.assertIn("收盘相对净值", text)

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_csi300_report_omits_premium_history_section(self, _):
        text = report.build_programmatic_report([complete_snapshot()], "market_watch_evening")
        self.assertNotIn("溢价变化", text)

    @patch.object(report, "now_beijing", return_value=NOW)
    @patch.object(report, "http_get")
    def test_nav_history_reads_two_pages(self, get, _):
        get.side_effect = [
            response({"Data": {"LSJZList": [
                {"FSRQ": f"2026-07-{day:02d}", "DWJZ": "2.00"} for day in range(10, 20)
            ]}}),
            response({"Data": {"LSJZList": [
                {"FSRQ": f"2026-06-{day:02d}", "DWJZ": "1.90"} for day in range(10, 20)
            ]}}),
        ]
        navs = report.fetch_etf_nav_history_from_eastmoney(report.ETF_LIST[1])
        self.assertEqual(len(navs), 20)
        self.assertEqual(get.call_count, 2)
        self.assertEqual(get.call_args_list[0].kwargs["params"]["fundCode"], "513100")
        self.assertEqual(get.call_args_list[1].kwargs["params"]["pageIndex"], 2)


class HistoryTests(unittest.TestCase):
    @patch.object(report, "fetch_etf_daily_prices_from_tencent")
    @patch.object(report, "fetch_etf_daily_prices_from_eastmoney", side_effect=RuntimeError("主源失败"))
    def test_history_uses_tencent_fallback(self, _, tencent):
        tencent.return_value = prices("腾讯前复权日线")
        result = report.fetch_etf_daily_prices(ETF)
        self.assertEqual(result[-1]["source"], "腾讯前复权日线")

    def test_tencent_rejects_unadjusted_rows(self):
        body = {"data": {ETF["sina_code"]: {"day": [["2026-07-25", "4", "4.1", "4.2", "3.9"]]}}}
        self.assertEqual(report._extract_tencent_rows(body, ETF["sina_code"]), [])

    @patch.object(report, "http_get")
    def test_history_rejects_fewer_than_twenty_rows(self, get):
        get.return_value = response({
            "data": {
                "code": ETF["code"],
                "klines": [f"2026-07-{day:02d},4,4.1,4.2,3.9,0" for day in range(1, 20)],
            }
        })
        with self.assertRaisesRegex(RuntimeError, "数量不足"):
            report.fetch_etf_daily_prices_from_eastmoney(ETF)

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_cached_series_is_single_source_and_latest(self, _):
        old = prices("旧源", 20)
        newest = [{**item, "source": "新源", "date": item["date"].replace("2026-06", "2026-07")}
                  for item in prices("新源", 10)]
        chosen = report.select_cached_price_series(old + newest)
        self.assertTrue(chosen)
        self.assertEqual({item["source"] for item in chosen}, {"新源"})

    @patch.object(report, "build_price_context")
    @patch.object(report, "select_cached_price_series", return_value=prices("缓存源"))
    @patch.object(report, "fetch_etf_daily_prices", side_effect=RuntimeError("双源失败"))
    @patch.object(report, "fetch_cached_etf_prices", return_value=prices("缓存源"))
    def test_price_context_falls_back_to_cache(self, _, __, ___, build):
        build.return_value = {"source": "缓存源", "data_status": "cache"}
        quote = {"previous_close": 4.0, "data_time": "2026-07-27 14:00:00"}
        result = report.fetch_price_context(ETF, quote)
        self.assertIn("后端缓存", result["source"])
        build.assert_called_once()
        self.assertEqual(build.call_args.args[2], "cache")

    @patch.dict(os.environ, {
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
    }, clear=False)
    @patch.object(report.requests, "post")
    def test_price_ingest_excludes_quote_date(self, post):
        post.return_value = response({"code": 200})
        quote = {"data_time": "2026-06-03 14:00:00"}
        daily = prices(count=3)
        self.assertTrue(report.push_etf_price_history(ETF, daily, quote))
        sent = post.call_args.kwargs["json"]
        self.assertEqual([item["tradeDate"] for item in sent], ["2026-06-01", "2026-06-02"])
        self.assertEqual({item["fundCode"] for item in sent}, {ETF["code"]})
        self.assertTrue(all(item["fetchedAt"] for item in sent))

    @patch.dict(os.environ, {
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
    }, clear=False)
    @patch.object(report.requests, "post")
    def test_price_ingest_skips_unchanged_cached_rows(self, post):
        quote = {"data_time": "2026-06-03 14:00:00"}
        daily = prices(count=3)
        cached = [{**item, "tradeDate": item["date"]} for item in daily[:2]]
        self.assertTrue(report.push_etf_price_history(ETF, daily, quote, cached))
        post.assert_not_called()

    @patch.dict(os.environ, {
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
    }, clear=False)
    @patch.object(report, "ETF_LIST", [ETF])
    @patch.object(report, "fetch_cached_etf_prices")
    @patch.object(report, "push_etf_price_history", return_value=True)
    @patch.object(report, "fetch_etf_daily_prices", return_value=prices())
    @patch.object(report, "now_beijing", return_value=NOW)
    def test_sync_only_rejects_cache_readback_mismatch(self, _, __, ___, cached):
        cached.return_value = [{**item, "source": "错误来源"} for item in prices()]
        self.assertFalse(report.sync_price_history())

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_cached_close_quote_is_labeled_and_fresh(self, _):
        cached = [
            {"date": "2026-07-24", "open": 4.0, "close": 4.1, "high": 4.2, "low": 3.9,
             "source": "缓存源", "adjustmentType": "QFQ"},
            {"date": "2026-07-25", "open": 4.1, "close": 4.2, "high": 4.3, "low": 4.0,
             "source": "缓存源", "adjustmentType": "QFQ"},
        ]
        quote = report.quote_from_cached_prices(ETF, cached)
        self.assertEqual(quote["data_status"], "cached_close")
        self.assertIn("后端缓存最近确认收盘", quote["source"])
        self.assertEqual(quote["latest_price"], 4.2)


class ValuationTests(unittest.TestCase):
    @patch.object(report, "now_beijing", return_value=NOW)
    def test_pe_range_method_and_danjuan_ratio(self, _):
        with self.assertRaisesRegex(RuntimeError, "300"):
            report.validate_valuation({
                "pe_ttm": 301, "pe_percentile": 50, "updated_at": "2026-07-26",
                "percentile_method": report.CSI_PE_TTM_ROLLING_10Y,
            })
        item = {"pe": 20, "pe_percentile": 0.42, "date": "2026-07-26", "name": "指数"}
        valuation = report.valuation_from_danjuan_item(ETF, item)
        self.assertEqual(valuation["pe_percentile"], 42)
        self.assertEqual(valuation["percentile_method"], report.DANJUAN_PE_TTM_PROVIDER)

    @patch.dict(os.environ, {
        "CSI300_PE": "12", "CSI300_PE_PERCENTILE": "0.5",
        "CSI300_PE_PERCENTILE_METHOD": report.CSI_PE_TTM_ROLLING_10Y,
        "CSI300_VALUATION_DATE": "2026-07-26",
    }, clear=False)
    @patch.object(report, "now_beijing", return_value=NOW)
    def test_env_percentile_is_explicit_zero_to_hundred(self, _):
        valuation = report.fetch_valuation_from_env(ETF)
        self.assertEqual(valuation["pe_percentile"], 0.5)
        self.assertNotEqual(valuation["pe_percentile"], 50)

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_unavailable_snapshot_preserves_complete_shape(self, _):
        snapshot = report.unavailable_snapshot(ETF, "测试失败")
        self.assertEqual(set(snapshot), {"etf", "quote", "price_context", "premium", "valuation", "pe_history"})
        self.assertEqual(snapshot["price_context"]["data_status"], "unavailable")
        self.assertEqual(snapshot["valuation"]["percentile_method"], ETF["percentile_method"])

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_merge_history_uses_only_current_method(self, _):
        history = [
            {"tradeDate": "2026-07-25", "peTtm": 12, "pePercentile": 40,
             "percentileMethod": report.CSI_PE_TTM_ROLLING_10Y},
            {"tradeDate": "2026-07-26", "peTtm": 12, "pePercentile": 50,
             "percentileMethod": report.DANJUAN_PE_TTM_PROVIDER},
        ]
        merged = report.merge_pe_history(history, percentile_method=report.CSI_PE_TTM_ROLLING_10Y)
        self.assertEqual([item["tradeDate"] for item in merged], ["2026-07-25"])

    @patch.object(report, "fetch_cached_etf_prices", return_value=[])
    @patch.object(report, "fetch_etf_premium", return_value={})
    @patch.object(report, "fetch_price_context", return_value={})
    @patch.object(report, "fetch_etf_quote", return_value={"latest_price": 4.1})
    @patch.object(report, "fetch_valuation_from_env")
    @patch.object(report, "fetch_source_valuation", side_effect=RuntimeError("主源失败"))
    @patch.object(report, "fetch_valuation_history")
    @patch.object(report, "now_beijing", return_value=NOW)
    def test_snapshot_uses_same_method_backend_before_env(
        self, _, backend, __, env, ___, ____, _____, ______
    ):
        backend.return_value = [
            {"tradeDate": "2026-07-26", "peTtm": 12, "pePercentile": 45,
             "percentileMethod": report.DANJUAN_PE_TTM_PROVIDER, "source": "错误口径"},
            {"tradeDate": "2026-07-25", "peTtm": 13, "pePercentile": 50,
             "percentileMethod": report.CSI_PE_TTM_ROLLING_10Y, "source": "正确口径"},
        ]
        env.return_value = {"pe_ttm": None, "error": "不应使用"}
        snapshot = report.build_snapshot(ETF)
        self.assertEqual(snapshot["valuation"]["pe_ttm"], 13)
        self.assertEqual(snapshot["valuation"]["data_status"], "cache")
        env.assert_called_once_with(ETF)

    @patch.object(report, "fetch_cached_etf_prices", return_value=[])
    @patch.object(report, "fetch_etf_premium", return_value={})
    @patch.object(report, "fetch_price_context", return_value={})
    @patch.object(report, "fetch_etf_quote", return_value={"latest_price": 4.1})
    @patch.object(report, "fetch_valuation_from_env", return_value={"pe_ttm": None, "error": "环境失败"})
    @patch.object(report, "fetch_source_valuation", side_effect=RuntimeError("主源失败"))
    @patch.object(report, "fetch_valuation_history", return_value=[])
    def test_snapshot_preserves_quote_when_only_valuation_fails(self, *_):
        snapshot = report.build_snapshot(ETF)
        self.assertEqual(snapshot["valuation"]["data_status"], "unavailable")
        self.assertIn("主源失败", snapshot["valuation"]["error"])
        self.assertEqual(snapshot["quote"]["latest_price"], 4.1)

    @patch.object(report, "valuation_from_danjuan_item")
    @patch.object(report, "fetch_valuation_archive")
    def test_snapshot_local_failure_continues_and_three_transport_failures_stop(self, fetch, convert):
        valid_item = {"index_code": ETF["valuation_index_code"]}
        fetch.side_effect = [
            {"status": "ok", "items": [valid_item]},
            {"status": "ok", "items": [valid_item]},
            {"status": "missing", "items": None},
            {"status": "missing", "items": None},
        ]
        convert.side_effect = [RuntimeError("坏字段"), {
            "pe_ttm": 12, "pe_percentile": 40, "updated_at": "2026-07-25",
            "percentile_method": report.DANJUAN_PE_TTM_PROVIDER,
        }]
        value = report.fetch_archived_valuation_on_or_before(ETF, report.date(2026, 7, 26), 3)
        self.assertEqual(value["updated_at"], "2026-07-25")
        self.assertEqual(convert.call_count, 2)

        fetch.reset_mock()
        convert.reset_mock()
        fetch.side_effect = [{"status": "transport_error", "items": None}] * 5
        self.assertIsNone(report.fetch_archived_valuation_on_or_before(ETF, report.date(2026, 7, 26), 10))
        self.assertEqual(fetch.call_count, 3)


class ReportTests(unittest.TestCase):
    @patch.object(report, "now_beijing", return_value=NOW)
    def test_report_keeps_clear_layout_without_etf_advice(self, _):
        stocks = {
            "status": "available",
            "items": [{
                "name": "测试股份", "code": "600000",
                "reason": "成交活跃且估值过滤通过。",
                "trend": "短期更可能维持震荡。",
                "risk": "需核对基本面和公告。",
            }],
            "source": "测试源",
            "error": None,
        }
        text = report.build_programmatic_report(
            [complete_snapshot()], "market_watch_evening", stocks
        )
        for required in (
            "ETF 行情日报", "先看结论", "ETF变化", "PE分位变化",
            "按计划买", "行情价 4.100 元", "该交易日",
            "PE(TTM) 12.50", "PE分位 45%", "本次观测",
            "中证 PE(TTM) 滚动10年分位",
            "估值测试源", "2026-07-27", "外部数据已校验",
            "A股观察候选", "测试股份", "短期更可能维持震荡",
        ):
            self.assertIn(required, text)
        for forbidden in (
            "加仓", "减仓", "正常定投", "今日动作", "观察线",
            "定额配置官", "规则动作", "数据降级",
        ):
            self.assertNotIn(forbidden, text)
        summary = report.build_summary([complete_snapshot()])
        self.assertIn("仓位备忘 按计划买", summary)

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_wechat_report_keeps_action_and_change_sections_only(self, _):
        snapshots = [complete_snapshot(), complete_snapshot(report.ETF_LIST[1])]
        text = report.build_wechat_report(snapshots, "market_watch_evening")
        for required in (
            "ETF 行情日报", "先看结论", "按计划买",
            "ETF变化", "PE分位变化",
            "行情价 4.100 元", "PE(TTM) 12.50", "PE分位 45%",
            "一天前", "一周前", "一月前",
        ):
            self.assertIn(required, text)
        for forbidden in (
            "定额配置官", "规则动作", "数据降级", "A股观察候选",
            "加仓", "减仓", "正常定投", "今日动作",
        ):
            self.assertNotIn(forbidden, text)
        wx = report.convert_to_wework_markdown(text)
        self.assertNotIn("内容已截断", wx)
        self.assertLessEqual(len(wx.encode("utf-8")), 3800)
        self.assertIn("ETF变化", wx)
        self.assertIn("PE分位变化", wx)
        self.assertIn("一天前", wx)

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_wechat_conclusion_uses_allocation_action(self, _):
        expensive = complete_snapshot()
        expensive["valuation"]["pe_percentile"] = 75
        expensive["price_context"]["distance_from_month_high"] = -1
        expensive["premium"]["premium_rate"] = 1.0
        text = report.build_wechat_report([expensive], "market_watch_evening")
        self.assertIn("少买", text)
        self.assertIn("PE分位 75%", text)
        self.assertIn("行情价 4.100 元", text)

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_wechat_convert_does_not_keep_a_share_tail_after_truncate(self, _):
        stocks = {
            "status": "provider_error",
            "items": [],
            "source": "测试源",
            "error": "502 " + ("东方财富候选数据不可用 " * 80),
        }
        full = report.build_programmatic_report(
            [complete_snapshot(), complete_snapshot(report.ETF_LIST[1])],
            "market_watch_evening",
            stocks,
        )
        wx = report.convert_to_wework_markdown(full)
        self.assertLessEqual(len(wx.encode("utf-8")), 3800)
        self.assertIn("先看结论", wx)
        self.assertNotIn("东方财富候选数据不可用", wx)

    def test_watchlist_includes_bosera_sp500(self):
        codes = [item["code"] for item in report.ETF_LIST]
        self.assertEqual(codes, ["510300", "513100", "513500"])
        sp500 = report.ETF_LIST[2]
        self.assertEqual(sp500["valuation_index_code"], "SP500")
        self.assertTrue(report.etf_is_qdii(sp500))

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_full_report_covers_all_three_etfs(self, _):
        snapshots = [complete_snapshot(item) for item in report.ETF_LIST]
        text = report.build_programmatic_report(snapshots, "market_watch_evening")
        for name in ("沪深300ETF", "纳指100ETF", "标普500ETF"):
            self.assertIn(name, text)
        self.assertIn("ETF变化", text)
        self.assertNotIn("两只ETF变化", text)
        self.assertIn("溢价变化", text)
        self.assertIn("一天前溢价", text)
        self.assertIn("一周前溢价", text)
        self.assertIn("一月前溢价", text)
        self.assertIn("### 纳指100ETF", text)
        self.assertIn("### 标普500ETF", text)

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_allocation_memo_degrades_when_pe_is_missing(self, _):
        snapshot = complete_snapshot()
        snapshot["valuation"]["pe_percentile"] = None
        text = report.build_programmatic_report([snapshot], "market_watch_evening")
        self.assertIn("无法判断", text)
        self.assertIn("主数据不足不下动作", text)
        self.assertNotIn("数据降级", text)

    def test_a_share_observation_is_conditional_and_traceable(self):
        stock = {
            "name": "测试股份", "code": "600000", "amount": 800_000_000,
            "pe_dynamic": 18.0, "pb": 2.0, "main_net_inflow": 20_000_000,
            "pct_change": 1.5, "pct_change_60d": 12.0,
        }
        observation = report.a_share_observation(stock)
        self.assertIn("成交额", observation["reason"])
        self.assertIn("若", observation["trend"])
        self.assertNotIn("必涨", "".join(observation.values()))

    @patch.object(report, "fetch_a_share_candidate_pool")
    def test_a_share_selector_returns_two_scored_candidates(self, fetch):
        fetch.return_value = ([{
            "f12": f"60000{index}", "f14": f"测试{index}", "f2": 10,
            "f3": index, "f6": 1_000_000_000 - index, "f8": 2,
            "f9": 20 + index, "f10": 1.2, "f15": 10.5, "f16": 9.5,
            "f20": 50_000_000_000, "f23": 2, "f24": 10, "f25": 8,
            "f62": 10_000_000,
        } for index in range(3)], "东方财富A股行情")
        observations = report.build_a_share_observations()
        self.assertEqual(observations["status"], "available")
        self.assertEqual(len(observations["items"]), 2)
        self.assertEqual(observations["source"], "东方财富A股行情")

    def test_eastmoney_clist_requires_valid_response_shape(self):
        self.assertEqual(report.parse_eastmoney_clist({"data": {"diff": []}}), [])
        with self.assertRaisesRegex(RuntimeError, "diff 不是列表"):
            report.parse_eastmoney_clist({"data": {}})
        with self.assertRaisesRegex(RuntimeError, "缺少 data 对象"):
            report.parse_eastmoney_clist([])

    @patch.object(report, "http_get")
    def test_a_share_eastmoney_switches_host_after_502(self, get):
        get.side_effect = [
            response(status=502),
            response({"data": {"diff": [{"f12": "600000", "f14": "测试"}]}}),
        ]
        items = report.fetch_a_share_candidates_from_eastmoney()
        self.assertEqual(items[0]["f12"], "600000")
        self.assertEqual(get.call_count, 2)
        self.assertIn("push2delay.eastmoney.com", get.call_args_list[0].args[0])
        self.assertIn("82.push2.eastmoney.com", get.call_args_list[1].args[0])
        self.assertEqual(get.call_args.kwargs["headers"]["Referer"], report.A_SHARE_EASTMONEY_HEADERS["Referer"])

    @patch.object(report, "http_get")
    def test_a_share_eastmoney_splits_boards_when_combined_hosts_fail(self, get):
        combined_failures = [response(status=502)] * len(report.A_SHARE_EASTMONEY_HOSTS)
        board_payloads = [
            response({"data": {"diff": [{"f12": "600000"}]}}),
            response({"data": {"diff": [{"f12": "000001"}]}}),
            response({"data": {"diff": [{"f12": "300001"}]}}),
        ]
        get.side_effect = combined_failures + board_payloads
        items = report.fetch_a_share_candidates_from_eastmoney()
        self.assertEqual([item["f12"] for item in items], ["600000", "000001", "300001"])
        self.assertEqual(get.call_count, len(report.A_SHARE_EASTMONEY_HOSTS) + 3)

    @patch.object(report, "fetch_a_share_candidates_from_eastmoney", side_effect=RuntimeError("502"))
    @patch.object(report, "fetch_a_share_candidates_from_sina")
    def test_a_share_pool_falls_back_to_sina(self, sina, _):
        sina.return_value = [{"f12": "600000"}]
        items, source = report.fetch_a_share_candidate_pool()
        self.assertEqual(items[0]["f12"], "600000")
        self.assertEqual(source, "新浪财经A股行情")
        sina.assert_called_once_with()

    def test_sina_row_maps_market_cap_from_wan_yuan(self):
        item = report.sina_row_to_eastmoney_item({
            "code": "600000",
            "name": "测试股份",
            "trade": 10.2,
            "changepercent": 1.5,
            "amount": 800_000_000,
            "turnoverratio": 1.2,
            "per": 12.5,
            "high": 10.5,
            "low": 9.8,
            "mktcap": 2_000_000,
            "pb": 1.8,
        })
        stock = report.normalize_a_share(item)
        self.assertEqual(stock["code"], "600000")
        self.assertEqual(stock["total_market_cap"], 20_000_000_000)
        self.assertTrue(report.is_a_share_candidate(stock))

    @patch.object(report, "fetch_a_share_candidate_pool", return_value=([], "东方财富A股行情"))
    def test_a_share_selector_distinguishes_valid_empty_result(self, _):
        result = report.build_a_share_observations()
        self.assertEqual(result["status"], "empty")
        text = report.build_programmatic_report([complete_snapshot()], "market_watch_evening", result)
        self.assertIn("数据源正常", text)
        self.assertNotIn("候选数据源异常", text)

    @patch.object(
        report,
        "fetch_a_share_candidate_pool",
        side_effect=RuntimeError(
            "502 Server Error: Bad Gateway for url: "
            "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=100"
        ),
    )
    def test_a_share_selector_marks_provider_error(self, _):
        result = report.build_a_share_observations()
        self.assertEqual(result["status"], "provider_error")
        self.assertEqual(result["error"], "行情列表源网关繁忙（502）")
        text = report.build_programmatic_report([complete_snapshot()], "market_watch_evening", result)
        self.assertIn("候选数据源异常", text)
        self.assertIn("行情列表源网关繁忙（502）", text)
        self.assertNotIn("https://", text)
        self.assertNotIn("clist/get", text)

    @patch.object(report, "http_get")
    def test_premium_502_returns_provider_error(self, get):
        get.return_value = response(status=502)
        premium = report.fetch_etf_premium(
            ETF, {"latest_price": 4.1, "data_time": "2026-07-27 15:00:00"}
        )
        self.assertEqual(premium["data_status"], "provider_error")
        self.assertIsNone(premium["premium_rate"])
        self.assertIn("502", premium["error"])

    def test_morning_edition_is_rejected(self):
        with patch.dict(os.environ, {"EDITION": "morning"}, clear=False):
            with self.assertRaisesRegex(RuntimeError, "早间版已停用"):
                report.detect_edition()

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_premium_failure_is_included_in_data_risks(self, _):
        snapshot = complete_snapshot()
        snapshot["premium"].update({
            "premium_rate": None,
            "source": "不可确认",
            "data_time": "不可确认",
            "error": "溢价源失败",
        })
        text = report.build_programmatic_report([snapshot], "market_watch_evening")
        self.assertIn("溢折价：溢价源失败", text)
        self.assertNotIn("本次未发现缺失字段", text)

    @patch.object(report, "now_beijing", return_value=NOW)
    def test_large_valuation_change_is_labeled_as_possible_revision(self, _):
        snapshot = complete_snapshot()
        snapshot["pe_history"] = [{
            "tradeDate": "2026-07-26",
            "peTtm": 15.0,
            "pePercentile": 70.0,
            "percentileMethod": ETF["percentile_method"],
        }]
        text = report.build_programmatic_report([snapshot], "market_watch_evening")
        self.assertIn("可能包含数据源成分、盈利或历史样本修订", text)

    def test_weekend_skip_allows_dry_run_and_force_run(self):
        saturday = NOW + timedelta(days=5)
        with patch.dict(os.environ, {}, clear=True):
            self.assertTrue(report.should_skip_weekend_report(saturday, False))
            self.assertFalse(report.should_skip_weekend_report(saturday, True))
        with patch.dict(os.environ, {"ETF_FORCE_RUN": "true"}, clear=True):
            self.assertFalse(report.should_skip_weekend_report(saturday, False))

    @patch.dict(os.environ, {
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
    }, clear=False)
    @patch.object(report.requests, "post")
    def test_report_ingest_rejects_http_success_with_business_failure(self, post):
        post.return_value = response({"code": 500, "message": "保存失败"})
        with patch.object(report.time, "sleep"):
            self.assertFalse(report.push_to_backend(
                "market_watch_evening", "2026-08-18", "title", "content", "summary", "run"
            ))
        self.assertEqual(post.call_count, 3)

    @patch.dict(os.environ, {
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
    }, clear=False)
    @patch.object(report.requests, "post")
    def test_report_ingest_includes_business_date(self, post):
        post.return_value = response({"code": 200, "data": None})
        self.assertTrue(report.push_to_backend(
            "market_watch_evening", "2026-08-18", "title", "content", "summary", "run"
        ))
        self.assertEqual(post.call_args.kwargs["json"]["reportDate"], "2026-08-18")

    @patch.object(report.requests, "post")
    @patch.object(report.time, "sleep")
    def test_wechat_handles_retryable_and_non_object_business_responses(self, _, post):
        busy = response({"errcode": -1})
        success = response({"errcode": 0})
        post.side_effect = [busy, success]
        self.assertTrue(report.push_to_wechat("content", "https://wechat.test"))
        self.assertEqual(post.call_count, 2)

        post.reset_mock(side_effect=True)
        post.return_value = response([])
        self.assertFalse(report.push_to_wechat("content", "https://wechat.test"))
        self.assertEqual(post.call_count, 1)

    @patch.object(report, "build_snapshot")
    @patch.object(report, "now_beijing", return_value=NOW + timedelta(days=5))
    def test_weekend_main_exits_before_fetching(self, _, build_snapshot):
        with patch.dict(os.environ, {}, clear=True):
            report.main()
        build_snapshot.assert_not_called()

    @patch.object(report, "sync_price_history", return_value=True)
    @patch.object(report, "now_beijing", return_value=NOW + timedelta(days=5))
    def test_weekend_sync_only_still_runs(self, _, sync_price_history):
        with patch.dict(os.environ, {"ETF_SYNC_ONLY": "true"}, clear=True):
            report.main()
        sync_price_history.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
