# -*- coding: utf-8 -*-
import unittest

import requests

import warehouse_fixtures

warehouse_fixtures.ensure_path()

from warehouse.config import RateLimitConfig  # noqa: E402
from warehouse.rate_limit import (  # noqa: E402
    HttpSource,
    InMemorySourceStateStore,
    LocalThrottle,
    RateGate,
    RateLimited,
    SourceBusy,
    SourceCooling,
    SourceError,
    SourceStateUnavailable,
    SourceUnavailable,
    host_key_for,
    is_rate_limited,
)


class FakeClock:
    def __init__(self, start: float = 0.0):
        self.value = start

    def __call__(self) -> float:
        return self.value

    def sleep(self, seconds: float) -> None:
        self.value += seconds

    def advance(self, seconds: float) -> None:
        self.value += seconds


class FlakyStore(InMemorySourceStateStore):
    def reserve(self, provider, interval_ms, max_wait_ms):
        raise SourceStateUnavailable("db down")

    def cooldown(self, provider):
        raise SourceStateUnavailable("db down")


class FakeResponse:
    def __init__(self, status_code=200, text="", payload=None, raw=None, headers=None):
        self.status_code = status_code
        self._text = text
        self._payload = payload
        self._raw = raw
        self.headers = headers or {}
        self.encoding = None

    @property
    def text(self):
        if self._raw is not None:
            return self._raw.decode(self.encoding or "iso-8859-1")
        return self._text

    def json(self):
        if self._payload is None:
            raise ValueError("not json")
        return self._payload


class FakeSession:
    def __init__(self, response):
        self.response = response
        self.calls = []
        self.trust_env = False

    def mount(self, prefix, adapter):
        pass

    def get(self, url, params=None, headers=None, timeout=None):
        self.calls.append(url)
        return self.response


class ErrorSession(FakeSession):
    def __init__(self, error):
        super().__init__(None)
        self.error = error

    def get(self, url, params=None, headers=None, timeout=None):
        self.calls.append(url)
        raise self.error


def make_gate(clock=None, store=None):
    clock = clock or FakeClock()
    store = store or InMemorySourceStateStore(clock)
    return RateGate(RateLimitConfig(), store, clock=clock, sleeper=clock.sleep), clock, store


def make_http(response, clock=None):
    gate, clock, store = make_gate(clock)
    session = FakeSession(response)
    return HttpSource(gate, RateLimitConfig(), session=session), session, store, clock


def make_error_http(error, clock=None):
    gate, clock, store = make_gate(clock)
    session = ErrorSession(error)
    return HttpSource(gate, RateLimitConfig(), session=session), session, store, clock


class LocalThrottleTest(unittest.TestCase):
    def test_min_interval_is_enforced_with_fake_clock(self):
        clock = FakeClock()
        throttle = LocalThrottle(clock, clock.sleep)
        self.assertEqual(throttle.wait("p", 150), 0)
        self.assertEqual(throttle.wait("p", 150), 150)

    def test_each_provider_has_its_own_slot(self):
        clock = FakeClock()
        throttle = LocalThrottle(clock, clock.sleep)
        throttle.wait("a", 150)
        self.assertEqual(throttle.wait("b", 150), 0)


class InMemoryStoreTest(unittest.TestCase):
    def test_reserve_advances_next_allowed(self):
        clock = FakeClock()
        store = InMemorySourceStateStore(clock)
        first = store.reserve("p", 150, 8000)
        second = store.reserve("p", 150, 8000)
        self.assertTrue(first.allowed)
        self.assertTrue(second.allowed)
        self.assertEqual(second.wait_ms, 150)

    def test_budget_exceeded_is_busy_and_does_not_advance(self):
        clock = FakeClock()
        store = InMemorySourceStateStore(clock)
        store.reserve("p", 10000, 8000)
        busy = store.reserve("p", 150, 100)
        self.assertFalse(busy.allowed)
        self.assertEqual(busy.reason, "busy")

    def test_cooldown(self):
        clock = FakeClock()
        store = InMemorySourceStateStore(clock)
        store.enter_cooldown("p", 600, "HTTP 429")
        self.assertTrue(store.cooldown("p").cooling)
        blocked = store.reserve("p", 150, 8000)
        self.assertTrue(blocked.cooling)
        clock.advance(601)
        self.assertFalse(store.cooldown("p").cooling)
        self.assertTrue(store.reserve("p", 150, 8000).allowed)

    def test_recently_active_window(self):
        clock = FakeClock()
        store = InMemorySourceStateStore(clock)
        self.assertFalse(store.recently_active("p", 30))
        store.reserve("p", 0, 8000)
        self.assertTrue(store.recently_active("p", 30))
        clock.advance(31)
        self.assertFalse(store.recently_active("p", 30))


