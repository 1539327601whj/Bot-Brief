# -*- coding: utf-8 -*-
"""warehouse 手工回填 / 探针 CLI。

常用::

    py -3 automation/scripts/warehouse_sync.py --check-schema
    py -3 automation/scripts/warehouse_sync.py --daily
    py -3 automation/scripts/warehouse_sync.py --index              # 池子里全部指数
    py -3 automation/scripts/warehouse_sync.py --index SH000300
    py -3 automation/scripts/warehouse_sync.py --etf 510300 --etf 159915
    py -3 automation/scripts/warehouse_sync.py --stock 600519

所有数据写入 ``md_*``，重复执行是幂等的。**失败不重试**：429 只会记录并让该段
以 FAILED 结束，调用方看到非零退出码或 ``status=FAILED`` 后等冷却结束再跑。

``--noproxy`` 是默认行为（``WAREHOUSE_TRUST_ENV=false``）。本机开着系统代理 7890 时，
国内行情域名会失败；只有确实需要走代理时才加 ``--proxy``。
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from dataclasses import replace
from pathlib import Path
from typing import Any

# 直接以脚本方式运行时，把 automation/ 放进 sys.path（与 worker 一致）。
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from warehouse.clients import Clients, build_clients  # noqa: E402
from warehouse.config import Config  # noqa: E402
from warehouse.logs import configure as configure_logging  # noqa: E402
from warehouse.normalize import (  # noqa: E402
    asset_instrument_code,
    guess_market,
    now_beijing,
)
from warehouse.rate_limit import RateLimited, SourceCooling  # noqa: E402
from warehouse.schema import SchemaMismatch, check_schema  # noqa: E402
from warehouse.sync import SyncOutcome, combine  # noqa: E402
from warehouse.sync.index_profile import sync_index_detail  # noqa: E402
from warehouse.sync.industry import sync_industry_boards  # noqa: E402
from warehouse.sync.instruments import (  # noqa: E402
    index_market,
    load_index_pool,
    pool_rows,
)
from warehouse.sync.prices import sync_price_series  # noqa: E402
from warehouse.sync.snapshot import sync_market_snapshot  # noqa: E402
from warehouse.sync.stock import sync_stock_detail  # noqa: E402
from warehouse.sync.valuations import sync_stock_valuation  # noqa: E402

logger = logging.getLogger("warehouse.cli")


def _run(label: str, loader) -> SyncOutcome:
    try:
        return loader()
    except RateLimited as exc:
        logger.warning("%s 被限流，停止该段（不重试）: %s", label, exc)
        return SyncOutcome("FAILED", error=f"限流: {exc}")
    except SourceCooling as exc:
        logger.warning("%s 处于冷却，跳过: %s", label, exc)
        return SyncOutcome("SKIPPED", error=str(exc))
    except Exception as exc:  # noqa: BLE001
        logger.exception("%s 失败", label)
        return SyncOutcome("FAILED", error=f"{type(exc).__name__}: {exc}")


def _pool_entry(pool: list[dict[str, Any]], code: str) -> dict[str, Any] | None:
    target = code.strip().upper()
    for entry in pool:
        candidates = {
            str(entry.get("indexCode") or "").upper(),
            str(entry.get("csindexCode") or "").upper(),
            str(entry.get("danjuanCode") or "").upper(),
            str(entry.get("etfCode") or "").upper(),
        }
        if target in candidates:
            return entry
    return None


def sync_stock_full(clients: Clients, code: str) -> SyncOutcome:
    code = code.strip().upper()
    stamp = now_beijing()
    market = guess_market(code, "STOCK")
    parts: dict[str, SyncOutcome] = {}

    parts["valuation"] = _run(
        f"{code} 估值历史", lambda: sync_stock_valuation(clients.db, clients.eastmoney, code)
    )
    parts["prices"] = _run(
        f"{code} 日线",
        lambda: sync_price_series(
            clients.db, clients.eastmoney, clients.tencent,
            instrument_code=asset_instrument_code("STOCK", code),
            asset_type="STOCK", raw_code=code, market=market,
        ),
    )
    parts["detail"] = _run(
        f"{code} 详情",
        lambda: sync_stock_detail(clients.db, clients.eastmoney, clients.news, code),
    )
    outcome = combine(f"STOCK:{code}", parts)
    outcome.detail["syncedAt"] = stamp.isoformat(timespec="seconds")
    return outcome


def sync_index(clients: Clients, entry: dict[str, Any], *, with_etf_price: bool = False) -> SyncOutcome:
    index_code = str(entry.get("indexCode") or "").upper()
    etf_code = str(entry.get("etfCode") or "").upper() or None
    parts: dict[str, SyncOutcome] = {}

    parts["detail"] = _run(
        f"{index_code} 画像/权重",
        lambda: sync_index_detail(
            clients.db, clients.csindex, clients.danjuan,
            index_code=index_code,
            csindex_code=entry.get("csindexCode"),
            danjuan_code=entry.get("danjuanCode"),
        ),
    )
    parts["prices"] = _run(
        f"{index_code} 指数日线",
        lambda: sync_price_series(
            clients.db, clients.eastmoney, clients.tencent,
            instrument_code=asset_instrument_code("INDEX", index_code),
            asset_type="INDEX", raw_code=index_code,
            market=index_market(index_code),
        ),
    )
    if with_etf_price and etf_code:
        parts["etfPrices"] = _run(
            f"{etf_code} ETF日线",
            lambda: sync_price_series(
                clients.db, clients.eastmoney, clients.tencent,
                instrument_code=asset_instrument_code("ETF", etf_code),
                asset_type="ETF", raw_code=etf_code,
                market=guess_market(etf_code, "ETF"),
            ),
        )
    return combine(f"INDEX:{index_code}", parts)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="warehouse 手工回填")
    parser.add_argument("--index", nargs="*", metavar="CODE",
                        help="指数代码；不带值表示池子里全部指数")
    parser.add_argument("--etf", nargs="*", metavar="CODE",
                        help="ETF 代码；会同时同步其跟踪指数的画像/权重")
    parser.add_argument("--stock", action="append", default=[], metavar="CODE",
                        help="股票代码，可重复")
    parser.add_argument("--daily", action="store_true",
                        help="跑每日批量：全市场快照 + 指数画像 + 行业板块")
    parser.add_argument("--check-schema", action="store_true", help="只做 schema 自检")
    parser.add_argument("--proxy", action="store_true", help="信任系统代理（默认 False）")
    parser.add_argument("--json", action="store_true", help="只输出 JSON 摘要")
    args = parser.parse_args(argv)

    config = Config.from_env()
    if args.proxy:
        config = replace(config, trust_env=True)
    # --json 时把日志压到 WARNING，stdout 只留机器可读的摘要。
    configure_logging("WARNING" if args.json else config.log_level)

    clients = build_clients(config)
    results: dict[str, Any] = {}

    try:
        report = check_schema(clients.db)
    except SchemaMismatch as exc:
        logger.error("%s", exc)
        return 2
    except Exception as exc:  # noqa: BLE001
        logger.error("schema 自检无法完成: %s", exc)
        return 2
    if not report.ok:
        logger.error("schema 不完整: %s", report.as_dict())
        return 2
    if args.check_schema:
        results["schema"] = report.as_dict()
        _print(results)
        return 0

    if not any([args.index is not None, args.etf is not None, args.stock, args.daily]):
        parser.print_help()
        return 1

    try:
        pool = load_index_pool(config)
    except Exception as exc:  # noqa: BLE001
        logger.error("指数池读取失败: %s", exc)
        return 2

    # 母表先写，后续权重/成分有稳定的 index_code / tracking_index_code。
    results["instruments"] = _run(
        "指数池母表", lambda: _upsert_pool(clients, pool)
    ).as_dict()

    if args.daily:
        results["snapshot"] = _run(
            "全市场快照", lambda: sync_market_snapshot(clients.db, clients.eastmoney)
        ).as_dict()
        daily_parts = {
            str(entry.get("indexCode")): _run(
                f"{entry.get('indexCode')} 画像",
                lambda entry=entry: sync_index_detail(
                    clients.db, clients.csindex, clients.danjuan,
                    index_code=str(entry.get("indexCode")),
                    csindex_code=entry.get("csindexCode"),
                    danjuan_code=entry.get("danjuanCode"),
                ),
            )
            for entry in pool
        }
        results["indexProfile"] = combine("DAILY_INDEX_PROFILE", daily_parts).as_dict()
        results["industry"] = _run(
            "行业板块", lambda: sync_industry_boards(clients.db, clients.eastmoney)
        ).as_dict()

    if args.index is not None:
        wanted = args.index or [str(entry.get("indexCode")) for entry in pool]
        for code in wanted:
            entry = _pool_entry(pool, code)
            if entry is None:
                results[f"index:{code}"] = SyncOutcome(
                    "FAILED", error=f"指数池里没有 {code}"
                ).as_dict()
                continue
            results[f"index:{entry.get('indexCode')}"] = sync_index(
                clients, entry, with_etf_price=False
            ).as_dict()

    if args.etf is not None:
        wanted = args.etf or [str(entry.get("etfCode")) for entry in pool if entry.get("etfCode")]
        for code in wanted:
            entry = _pool_entry(pool, code)
            if entry is None or str(entry.get("etfCode") or "").upper() != code.upper():
                results[f"etf:{code}"] = SyncOutcome(
                    "FAILED", error=f"ETF 不在指数池里: {code}"
                ).as_dict()
                continue
            results[f"etf:{code}"] = sync_index(
                clients, entry, with_etf_price=True
            ).as_dict()

    for code in args.stock:
        results[f"stock:{code.upper()}"] = sync_stock_full(clients, code).as_dict()

    _print(results)

    failed = [
        key for key, value in results.items()
        if isinstance(value, dict) and value.get("status") == "FAILED"
    ]
    return 1 if failed else 0


def _upsert_pool(clients: Clients, pool: list[dict[str, Any]]) -> SyncOutcome:
    rows = pool_rows(pool)
    if not rows:
        return SyncOutcome("EMPTY", error="指数池没有可用条目")
    written = clients.db.upsert("md_instrument", rows, ["instrument_code"])
    return SyncOutcome("OK", rows=written, detail={"poolSize": len(pool)})


def _print(results: dict[str, Any]) -> None:
    payload = {
        "generatedAt": now_beijing().isoformat(timespec="seconds"),
        "results": results,
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))


if __name__ == "__main__":
    sys.exit(main())
