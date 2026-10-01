import importlib.util
import logging
import os
import tempfile
import unittest
from logging.handlers import RotatingFileHandler
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "logging_setup.py"
SPEC = importlib.util.spec_from_file_location("logging_setup", MODULE_PATH)
logging_setup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(logging_setup)


class _FakeStream:
    """只实现 reconfigure/isatty，用来断言编码策略，且不真去动 stdout。"""

    def __init__(self, raise_on_reconfigure=False, tty=False):
        self.calls = []
        self.raise_on_reconfigure = raise_on_reconfigure
        self.tty = tty

    def isatty(self):
        return self.tty

    def reconfigure(self, **kwargs):
        self.calls.append(kwargs)
        if self.raise_on_reconfigure:
            raise ValueError("stream is detached")


class LoggingSetupTests(unittest.TestCase):
    def setUp(self):
        self.root = logging.getLogger()
        self.saved_handlers = self.root.handlers[:]
        self.saved_level = self.root.level
        self.root.handlers = []
        logging_setup._configured = False

    def tearDown(self):
        # 把测试装上去的 handler 关掉再还原，避免文件句柄泄漏到其它用例
        for handler in self.root.handlers:
            handler.close()
        self.root.handlers = self.saved_handlers
        self.root.setLevel(self.saved_level)
        logging_setup._configured = False

    def test_returns_named_logger_and_installs_stream_handler(self):
        logger = logging_setup.setup_logging("poller")
        self.assertEqual(logger.name, "poller")
        streams = [h for h in self.root.handlers if isinstance(h, logging.StreamHandler)]
        self.assertEqual(len(streams), 1)

    def test_level_defaults_to_info_and_honours_env(self):
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("LOG_LEVEL", None)
            logging_setup.setup_logging("poller")
            self.assertEqual(self.root.level, logging.INFO)

        with patch.dict(os.environ, {"LOG_LEVEL": "debug"}, clear=False):
            logging_setup.setup_logging("poller")
            self.assertEqual(self.root.level, logging.DEBUG)

    def test_explicit_level_argument_wins_over_env(self):
        with patch.dict(os.environ, {"LOG_LEVEL": "DEBUG"}, clear=False):
            logging_setup.setup_logging("poller", level="warning")
        self.assertEqual(self.root.level, logging.WARNING)

    def test_repeated_calls_do_not_duplicate_handlers(self):
        logging_setup.setup_logging("poller")
        logging_setup.setup_logging("daily_report")
        streams = [h for h in self.root.handlers if isinstance(h, logging.StreamHandler)]
        self.assertEqual(len(streams), 1)

    def test_streams_are_reconfigured_to_requested_charset(self):
        stdout = _FakeStream()
        stderr = _FakeStream()
        with patch.dict(os.environ, {"PYTHONIOENCODING": "gbk"}, clear=False):
            with patch.object(logging_setup.sys, "stdout", stdout), \
                    patch.object(logging_setup.sys, "stderr", stderr):
                logging_setup.setup_logging("poller")
        self.assertEqual(stdout.calls, [{"encoding": "gbk", "errors": "replace"}])
        self.assertEqual(stderr.calls, [{"encoding": "gbk", "errors": "replace"}])

    def test_terminal_keeps_its_own_encoding(self):
        # Windows 终端按 GBK 解码，硬写 UTF-8 才是乱码的来源，所以终端下不动编码
        tty = _FakeStream(tty=True)
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("PYTHONIOENCODING", None)
            with patch.object(logging_setup.sys, "stdout", tty), \
                    patch.object(logging_setup.sys, "stderr", _FakeStream(tty=True)):
                logging_setup.setup_logging("poller")
        self.assertEqual(tty.calls, [{"errors": "replace"}])

    def test_redirected_stream_is_forced_utf8(self):
        # 管道/重定向下按落盘格式统一 UTF-8，方便 grep
        piped = _FakeStream(tty=False)
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("PYTHONIOENCODING", None)
            with patch.object(logging_setup.sys, "stdout", piped), \
                    patch.object(logging_setup.sys, "stderr", _FakeStream(tty=False)):
                logging_setup.setup_logging("poller")
        self.assertEqual(piped.calls, [{"encoding": "utf-8", "errors": "replace"}])

    def test_stream_without_isatty_is_treated_as_redirected(self):
        # 有的流包装没有 isatty（或它会抛），此时当重定向处理，不能抛异常
        class _NoIsatty:
            def __init__(self):
                self.calls = []

            def reconfigure(self, **kwargs):
                self.calls.append(kwargs)

        out, err = _NoIsatty(), _NoIsatty()
        with patch.object(logging_setup.sys, "stdout", out), \
                patch.object(logging_setup.sys, "stderr", err):
            logging_setup._reconfigure_streams()
        self.assertEqual(out.calls, [{"encoding": "utf-8", "errors": "replace"}])
        self.assertEqual(err.calls, [{"encoding": "utf-8", "errors": "replace"}])

    def test_reconfigure_failure_does_not_break_startup(self):
        # 被重定向成没有 reconfigure 的流、或 reconfigure 抛错时，都必须照常起得来
        broken = _FakeStream(raise_on_reconfigure=True)
        with patch.object(logging_setup.sys, "stdout", broken), \
                patch.object(logging_setup.sys, "stderr", object()):
            logging_setup.setup_logging("poller")
        self.assertEqual(self.root.level, logging.INFO)

    def test_file_handler_writes_utf8_when_log_file_dir_is_set(self):
        with tempfile.TemporaryDirectory() as log_dir:
            with patch.dict(os.environ, {"LOG_FILE_DIR": log_dir}, clear=False):
                logger = logging_setup.setup_logging("poller")
                logger.info("中文不应乱码")

            files = [h for h in self.root.handlers if isinstance(h, RotatingFileHandler)]
            self.assertEqual(len(files), 1)
            self.assertEqual(files[0].encoding, "utf-8")

            log_path = Path(log_dir) / "poller.log"
            self.assertTrue(log_path.exists())
            self.assertIn("中文不应乱码", log_path.read_text(encoding="utf-8"))
            # Windows 上文件仍被 handler 占用时 TemporaryDirectory 删不掉，先松手
            for handler in files:
                handler.close()

    def test_unwritable_log_dir_degrades_to_console_only(self):
        # 只读挂载/权限不足时不该让任务起不来。
        # 用一个「父路径是普通文件」的目录名，makedirs 必定抛 OSError。
        with tempfile.TemporaryDirectory() as tmp:
            blocker = Path(tmp) / "not_a_dir"
            blocker.write_text("x", encoding="utf-8")
            with patch.dict(os.environ, {"LOG_FILE_DIR": str(blocker / "logs")}, clear=False):
                logging_setup.setup_logging("poller")
        files = [h for h in self.root.handlers if isinstance(h, RotatingFileHandler)]
        self.assertEqual(files, [])
        streams = [h for h in self.root.handlers if isinstance(h, logging.StreamHandler)]
        self.assertEqual(len(streams), 1)

    def test_no_file_handler_without_log_file_dir(self):
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("LOG_FILE_DIR", None)
            logging_setup.setup_logging("poller")
        files = [h for h in self.root.handlers if isinstance(h, RotatingFileHandler)]
        self.assertEqual(files, [])


if __name__ == "__main__":
    unittest.main()