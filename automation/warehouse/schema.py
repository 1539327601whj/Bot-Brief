# -*- coding: utf-8 -*-
"""schema 自检。

``backend/sql/V15__investment_warehouse.sql`` 是表结构的唯一事实来源；
``schema_manifest.json`` 是给进程看的同一份契约。worker 启动时比对
``information_schema``，缺表/缺列**直接退出**，不允许带着半个 schema 跑：

* 缺表时写入会抛 SQL 错误，但批量任务里那个错误很容易被「单段失败不影响其他段」
  吞掉，最后表现为页面长期空白；
* 缺列时更糟——读路径会一直少一个字段，看起来像「源不提供」。

改表时三处必须一起改：V15、``backend/sql/init.sql``、本 manifest。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping

from .db import WarehouseDb

MANIFEST_PATH = Path(__file__).with_name("schema_manifest.json")
MIGRATION_FILE = "backend/sql/V15__investment_warehouse.sql"


class SchemaMismatch(RuntimeError):
    def __init__(self, missing_tables: list[str], missing_columns: dict[str, list[str]]):
        self.missing_tables = missing_tables
        self.missing_columns = missing_columns
        parts = []
        if missing_tables:
            parts.append("缺表: " + ", ".join(missing_tables))
        if missing_columns:
            detail = "; ".join(f"{table}({', '.join(cols)})" for table, cols in missing_columns.items())
            parts.append("缺列: " + detail)
        super().__init__(
            "warehouse schema 不完整：" + "；".join(parts)
            + f"。请先执行 {MIGRATION_FILE}，再启动 warehouse。"
        )


@dataclass(frozen=True)
class SchemaReport:
    ok: bool
    checked_tables: int
    missing_tables: list[str] = field(default_factory=list)
    missing_columns: dict[str, list[str]] = field(default_factory=dict)
    database: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "ok": self.ok,
            "database": self.database,
            "checkedTables": self.checked_tables,
            "missingTables": self.missing_tables,
            "missingColumns": self.missing_columns,
        }


def load_manifest(path: Path | None = None) -> dict[str, Any]:
    manifest_path = path or MANIFEST_PATH
    with open(manifest_path, "r", encoding="utf-8") as handle:
        manifest = json.load(handle)
    if not isinstance(manifest, dict) or not isinstance(manifest.get("tables"), dict):
        raise ValueError(f"schema manifest 格式错误: {manifest_path}")
    return manifest


def check_schema(db: WarehouseDb, manifest: Mapping[str, Any] | None = None) -> SchemaReport:
    contract = manifest or load_manifest()
    database_row = db.query_one("SELECT DATABASE() AS db")
    database = (database_row or {}).get("db")

    table_rows = db.query_all(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
    )
    existing_tables = {str(row.get("table_name") or row.get("TABLE_NAME") or "") for row in table_rows}

    column_rows = db.query_all(
        "SELECT table_name, column_name FROM information_schema.columns "
        "WHERE table_schema = DATABASE()"
    )
    existing_columns: dict[str, set[str]] = {}
    for row in column_rows:
        table = str(row.get("table_name") or row.get("TABLE_NAME") or "")
        column = str(row.get("column_name") or row.get("COLUMN_NAME") or "")
        existing_columns.setdefault(table, set()).add(column)

    tables = contract.get("tables") or {}
    missing_tables: list[str] = []
    missing_columns: dict[str, list[str]] = {}
    for table, spec in tables.items():
        if table not in existing_tables:
            missing_tables.append(table)
            continue
        required = list((spec or {}).get("requiredColumns") or [])
        absent = [column for column in required if column not in existing_columns.get(table, set())]
        if absent:
            missing_columns[table] = absent

    return SchemaReport(
        ok=not missing_tables and not missing_columns,
        checked_tables=len(tables),
        missing_tables=missing_tables,
        missing_columns=missing_columns,
        database=str(database) if database is not None else None,
    )


def assert_schema(db: WarehouseDb, manifest: Mapping[str, Any] | None = None) -> SchemaReport:
    report = check_schema(db, manifest)
    if not report.ok:
        raise SchemaMismatch(report.missing_tables, report.missing_columns)
    return report
