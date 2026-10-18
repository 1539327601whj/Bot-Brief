# -*- coding: utf-8 -*-
"""warehouse schema 自检（部署/健康检查用）。

与 worker 启动时的自检读同一份 ``warehouse/schema_manifest.json``，区别是这里
**不退出进程、不落任何数据**，只回报结果并以退出码表达：

* ``0`` schema 完整；
* ``2`` 缺表/缺列，或数据库不可达（两者对调用方都是「现在不能写」）；
* ``1`` 参数/清单文件本身有问题。

常用::

    py -3 automation/scripts/verify_warehouse_schema.py --manifest
    docker compose exec warehouse python scripts/verify_warehouse_schema.py --manifest

``--manifest`` 不带值时使用内置契约；需要比对别的库时用 ``--manifest-path``。
容器 healthcheck 依赖 ``0/2`` 的区分，**不要把「DB 不通」当成健康**。
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import replace
from pathlib import Path
from typing import Any

# 直接以脚本方式运行时，把 automation/ 放进 sys.path（与 worker / sync CLI 一致）。
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from warehouse.config import Config, DbConfig  # noqa: E402
from warehouse.db import WarehouseDb, WarehouseDbError  # noqa: E402
from warehouse.schema import (  # noqa: E402
    MANIFEST_PATH,
    SchemaMismatch,
    check_schema,
    load_manifest,
)

EXIT_OK = 0
EXIT_BAD_ARGS = 1
EXIT_NOT_READY = 2


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="检查 warehouse 的 md_* 表结构")
    parser.add_argument(
        "--manifest",
        action="store_true",
        help="按 manifest 校验（默认行为；保留该开关供 healthcheck 显式表达意图）",
    )
    parser.add_argument(
        "--manifest-path",
        metavar="PATH",
        help="自定义 manifest 路径（默认 warehouse/schema_manifest.json）",
    )
    parser.add_argument("--json", action="store_true", help="只输出 JSON")
    parser.add_argument(
        "--connect-timeout",
        type=int,
        default=None,
        help="覆盖数据库连接超时秒数（容器 healthcheck 建议 3）",
    )
    return parser.parse_args(argv)


def _db_config(base: DbConfig, connect_timeout: int | None) -> DbConfig:
    if connect_timeout is None:
        return base
    return DbConfig(
        host=base.host,
        port=base.port,
        name=base.name,
        user=base.user,
        password=base.password,
        connect_timeout=max(1, connect_timeout),
        read_timeout=base.read_timeout,
        write_timeout=base.write_timeout,
        _using_app_account=base.using_app_account,
    )


def verify(config: Config, manifest_path: Path | None, json_only: bool) -> int:
    payload: dict[str, Any] = {
        "ok": False,
        "database": config.db.name,
        "host": config.db.host,
        "manifest": str(manifest_path or MANIFEST_PATH),
    }

    try:
        manifest = load_manifest(manifest_path)
    except (OSError, ValueError) as exc:
        payload["error"] = f"manifest 读取失败: {exc}"
        _emit(payload, json_only)
        return EXIT_BAD_ARGS

    db = WarehouseDb(config.db)
    try:
        report = check_schema(db, manifest)
    except SchemaMismatch as exc:  # 目前 check_schema 不抛，保留以防行为变化
        payload["error"] = str(exc)
        _emit(payload, json_only)
        return EXIT_NOT_READY
    except WarehouseDbError as exc:
        payload["error"] = f"数据库不可用: {exc}"
        _emit(payload, json_only)
        return EXIT_NOT_READY
    except Exception as exc:  # noqa: BLE001 - 任何未知错误都不能被当成健康
        payload["error"] = f"{type(exc).__name__}: {exc}"
        _emit(payload, json_only)
        return EXIT_NOT_READY
    finally:
        db.close()

    payload.update(report.as_dict())
    payload["database"] = report.database or config.db.name
    _emit(payload, json_only)
    return EXIT_OK if report.ok else EXIT_NOT_READY


def _emit(payload: dict[str, Any], json_only: bool) -> None:
    if json_only:
        print(json.dumps(payload, ensure_ascii=False, default=str))
        return
    if payload.get("ok"):
        print(
            f"schema ok database={payload.get('database')} "
            f"tables={payload.get('checkedTables')} manifest={payload.get('manifest')}"
        )
        return
    print(f"schema NOT ready: {payload.get('error') or ''}", file=sys.stderr)
    missing_tables = payload.get("missingTables") or []
    if missing_tables:
        print("  缺表: " + ", ".join(missing_tables), file=sys.stderr)
    for table, columns in (payload.get("missingColumns") or {}).items():
        print(f"  {table} 缺列: {', '.join(columns)}", file=sys.stderr)
    if not payload.get("error") and not missing_tables and not payload.get("missingColumns"):
        print("  （原因未知，见 --json 输出）", file=sys.stderr)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    manifest_path = Path(args.manifest_path).expanduser() if args.manifest_path else None
    config = Config.from_env()
    if args.connect_timeout is not None:
        config = replace(config, db=_db_config(config.db, args.connect_timeout))
    return verify(config, manifest_path, args.json)


if __name__ == "__main__":
    sys.exit(main())
