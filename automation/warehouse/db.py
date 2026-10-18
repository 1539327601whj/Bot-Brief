# -*- coding: utf-8 -*-
"""MySQL 访问层。

只用显式 SQL，不引入 ORM：warehouse 写的列很少、批量很大，ORM 的便利在这里换不来
任何东西；而 ``ON DUPLICATE KEY UPDATE`` 的 ``fetched_at`` 保护必须能一眼看清。

三条约束：

* **幂等 upsert**：唯一键冲突时，只有 ``VALUES(fetched_at) >= fetched_at`` 才更新，
  且每个列都用 ``COALESCE(VALUES(col), col)``——本次没给的字段保留旧值，绝不清空。
* **缺失是 NULL**：本层不做任何「0 补齐」。
* **批量分块**：单条 SQL 最多 500 行，避免 ``max_allowed_packet`` 与占位符上限。
"""

from __future__ import annotations

import logging
import re
from contextlib import contextmanager
from typing import Any, Iterator, Sequence

from .config import Config, DbConfig

logger = logging.getLogger(__name__)

_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
DEFAULT_CHUNK_SIZE = 500


class WarehouseDbError(RuntimeError):
    """数据库不可用或 SQL 执行失败。"""


def _quote(identifier: str) -> str:
    if not _IDENTIFIER.fullmatch(identifier or ""):
        raise ValueError(f"非法 SQL 标识符: {identifier!r}")
    return f"`{identifier}`"


def chunked(items: Sequence[Any], size: int = DEFAULT_CHUNK_SIZE) -> Iterator[Sequence[Any]]:
    if size <= 0:
        raise ValueError("chunk size 必须大于 0")
    for start in range(0, len(items), size):
        yield items[start:start + size]


