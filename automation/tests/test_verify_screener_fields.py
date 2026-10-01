"""verify_screener_fields 自身的测试。

这个脚本是用来给上线前把关的，它自己要是悄悄永远返回 0，比不存在更糟。
所以这里必须同时证明两件事：**数据对时它能通过**，**字段漂移时必须报错**。
沙箱连不上东财，全部用合成响应喂。

数据源换过一次（clist → datacenter-web 清单 + ulist 行情），所以这里也钉住了
那次换源特有的三条：`filter` 必须原样发、secid 的点与逗号必须原样、
以及「池子是市值前 N 只、不是全市场」这句话必须被算出来。
"""

import importlib.util
import io
import json
import unittest
import urllib.error
import urllib.parse
from datetime import date, timedelta
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "verify_screener_fields.py"
SPEC = importlib.util.spec_from_file_location("verify_screener_fields", MODULE_PATH)
vsf = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(vsf)

TRADE_DATE = "2026-09-30"


class Resp(io.BytesIO):
    """urlopen 的返回值只需要能被 with 包住、能 read。"""

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def param(path_query, name):
    return urllib.parse.parse_qs(urllib.parse.urlparse(path_query).query).get(name, [None])[0]


def pool_row(code, secucode, name, cap):
    """清单行：只有 Java 侧 columns 里点名的四个字段。"""
    return {"SECURITY_CODE": code, "SECUCODE": secucode,
            "SECURITY_NAME_ABBR": name, "TOTAL_MARKET_CAP": cap}


def make_pool(n=500):
    """市值降序、全是沪市的池子。下限 ~400 亿，高于稳健档要求的 200 亿。"""
    top, bottom = 2.0e12, 4.0e10
    return [
        pool_row(f"6005{i:02d}", f"6005{i:02d}.SH", f"公司{i}",
                 top - (top - bottom) * i / max(n - 1, 1))
        for i in range(n)
    ]


def quote_row(secid, price=12.36, bps=6.18, pe=21.4, list_date=20010827, pb=None):
    """默认数值自洽（f23 == f2 / f113，f26 是 YYYYMMDD）。

    pb 显式给出时才用它——要让「市净率口径漂移」这类测试真的造出不一致，
    不能让每行都自洽；自洽的假数据会把漂移测试变成永远通过的摆设。
    """
    code = secid.split(".")[-1]
    return {
        "f12": code, "f14": "名称" + code, "f2": price, "f3": -1.24, "f6": 3.5e9, "f8": 0.42,
        "f20": 1.9e12, "f21": 1.9e12,
        "f23": pb if pb is not None else ((price / bps) if bps else None),
        "f24": -8.5, "f25": 3.2, "f26": list_date, "f37": 34.2, "f41": 9.7, "f46": 11.1,
        "f49": 91.5, "f57": 16.3, "f100": "酿酒行业", "f113": bps, "f115": pe, "f133": 2.9,
    }


