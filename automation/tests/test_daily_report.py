import importlib.util
import os
import re
import sys
import unittest
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, mock_open, patch

if "feedparser" not in sys.modules:
    try:
        import feedparser  # noqa: F401
    except ModuleNotFoundError:
        sys.modules["feedparser"] = Mock()

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "daily_report.py"
SPEC = importlib.util.spec_from_file_location("daily_report", MODULE_PATH)
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


def llm_response(content, finish_reason="stop"):
    return SimpleNamespace(
        choices=[SimpleNamespace(
            message=SimpleNamespace(content=content),
            finish_reason=finish_reason,
        )]
    )


class LlmResponseTests(unittest.TestCase):
    def test_default_model_uses_cost_effective_flash(self):
        self.assertEqual(report.LLM_MODELS[0]["name"], "deepseek-v4-flash")

    def test_rejects_missing_choices(self):
        with self.assertRaisesRegex(report.InvalidLLMResponseError, "choices"):
            report.extract_llm_content(SimpleNamespace(choices=[]), "test-model")

    def test_rejects_none_blank_and_empty_fence(self):
        for content in (None, "", "  \n", "```markdown\n\n```"):
            with self.subTest(content=content):
                with self.assertRaises(report.InvalidLLMResponseError):
                    report.extract_llm_content(llm_response(content), "test-model")

    def test_strips_markdown_fence(self):
        content = report.extract_llm_content(
            llm_response("```markdown\n## 今日要点\n正文内容\n```"),
            "test-model",
        )
        self.assertEqual(content, "## 今日要点\n正文内容")

    def test_content_quality_rejects_shell(self):
        self.assertFalse(report.has_substantive_report_content("# 标题\n\n---\n"))
        self.assertFalse(report.has_substantive_report_content("# 标题\n\n> 数据来源：测试源"))
        self.assertTrue(report.has_substantive_report_content("# 标题\n\n## 要点\n正文内容"))

    @patch.dict(os.environ, {"DEEPSEEK_API_KEY": "secret"}, clear=False)
    @patch.object(report.time, "sleep")
    def test_empty_response_retries_then_succeeds(self, _):
        create = Mock(side_effect=[llm_response(" "), llm_response("## 要点\n正文")])
        client = Mock()
        client.chat.completions.create = create
        openai_module = SimpleNamespace(OpenAI=Mock(return_value=client))
        with patch.dict(sys.modules, {"openai": openai_module}):
            result = report.call_llm_with_retry("prompt", max_retries=1)
        self.assertEqual(result, "## 要点\n正文")
        self.assertEqual(create.call_count, 2)
        self.assertEqual(
            create.call_args.kwargs["extra_body"],
            {"thinking": {"type": "disabled"}},
        )