class WarehouseDb:
    """一个进程一个连接；worker 是单线程，CLI 也是。

    ``connect_factory`` 只给测试用：单测不需要真的 pymysql，也不需要 MySQL。
    """

    def __init__(self, config: DbConfig, connect_factory=None, chunk_size: int = DEFAULT_CHUNK_SIZE):
        self.config = config
        self._connect_factory = connect_factory
        self._connection = None
        self.chunk_size = chunk_size

    # ------------------------------------------------------------------
    # 连接
    # ------------------------------------------------------------------
    def _open(self):
        if self._connect_factory is not None:
            return self._connect_factory(self.config)
        try:
            import pymysql
            from pymysql.cursors import DictCursor
        except ImportError as exc:  # pragma: no cover - 依赖缺失时给出可操作提示
            raise WarehouseDbError(
                "缺少 pymysql。请先执行 `pip install -r automation/requirements.txt`，"
                "或在 docker compose 里重建 poller/warehouse 镜像。"
            ) from exc
        return pymysql.connect(
            host=self.config.host,
            port=self.config.port,
            user=self.config.user,
            password=self.config.password,
            database=self.config.name,
            charset="utf8mb4",
            autocommit=True,
            cursorclass=DictCursor,
            connect_timeout=self.config.connect_timeout,
            read_timeout=self.config.read_timeout,
            write_timeout=self.config.write_timeout,
        )

    def connection(self):
        """返回复用的连接；断线时 ``ping(reconnect=True)``。"""
        if self._connection is None:
            self._connection = self._open()
            logger.info(
                "warehouse DB 已连接 host=%s port=%s db=%s user=%s",
                self.config.host, self.config.port, self.config.name, self.config.user,
            )
        else:
            try:
                self._connection.ping(reconnect=True)
            except Exception:
                # 重连也失败就让调用方看到真实的 DB 错误，不要假装还活着。
                self.close()
                self._connection = self._open()
        return self._connection

    def close(self) -> None:
        if self._connection is not None:
            try:
                self._connection.close()
            except Exception:  # pragma: no cover - 关闭失败没有补救动作
                pass
            self._connection = None

    def __enter__(self) -> "WarehouseDb":
        self.connection()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    # ------------------------------------------------------------------
    # 查询 / 执行
    # ------------------------------------------------------------------
    def execute(self, sql: str, params: Sequence[Any] | None = None) -> int:
        conn = self.connection()
        try:
            with conn.cursor() as cursor:
                return cursor.execute(sql, tuple(params or ()))
        except Exception as exc:
            raise WarehouseDbError(f"SQL 执行失败: {type(exc).__name__}: {exc}") from exc

    def execute_many(self, sql: str, rows: Sequence[Sequence[Any]]) -> int:
        if not rows:
            return 0
        conn = self.connection()
        try:
            with conn.cursor() as cursor:
                return cursor.executemany(sql, [tuple(row) for row in rows])
        except Exception as exc:
            raise WarehouseDbError(f"SQL 批量执行失败: {type(exc).__name__}: {exc}") from exc

    def query_all(self, sql: str, params: Sequence[Any] | None = None) -> list[dict[str, Any]]:
        conn = self.connection()
        try:
            with conn.cursor() as cursor:
                cursor.execute(sql, tuple(params or ()))
                return list(cursor.fetchall())
        except Exception as exc:
            raise WarehouseDbError(f"SQL 查询失败: {type(exc).__name__}: {exc}") from exc

    def query_one(self, sql: str, params: Sequence[Any] | None = None) -> dict[str, Any] | None:
        rows = self.query_all(sql, params)
        return rows[0] if rows else None

    @contextmanager
    def transaction(self):
        """显式事务。调用方负责 commit / rollback；异常时自动 rollback。"""
        conn = self.connection()
        conn.begin()
        try:
            yield conn
        except Exception:
            try:
                conn.rollback()
            finally:
                raise
        else:
            conn.commit()

    # ------------------------------------------------------------------
    # 幂等 upsert
    # ------------------------------------------------------------------
    def upsert(
        self,
        table: str,
        rows: Sequence[dict[str, Any]],
        key_columns: Sequence[str],
        *,
        guard_column: str = "fetched_at",
        chunk_size: int | None = None,
    ) -> int:
        """批量 upsert。返回写入行数（按输入行数计，不是 MySQL 的 affected rows）。

        ``rows`` 里每一行的列必须完全一致。``guard_column`` 必须在列里：
        它既是「旧数据不覆盖新数据」的比较基准，也是唯一键冲突时最后一列更新的对象。
        """
        if not rows:
            return 0
        keys = list(key_columns)
        if not keys:
            raise ValueError("key_columns 不能为空")
        columns = list(rows[0].keys())
        if guard_column not in columns:
            raise ValueError(f"{table}: 缺少 fetched_at 保护列 {guard_column}")
        for row in rows:
            if list(row.keys()) != columns:
                raise ValueError(f"{table}: 同一批行的列不一致")
        for name in [table, *columns]:
            _quote(name)

        update_columns = [c for c in columns if c not in keys and c != guard_column]
        guard = _quote(guard_column)
        assignments = [
            f"{_quote(c)} = IF(VALUES({guard}) >= {guard}, COALESCE(VALUES({_quote(c)}), {_quote(c)}), {_quote(c)})"
            for c in update_columns
        ]
        assignments.append(f"{guard} = GREATEST({guard}, VALUES({guard}))")
        placeholders = "(" + ", ".join(["%s"] * len(columns)) + ")"
        sql = (
            f"INSERT INTO {_quote(table)} ({', '.join(_quote(c) for c in columns)}) "
            f"VALUES {placeholders} "
            f"ON DUPLICATE KEY UPDATE {', '.join(assignments)}"
        )

        size = chunk_size or self.chunk_size
        written = 0
        for batch in chunked(rows, size):
            params = [tuple(row[c] for c in columns) for row in batch]
            self.execute_many(sql, params)
            written += len(batch)
        return written

    def upsert_sql(self, table: str, columns: Sequence[str], key_columns: Sequence[str],
                   guard_column: str = "fetched_at") -> str:
        """只拼 SQL，不执行。测试用它钉住 ``fetched_at`` 保护与列白名单。"""
        keys = set(key_columns)
        for name in [table, *columns]:
            _quote(name)
        if guard_column not in columns:
            raise ValueError(f"{table}: 缺少保护列 {guard_column}")
        guard = _quote(guard_column)
        updates = [
            f"{_quote(c)} = IF(VALUES({guard}) >= {guard}, "
            f"COALESCE(VALUES({_quote(c)}), {_quote(c)}), {_quote(c)})"
            for c in columns if c not in keys and c != guard_column
        ]
        updates.append(f"{guard} = GREATEST({guard}, VALUES({guard}))")
        placeholders = "(" + ", ".join(["%s"] * len(columns)) + ")"
        return (
            f"INSERT INTO {_quote(table)} ({', '.join(_quote(c) for c in columns)}) "
            f"VALUES {placeholders} ON DUPLICATE KEY UPDATE {', '.join(updates)}"
        )