def flat_klines(n=250, close_of=None):
    start = date.today() - timedelta(days=n)
    close_of = close_of or (lambda i: 100 + (i % 37) - (i // 40) * 3)
    return [
        f"{(start + timedelta(days=i)).isoformat()},0,{close_of(i)},1,1,1,0,0"
        for i in range(n)
    ]


class VerifyScreenerFieldsTest(unittest.TestCase):
    def setUp(self):
        vsf.failures.clear()
        vsf.notes.clear()

    def fake_fetch(self, pool=None, klines=None, quote_kwargs=None, accept_encoded=False):
        """假响应。按 path_query 的形态分派：清单 / 行情 / 日线。

        accept_encoded=True 用来演「服务端开始接受编码后的 filter」这一种未来变化，
        脚本必须因此判失败——否则那条反向验证就是空的。
        """
        pool = pool if pool is not None else make_pool()
        klines = klines if klines is not None else flat_klines()
        quote_kwargs = quote_kwargs or {}

        def fetch(path_query, hosts):
            if "%28" in path_query and not accept_encoded:
                raise RuntimeError("datacenter-web.eastmoney.com: HTTP 500 "
                                   "参数预处理错误:org.antlr.v4.runtime.NoViableAltException")
            if "reportName=" in path_query:
                if "columns=TRADE_DATE" in path_query:
                    return {"result": {"data": [{"TRADE_DATE": TRADE_DATE + " 00:00:00"}],
                                       "count": 1}}
                return {"result": {"data": pool, "count": len(pool)}}
            if "kline/get" in path_query:
                return {"data": {"code": param(path_query, "secid").split(".")[-1],
                                 "klines": klines}}
            if "ulist.np/get" in path_query:
                secids = (param(path_query, "secids") or "").split(",")
                if any(s.endswith("510300") or s.endswith("159915") or s.endswith("588000")
                       for s in secids):
                    return {"data": {"diff": [
                        {"f12": s.split(".")[-1], "f14": "指数基金", "f2": 3.98, "f3": 0.51,
                         "f6": 1.4e9, "f20": 1.2e11} for s in secids]}}
                return {"data": {"diff": [quote_row(s, **quote_kwargs) for s in secids]}}
            raise AssertionError(f"未预期的请求 {path_query}")

        return fetch

    def run_with(self, fetch):
        with mock.patch.object(vsf, "fetch_json", fetch):
            with mock.patch("sys.stdout", new=io.StringIO()) as out:
                code = vsf.main()
        return code, out.getvalue()

    # ---------- 数据正确时必须通过 ----------

    def test_self_consistent_data_passes(self):
        code, out = self.run_with(self.fake_fetch())
        self.assertEqual(code, 0, f"自洽数据不该报错：\n{out}")
        self.assertIn("全部通过", out)
        self.assertIn("市净率 = 价/每股净资产", out)

    def test_small_rounding_in_pb_is_tolerated(self):
        # 东财的 f23 与 f2/f113 有末位取整差，不能因此判字段漂移
        code, out = self.run_with(self.fake_fetch(quote_kwargs={
            "price": 1520.5, "bps": 195.0, "pb": (1520.5 / 195.0) * 1.03}))
        self.assertEqual(code, 0, out)

    def test_negative_pe_is_reported_so_the_loss_veto_has_input(self):
        code, _ = self.run_with(self.fake_fetch(quote_kwargs={"pe": -7.4}))
        self.assertEqual(code, 0)
        self.assertTrue(any("PE(TTM) 为负的有" in n for n in vsf.notes), vsf.notes)

    def test_stale_kline_is_flagged_as_a_note_not_a_failure(self):
        # 陈旧日线在业务侧是「降级」，不是「字段错了」，不该让校验失败。
        last = date.today() - timedelta(days=60)
        start = last - timedelta(days=249)
        klines = [f"{(start + timedelta(days=i)).isoformat()},0,{100 + i % 7},1,1,1,0,0"
                  for i in range(250)]
        code, out = self.run_with(self.fake_fetch(klines=klines))
        self.assertEqual(code, 0, out)
        self.assertTrue(any("超过 15 天" in n for n in vsf.notes), vsf.notes)

    # ---------- 字段漂移时必须报错 ----------

    def test_pb_drift_fails(self):
        # 最要紧的一条：f23 被改成别的口径时，必须靠 f2/f113 交叉验证抓出来。
        # 故意给一个与 f2/f113 对不上的 f23。
        code, out = self.run_with(self.fake_fetch(quote_kwargs={
            "price": 1520.5, "bps": 195.0, "pb": 12.0}))
        self.assertEqual(code, 1, out)
        self.assertIn("市净率 = 价/每股净资产", out)

    def test_list_date_no_longer_yyyymmdd_fails(self):
        code, out = self.run_with(self.fake_fetch(quote_kwargs={"list_date": "2001-08-27"}))
        self.assertEqual(code, 1, out)
        self.assertIn("f26 是 YYYYMMDD 整数", out)

    def test_missing_scored_field_fails(self):
        def fetch(path_query, hosts):
            body = self.fake_fetch()(path_query, hosts)
            if "ulist.np/get" in path_query and "510300" not in path_query:
                for row in body["data"]["diff"]:
                    row.pop("f37", None)
                    row.pop("f100", None)
            return body

        code, out = self.run_with(fetch)
        self.assertEqual(code, 1, out)
        self.assertIn("f37", out)
        self.assertIn("f100", out)

    def test_no_row_can_be_cross_checked_is_itself_a_failure(self):
        # 全都缺 f113 就没人能验证市净率口径了，这不能算「通过」
        code, out = self.run_with(self.fake_fetch(quote_kwargs={"bps": None}))
        self.assertEqual(code, 1, out)
        self.assertIn("无法交叉验证市净率口径", out)

    def test_non_positive_closes_are_reported_as_a_parse_mismatch(self):
        # 收盘价为 0 是坏数据。业务侧会把它丢掉（否则会伪造一个 0 分位），
        # 但校验脚本要报出来：解析数量和返回根数对不上就说明有东西没读懂。
        klines = flat_klines(close_of=lambda i: 0 if i == 10 else 100 + i % 37)
        code, out = self.run_with(self.fake_fetch(klines=klines))
        self.assertEqual(code, 1, out)
        self.assertIn("每根日线都能解析出日期与收盘价", out)

    # ---------- 换源特有的三条 ----------

    def test_encoded_filter_is_rejected_by_the_server_and_that_is_the_expected_state(self):
        # 脚本必须主动发一次「编码过的 filter」，并期望它**失败**。
        # 服务端接受了才说明「必须原样」这条结论失效了，那要重新评估 Java 侧的实现。
        code, out = self.run_with(self.fake_fetch())
        self.assertEqual(code, 0, out)
        self.assertIn("filter 不能被百分号编码", out)

    def test_if_the_server_starts_accepting_an_encoded_filter_the_script_must_fail(self):
        # 反向验证本身不能是空的：服务端哪天开始解码了，这里必须红。
        code, out = self.run_with(self.fake_fetch(accept_encoded=True))
        self.assertEqual(code, 1, out)
        self.assertIn("编码后的 filter 会被服务端拒绝", out)

    def test_secids_keep_their_dots_and_commas(self):
        query = vsf.quote_query(["1.510300", "0.159915"])
        self.assertIn("secids=1.510300,0.159915", query)
        self.assertNotIn("1%2E510300", query)
        self.assertNotIn("1.510300%2C", query)
        self.assertIn("fields=" + vsf.QUOTE_FIELDS, query)

    def test_pool_floor_is_computed_and_says_the_scan_was_not_the_whole_market(self):
        # 「扫描总数不是全市场股票数」这件事必须每次都被算出来。
        # 池子下限一旦高于稳健档的 200 亿，中间那段标的就不在视野里了。
        code, out = self.run_with(self.fake_fetch())
        self.assertEqual(code, 0, out)
        self.assertTrue(any("池子市值下限" in line for line in out.splitlines()))
        self.assertTrue(any("200 亿" in n for n in vsf.notes), vsf.notes)

    def test_northbound_codes_are_noted_and_excluded_from_secids(self):
        pool = make_pool(60)
        # 市值给得比榜首大，插到最前面才不会破坏「降序」这条检查
        pool.insert(0, pool_row("920014", "920014.BJ", "特瑞斯", 2.1e12))
        code, out = self.run_with(self.fake_fetch(pool=pool))
        self.assertEqual(code, 0, out)
        self.assertTrue(any("北交所" in n for n in vsf.notes), vsf.notes)
        self.assertTrue(all(".BJ" not in s for s in vsf._secids(pool)))

    def test_pool_not_sorted_by_market_cap_descending_fails(self):
        pool = make_pool(60)
        pool[0], pool[-1] = pool[-1], pool[0]
        code, out = self.run_with(self.fake_fetch(pool=pool))
        self.assertEqual(code, 1, out)
        self.assertIn("市值是降序的", out)

    def test_market_cap_in_ten_thousands_fails_so_a_unit_change_is_caught(self):
        # 单位从「元」变成「万元」时数字照样好看，只有量级检查能抓到。
        pool = [dict(r, TOTAL_MARKET_CAP=r["TOTAL_MARKET_CAP"] / 1e4) for r in make_pool(60)]
        code, out = self.run_with(self.fake_fetch(pool=pool))
        self.assertEqual(code, 1, out)
        self.assertIn("市值单位是「元」", out)

    def test_pool_carrying_a_second_set_of_valuation_fields_is_noted(self):
        # 清单哪天开始带 PE/PB，就等于偷偷进来了第二套口径。
        pool = [dict(r, PE_TTM=12.3, PB_MRQ=1.8) for r in make_pool(60)]
        code, out = self.run_with(self.fake_fetch(pool=pool))
        self.assertEqual(code, 0, out)
        self.assertTrue(any("额外字段" in n and "PE_TTM" in n for n in vsf.notes), vsf.notes)

    # ---------- 批量上限 ----------

    def test_batch_limit_is_measured_and_written_into_the_notes(self):
        code, out = self.run_with(self.fake_fetch(pool=make_pool(500)))
        self.assertEqual(code, 0, out)
        self.assertTrue(any("行情批量实测至少能吃 500 只" in n for n in vsf.notes), vsf.notes)

    def test_a_batch_that_silently_drops_rows_is_a_failure(self):
        # 少一只就说明超了上限。少了的那只最后会被当成「行情没回来」剔除，
        # 池子静默缩水，摘要里的「缺少的行情数」莫名其妙变大——必须报出来。
        def fetch(path_query, hosts):
            body = self.fake_fetch(pool=make_pool(500))(path_query, hosts)
            if "ulist.np/get" in path_query and "510300" not in path_query:
                rows = body["data"]["diff"]
                if len(rows) > 100:
                    body["data"]["diff"] = rows[:-1]
            return body

        code, out = self.run_with(fetch)
        self.assertEqual(code, 1, out)
        self.assertIn("只一次取回", out)

    # ---------- 网络层 ----------

    def test_fetch_json_walks_the_whole_host_chain_then_reports_the_last_error(self):
        attempted = []

        def boom(request, timeout=None):
            attempted.append(request.full_url)
            raise urllib.error.URLError("connection refused")

        with mock.patch("urllib.request.urlopen", boom):
            with self.assertRaises(RuntimeError) as ctx:
                vsf.fetch_json("/api/qt/ulist.np/get?secids=1.510300", vsf.QUOTE_HOSTS)

        self.assertEqual(len(attempted), len(vsf.QUOTE_HOSTS))
        self.assertIn("connection refused", str(ctx.exception))

    def test_fetch_json_falls_back_to_the_next_host_when_the_first_is_down(self):
        seen = []

        def flaky(request, timeout=None):
            seen.append(request.full_url)
            if vsf.QUOTE_HOSTS[0] in request.full_url:
                raise urllib.error.URLError("first host down")
            return Resp(json.dumps({"ok": True}).encode())

        with mock.patch("urllib.request.urlopen", flaky):
            body = vsf.fetch_json("/api/qt/ulist.np/get?secids=1.510300", vsf.QUOTE_HOSTS)

        self.assertEqual(body, {"ok": True})
        self.assertTrue(any(vsf.QUOTE_HOSTS[1] in u for u in seen), "回退没用上第二个 host")

    def test_each_host_gets_its_own_referer(self):
        # 清单域名认 data.eastmoney.com，行情域名认 quote.eastmoney.com。
        # 发错了不报错，只是可能被静默降级——所以这里显式钉住。
        seen = {}

        def capture(request, timeout=None):
            seen[urllib.parse.urlparse(request.full_url).netloc] = request.get_header("Referer")
            return Resp(json.dumps({"ok": True}).encode())

        with mock.patch("urllib.request.urlopen", capture):
            vsf.fetch_json("/api/data/v1/get?reportName=x", vsf.LIST_HOSTS)
            vsf.fetch_json("/api/qt/ulist.np/get?secids=1.510300", vsf.QUOTE_HOSTS[:1])

        self.assertEqual(seen[vsf.LIST_HOSTS[0]], "https://data.eastmoney.com/")
        self.assertEqual(seen[vsf.QUOTE_HOSTS[0]],
                         "https://quote.eastmoney.com/center/gridlist.html")

    def test_all_hosts_fail_reports_the_last_error_and_no_traceback(self):
        attempted = []

        def boom(request, timeout=None):
            attempted.append(request.full_url)
            raise urllib.error.URLError("connection refused")

        with mock.patch("urllib.request.urlopen", boom):
            with mock.patch("sys.stdout", new=io.StringIO()) as out:
                code = vsf.main()
        text = out.getvalue()

        self.assertEqual(code, 1)
        self.assertIn("取数失败", text)
        self.assertNotIn("Traceback", text)
        # 被限流的特征要说出来，否则用户会以为是网络故障而反复重试
        self.assertIn("限流", text)
        self.assertTrue(attempted)
        self.assertIn(vsf.LIST_HOSTS[0], attempted[0])

    def test_the_url_is_built_exactly_as_java_builds_it(self):
        # 与 Java 侧 MarketDataClient.listUrl 发出的形态一致，便于两边对照同一个 URL
        query = vsf.list_query(500, "TOTAL_MARKET_CAP", -1, f"(TRADE_DATE='{TRADE_DATE}')")
        self.assertIn(f"&filter=(TRADE_DATE='{TRADE_DATE}')", query)
        for bad in ("%28", "%29", "%27", "%3D"):
            self.assertNotIn(bad, query)
        self.assertIn("columns=" + vsf.LIST_COLUMNS, query)
        self.assertIn("pageSize=500", query)
        self.assertIn("sortColumns=TOTAL_MARKET_CAP", query)
        self.assertIn("sortTypes=-1", query)

    def test_the_encoded_variant_really_is_encoded(self):
        query = vsf.list_query(8, "TOTAL_MARKET_CAP", -1, f"(TRADE_DATE='{TRADE_DATE}')",
                              encoded_filter=True)
        self.assertIn("%28", query)
        self.assertNotIn(f"filter=(TRADE_DATE='{TRADE_DATE}')", query)


if __name__ == "__main__":
    unittest.main()