class RateGateTest(unittest.TestCase):
    def test_acquire_waits_for_interval(self):
        gate, clock, _ = make_gate()
        gate.acquire("p")
        gate.acquire("p")
        self.assertGreaterEqual(clock.value, 0.15)

    def test_cooldown_blocks_without_calling_store_reserve(self):
        gate, clock, store = make_gate()
        gate.note_rate_limited("eastmoney:batch", "eastmoney:push2", "HTTP 429")
        with self.assertRaises(SourceCooling) as ctx:
            gate.acquire("eastmoney:push2")
        self.assertGreater(ctx.exception.remaining_ms, 0)
        self.assertIn("HTTP 429", str(ctx.exception))

    def test_eastmoney_rate_limit_backs_off_interactive_lane(self):
        gate, clock, store = make_gate()
        gate.note_rate_limited("eastmoney:batch", "eastmoney:push2", "HTTP 429")
        info = store.cooldown("eastmoney:interactive")
        self.assertTrue(info.cooling)
        self.assertEqual(info.remaining_ms, 120 * 1000)

    def test_cooldown_expires(self):
        gate, clock, _ = make_gate()
        gate.note_rate_limited("p", None, "HTTP 429")
        clock.advance(601)
        gate.acquire("p")  # 不抛异常即通过

    def test_network_failure_cools_host_family_not_batch_lane(self):
        gate, clock, store = make_gate()
        gate.note_network_failure("eastmoney:batch", "eastmoney:push2", "RemoteDisconnected")
        self.assertTrue(store.cooldown("eastmoney:push2").cooling)
        # datacenter-web 仍然可用，不能被整个 batch 车道一起停掉。
        self.assertFalse(store.cooldown("eastmoney:batch").cooling)
        self.assertTrue(store.cooldown("eastmoney:interactive").cooling)

    def test_datacenter_network_failure_does_not_stop_interactive(self):
        gate, clock, store = make_gate()
        gate.note_network_failure(
            "eastmoney:batch", "eastmoney:datacenter-web", "RemoteDisconnected",
        )
        self.assertTrue(store.cooldown("eastmoney:datacenter-web").cooling)
        self.assertFalse(store.cooldown("eastmoney:interactive").cooling)

    def test_network_cooldown_expires(self):
        gate, clock, _ = make_gate()
        gate.note_network_failure("eastmoney:batch", "eastmoney:push2", "RemoteDisconnected")
        clock.advance(901)
        gate.acquire("eastmoney:push2")  # 不抛异常即通过

    def test_busy_when_budget_is_exceeded(self):
        gate, clock, _ = make_gate()
        gate.acquire("p", interval_ms=10000)
        with self.assertRaises(SourceBusy):
            gate.acquire("p", interval_ms=10000, max_wait_ms=100)

    def test_store_outage_degrades_to_local_throttle(self):
        clock = FakeClock()
        store = FlakyStore(clock)
        gate = RateGate(RateLimitConfig(), store, clock=clock, sleeper=clock.sleep)
        gate.acquire("p")
        gate.acquire("p")  # 不应抛 SourceStateUnavailable
        self.assertGreater(gate._breaker_until, 0)

    def test_recently_active_is_false_when_store_down(self):
        clock = FakeClock()
        gate = RateGate(RateLimitConfig(), FlakyStore(clock), clock=clock, sleeper=clock.sleep)
        self.assertFalse(gate.recently_active("p", 30))


