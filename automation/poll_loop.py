# -*- coding: utf-8 -*-
"""按北京时间整分对齐，每分钟询问是否有到期订阅并生成主题段。"""
import os
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

BEIJING_TZ = timezone(timedelta(hours=8))

# 日志模块与各报告脚本同在 scripts/ 下，先把它挂进 sys.path 再导入
SCRIPTS_DIR = Path(__file__).resolve().parent / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from logging_setup import setup_logging  # noqa: E402

logger = setup_logging("poller")


def now_beijing():
    return datetime.now(BEIJING_TZ)


def seconds_until_next_minute(now=None):
    current = now if now is not None else now_beijing()
    nxt = current.replace(second=0, microsecond=0) + timedelta(minutes=1)
    return max(0.05, (nxt - current).total_seconds())


def load_daily_report():
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    import daily_report
    return daily_report


def run_once(daily_report=None):
    os.environ["MODE"] = "poll"
    report = daily_report if daily_report is not None else load_daily_report()
    try:
        report.main()
    except SystemExit as exc:
        if exc.code not in (0, None):
            logger.warning("本轮轮询退出码 %s", exc.code)


def start_heartbeat(daily_report):
    def beat():
        while True:
            try:
                daily_report.post_poller_heartbeat("running")
            except Exception:
                logger.warning("心跳线程异常", exc_info=True)
            time.sleep(60)
    thread = threading.Thread(target=beat, name="poller-heartbeat", daemon=True)
    thread.start()
    return thread


def loop(sleep=time.sleep, daily_report=None):
    logger.info("订阅生成器已启动，按北京时间整分对齐")
    report = daily_report if daily_report is not None else load_daily_report()
    start_heartbeat(report)
    while True:
        # 每分钟一条，放 debug，需要时用 LOG_LEVEL=DEBUG 打开
        logger.debug("轮询 %s", now_beijing().strftime("%H:%M:%S"))
        run_once(daily_report=report)
        sleep(seconds_until_next_minute())


if __name__ == "__main__":
    loop()