class DeliveryTests(unittest.TestCase):
    @patch.dict(os.environ, {
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
    }, clear=False)
    @patch("requests.post")
    def test_backend_requires_success_business_code(self, post):
        response = Mock(status_code=200)
        response.json.return_value = {"code": 401, "message": "token invalid"}
        post.return_value = response
        self.assertFalse(report.push_to_backend("morning", "2026-08-18", "title", "content", "summary", "run"))
        self.assertEqual(post.call_count, 1)

    @patch.dict(os.environ, {
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
    }, clear=False)
    @patch("requests.post")
    def test_backend_accepts_http_and_business_success(self, post):
        response = Mock(status_code=200)
        response.json.return_value = {"code": 200}
        post.return_value = response
        self.assertTrue(report.push_to_backend("morning", "2026-08-18", "title", "content", "summary", "run"))
        self.assertEqual(post.call_args.kwargs["json"]["reportDate"], "2026-08-18")

    @patch("requests.post")
    @patch.object(report.time, "sleep")
    def test_wechat_retries_retryable_business_error_and_rejects_non_object_json(self, _, post):
        busy = Mock(status_code=200)
        busy.json.return_value = {"errcode": -1}
        success = Mock(status_code=200)
        success.json.return_value = {"errcode": 0}
        post.side_effect = [busy, success]
        self.assertTrue(report.push_to_wechat("content", "https://wechat.test"))
        self.assertEqual(post.call_count, 2)

        post.reset_mock(side_effect=True)
        invalid = Mock(status_code=200)
        invalid.json.return_value = []
        post.return_value = invalid
        self.assertFalse(report.push_to_wechat("content", "https://wechat.test"))
        self.assertEqual(post.call_count, 1)

    @patch.dict(os.environ, {
        "DEEPSEEK_API_KEY": "secret",
        "WECHAT_WEBHOOK": "https://wechat.test",
    }, clear=False)
    @patch.object(report, "extract_ai_news", return_value=[{"title": "AI", "summary": "news"}])
    @patch.object(report, "format_news_for_prompt", return_value="news")
    @patch.object(report, "build_prompt", return_value="prompt")
    @patch.object(report, "call_llm_with_retry", return_value="")
    @patch.object(report, "push_to_backend")
    @patch.object(report, "push_to_wechat")
    @patch("builtins.open", new_callable=mock_open)
    def test_empty_report_has_no_side_effects(self, opened, wechat, backend, *_):
        with self.assertRaises(SystemExit) as error:
            report.main()
        self.assertEqual(error.exception.code, 1)
        opened.assert_not_called()
        backend.assert_not_called()
        wechat.assert_not_called()

    @patch.dict(os.environ, {
        "DEEPSEEK_API_KEY": "secret",
        "WECHAT_WEBHOOK": "https://wechat.test",
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
        "EDITION": "evening",
    }, clear=False)
    @patch.object(report, "extract_ai_news", return_value=[{"title": "AI", "summary": "news"}])
    @patch.object(report, "format_news_for_prompt", return_value="news")
    @patch.object(report, "build_prompt", return_value="prompt")
    @patch.object(report, "call_llm_with_retry", return_value="## 要点\n正文")
    @patch.object(report, "generate_due_topic_sections", return_value=0)
    @patch.object(report, "push_to_backend", return_value=True)
    @patch.object(report, "dispatch_due_pushes", return_value=True)
    @patch.object(report, "push_to_wechat")
    @patch("builtins.open", new_callable=mock_open)
    def test_backend_ingest_skips_script_wechat(self, opened, wechat, dispatch, backend, *_):
        report.main()
        backend.assert_called_once()
        wechat.assert_not_called()
        dispatch.assert_called_once()

    @patch.dict(os.environ, {
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
    }, clear=False)
    @patch("requests.post")
    def test_record_ops_delivery_posts_ingest(self, post):
        resp = Mock(status_code=200)
        resp.json.return_value = {"code": 200, "data": 1}
        post.return_value = resp
        self.assertTrue(report.record_ops_delivery("morning", "2026-09-03"))
        post.assert_called_once()
        args, kwargs = post.call_args
        self.assertTrue(args[0].endswith("/api/reports/record-delivery"))
        self.assertEqual(kwargs["json"]["edition"], "morning")
        self.assertEqual(kwargs["json"]["channelType"], "wechat")


