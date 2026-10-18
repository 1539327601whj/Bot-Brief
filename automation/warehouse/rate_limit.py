# -*- coding: utf-8 -*-
"""跨进程限速与冷却。

Java 侧的 ``MarketRateLimitGate`` 只在一个 JVM 里排队；warehouse 再起一个进程后，
两边各按 150ms/500ms 发车，实际速率会叠成两倍——对按 IP 限流的东财来说，这正是
「短时间反复查询就被风控」的来源。所以两边共用 ``md_source_state``：

* 发车前原子预约 ``next_allowed_at``（``SELECT ... FOR UPDATE``，时间全部由 DB 的
  ``NOW(3)`` 算，避免 JVM/DB 时区不一致）；
* 429 写 ``cooldown_until``，**冷却期内零外呼，不重试、不换域名重试**；
* 批量任务用 ``eastmoney:batch`` 车道，交互链路用 ``eastmoney:interactive``；
* 东财按域名族分别记冷却（``eastmoney:push2`` 与 ``eastmoney:datacenter-web`` 是两行），
  因为封禁面不一定覆盖全部域族。

DB 不可用时降级为「只做进程内限速」，打 WARN，不阻断整个查询——限速是保护措施，
不是正确性前提。
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Callable, Protocol, Sequence
from urllib.parse import urlparse

import requests

from .config import EASTMONEY_HOST_KEYS, RateLimitConfig
from .db import WarehouseDb, WarehouseDbError

logger = logging.getLogger(__name__)

RATE_LIMIT_MARKERS = ("too many requests", "访问频率", "请求过于频繁", "rate limit")


class SourceStateUnavailable(RuntimeError):
    """共享闸门暂时不可用（DB 断开等）。调用方应降级而不是失败。"""


class SourceCooling(RuntimeError):
    def __init__(self, provider: str, remaining_ms: int, last_error: str | None = None):
        self.provider = provider
        self.remaining_ms = max(0, remaining_ms)
        self.last_error = last_error
        super().__init__(
            f"{provider} 处于冷却中，剩余 {self.remaining_ms / 1000:.0f}s"
            + (f"（{last_error}）" if last_error else "")
        )


class SourceBusy(RuntimeError):
    """共享闸门排队超过预算。"""

    def __init__(self, provider: str, wait_ms: int):
        self.provider = provider
        self.wait_ms = max(0, wait_ms)
        super().__init__(f"{provider} 下一次允许发车还要 {self.wait_ms}ms，超过等待预算")


class RateLimited(RuntimeError):
    """上游明确限流。**调用方不得重试**。"""

    def __init__(self, provider: str, retry_after_seconds: int = 0, detail: str = ""):
        self.provider = provider
        self.retry_after_seconds = max(0, retry_after_seconds)
        self.detail = detail
        super().__init__(detail or f"{provider} 返回限流信号")


class SourceUnavailable(RuntimeError):
    """网络错误 / 5xx。可等下一轮，但本进程不重试。"""


class SourceError(RuntimeError):
    """上游 4xx 或返回体不符合契约。通常重试也没用。"""

    def __init__(self, provider: str, message: str, status: int | None = None):
        self.provider = provider
        self.status = status
        super().__init__(message)


@dataclass(frozen=True)
class Reservation:
    allowed: bool
    wait_ms: int = 0
    cooling: bool = False
    cooldown_remaining_ms: int = 0
    reason: str = ""


@dataclass(frozen=True)
class CooldownInfo:
    provider: str
    cooling: bool
    remaining_ms: int
    last_error: str | None = None


class SourceStateStore(Protocol):
    def reserve(self, provider: str, interval_ms: int, max_wait_ms: int) -> Reservation: ...
    def cooldown(self, provider: str) -> CooldownInfo: ...
    def enter_cooldown(self, provider: str, seconds: int, reason: str | None = None) -> None: ...
    def clear_cooldown(self, provider: str) -> None: ...
    def recently_active(self, provider: str, window_seconds: int) -> bool: ...


class MySqlSourceStateStore:
    """``md_source_state`` 的实现，语义与 Java ``WarehouseRepository.reserve`` 对齐。"""

    def __init__(self, db: WarehouseDb):
        self.db = db

    def reserve(self, provider: str, interval_ms: int, max_wait_ms: int) -> Reservation:
        conn = self.db.connection()
        try:
            conn.begin()
            with conn.cursor() as cursor:
                cursor.execute(
                    "INSERT IGNORE INTO md_source_state (provider) VALUES (%s)", (provider,)
                )
                cursor.execute(
                    """
                    SELECT
                        TIMESTAMPDIFF(MICROSECOND, NOW(3),
                            COALESCE(next_allowed_at, NOW(3))) AS next_us,
                        TIMESTAMPDIFF(MICROSECOND, NOW(3), cooldown_until) AS cooldown_us,
                        last_error
                    FROM md_source_state
                    WHERE provider = %s
                    FOR UPDATE
                    """,
                    (provider,),
                )
                row = cursor.fetchone() or {}
                cooldown_us = int(row.get("cooldown_us") or 0)
                if cooldown_us > 0:
                    conn.rollback()
                    return Reservation(
                        allowed=False,
                        cooling=True,
                        cooldown_remaining_ms=max(1, cooldown_us // 1000),
                        reason=row.get("last_error") or "cooldown",
                    )
                wait_us = max(0, int(row.get("next_us") or 0))
                wait_ms = (wait_us + 999) // 1000
                if wait_ms > max_wait_ms:
                    # 关键：拒绝时**不推进** next_allowed_at，否则一次超时就永久把槽位越推越远。
                    conn.rollback()
                    return Reservation(allowed=False, wait_ms=wait_ms, reason="busy")
                cursor.execute(
                    """
                    UPDATE md_source_state
                    SET next_allowed_at = TIMESTAMPADD(
                        MICROSECOND, %s,
                        GREATEST(COALESCE(next_allowed_at, NOW(3)), NOW(3)))
                    WHERE provider = %s
                    """,
                    (max(0, interval_ms) * 1000, provider),
                )
            conn.commit()
            return Reservation(allowed=True, wait_ms=wait_ms)
        except Exception as exc:
            try:
                conn.rollback()
            except Exception:
                pass
            raise SourceStateUnavailable(f"共享闸门不可用: {type(exc).__name__}: {exc}") from exc

    def cooldown(self, provider: str) -> CooldownInfo:
        try:
            row = self.db.query_one(
                """
                SELECT provider,
                       TIMESTAMPDIFF(MICROSECOND, NOW(3), cooldown_until) AS remaining_us,
                       last_error
                FROM md_source_state WHERE provider = %s
                """,
                (provider,),
            )
        except WarehouseDbError as exc:
            raise SourceStateUnavailable(str(exc)) from exc
        if not row:
            return CooldownInfo(provider=provider, cooling=False, remaining_ms=0)
        remaining_ms = max(0, int(row.get("remaining_us") or 0) // 1000)
        return CooldownInfo(
            provider=provider,
            cooling=remaining_ms > 0,
            remaining_ms=remaining_ms,
            last_error=row.get("last_error"),
        )

    def enter_cooldown(self, provider: str, seconds: int, reason: str | None = None) -> None:
        try:
            self.db.execute(
                """
                INSERT INTO md_source_state (provider, cooldown_until, last_error)
                VALUES (%s, TIMESTAMPADD(SECOND, %s, NOW(3)), %s)
                ON DUPLICATE KEY UPDATE
                    cooldown_until = GREATEST(
                        COALESCE(cooldown_until, NOW(3)),
                        TIMESTAMPADD(SECOND, %s, NOW(3))),
                    last_error = VALUES(last_error)
                """,
                (provider, max(0, seconds), _truncate(reason, 500), max(0, seconds)),
            )
        except WarehouseDbError as exc:
            raise SourceStateUnavailable(str(exc)) from exc

    def clear_cooldown(self, provider: str) -> None:
        try:
            self.db.execute(
                "UPDATE md_source_state SET cooldown_until = NULL, last_error = NULL "
                "WHERE provider = %s",
                (provider,),
            )
        except WarehouseDbError as exc:
            raise SourceStateUnavailable(str(exc)) from exc

    def recently_active(self, provider: str, window_seconds: int) -> bool:
        try:
            row = self.db.query_one(
                "SELECT TIMESTAMPDIFF(SECOND, updated_at, NOW(3)) AS age_seconds "
                "FROM md_source_state WHERE provider = %s",
                (provider,),
            )
        except WarehouseDbError as exc:
            raise SourceStateUnavailable(str(exc)) from exc
        if not row:
            return False
        age = row.get("age_seconds")
        return age is not None and 0 <= int(age) <= window_seconds


class InMemorySourceStateStore:
    """测试用假闸门。时间基准用 ``monotonic``，不依赖真实时钟。"""

    def __init__(self, clock: Callable[[], float] | None = None):
        self.clock = clock or time.monotonic
        self.next_allowed: dict[str, float] = {}
        self.cooldown_until: dict[str, float] = {}
        self.last_error: dict[str, str] = {}
        self.updated_at: dict[str, float] = {}

    def reserve(self, provider: str, interval_ms: int, max_wait_ms: int) -> Reservation:
        now = self.clock()
        cooldown = self.cooldown_until.get(provider)
        if cooldown is not None and cooldown > now:
            return Reservation(
                allowed=False,
                cooling=True,
                cooldown_remaining_ms=int((cooldown - now) * 1000),
                reason=self.last_error.get(provider),
            )
        fire = max(self.next_allowed.get(provider, now), now)
        wait_ms = int((fire - now) * 1000)
        if wait_ms > max_wait_ms:
            return Reservation(allowed=False, wait_ms=wait_ms, reason="busy")
        self.next_allowed[provider] = fire + max(0, interval_ms) / 1000.0
        self.updated_at[provider] = now
        return Reservation(allowed=True, wait_ms=wait_ms)

    def cooldown(self, provider: str) -> CooldownInfo:
        now = self.clock()
        until = self.cooldown_until.get(provider)
        remaining = max(0, int(((until or 0) - now) * 1000))
        return CooldownInfo(provider, remaining > 0, remaining, self.last_error.get(provider))

    def enter_cooldown(self, provider: str, seconds: int, reason: str | None = None) -> None:
        now = self.clock()
        until = max(self.cooldown_until.get(provider, now), now + max(0, seconds))
        self.cooldown_until[provider] = until
        self.last_error[provider] = reason or ""
        self.updated_at[provider] = now

    def clear_cooldown(self, provider: str) -> None:
        self.cooldown_until.pop(provider, None)
        self.last_error.pop(provider, None)

    def recently_active(self, provider: str, window_seconds: int) -> bool:
        seen = self.updated_at.get(provider)
        return seen is not None and 0 <= (self.clock() - seen) <= window_seconds


class LocalThrottle:
    """进程内最小间隔。可注入时钟与睡眠函数，测试里不用真的等。"""

    def __init__(self, clock: Callable[[], float] | None = None,
                 sleeper: Callable[[float], None] | None = None):
        self.clock = clock or time.monotonic
        self.sleeper = sleeper or time.sleep
        self.last_call: dict[str, float] = {}

    def wait(self, provider: str, interval_ms: int) -> int:
        now = self.clock()
        last = self.last_call.get(provider)
        wait_seconds = 0.0 if last is None else max(0.0, last + interval_ms / 1000.0 - now)
        if wait_seconds > 0:
            self.sleeper(wait_seconds)
        self.last_call[provider] = self.clock()
        return int(wait_seconds * 1000)


class RateGate:
    def __init__(
        self,
        config: RateLimitConfig,
        store: SourceStateStore,
        *,
        local: LocalThrottle | None = None,
        clock: Callable[[], float] | None = None,
        sleeper: Callable[[float], None] | None = None,
    ):
        self.config = config
        self.store = store
        self.clock = clock or time.monotonic
        self.sleeper = sleeper or time.sleep
        self.local = local or LocalThrottle(self.clock, self.sleeper)
        self._cooldown_cache: dict[str, tuple[float, CooldownInfo]] = {}
        self._breaker_until = 0.0
        self._last_warn_at = 0.0

    # ------------------------------------------------------------------
    def acquire(
        self,
        provider: str,
        *,
        cooldown_keys: Sequence[str] = (),
        interval_ms: int | None = None,
        max_wait_ms: int | None = None,
    ) -> None:
        interval = self.config.interval_ms(provider) if interval_ms is None else interval_ms
        budget = self.config.max_wait_ms if max_wait_ms is None else max_wait_ms

        self._raise_if_cooling(provider, cooldown_keys)

        # 先排进程内，再从剩余预算里排共享闸门——与 Java 侧一致。
        local_wait = self.local.wait(provider, interval)
        remaining = budget - local_wait
        if remaining <= 0:
            raise SourceBusy(provider, local_wait)

        if self.clock() < self._breaker_until:
            return
        try:
            reservation = self.store.reserve(provider, interval, remaining)
        except SourceStateUnavailable as exc:
            self._trip_breaker(str(exc))
            return
        if not reservation.allowed:
            if reservation.cooling:
                info = CooldownInfo(
                    provider, True, reservation.cooldown_remaining_ms, reservation.reason
                )
                self._cache_cooldown(provider, info)
                raise SourceCooling(provider, reservation.cooldown_remaining_ms, reservation.reason)
            raise SourceBusy(provider, reservation.wait_ms)
        if reservation.wait_ms > 0:
            self.sleeper(reservation.wait_ms / 1000.0)

    def _raise_if_cooling(self, provider: str, cooldown_keys: Sequence[str]) -> None:
        for key in dict.fromkeys((provider, *cooldown_keys)):
            info = self._cooldown_info(key)
            if info.cooling:
                raise SourceCooling(key, info.remaining_ms, info.last_error)

    def _cooldown_info(self, provider: str) -> CooldownInfo:
        now = self.clock()
        cached = self._cooldown_cache.get(provider)
        ttl = self.config.cooldown_cache_ms / 1000.0
        if cached is not None and now - cached[0] <= ttl:
            return cached[1]
        try:
            info = self.store.cooldown(provider)
        except SourceStateUnavailable as exc:
            self._trip_breaker(str(exc))
            return CooldownInfo(provider, False, 0, None)
        self._cache_cooldown(provider, info)
        return info

    def _cache_cooldown(self, provider: str, info: CooldownInfo) -> None:
        self._cooldown_cache[provider] = (self.clock(), info)

    # ------------------------------------------------------------------
    def note_rate_limited(
        self,
        provider: str,
        host_key: str | None,
        detail: str,
        *,
        retry_after_seconds: int = 0,
    ) -> None:
        """429 之后写冷却。写失败只打日志，仍然把 RateLimited 抛给调用方。"""
        seconds = max(self.config.cooldown_seconds, retry_after_seconds)
        seconds = min(seconds, 6 * 3600)
        keys = [k for k in (host_key, provider) if k]
        # 东财域族被封时，让交互链路也立刻退避一小段；这不替代域名族冷却。
        if provider.startswith("eastmoney") and self.config.interactive_backoff_seconds > 0:
            keys.append("eastmoney:interactive")
        for key in dict.fromkeys(keys):
            key_seconds = (
                self.config.interactive_backoff_seconds
                if key == "eastmoney:interactive" else seconds
            )
            try:
                self.store.enter_cooldown(key, key_seconds, detail)
            except SourceStateUnavailable as exc:
                logger.warning("写入冷却失败 provider=%s: %s", key, exc)
            self._cache_cooldown(key, CooldownInfo(key, True, key_seconds * 1000, detail))
        now = self.clock()
        if now - self._last_warn_at > 60:
            logger.warning("源被限流，已进入冷却 provider=%s host=%s detail=%s", provider, host_key, detail)
            self._last_warn_at = now

    def recently_active(self, provider: str, window_seconds: int) -> bool:
        try:
            return self.store.recently_active(provider, window_seconds)
        except SourceStateUnavailable:
            return False

    def _trip_breaker(self, reason: str) -> None:
        now = self.clock()
        if now >= self._breaker_until:
            logger.warning(
                "共享限速闸门不可用，%ss 内降级为进程内限速: %s",
                self.config.breaker_seconds, reason,
            )
        self._breaker_until = max(self._breaker_until, now + self.config.breaker_seconds)


def host_key_for(url: str) -> str | None:
    host = (urlparse(url).hostname or "").lower()
    return EASTMONEY_HOST_KEYS.get(host)


def is_rate_limited(status_code: int, text: str) -> bool:
    if status_code == 429:
        return True
    lowered = (text or "").lower()
    return any(marker in lowered for marker in RATE_LIMIT_MARKERS)


def retry_after_seconds(response) -> int:
    raw = response.headers.get("Retry-After") if response is not None else None
    if not raw:
        return 0
    try:
        return max(0, int(float(raw)))
    except (TypeError, ValueError):
        return 0


class HttpSource:
    """带限速的 GET 客户端。**任何情况下都不重试。**"""

    def __init__(self, gate: RateGate, config: RateLimitConfig, session: requests.Session | None = None,
                 trust_env: bool = False, user_agent: str | None = None):
        self.gate = gate
        self.config = config
        self.session = session or requests.Session()
        self.session.trust_env = trust_env
        self.user_agent = user_agent or (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        )
        # 明确关掉 urllib3 自动重试：429 重试是封禁加深的最快路径。
        adapter = requests.adapters.HTTPAdapter(max_retries=0)
        self.session.mount("https://", adapter)
        self.session.mount("http://", adapter)

    def _request(self, provider: str, url: str, params=None, headers=None,
                 timeout: tuple[float, float] | None = None):
        cooldown_keys = []
        if provider.startswith("eastmoney"):
            key = host_key_for(url)
            if key:
                cooldown_keys.append(key)
        self.gate.acquire(provider, cooldown_keys=cooldown_keys)
        merged = {
            "User-Agent": self.user_agent,
            "Accept": "application/json, text/plain, */*",
        }
        if headers:
            merged.update(headers)
        try:
            response = self.session.get(
                url, params=params, headers=merged,
                timeout=timeout or self.config.http_timeout,
            )
        except requests.RequestException as exc:
            raise SourceUnavailable(f"{provider} 网络错误: {type(exc).__name__}: {exc}") from exc

        text = response.text or ""
        if is_rate_limited(response.status_code, text):
            detail = f"HTTP {response.status_code} {provider}"
            self.gate.note_rate_limited(
                provider, host_key_for(url), detail,
                retry_after_seconds=retry_after_seconds(response),
            )
            raise RateLimited(
                provider, retry_after_seconds(response), detail
            )
        if response.status_code >= 500:
            raise SourceUnavailable(f"{provider} HTTP {response.status_code}")
        if response.status_code >= 400:
            raise SourceError(provider, f"{provider} HTTP {response.status_code}", response.status_code)
        return response

    def get_json(self, provider: str, url: str, *, params=None, headers=None,
                 timeout: tuple[float, float] | None = None) -> dict:
        response = self._request(provider, url, params=params, headers=headers, timeout=timeout)
        try:
            body = response.json()
        except ValueError as exc:
            raise SourceError(provider, f"{provider} 返回的不是 JSON") from exc
        if not isinstance(body, dict):
            raise SourceError(provider, f"{provider} 返回体不是对象")
        return body

    def get_text(self, provider: str, url: str, *, params=None, headers=None,
                 timeout: tuple[float, float] | None = None,
                 encoding: str | None = None) -> str:
        response = self._request(provider, url, params=params, headers=headers, timeout=timeout)
        if encoding:
            # 腾讯/新浪行情是 GBK；requests 猜成 ISO-8859-1 会把名称变成乱码。
            response.encoding = encoding
        return response.text


def _truncate(value: str | None, limit: int) -> str | None:
    if value is None:
        return None
    return value if len(value) <= limit else value[: limit - 1] + "…"
