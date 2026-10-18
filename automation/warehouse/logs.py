# -*- coding: utf-8 -*-
"""日志配置。容器里 ``docker logs`` 要能直接看出「哪一段失败、为什么」。"""

from __future__ import annotations

import logging
import sys


def configure(level: str = "INFO") -> None:
    logging.basicConfig(
        level=getattr(logging, (level or "INFO").upper(), logging.INFO),
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
        stream=sys.stdout,
        force=True,
    )
    # requests/urllib3 的 INFO 日志会把每个连接都打出来，噪声太大。
    logging.getLogger("urllib3").setLevel(logging.WARNING)
    logging.getLogger("requests").setLevel(logging.WARNING)


__all__ = ["configure"]
