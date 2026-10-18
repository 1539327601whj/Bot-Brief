# -*- coding: utf-8 -*-
"""warehouse 进程的配置。

所有外部依赖都从环境变量读，默认值以「服务器上直接 docker compose 起」为准。
本地调试时最常改的两项是 ``WAREHOUSE_DB_*`` 与 ``WAREHOUSE_NOPROXY``。

**数据库账号**：生产环境必须用只授了 ``md_*`` SELECT/INSERT/UPDATE 的独立账号
（``warehouse``）。为了让本地不用先建账号，未显式给 ``WAREHOUSE_DB_USER`` 时会退回
``DB_USER``；这时 :attr:`DbConfig.using_app_account` 为真，进程启动会打一条 WARN。
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Mapping
from zoneinfo import ZoneInfo

BEIJING = ZoneInfo("Asia/Shanghai")

DEFAULT_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)

# 车道 / 源的默认最小间隔。东财交互链路是 150ms（Java 现有值），
# 批量链路刻意压到 500ms：17:00 之后跑，没必要和用户点击抢额度。
DEFAULT_MIN_INTERVAL_MS: dict[str, int] = {
    "eastmoney:batch": 500,
    "eastmoney:interactive": 150,
    "tencent": 150,
    "sina": 200,
    "csindex": 300,
    "danjuan": 500,
    "google-news": 1000,
    "eastmoney-news": 400,
    "eastmoney-notice": 400,
}

# 东财的域族。封禁可能只落在其中一个域名上（push2 挂 ≠ datacenter-web 全挂），
# 所以冷却按 host 记；同时写车道冷却，避免本进程继续往同一个域名打。
EASTMONEY_HOST_KEYS: dict[str, str] = {
    "push2.eastmoney.com": "eastmoney:push2",
    "push2his.eastmoney.com": "eastmoney:push2his",
    "datacenter-web.eastmoney.com": "eastmoney:datacenter-web",
    "search-api-web.eastmoney.com": "eastmoney:search",
    "np-anotice-stock.eastmoney.com": "eastmoney:notice",
    "api.fund.eastmoney.com": "eastmoney:fund",
}

TRUE_VALUES = {"1", "true", "yes", "y", "on"}
FALSE_VALUES = {"0", "false", "no", "n", "off", ""}


def _env(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip()


def env_bool(name: str, default: bool) -> bool:
    raw = _env(name)
    if raw is None:
        return default
    lowered = raw.lower()
    if lowered in TRUE_VALUES:
        return True
    if lowered in FALSE_VALUES:
        return False
    return default


def env_int(name: str, default: int, minimum: int | None = None) -> int:
    raw = _env(name)
    if raw in (None, ""):
        return default
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return default
    if minimum is not None and value < minimum:
        return minimum
    return value


def env_float(name: str, default: float, minimum: float | None = None) -> float:
    raw = _env(name)
    if raw in (None, ""):
        return default
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return default
    if minimum is not None and value < minimum:
        return minimum
    return value


def repo_root() -> Path:
    # automation/warehouse/config.py -> automation/warehouse -> automation -> repo root
    return Path(__file__).resolve().parents[2]


@dataclass(frozen=True)
class DbConfig:
    host: str
    port: int
    name: str
    user: str
    password: str
    connect_timeout: int = 5
    read_timeout: int = 30
    write_timeout: int = 30
    _using_app_account: bool = False

    @property
    def using_app_account(self) -> bool:
        """是否退回了应用账号。生产部署应始终为 False。"""
        return self._using_app_account

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "DbConfig":
        source = os.environ if env is None else env

        def pick(name: str, fallback: str | None = None) -> str | None:
            value = source.get(name)
            return value.strip() if isinstance(value, str) else fallback

        host = pick("WAREHOUSE_DB_HOST") or pick("DB_HOST") or "127.0.0.1"
        port_raw = pick("WAREHOUSE_DB_PORT") or pick("DB_PORT") or "3306"
        try:
            port = int(port_raw)
        except (TypeError, ValueError):
            port = 3306
        name = pick("WAREHOUSE_DB_NAME") or pick("DB_NAME") or "brief"

        # compose 里常见的 `WAREHOUSE_DB_USER: ${WAREHOUSE_DB_USER:-}` 会给出空串，
        # 空串必须按「没配」处理，否则会拿空用户名去连库。
        explicit_user = pick("WAREHOUSE_DB_USER") or None
        explicit_password = pick("WAREHOUSE_DB_PASSWORD")
        if explicit_user is None:
            explicit_password = None
        using_app = explicit_user is None
        user = explicit_user or pick("DB_USER") or "warehouse"
        password = explicit_password if explicit_password is not None else (pick("DB_PASSWORD") or "")

        config = cls(
            host=host,
            port=port,
            name=name,
            user=user,
            password=password,
            connect_timeout=env_int("WAREHOUSE_DB_CONNECT_TIMEOUT", 5, 1),
            read_timeout=env_int("WAREHOUSE_DB_READ_TIMEOUT", 30, 1),
            write_timeout=env_int("WAREHOUSE_DB_WRITE_TIMEOUT", 30, 1),
        )
        return replace(config, _using_app_account=using_app)


@dataclass(frozen=True)
class RateLimitConfig:
    """跨进程限速配置。

    ``min_interval_ms`` 是**每个 provider 的最小间隔**；``max_wait_ms`` 是单次请求
    愿意排队的最长时间——超过就失败，不无限睡眠。429 冷却默认 10 分钟。
    """

    min_interval_ms: dict[str, int] = field(default_factory=lambda: dict(DEFAULT_MIN_INTERVAL_MS))
    max_wait_ms: int = 8000
    cooldown_seconds: int = 600
    # 批量链路拿到 429 时，交互链路也同步退避一小段，避免 Java 在这段时间继续撞墙。
    interactive_backoff_seconds: int = 120
    breaker_seconds: int = 30
    cooldown_cache_ms: int = 1000
    http_timeout: tuple[float, float] = (5.0, 20.0)

    @classmethod
    def from_env(cls) -> "RateLimitConfig":
        intervals = dict(DEFAULT_MIN_INTERVAL_MS)
        override = _env("WAREHOUSE_MIN_INTERVAL_MS")
        if override:
            # 形如 "eastmoney:batch=800,csindex=400"
            for part in override.split(","):
                key, _, value = part.partition("=")
                key, value = key.strip(), value.strip()
                if not key or not value:
                    continue
                try:
                    intervals[key] = max(0, int(value))
                except ValueError:
                    continue
        return cls(
            min_interval_ms=intervals,
            max_wait_ms=env_int("WAREHOUSE_MAX_WAIT_MS", 8000, 100),
            cooldown_seconds=env_int("WAREHOUSE_COOLDOWN_SECONDS", 600, 30),
            interactive_backoff_seconds=env_int("WAREHOUSE_INTERACTIVE_BACKOFF_SECONDS", 120, 0),
            breaker_seconds=env_int("WAREHOUSE_DB_BREAKER_SECONDS", 30, 1),
            cooldown_cache_ms=env_int("WAREHOUSE_COOLDOWN_CACHE_MS", 1000, 0),
            http_timeout=(
                env_float("WAREHOUSE_HTTP_CONNECT_TIMEOUT", 5.0, 0.5),
                env_float("WAREHOUSE_HTTP_READ_TIMEOUT", 20.0, 1.0),
            ),
        )

    def interval_ms(self, provider: str) -> int:
        return self.min_interval_ms.get(provider, 300)


@dataclass(frozen=True)
class Config:
    db: DbConfig
    rate: RateLimitConfig
    #: True 表示信任系统代理（requests 默认行为）。本机 7890 代理会让国内行情域名失败，
    #: 所以默认 False；容器/服务器上也没有必要开。
    trust_env: bool = False
    batch_start_hour: int = 17
    industry_start_hour: int = 17
    industry_start_minute: int = 30
    news_refresh_enabled: bool = False
    universe_size: int = 3000
    universe_page_size: int = 100
    quote_batch_size: int = 100
    db_batch_size: int = 500
    kline_limit: int = 2600
    valuation_page_size: int = 5000
    financial_page_size: int = 16
    moneyflow_limit: int = 260
    csindex_limit: int = 60
    job_poll_seconds: float = 2.0
    job_max_attempts: int = 3
    index_pool_path: Path | None = None
    backend_url: str | None = None
    ingest_token: str | None = None
    log_level: str = "INFO"

    @classmethod
    def from_env(cls) -> "Config":
        pool_raw = _env("WAREHOUSE_INDEX_POOL_PATH")
        pool_path = Path(pool_raw) if pool_raw else default_index_pool_path()
        backend = _env("BACKEND_API_URL")
        return cls(
            db=DbConfig.from_env(),
            rate=RateLimitConfig.from_env(),
            trust_env=env_bool("WAREHOUSE_TRUST_ENV", False),
            batch_start_hour=env_int("WAREHOUSE_BATCH_START_HOUR", 17, 0),
            industry_start_hour=env_int("WAREHOUSE_INDUSTRY_START_HOUR", 17, 0),
            industry_start_minute=env_int("WAREHOUSE_INDUSTRY_START_MINUTE", 30, 0),
            news_refresh_enabled=env_bool("WAREHOUSE_NEWS_REFRESH_ENABLED", False),
            universe_size=env_int("WAREHOUSE_UNIVERSE_SIZE", 3000, 100),
            universe_page_size=env_int("WAREHOUSE_UNIVERSE_PAGE_SIZE", 100, 1),
            quote_batch_size=env_int("WAREHOUSE_QUOTE_BATCH_SIZE", 100, 1),
            db_batch_size=env_int("WAREHOUSE_DB_BATCH_SIZE", 500, 1),
            kline_limit=env_int("WAREHOUSE_KLINE_LIMIT", 2600, 250),
            valuation_page_size=env_int("WAREHOUSE_VALUATION_PAGE_SIZE", 5000, 100),
            financial_page_size=env_int("WAREHOUSE_FINANCIAL_PAGE_SIZE", 16, 4),
            moneyflow_limit=env_int("WAREHOUSE_MONEYFLOW_LIMIT", 260, 5),
            csindex_limit=env_int("WAREHOUSE_CSINDEX_LIMIT", 60, 0),
            job_poll_seconds=env_float("WAREHOUSE_JOB_POLL_SECONDS", 2.0, 0.2),
            job_max_attempts=env_int("WAREHOUSE_JOB_MAX_ATTEMPTS", 3, 1),
            index_pool_path=pool_path if pool_path and pool_path.exists() else None,
            backend_url=backend.rstrip("/") if backend else None,
            ingest_token=_env("REPORT_INGEST_TOKEN"),
            log_level=(_env("LOG_LEVEL") or "INFO").upper(),
        )

    def with_pool_path(self, path: Path | None) -> "Config":
        return replace(self, index_pool_path=path)


def default_index_pool_path() -> Path:
    """仓库内运行时，池子直接读后端 resources 的那一份。

    容器里 ``automation/`` 是构建上下文，拿不到 ``backend/``，这时
    :class:`Config` 会把路径置空，由 ``sync.instruments`` 退回后端
    ``/api/index-pool``。**不在 automation 下存第二份池子**：两份必然漂移。
    """
    return repo_root() / "backend" / "src" / "main" / "resources" / "screener" / "index-pool.json"
