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
        self.assertFalse(report.push_to_backend("morning", "title", "content", "summary", "run"))
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
        self.assertTrue(report.push_to_backend("morning", "title", "content", "summary", "run"))

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


if __name__ == "__main__":
    unittest.main()
