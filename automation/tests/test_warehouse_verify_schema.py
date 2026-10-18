# -*- coding: utf-8 -*-
import contextlib
import importlib.util
import io
from pathlib import Path

import unittest

import warehouse_fixtures

warehouse_fixtures.ensure_path()

from warehouse.config import Config  # noqa: E402
from warehouse.db import WarehouseDbError  # noqa: E402
from warehouse.schema import load_manifest  # noqa: E402

FakeDb = warehouse_fixtures.FakeDb
SCRIPT = Path(warehouse_fixtures.AUTOMATION_DIR) / "scripts" / "verify_warehouse_schema.py"


def load_script():
    spec = importlib.util.spec_from_file_location("verify_warehouse_schema", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def healthy_db():
    tables = list((load_manifest().get("tables") or {}).keys())
    columns = {
        table: list(spec.get("requiredColumns") or [])
        for table, spec in load_manifest()["tables"].items()
    }
    return FakeDb(query_results=[
        {"db": "brief"},
        [{"table_name": name} for name in tables],
        [{"table_name": table, "column_name": column} for table, cols in columns.items()
         for column in cols],
    ])


class VerifySchemaCliTest(unittest.TestCase):
    def run_verify(self, db, manifest_path=None):
        script = load_script()
        script.WarehouseDb = lambda config: db
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = script.verify(Config.from_env(), manifest_path, True)
        return code, buffer.getvalue()

    def test_healthy_schema_exits_zero(self):
        code, output = self.run_verify(healthy_db())
        self.assertEqual(code, 0)
        self.assertIn('"ok": true', output)

    def test_database_unreachable_exits_two(self):
        code, output = self.run_verify(FakeDb(error=WarehouseDbError("down")))
        self.assertEqual(code, 2)
        self.assertIn("数据库不可用", output)

    def test_missing_table_exits_two(self):
        code, output = self.run_verify(FakeDb(query_results=[
            {"db": "brief"},
            [],
            [],
        ]))
        self.assertEqual(code, 2)
        self.assertIn("missingTables", output)

    def test_bad_manifest_path_exits_one(self):
        code, output = self.run_verify(healthy_db(), Path("does-not-exist.json"))
        self.assertEqual(code, 1)
        self.assertIn("manifest", output)


if __name__ == "__main__":
    unittest.main()
