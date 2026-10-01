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

# 指数池每天 16:30 后同步一次（收盘 + 估值数据源更新完毕）。
# **只按日期门控，与订阅无关**：没有订阅的部署也要刷新池子，挂到订阅到期分支上就永远不跑。
INDEX_POOL_SYNC_HOUR = 16
INDEX_POOL_SYNC_MINUTE = 30


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


def load_index_pool_sync():
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    import index_pool_sync
    return index_pool_sync


def should_sync_index_pool(now, latest_trade_date):
    """今天该不该同步指数池。**纯函数**，时间与库里的值都由调用方给。

    幂等门用的是库里最新一条 `tradeDate`：它已经是今天就说明今天跑过了。
    周末直接跳过（休市，重跑只会拿到同一批数据，还白打几十次中证官网）；
    节假日识别不了，会白跑一轮并原地覆写同一行——代价是流量，不是数据正确性。
    """
    if now.weekday() >= 5:
        return False
    if (now.hour, now.minute) < (INDEX_POOL_SYNC_HOUR, INDEX_POOL_SYNC_MINUTE):
        return False
    return latest_trade_date != now.date().isoformat()


def sync_index_pool_once(index_pool_sync=None, now=None):
    """轮询循环里的指数池同步。**任何异常都不许冒出来**——它跟在订阅生成后面跑，
    漏做一次订阅和进程崩掉是两件事，不能混在一起。"""
    try:
        module = index_pool_sync if index_pool_sync is not None else load_index_pool_sync()
        current = now if now is not None else now_beijing()
        latest = module.latest_valuation_trade_date(module.PROBE_INDEX_CODE)
        if not should_sync_index_pool(current, latest):
            return False
        return bool(module.sync_index_pool())
    except Exception:
        logger.exception("指数池同步异常")
        return False


def run_once(daily_report=None, index_pool_sync=None):
    os.environ["MODE"] = "poll"
    report = daily_report if daily_report is not None else load_daily_report()
    try:
        report.main()
    except SystemExit as exc:
        if exc.code not in (0, None):
            logger.warning("本轮轮询退出码 %s", exc.code)
    sync_index_pool_once(index_pool_sync=index_pool_sync)


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