class HttpSourceTest(unittest.TestCase):
    def test_rate_limited_is_not_retried_and_enters_cooldown(self):
        http, session, store, _ = make_http(FakeResponse(429, "too many requests"))
        with self.assertRaises(RateLimited):
            http.get_json("eastmoney:batch", "https://push2.eastmoney.com/api")
        self.assertEqual(len(session.calls), 1)
        self.assertTrue(store.cooldown("eastmoney:push2").cooling)
        self.assertTrue(store.cooldown("eastmoney:batch").cooling)

    def test_rate_limit_marker_in_body(self):
        self.assertTrue(is_rate_limited(200, "访问频率过高"))
        self.assertFalse(is_rate_limited(200, "ok"))

    def test_connection_reset_is_not_retried_and_cools_host(self):
        error = requests.exceptions.ConnectionError(
            "Remote end closed connection without response"
        )
        http, session, store, _ = make_error_http(error)
        with self.assertRaises(SourceUnavailable):
            http.get_json("eastmoney:batch", "https://push2.eastmoney.com/api")
        self.assertEqual(len(session.calls), 1)
        self.assertTrue(store.cooldown("eastmoney:push2").cooling)
        self.assertFalse(store.cooldown("eastmoney:batch").cooling)
        # 冷却期内再次请求应直接失败，不再打网络。
        with self.assertRaises(SourceCooling):
            http.get_json("eastmoney:batch", "https://push2.eastmoney.com/api")
        self.assertEqual(len(session.calls), 1)

    def test_csindex_403_is_treated_as_rate_limited(self):
        http, session, store, _ = make_http(FakeResponse(403, "<html>405</html>"))
        with self.assertRaises(RateLimited):
            http.get_json("csindex", "https://www.csindex.com.cn/csindex-home/indexInfo/index-basic-info/399975")
        self.assertEqual(len(session.calls), 1)
        self.assertTrue(store.cooldown("csindex").cooling)
        self.assertEqual(store.cooldown("csindex").remaining_ms, 1800 * 1000)

    def test_server_error_is_unavailable(self):
        http, session, _, _ = make_http(FakeResponse(503, "down"))
        with self.assertRaises(SourceUnavailable):
            http.get_json("p", "https://example.com/api")
        self.assertEqual(len(session.calls), 1)

    def test_client_error(self):
        http, _, _, _ = make_http(FakeResponse(404, "not found"))
        with self.assertRaises(SourceError) as ctx:
            http.get_json("p", "https://example.com/api")
        self.assertEqual(ctx.exception.status, 404)

    def test_non_json_body(self):
        http, _, _, _ = make_http(FakeResponse(200, "not json"))
        with self.assertRaises(SourceError):
            http.get_json("p", "https://example.com/api")

    def test_get_text_uses_explicit_encoding(self):
        raw = "贵州茅台".encode("gbk")
        http, _, _, _ = make_http(FakeResponse(200, raw=raw))
        text = http.get_text("tencent", "https://qt.gtimg.cn/q=sh600519", encoding="gbk")
        self.assertEqual(text, "贵州茅台")

    def test_retry_after_header_is_propagated(self):
        response = FakeResponse(429, "slow down", headers={"Retry-After": "120"})
        http, _, _, _ = make_http(response)
        with self.assertRaises(RateLimited) as ctx:
            http.get_json("p", "https://example.com/api")
        self.assertEqual(ctx.exception.retry_after_seconds, 120)

    def test_host_key_for(self):
        self.assertEqual(host_key_for("https://push2.eastmoney.com/x"), "eastmoney:push2")
        self.assertEqual(host_key_for("https://datacenter-web.eastmoney.com/x"),
                         "eastmoney:datacenter-web")
        self.assertIsNone(host_key_for("https://qt.gtimg.cn/x"))


if __name__ == "__main__":
    unittest.main()
