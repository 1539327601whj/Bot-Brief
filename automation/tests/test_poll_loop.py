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
        poll_loop.run_once(daily_report=report)
        report.main.assert_called_once()
        self.assertEqual(poll_loop.os.environ.get("MODE"), "poll")

    def test_run_once_does_not_stop_loop_on_script_exit(self):
        report = Mock()
        report.main.side_effect = SystemExit(1)
        poll_loop.run_once(daily_report=report)


if __name__ == "__main__":
    unittest.main()