class TopicSectionTests(unittest.TestCase):
    def test_selects_only_matching_topic_news(self):
        items = [
            {"title": "PostgreSQL 18 发布", "summary": "数据库查询更快", "score": 10},
            {"title": "Flutter 新版本", "summary": "移动端体验", "score": 9},
            {"title": "无关财经新闻", "summary": "股市上涨", "score": 8},
        ]
        selected = report.select_news_for_topic(items, "数据库")
        self.assertEqual([item["title"] for item in selected], ["PostgreSQL 18 发布"])

    def test_generation_concurrency_is_capped(self):
        with patch.dict(os.environ, {"GENERATION_CONCURRENCY": "99"}, clear=False):
            self.assertEqual(report.generation_concurrency(), 8)
        with patch.dict(os.environ, {"GENERATION_CONCURRENCY": "0"}, clear=False):
            self.assertEqual(report.generation_concurrency(), 1)

    def test_skips_preset_topic_generation_when_no_matching_news(self):
        saved = report.generate_topic_sections(
            [{"title": "Flutter 新版本", "summary": "移动端体验", "score": 9}],
            "morning",
            ["数据库"],
            "2026-08-28",
            "run-1",
        )
        self.assertEqual(saved, 0)

    def test_custom_search_drops_unrelated_items(self):
        loose = {"title": "今日财经综述", "summary": "股市上涨", "score": 8}
        hit = {"title": "具身智能新突破", "summary": "机器人落地", "score": 9}
        self.assertFalse(report.news_mentions_topic(loose, "具身智能"))
        self.assertTrue(report.news_mentions_topic(hit, "具身智能"))

    def test_custom_topic_is_not_treated_as_preset(self):
        self.assertTrue(report.is_preset_topic("数据库"))
        self.assertFalse(report.is_preset_topic("具身智能"))

    def test_tech_digest_is_not_generated_as_short_section(self):
        with patch.object(report, "collect_news_for_topic") as collect:
            saved = report.generate_one_topic_section([], "morning", "AI科技", "2026-08-31", "run-1")
        self.assertFalse(saved)
        collect.assert_not_called()
        self.assertTrue(report.is_digest_topic("纳指标普沪深300ETF"))

    @patch.object(report, "report_generation_status")
    @patch.object(report, "generate_public_ai_digest", return_value=True)
    def test_due_digest_generates_public_ai_report(self, generate, status):
        saved = report.generate_due_digest_reports(
            [{"window": "w06_12", "topic": "AI科技", "generateAt": "08:00"}],
            [{"title": "AI", "summary": "news"}],
            "2026-08-31",
            "run-1",
        )
        self.assertEqual(saved, 1)
        generate.assert_called_once_with(
            [{"title": "AI", "summary": "news"}], "morning", "2026-08-31", "run-1")
        status.assert_called_once()

    @patch.object(report, "report_generation_status")
    def test_due_digest_generates_etf_report(self, status):
        generate = Mock(return_value=True)
        fake = SimpleNamespace(
            generate_and_ingest=generate,
            now_beijing=lambda: None,
            should_skip_weekend_report=lambda *_: False,
        )
        with patch.dict(sys.modules, {"etf_report": fake}):
            saved = report.generate_due_digest_reports(
                [{"window": "w18_24", "topic": "纳指标普沪深300ETF", "generateAt": "18:00"}],
                [],
                "2026-08-31",
                "run-1",
            )
        self.assertEqual(saved, 1)
        generate.assert_called_once()
        status.assert_called_once()

    @patch.object(report, "report_generation_status")
    def test_due_etf_digest_skips_weekend(self, status):
        generate = Mock(return_value=True)
        fake = SimpleNamespace(
            generate_and_ingest=generate,
            now_beijing=lambda: None,
            should_skip_weekend_report=lambda *_: True,
        )
        with patch.dict(sys.modules, {"etf_report": fake}):
            saved = report.generate_due_digest_reports(
                [{"window": "w18_24", "topic": "纳指标普沪深300ETF", "generateAt": "18:00"}],
                [],
                "2026-08-29",
                "run-1",
            )
        self.assertEqual(saved, 0)
        generate.assert_not_called()
        status.assert_called_once()
        self.assertEqual(status.call_args.args[3], "skipped_no_news")

    def test_custom_topic_searches_when_pool_has_no_match(self):
        searched = [{"title": "具身智能新突破", "summary": "机器人落地", "score": 8, "source": "主题检索·中文"}]
        with patch.object(report, "fetch_topic_search_news", return_value=searched) as search:
            selected = report.collect_news_for_topic(
                [{"title": "Flutter 新版本", "summary": "移动端体验", "score": 9}],
                "具身智能",
            )
        self.assertEqual(selected, searched)
        search.assert_called_once_with("具身智能")

    def test_custom_topic_merges_pool_and_search(self):
        pool_item = {"title": "具身智能融资", "summary": "机器人公司", "score": 9, "link": "https://a.test/1"}
        searched = [{
            "title": "具身智能新突破",
            "summary": "机器人落地",
            "score": 8,
            "source": "主题检索·中文",
            "link": "https://b.test/2",
        }]
        with patch.object(report, "fetch_topic_search_news", return_value=searched) as search:
            selected = report.collect_news_for_topic([pool_item], "具身智能")
        search.assert_called_once_with("具身智能")
        titles = [item["title"] for item in selected]
        self.assertIn("具身智能融资", titles)
        self.assertIn("具身智能新突破", titles)

    def test_topic_search_urls_include_encoded_query(self):
        named = report.topic_search_urls("具身智能")
        names = [name for name, _url in named]
        urls = [url for _, url in named]
        self.assertTrue(any("%E5%85%B7%E8%BA%AB%E6%99%BA%E8%83%BD" in url for url in urls))
        self.assertIn("Google中文", names)
        self.assertIn("Bing新闻", names)
        self.assertIn("Reddit", names)
        self.assertIn("Hacker News", names)
        scan_names = [name for name, _url in report.topic_scan_feeds()]
        self.assertIn("Electrek", scan_names)
        self.assertIn("TechCrunch", scan_names)
        self.assertIn("IT之家", scan_names)
        self.assertIn("Space.com", scan_names)

    @patch.dict(os.environ, {
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
    }, clear=False)
    @patch("requests.get")
    def test_fetch_subscribed_topics_reads_backend_list(self, get):
        response = Mock(status_code=200)
        response.json.return_value = {"code": 200, "data": {"topics": ["AI大模型", "安全"]}}
        get.return_value = response
        self.assertEqual(report.fetch_subscribed_topics("morning"), ["AI大模型", "安全"])
        self.assertIn("subscribed-topics", get.call_args.args[0])

    @patch.dict(os.environ, {
        "DEEPSEEK_API_KEY": "secret",
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
        "MODE": "poll",
    }, clear=False)
    @patch.object(report, "dispatch_due_pushes", return_value=True)
    @patch.object(report, "post_poller_heartbeat", return_value=True)
    @patch.object(report, "fetch_due_generations", return_value=[])
    @patch.object(report, "extract_ai_news")
    @patch.object(report, "generate_due_topic_sections")
    def test_poll_skips_crawl_when_nothing_due(self, generate, extract, _due, _beat, dispatch):
        report.main()
        extract.assert_not_called()
        generate.assert_not_called()
        dispatch.assert_called_once()

    @patch.dict(os.environ, {
        "DEEPSEEK_API_KEY": "secret",
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
        "MODE": "poll",
        "GITHUB_RUN_ID": "run-99",
    }, clear=False)
    @patch.object(report, "dispatch_due_pushes", return_value=True)
    @patch.object(report, "post_poller_heartbeat", return_value=True)
    @patch.object(report, "fetch_due_generations", return_value=[
        {"window": "w18_24", "topic": "区块链", "generateAt": "20:20"},
    ])
    @patch.object(report, "extract_ai_news", return_value=[{"title": "链上", "summary": "news"}])
    @patch.object(report, "generate_due_topic_sections", return_value=1)
    def test_poll_generates_due_topics(self, generate, extract, due, _beat, dispatch):
        report.main()
        extract.assert_called_once()
        generate.assert_called_once()
        self.assertEqual(generate.call_args.args[1], report.now_beijing().strftime("%Y-%m-%d"))
        self.assertEqual(generate.call_args.args[2], "run-99")
        self.assertEqual(generate.call_args.kwargs["due"], due.return_value)
        dispatch.assert_called_once()

    @patch.dict(os.environ, {
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
    }, clear=False)
    @patch("requests.get")
    def test_fetch_due_generations_reads_backend_list(self, get):
        response = Mock(status_code=200)
        response.json.return_value = {
            "code": 200,
            "data": {"date": "2026-08-29", "items": [
                {"window": "w06_12", "topic": "AI大模型", "generateAt": "08:00"},
            ]},
        }
        get.return_value = response
        self.assertEqual(report.fetch_due_generations("2026-08-29"), [
            {"window": "w06_12", "topic": "AI大模型", "generateAt": "08:00", "intent": ""},
        ])
        self.assertIn("due-generations", get.call_args.args[0])

    def test_window_digest_style(self):
        self.assertEqual(report.window_digest_style("w06_12"), "morning")
        self.assertEqual(report.window_digest_style("w18_24"), "evening")

    def test_intent_narrows_keywords_and_prompt(self):
        self.assertEqual(report.normalize_intent("  只要芯片和航天  "), "只要芯片和航天")
        self.assertIn("芯片", report.topic_keywords("AI科技", "只要芯片和航天"))
        self.assertIn("航天", report.topic_keywords("AI科技", "只要芯片和航天"))
        self.assertFalse(report.is_digest_topic("AI科技", "只要芯片"))
        self.assertTrue(report.is_digest_topic("AI科技", ""))
        prompt = report.build_topic_prompt("新闻", "AI科技", "morning", "只要芯片和航天")
        self.assertIn("只要芯片和航天", prompt)
        fallback = report.build_topic_prompt("新闻", "马斯克", "morning", "只要演讲", "topic")
        self.assertIn("最接近", fallback)
        self.assertIn("不要编造", fallback)

    def test_attach_sources_uses_candidate_links_not_model_footer(self):
        items = [
            {"title": "PostgreSQL 18 发布", "link": "https://www.postgresql.org/about/news/18/", "source": "数据库周报"},
            {"title": "重复标题", "link": "https://www.postgresql.org/about/news/18/#comments", "source": "其它"},
            {"title": "无链接", "link": "", "source": "内部"},
        ]
        attached = report.attach_sources("## 数据库\n\n**要点：** 有版本发布。\n\n> 数据来源：模型自己写的", items)
        self.assertIn("### 今日来源", attached)
        self.assertIn("[数据库周报 · PostgreSQL 18 发布](https://www.postgresql.org/about/news/18/)", attached)
        self.assertNotIn("模型自己写的", attached)
        self.assertEqual(attached.count("https://www.postgresql.org/about/news/18"), 1)

    def test_attach_sources_keeps_three_links(self):
        items = [
            {"title": f"条目{index}", "link": f"https://example.test/{index}", "source": "源"}
            for index in range(6)
        ]
        attached = report.attach_sources("## 主题\n\n正文", items)
        self.assertEqual(attached.count("https://example.test/"), 3)
        prompt = report.build_topic_prompt("新闻", "马斯克", "morning")
        self.assertIn("今日概览", prompt)
        self.assertIn("马斯克日报", prompt)
        self.assertNotIn("280 字", prompt)

    def test_wework_keeps_clickable_source_links(self):
        text = "## 数据库\n\n正文\n\n### 今日来源\n\n1. [机器之心 · 大模型](https://www.jiqizhixin.com/a)\n"
        wx = report.convert_to_wework_markdown(text)
        self.assertIn("[机器之心 · 大模型](https://www.jiqizhixin.com/a)", wx)
        self.assertIn("今日来源", wx)

    def test_intent_can_search_when_pool_has_no_match(self):
        searched = [{"title": "英伟达芯片发布", "summary": "数据中心", "score": 8, "source": "主题检索·中文"}]
        with patch.object(report, "fetch_topic_search_news", return_value=searched) as search:
            selected = report.collect_news_for_topic(
                [{"title": "Flutter 新版本", "summary": "移动端体验", "score": 9}],
                "AI科技",
                "只要芯片",
            )
        self.assertEqual(selected, searched)
        search.assert_called_once()

    def test_intent_prefers_focus_then_falls_back_to_topic(self):
        speech = {
            "title": "Musk keynote on Mars",
            "summary": "speech at conference",
            "score": 8,
            "link": "https://m.test/speech",
        }
        tesla = {
            "title": "Tesla recalls vehicles",
            "summary": "safety",
            "score": 7,
            "link": "https://m.test/tesla",
        }
        with patch.object(report, "fetch_topic_search_news", return_value=[speech, tesla]):
            items, match = report.collect_topic_candidates([], "马斯克", "最近最火的演讲")
        self.assertEqual(match, "intent")
        self.assertEqual([item["title"] for item in items], ["Musk keynote on Mars"])
        with patch.object(report, "fetch_topic_search_news", return_value=[tesla]):
            items, match = report.collect_topic_candidates([], "马斯克", "最近最火的演讲")
        self.assertEqual(match, "topic")
        self.assertEqual([item["title"] for item in items], ["Tesla recalls vehicles"])
        with patch.object(report, "fetch_topic_search_news", return_value=[]):
            items, match = report.collect_topic_candidates(
                [{"title": "今日财经", "summary": "股市", "score": 9}],
                "马斯克",
                "最近最火的演讲",
            )
        self.assertEqual(items, [])
        self.assertEqual(match, "none")

    def test_musk_intent_extracts_speech_query_and_aliases(self):
        terms = report.intent_terms("马斯克最近最火的演讲或者热点")
        self.assertTrue(any("演讲" in term for term in terms))
        self.assertNotIn("热点", terms)
        queries = report.topic_search_queries("马斯克", "马斯克最近最火的演讲或者热点")
        self.assertEqual(queries[0], "马斯克")
        self.assertTrue(any("elon musk" == query.lower() for query in queries))
        self.assertTrue(any("演讲" in query for query in queries))
        musk = {"title": "Musk previews Tesla robotaxi", "summary": "event", "score": 8}
        tesla = {"title": "Tesla recalls vehicles", "summary": "safety", "score": 7}
        self.assertTrue(report.news_mentions_topic(musk, "马斯克"))
        self.assertTrue(report.news_mentions_topic(tesla, "马斯克"))
        self.assertFalse(report.news_mentions_topic({"title": "今日财经", "summary": "股市"}, "马斯克"))

    def test_huang_aliases_expand_and_match(self):
        terms = [term.lower() for term in report.expand_topic_terms("黄仁勋")]
        self.assertIn("nvidia", terms)
        self.assertIn("jensen huang", terms)
        queries = report.topic_search_queries("黄仁勋", "只要芯片")
        self.assertEqual(queries[0], "黄仁勋")
        self.assertTrue(any(re.search(r"[A-Za-z]", query) for query in queries))
        self.assertTrue(any("芯片" in query for query in queries))
        nvidia = {"title": "NVIDIA unveils new GPU", "summary": "data center", "score": 8}
        self.assertTrue(report.news_mentions_topic(nvidia, "黄仁勋"))
        self.assertFalse(report.news_mentions_topic({"title": "今日财经", "summary": "股市"}, "黄仁勋"))

    def test_filter_recent_items_drops_week_old_news(self):
        now = datetime(2026, 9, 1, 15, 0, tzinfo=report.BEIJING_TZ)
        fresh = {"title": "新", "published": "2026-09-01 08:00", "score": 9}
        mid = {"title": "三天前", "published": "2026-08-29 08:00", "score": 8}
        stale = {"title": "十天前", "published": "2026-08-20 08:00", "score": 7}
        undated = {"title": "无日期", "score": 6}
        kept = report.filter_recent_items([fresh, mid, stale, undated], now=now, min_keep=1)
        self.assertEqual([item["title"] for item in kept], ["新", "无日期"])
        sparse = report.filter_recent_items([mid, stale], now=now, min_keep=3)
        self.assertEqual([item["title"] for item in sparse], ["三天前"])

    def test_dedupe_news_items_by_canonical_link(self):
        items = [
            {"title": "A 版本", "link": "https://example.com/a?utm_source=x", "score": 5},
            {"title": "A 另一标题", "link": "https://example.com/a#comments", "score": 9},
            {"title": "B", "link": "https://example.com/b", "score": 3},
        ]
        deduped = report.dedupe_news_items(items)
        self.assertEqual([item["title"] for item in deduped], ["A 另一标题", "B"])

    def test_topic_search_queries_stay_single_for_unknown_topic(self):
        self.assertEqual(report.topic_search_queries("具身智能"), ["具身智能"])

    def test_fetch_includes_scan_feed_matches(self):
        now = datetime(2026, 9, 1, 15, 0, tzinfo=report.BEIJING_TZ)

        def fake_parse(xml, source, max_items=12):
            if source == "Electrek":
                return [{
                    "title": "Tesla launches cheaper Model Y",
                    "summary": "EV",
                    "score": 8,
                    "source": source,
                    "link": "https://e.test/1",
                    "published_at": now,
                }]
            return []

        with patch.object(report, "topic_search_urls", return_value=[]), \
             patch.object(report, "topic_scan_feeds", return_value=[("Electrek", "https://electrek.co/feed/")]), \
             patch.object(report, "fetch_feed", return_value="<rss/>"), \
             patch.object(report, "parse_rss_items", side_effect=fake_parse), \
             patch.object(report, "now_beijing", return_value=now):
            items = report.fetch_topic_search_news("马斯克")
        self.assertTrue(any("Tesla" in item["title"] for item in items))

    def test_fetch_topic_search_news_uses_alias_queries_and_recency(self):
        now = datetime(2026, 9, 1, 15, 0, tzinfo=report.BEIJING_TZ)
        seen_queries = []

        def fake_urls(query):
            seen_queries.append(query)
            return [(f"源-{query}", f"https://example.test/{query}")]

        def fake_parse(xml, source, max_items=12):
            if "nvidia" in source.lower() or "jensen" in source.lower():
                return [{
                    "title": "NVIDIA ships new chip",
                    "summary": "GPU",
                    "score": 8,
                    "source": source,
                    "link": "https://n.test/1",
                    "published": "2026-09-01 10:00",
                    "published_at": now,
                }]
            return [{
                "title": "旧闻黄仁勋",
                "summary": "过期",
                "score": 9,
                "source": source,
                "link": "https://n.test/old",
                "published": "2026-08-20 10:00",
                "published_at": datetime(2026, 8, 20, 10, 0, tzinfo=report.BEIJING_TZ),
            }]

        with patch.object(report, "topic_search_urls", side_effect=fake_urls), \
             patch.object(report, "fetch_feed", return_value="<rss/>"), \
             patch.object(report, "parse_rss_items", side_effect=fake_parse), \
             patch.object(report, "now_beijing", return_value=now):
            items = report.fetch_topic_search_news("黄仁勋")
        self.assertTrue(any(query.lower() != "黄仁勋" for query in seen_queries))
        titles = [item["title"] for item in items]
        self.assertIn("NVIDIA ships new chip", titles)
        self.assertNotIn("旧闻黄仁勋", titles)


if __name__ == "__main__":
    unittest.main()
