# -*- coding: utf-8 -*-
"""统一的日志初始化：把裸 print 换成带级别、带时间戳的 logging。

解决三件事：
1. 中文乱码 —— 终端下跟随终端编码（Windows 上是 GBK，硬写 UTF-8 才会乱码），
   重定向/管道下固定 UTF-8；两种情况都带 errors="replace"，emoji 打不出来也不抛
   UnicodeEncodeError。
2. 落盘 —— 设置了 LOG_FILE_DIR 就额外写一份 UTF-8 的轮转文件，终端怎么显示都不影响事后查。
3. 级别 —— LOG_LEVEL 环境变量控制，默认 INFO。

用法：
    from logging_setup import setup_logging
    logger = setup_logging(__name__)
    logger.info("...")
"""
import logging
import os
import sys
from logging.handlers import RotatingFileHandler

LOG_FORMAT = "%(asctime)s %(levelname)-5s [%(name)s] %(message)s"
DATE_FORMAT = "%Y-%m-%d %H:%M:%S"

FILE_MAX_BYTES = 50 * 1024 * 1024
FILE_BACKUP_COUNT = 7

_configured = False


def _reconfigure_streams(charset=None):
    """就地调整 stdout/stderr 的编码。

    errors="replace" 保证编码失败（典型是 GBK 终端打 emoji）时只丢字符、不炸流程。

    编码怎么定：
    - 显式给了 charset（PYTHONIOENCODING）就听它的；
    - 没给且是终端 —— 跟随终端自己的编码。写 UTF-8 给按 GBK 解码的 Windows 终端
      看就是乱码，而这是本地跑脚本的默认情形；
    - 没给且被重定向/管道 —— 固定 UTF-8，跟落盘格式保持一致，方便 grep。
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            # 被重定向成非 TextIOWrapper 时没有这个方法，跳过即可
            continue
        try:
            if charset:
                reconfigure(encoding=charset, errors="replace")
            elif _is_tty(stream):
                reconfigure(errors="replace")
            else:
                reconfigure(encoding="utf-8", errors="replace")
        except (ValueError, OSError):
            pass


def _is_tty(stream):
    isatty = getattr(stream, "isatty", None)
    if isatty is None:
        return False
    try:
        return bool(isatty())
    except (ValueError, OSError):
        return False


def _build_file_handler(formatter):
    log_dir = os.environ.get("LOG_FILE_DIR", "").strip()
    if not log_dir:
        return None
    try:
        os.makedirs(log_dir, exist_ok=True)
        handler = RotatingFileHandler(
            os.path.join(log_dir, "poller.log"),
            maxBytes=FILE_MAX_BYTES,
            backupCount=FILE_BACKUP_COUNT,
            encoding="utf-8",
        )
    except OSError:
        # 目录建不出来（权限/只读挂载）不该让任务起不来，退化成只打控制台
        return None
    handler.setFormatter(formatter)
    return handler


def setup_logging(name="automation", level=None):
    """初始化根 logger 并返回具名 logger。重复调用安全，不会重复挂 handler。"""
    global _configured
    resolved = (level or os.environ.get("LOG_LEVEL") or "INFO").upper()

    root = logging.getLogger()
    if not _configured:
        _reconfigure_streams(os.environ.get("PYTHONIOENCODING") or None)
        formatter = logging.Formatter(LOG_FORMAT, datefmt=DATE_FORMAT)
        if not root.handlers:
            stream_handler = logging.StreamHandler(sys.stdout)
            stream_handler.setFormatter(formatter)
            root.addHandler(stream_handler)
            file_handler = _build_file_handler(formatter)
            if file_handler is not None:
                root.addHandler(file_handler)
        _configured = True

    root.setLevel(resolved)
    return logging.getLogger(name)