import importlib.util
import os
import sys
import unittest
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
    @patch.object(report, "push_to_wechat")
    @patch("builtins.open", new_callable=mock_open)
    def test_backend_ingest_skips_script_wechat(self, opened, wechat, backend, *_):
        report.main()
        backend.assert_called_once()
        wechat.assert_not_called()


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
            saved = report.generate_one_topic_section([], "morning", "科技", "2026-08-31", "run-1")
        self.assertFalse(saved)
        collect.assert_not_called()

    def test_custom_topic_searches_when_pool_has_no_match(self):
        searched = [{"title": "具身智能新突破", "summary": "机器人落地", "score": 8, "source": "主题检索·中文"}]
        with patch.object(report, "fetch_topic_search_news", return_value=searched) as search:
            selected = report.collect_news_for_topic(
                [{"title": "Flutter 新版本", "summary": "移动端体验", "score": 9}],
                "具身智能",
            )
        self.assertEqual(selected, searched)
        search.assert_called_once_with("具身智能")

    def test_custom_topic_uses_pool_and_skips_search_when_matched(self):
        pool_item = {"title": "具身智能融资", "summary": "机器人公司", "score": 9}
        with patch.object(report, "fetch_topic_search_news") as search:
            selected = report.collect_news_for_topic([pool_item], "具身智能")
        self.assertEqual(selected, [pool_item])
        search.assert_not_called()

    def test_topic_search_urls_include_encoded_query(self):
        urls = [url for _, url in report.topic_search_urls("具身智能")]
        self.assertTrue(any("q=%E5%85%B7%E8%BA%AB%E6%99%BA%E8%83%BD" in url for url in urls))

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
    @patch.object(report, "post_poller_heartbeat", return_value=True)
    @patch.object(report, "fetch_due_generations", return_value=[])
    @patch.object(report, "extract_ai_news")
    @patch.object(report, "generate_due_topic_sections")
    def test_poll_skips_crawl_when_nothing_due(self, generate, extract, _due, _beat):
        report.main()
        extract.assert_not_called()
        generate.assert_not_called()

    @patch.dict(os.environ, {
        "DEEPSEEK_API_KEY": "secret",
        "BACKEND_API_URL": "https://backend.test",
        "REPORT_INGEST_TOKEN": "token",
        "MODE": "poll",
        "GITHUB_RUN_ID": "run-99",
    }, clear=False)
    @patch.object(report, "post_poller_heartbeat", return_value=True)
    @patch.object(report, "fetch_due_generations", return_value=[
        {"window": "w18_24", "topic": "区块链", "generateAt": "20:20"},
    ])
    @patch.object(report, "extract_ai_news", return_value=[{"title": "链上", "summary": "news"}])
    @patch.object(report, "generate_due_topic_sections", return_value=1)
    def test_poll_generates_due_topics(self, generate, extract, due, _beat):
        report.main()
        extract.assert_called_once()
        generate.assert_called_once()
        self.assertEqual(generate.call_args.args[1], report.now_beijing().strftime("%Y-%m-%d"))
        self.assertEqual(generate.call_args.args[2], "run-99")
        self.assertEqual(generate.call_args.kwargs["due"], due.return_value)

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
            {"window": "w06_12", "topic": "AI大模型", "generateAt": "08:00"},
        ])
        self.assertIn("due-generations", get.call_args.args[0])

    def test_window_digest_style(self):
        self.assertEqual(report.window_digest_style("w06_12"), "morning")
        self.assertEqual(report.window_digest_style("w18_24"), "evening")


if __name__ == "__main__":
    unittest.main()
