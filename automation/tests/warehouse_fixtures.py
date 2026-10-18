# -*- coding: utf-8 -*-
"""warehouse 单测的公共夹具。

目录结构（**没有** ``warehouse/__init__.py``）：

* ``automation/tests/warehouse_fixtures.py`` —— 本文件，纯 helper，名字不以 ``test`` 开头，
  所以 ``unittest discover`` 不会把它当测试模块；
* ``automation/tests/warehouse/fixtures/`` —— 各源的响应样例。

``warehouse/`` 下刻意不放 ``__init__.py``：否则 discovery 会把它当成名为 ``warehouse``
的包，和 ``automation/warehouse`` 撞名，测试 import 到的就不是源码包了。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

AUTOMATION_DIR = Path(__file__).resolve().parents[1]
FIXTURES_DIR = Path(__file__).resolve().parent / "warehouse" / "fixtures"


def ensure_path() -> None:
    """把 ``automation/`` 放到 ``sys.path`` 最前，确保 ``import warehouse`` 命中源码包。"""
    target = str(AUTOMATION_DIR)
    if target in sys.path:
        sys.path.remove(target)
    sys.path.insert(0, target)


def load_json(name: str) -> Any:
    with open(FIXTURES_DIR / name, "r", encoding="utf-8") as handle:
        return json.load(handle)


def load_text(name: str) -> str:
    with open(FIXTURES_DIR / name, "r", encoding="utf-8") as handle:
        return handle.read()


class FakeDb:
    """最小 DB 替身：记录写入，按需返回查询结果。

    ``query_results`` 是队列，按调用顺序弹出；``sources`` 专门用于
    ``existing_sources`` 的 ``DISTINCT source`` 查询。
    """

    def __init__(
        self,
        query_results: list[Any] | None = None,
        *,
        sources: list[str] | None = None,
        error: Exception | None = None,
    ):
        self.query_results = list(query_results or [])
        self.sources = list(sources or [])
        self.error = error
        self.upserts: list[dict[str, Any]] = []
        self.executed: list[tuple[str, Any]] = []

    def _maybe_raise(self) -> None:
        if self.error is not None:
            raise self.error

    def upsert(self, table, rows, key_columns, **kwargs):
        self._maybe_raise()
        rows = list(rows)
        self.upserts.append({"table": table, "rows": rows, "keys": list(key_columns)})
        return len(rows)

    def query_all(self, sql, params=None):
        self._maybe_raise()
        if "DISTINCT source FROM md_price_daily" in sql:
            return [{"source": source} for source in self.sources]
        if self.query_results:
            result = self.query_results.pop(0)
            return result if isinstance(result, list) else [result]
        return []

    def query_one(self, sql, params=None):
        self._maybe_raise()
        if self.query_results:
            result = self.query_results.pop(0)
            if isinstance(result, list):
                return result[0] if result else None
            return result
        return None

    def execute(self, sql, params=None):
        self._maybe_raise()
        self.executed.append((sql, params))
        return 1

    def close(self) -> None:
        pass

    def fetch_upsert(self, table: str) -> list[dict[str, Any]]:
        return [item for item in self.upserts if item["table"] == table]


class FakeHttp:
    """``HttpSource`` 替身：按 URL 片段返回预置响应，并记录调用次数。"""

    def __init__(self, json_by_url: dict[str, Any] | None = None,
                 text_by_url: dict[str, Any] | None = None):
        self.json_by_url = json_by_url or {}
        self.text_by_url = text_by_url or {}
        self.calls: list[tuple[str, str, dict[str, Any] | None]] = []

    def _pick(self, mapping: dict[str, Any], url: str) -> Any:
        for marker, value in mapping.items():
            if marker in url:
                return value() if callable(value) else value
        raise AssertionError(f"没有为 {url} 预置响应")

    def get_json(self, provider, url, *, params=None, headers=None, timeout=None):
        self.calls.append((provider, url, params))
        return self._pick(self.json_by_url, url)

    def get_text(self, provider, url, *, params=None, headers=None, timeout=None,
                 encoding=None):
        self.calls.append((provider, url, params))
        return self._pick(self.text_by_url, url)


__all__ = [
    "AUTOMATION_DIR",
    "FIXTURES_DIR",
    "FakeDb",
    "FakeHttp",
    "ensure_path",
    "load_json",
    "load_text",
]
