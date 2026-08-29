# -*- coding: utf-8 -*-
"""按北京时间整分对齐，每分钟询问是否有到期订阅并生成主题段。"""
import os
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

BEIJING_TZ = timezone(timedelta(hours=8))


def now_beijing():
    return datetime.now(BEIJING_TZ)


def seconds_until_next_minute(now=None):
    current = now if now is not None else now_beijing()
    nxt = current.replace(second=0, microsecond=0) + timedelta(minutes=1)
    return max(0.05, (nxt - current).total_seconds())


def load_daily_report():
    module_path = Path(__file__).resolve().parent / "scripts" / "daily_report.py"
    sys.path.insert(0, str(module_path.parent))
    import daily_report
    return daily_report


def run_once(daily_report=None):
    os.environ["MODE"] = "poll"
    report = daily_report if daily_report is not None else load_daily_report()
    try:
        report.main()
    except SystemExit as exc:
        if exc.code not in (0, None):
            print(f"⚠️ 本轮轮询退出码 {exc.code}")


def start_heartbeat(daily_report):
    def beat():
        while True:
            try:
                daily_report.post_poller_heartbeat("running")
            except Exception as exc:
                print(f"⚠️ 心跳线程异常: {exc}")
            time.sleep(60)
    thread = threading.Thread(target=beat, name="poller-heartbeat", daemon=True)
    thread.start()
    return thread


def loop(sleep=time.sleep, daily_report=None):
    print("🕒 订阅生成器已启动，按北京时间整分对齐")
    report = daily_report if daily_report is not None else load_daily_report()
    start_heartbeat(report)
    while True:
        started = now_beijing().strftime("%H:%M:%S")
        print(f"\n—— 轮询 {started} ——")
        run_once(daily_report=report)
        sleep(seconds_until_next_minute())


if __name__ == "__main__":
    loop()
