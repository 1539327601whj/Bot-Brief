import importlib.util
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import Mock

MODULE_PATH = Path(__file__).parents[1] / "poll_loop.py"
SPEC = importlib.util.spec_from_file_location("poll_loop", MODULE_PATH)
poll_loop = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(poll_loop)

BEIJING = timezone(timedelta(hours=8))
# 2026-08-31 是周一，2026-08-29 是周六
MONDAY_AFTERNOON = datetime(2026, 8, 31, 16, 31, tzinfo=BEIJING)
MONDAY_MORNING = datetime(2026, 8, 31, 16, 29, tzinfo=BEIJING)
SATURDAY = datetime(2026, 8, 29, 16, 31, tzinfo=BEIJING)


def idle_pool_sync(latest_trade_date=None, sync_result=True):
    """一个不会碰网络的 index_pool_sync 替身。

    真模块 import 进来会去读 BACKEND_API_URL，测试里必须把它换掉，
    否则断言结果会随「跑测试的墙上时间」变化。
    """
    module = Mock()
    module.PROBE_INDEX_CODE = "SH000300"
    module.latest_valuation_trade_date.return_value = latest_trade_date
    module.sync_index_pool.return_value = sync_result
    return module


class PollLoopTests(unittest.TestCase):
    def test_aligns_to_next_clock_minute(self):
        now = datetime(2026, 8, 29, 20, 20, 0, tzinfo=BEIJING)
        self.assertAlmostEqual(poll_loop.seconds_until_next_minute(now), 60.0)

        late = datetime(2026, 8, 29, 20, 20, 45, tzinfo=BEIJING)
        self.assertAlmostEqual(poll_loop.seconds_until_next_minute(late), 15.0)

    def test_off_grid_time_still_waits_for_next_minute(self):
        now = datetime(2026, 8, 29, 20, 17, 12, tzinfo=BEIJING)
        self.assertAlmostEqual(poll_loop.seconds_until_next_minute(now), 48.0)

    def test_run_once_forces_poll_mode(self):
        report = Mock()
        poll_loop.run_once(daily_report=report, index_pool_sync=idle_pool_sync())
        report.main.assert_called_once()
        self.assertEqual(poll_loop.os.environ.get("MODE"), "poll")

    def test_run_once_does_not_stop_loop_on_script_exit(self):
        report = Mock()
        report.main.side_effect = SystemExit(1)
        poll_loop.run_once(daily_report=report, index_pool_sync=idle_pool_sync())


class IndexPoolGateTests(unittest.TestCase):
    """指数池的日更门控：只按日期，与订阅无关。"""

    def test_syncs_after_half_past_four_when_todays_row_is_missing(self):
        self.assertTrue(poll_loop.should_sync_index_pool(MONDAY_AFTERNOON, "2026-08-28"))

    def test_does_not_sync_before_the_cutoff(self):
        self.assertFalse(poll_loop.should_sync_index_pool(MONDAY_MORNING, "2026-08-28"))

    def test_does_not_sync_twice_in_one_day(self):
        self.assertFalse(poll_loop.should_sync_index_pool(MONDAY_AFTERNOON, "2026-08-31"))

    def test_does_not_sync_on_weekends(self):
        self.assertFalse(poll_loop.should_sync_index_pool(SATURDAY, "2026-08-28"))

    def test_a_missing_database_row_opens_the_gate(self):
        self.assertTrue(poll_loop.should_sync_index_pool(MONDAY_AFTERNOON, None))

    def test_the_gate_reads_the_pool_sync_own_probe_index(self):
        module = idle_pool_sync(latest_trade_date="2026-08-31")
        poll_loop.sync_index_pool_once(index_pool_sync=module, now=MONDAY_AFTERNOON)

        module.latest_valuation_trade_date.assert_called_once_with(module.PROBE_INDEX_CODE)
        module.sync_index_pool.assert_not_called()

    def test_a_due_sync_calls_the_real_thing_once(self):
        module = idle_pool_sync(latest_trade_date="2026-08-28")
        self.assertTrue(poll_loop.sync_index_pool_once(index_pool_sync=module, now=MONDAY_AFTERNOON))
        module.sync_index_pool.assert_called_once_with()

    def test_a_broken_sync_never_escapes_into_the_loop(self):
        # 它跟在订阅生成后面跑：漏做一次订阅和进程崩掉是两件事
        module = idle_pool_sync(latest_trade_date="2026-08-28")
        module.sync_index_pool.side_effect = RuntimeError("炸了")

        self.assertFalse(poll_loop.sync_index_pool_once(index_pool_sync=module, now=MONDAY_AFTERNOON))


if __name__ == "__main__":
    unittest.main()
