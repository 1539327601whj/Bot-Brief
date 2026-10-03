"""verify_lookup_sources 自身的测试。

这个脚本是「代码查询」页上线前的把关人，它自己要是悄悄永远返回 0，比不存在更糟。
所以每条断言都要成对：**数据对时通过**，**接口口径漂移时报错**。
沙箱连不上东财，全部用合成响应喂。

它钉住的三件事都特别容易静默出事，这里逐个造出「漂移」的样子：
  1. 代码加引号就从「查得到」变成「永远 0 行」——服务端不报错，最难排查；
  2. PE_TTM 一旦不等于行情族 f115，f115 就不再是备源而是第二套口径；
  3. 历史起点一旦提前，个股「十年」这一档就从「必然为空」变成「应该有数」。
"""

import importlib.util
import io
import json
import re
import unittest
import urllib.error
import urllib.parse
from datetime import date
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "verify_lookup_sources.py"
SPEC = importlib.util.spec_from_file_location("verify_lookup_sources", MODULE_PATH)
vls = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(vls)

TODAY = date.today().isoformat()
# 样本股的 PE 与收盘价。刻意让两只量级差三倍，交叉验证才不是「碰巧相等」。
SAMPLE = {"300274": (15.568, 88.10, "阳光电源"), "600519": (21.42, 1520.5, "贵州茅台")}


class Resp(io.BytesIO):
    """urlopen 的返回值只需要能被 with 包住、能 read。"""

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def param(path_query, name):
    return urllib.parse.parse_qs(urllib.parse.urlparse(path_query).query).get(name, [None])[0]


def stock_rows(code, oldest="2018-01-02", pe=None, close=None):
    """一段降序的估值历史：只有脚本点名的列。"""
    if code in SAMPLE:
        pe = SAMPLE[code][0] if pe is None else pe
        close = SAMPLE[code][1] if close is None else close
    # SAMPLE 之外的代码（如演「指数跑进个股源」的 000300）给一套普通数值即可，
    # 它们不参与 PE 交叉验证，只需要能安安稳稳地走完字段检查。
    pe = 20.0 if pe is None else pe
    close = 100.0 if close is None else close
    name = SAMPLE.get(code, (0, 0, "名称" + code))[2]
    ascending = [oldest, "2020-01-02", "2024-01-02"]
    if TODAY != oldest:
        ascending.append(TODAY)
    return [
        {"SECURITY_CODE": code, "SECUCODE": code + ".SZ", "SECURITY_NAME_ABBR": name,
         "TRADE_DATE": d + " 00:00:00", "PE_TTM": pe, "PE_LAR": pe * 0.81,
         "PB_MRQ": 3.41, "CLOSE_PRICE": close}
        for d in reversed(ascending)
    ]


def csindex_points(oldest="2011-06-28", compact=True):
    """中证官网的 tradeDate 实测是**紧凑的 YYYYMMDD**（`20110628`），不是带横线的。

    这里默认就按实测形态造，否则测试会替被测脚本把格式假设擦干净——
    这个 bug 上一版就是被「带横线的假数据」漏过去的。
    """
    dates = [oldest, "2014-01-02", "2018-01-02", "2022-01-04", "2026-09-30"]
    if compact:
        dates = [d.replace("-", "") for d in dates]
    return [{"tradeDate": d, "peg": 12.0 + i} for i, d in enumerate(dates)]


def danjuan_items():
    """>50 条，含四只脚本点名要有的代码，外加一条 pe=0（缺数是用 0 表示的）。"""
    items = [{"index_code": c, "name": "指数", "pe": 20.0, "pe_percentile": 0.4}
             for c in ("NDX", "SP500", "SZ399975", "CSIH30533", "SZ399393")]
    items += [{"index_code": f"X{i:02d}", "name": f"填数{i}", "pe": 18.0, "pe_percentile": 0.5}
              for i in range(55)]
    items.append({"index_code": "SZ399812", "name": "缺数", "pe": 0, "pe_percentile": 0})
    return items


class VerifyLookupSourcesTest(unittest.TestCase):
    def setUp(self):
        vls.failures.clear()
        vls.network_failures.clear()
        vls.notes.clear()

    def fake_fetch(self, histories=None, csindex=None, items=None, quotes=None,
                   accept_quoted=False, quoted_rows=None, envelope=True):
        """假响应，按 path_query 的形态分派：估值分析 / 中证官网 / 蛋卷 / 行情。"""
        histories = histories if histories is not None else {
            code: stock_rows(code) for code in SAMPLE}
        csindex = csindex if csindex is not None else {
            "000300": csindex_points(), "399006": []}
        items = items if items is not None else danjuan_items()

        def fetch(path_query, hosts):
            if "reportName=" in path_query:
                if 'SECURITY_CODE="' in path_query:
                    # 带引号的形态：服务端既不报错也不给数。accept_quoted=True
                    # 用来演「服务端哪天开始认引号了」，脚本必须因此判失败。
                    rows = quoted_rows if accept_quoted else []
                    rows = rows if rows is not None else stock_rows("300274")
                    return {"result": {"data": rows if accept_quoted else [], "count": 0}}
                code = re.search(r"SECURITY_CODE=([^)&]+)", path_query).group(1)
                rows = histories.get(code, [])
                return {"result": {"data": rows, "count": len(rows)}}
            if "indexCsiDsPe" in path_query:
                data = csindex.get(param(path_query, "indexCode"), [])
                # envelope=False 演「信封没了」：数据还在，但契约变了。
                if not envelope:
                    return {"data": data}
                return {"code": "200", "success": True, "data": data}
            if "index_eva/dj" in path_query:
                return {"result_code": 0, "data": {"items": items}}
            if "ulist.np/get" in path_query:
                if quotes is not None:
                    return {"data": {"diff": quotes}}
                diff = []
                for s in (param(path_query, "secids") or "").split(","):
                    code = s.split(".")[-1]
                    pe, close, _ = SAMPLE.get(code, (None, None, None))
                    diff.append({"f12": code, "f14": "名称" + code, "f2": close, "f115": pe})
                return {"data": {"diff": diff}}
            raise AssertionError(f"未预期的请求 {path_query}")

        return fetch

    def run_with(self, fetch, args=()):
        # args 显式传空：main 默认读 sys.argv[1:]，那是测试运行器的参数，
        # 让被测脚本去猜它就会变成一个随命令行变化的测试。
        with mock.patch.object(vls, "fetch_json", fetch):
            with mock.patch("sys.stdout", new=io.StringIO()) as out:
                code = vls.main(list(args))
        return code, out.getvalue()

    # ---------- 数据正确时必须通过 ----------

    def test_self_consistent_data_passes(self):
        code, out = self.run_with(self.fake_fetch())
        self.assertEqual(code, 0, f"自洽数据不该报错：\n{out}")
        self.assertIn("全部通过", out)

    def test_short_stock_history_is_a_note_not_a_failure(self):
        # 历史不足 10 年是**当前事实**，不是错误。它必须变成一句明确的说明，
        # 而不是一个失败——失败会让人去改脚本，而事实不该被改。
        code, _ = self.run_with(self.fake_fetch())
        self.assertEqual(code, 0)
        self.assertTrue(any("必然为空" in n for n in vls.notes), vls.notes)
        self.assertTrue(any("「十年」基线不可得" in n for n in vls.notes), vls.notes)

    def test_zero_pe_in_danjuan_is_a_note_not_a_failure(self):
        # 蛋卷用 0 表示「没有数」。下游必须按非正 PE 丢弃，否则 0 会被当成极度便宜。
        code, _ = self.run_with(self.fake_fetch())
        self.assertEqual(code, 0)
        self.assertTrue(any("0 是「没有数」" in n for n in vls.notes), vls.notes)

    def test_pe_matching_f115_is_reported_as_the_reason_f115_can_be_a_fallback(self):
        code, _ = self.run_with(self.fake_fetch())
        self.assertEqual(code, 0)
        self.assertTrue(any("f115 可以顶「今日 PE」" in n for n in vls.notes), vls.notes)

    # ---------- 三条「静默出事」的反向验证 ----------

    def test_quoted_code_returns_nothing_and_that_is_the_expected_state(self):
        # 脚本必须主动发一次带引号的代码，并期望它**返回 0 行**。
        code, out = self.run_with(self.fake_fetch())
        self.assertEqual(code, 0, out)
        self.assertIn("加引号拿不到数据", out)

    def test_if_the_server_starts_accepting_quoted_codes_the_script_must_fail(self):
        # 反向验证本身不能是空的：服务端哪天开始认引号，「代码不能加引号」这条
        # 结论就失效了，注释与 Java 侧实现都要重新评估，这里必须红。
        code, out = self.run_with(self.fake_fetch(accept_quoted=True))
        self.assertEqual(code, 1, out)
        self.assertIn("加引号拿不到数据", out)

    def test_pe_no_longer_equal_to_f115_fails(self):
        # f115 被换成另一套口径时，同一天会出现两个 PE，而页面上看不出来。
        code, out = self.run_with(self.fake_fetch(quotes=[
            {"f12": "300274", "f2": 88.10, "f115": 12.6},
            {"f12": "600519", "f2": 1520.5, "f115": 21.42}]))
        self.assertEqual(code, 1, out)
        self.assertIn("PE_TTM == f115", out)

    def test_history_start_moving_earlier_fails_so_the_ten_year_claim_is_reevaluated(self):
        # 起点一旦变成 2015，个股「十年」就不再必然为空，页面文案与 §2.5 都要改。
        code, out = self.run_with(self.fake_fetch(histories={
            "300274": stock_rows("300274", oldest="2015-01-02"),
            "600519": stock_rows("600519", oldest="2015-01-02")}))
        self.assertEqual(code, 1, out)
        self.assertIn("历史起点就是表级保留范围", out)

    def test_samples_disagreeing_on_history_start_fails(self):
        # 起点不一致说明它按上市时间给，而不是表级范围——那「十年必然为空」就不成立。
        code, out = self.run_with(self.fake_fetch(histories={
            "300274": stock_rows("300274"),
            "600519": stock_rows("600519", oldest="2014-01-02")}))
        self.assertEqual(code, 1, out)
        self.assertIn("历史起点一致", out)

    # ---------- 字段漂移 ----------

    def test_missing_field_fails(self):
        rows = stock_rows("300274")
        for r in rows:
            r.pop("PE_LAR", None)
        histories = {"300274": rows, "600519": stock_rows("600519")}
        code, out = self.run_with(self.fake_fetch(histories=histories))
        self.assertEqual(code, 1, out)
        self.assertIn("PE_LAR", out)

    def test_trade_date_losing_its_time_part_fails(self):
        # 少了 00:00:00 说明格式变了，day_of 截前 10 位这条假设要重新看。
        rows = stock_rows("300274")
        for r in rows:
            r["TRADE_DATE"] = r["TRADE_DATE"][:10]
        code, out = self.run_with(self.fake_fetch(histories={
            "300274": rows, "600519": stock_rows("600519")}))
        self.assertEqual(code, 1, out)
        self.assertIn("TRADE_DATE 是", out)

    def test_unsorted_history_fails(self):
        # 基线靠「降序取第一条」找，顺序错了会静默取到最老的一条当今日。
        rows = list(reversed(stock_rows("300274")))
        code, out = self.run_with(self.fake_fetch(histories={
            "300274": rows, "600519": stock_rows("600519")}))
        self.assertEqual(code, 1, out)
        self.assertIn("按交易日降序", out)

    def test_close_price_that_no_longer_matches_f2_fails(self):
        rows = stock_rows("300274", close=1.0)
        code, out = self.run_with(self.fake_fetch(histories={
            "300274": rows, "600519": stock_rows("600519")}))
        self.assertEqual(code, 1, out)
        self.assertIn("CLOSE_PRICE == f2", out)

    def test_nothing_cross_checkable_is_itself_a_failure(self):
        # 全都缺 f115 就没人能确认 PE_TTM 与行情同口径，那 f115 就不能当备源。
        code, out = self.run_with(self.fake_fetch(quotes=[
            {"f12": "300274", "f2": 88.10, "f115": None},
            {"f12": "600519", "f2": 1520.5, "f115": None}]))
        self.assertEqual(code, 1, out)
        self.assertIn("无法确认两者同口径", out)

    # ---------- 源的边界 ----------

    def test_index_appearing_in_the_stock_source_fails(self):
        # 个股估值源里一旦有了指数，池外指数的路由规则就要重新想。
        code, out = self.run_with(self.fake_fetch(histories={
            "300274": stock_rows("300274"), "600519": stock_rows("600519"),
            "000300": stock_rows("000300")}))
        self.assertEqual(code, 1, out)
        self.assertIn("指数代码在这个源里回 0 行", out)

    def test_csindex_now_covering_shenzhen_399_fails(self):
        # 399006 一旦被中证官网覆盖，深证系就不必绕蛋卷了。
        code, out = self.run_with(self.fake_fetch(csindex={
            "000300": csindex_points(), "399006": csindex_points()}))
        self.assertEqual(code, 1, out)
        self.assertIn("不在覆盖范围内", out)

    def test_csindex_history_too_short_fails(self):
        # 中证历史不够 10 年，指数的 5/10 年就落不上数——那是要重新设计的事。
        code, out = self.run_with(self.fake_fetch(csindex={
            "000300": csindex_points(oldest="2020-01-02"), "399006": []}))
        self.assertEqual(code, 1, out)
        self.assertIn("历史够长", out)

    def test_csindex_not_ascending_fails(self):
        # 滚动分位窗口依赖升序，顺序变了算出来的分位就是错的。
        code, out = self.run_with(self.fake_fetch(csindex={
            "000300": list(reversed(csindex_points())), "399006": []}))
        self.assertEqual(code, 1, out)
        self.assertIn("按交易日升序", out)

    def test_csindex_date_switching_to_dashes_fails(self):
        # 这就是上一版被测脚本漏掉的真事：中证回的是 20110628，不是 2011-06-28。
        # 换成带横线后 fetch_csindex_pe_history 会静默丢掉每一个点，
        # 最后只报「未返回有效PE历史」——必须在这里红，而不是在线上。
        code, out = self.run_with(self.fake_fetch(csindex={
            "000300": csindex_points(compact=False), "399006": []}))
        self.assertEqual(code, 1, out)
        self.assertIn("紧凑的 YYYYMMDD", out)

    def test_csindex_business_envelope_being_dropped_fails(self):
        # HTTP 200 也可以是业务失败；信封没了就说明这个接口的契约变了。
        code, out = self.run_with(self.fake_fetch(csindex={
            "000300": csindex_points(), "399006": []}, envelope=False))
        self.assertEqual(code, 1, out)
        self.assertIn("业务信封", out)

    def test_compact_csindex_dates_are_normalised_before_comparison(self):
        # 归一化必须真的发生，否则「升序」检查是在比较字符串而不是日期。
        self.assertEqual(vls.csindex_day("20110628"), "2011-06-28")
        self.assertEqual(vls.csindex_day("2011-06-28"), "")

    def test_danjuan_losing_an_overseas_index_fails(self):
        items = [i for i in danjuan_items() if i["index_code"] != "NDX"]
        code, out = self.run_with(self.fake_fetch(items=items))
        self.assertEqual(code, 1, out)
        self.assertIn("蛋卷含 NDX", out)

    def test_danjuan_shrinking_below_the_single_call_size_fails(self):
        # 一次回不满就说明它改成翻页了，那「一次请求」的假设要重新看。
        code, out = self.run_with(self.fake_fetch(items=danjuan_items()[:5]))
        self.assertEqual(code, 1, out)
        self.assertIn("一次回 ≥", out)

    # ---------- URL 拼装 ----------

    def test_code_filter_is_sent_unquoted(self):
        query = vls.valuation_query("300274")
        self.assertIn("&filter=(SECURITY_CODE=300274)", query)
        for bad in ("%28", "%29", "%3D"):
            self.assertNotIn(bad, query)
        self.assertIn("columns=" + vls.VALUATION_COLUMNS, query)
        self.assertIn("sortColumns=TRADE_DATE", query)
        self.assertIn("sortTypes=-1", query)

    def test_the_quoted_variant_really_is_quoted(self):
        # 反向验证那一发必须真的带引号，否则「加引号查不到」就是自说自话。
        query = vls.valuation_query("300274", quoted=True)
        self.assertIn('filter=(SECURITY_CODE="300274")', query)

    def test_secids_keep_their_dots_and_commas(self):
        query = vls.quote_query(["0.300274", "1.600519"])
        self.assertIn("secids=0.300274,1.600519", query)
        self.assertNotIn("0%2E300274", query)
        self.assertNotIn("0.300274%2C", query)

    def test_csindex_query_is_the_same_shape_etf_report_uses(self):
        # 与 etf_report.fetch_csindex_pe_history 打的是同一个接口、同一个参数名，
        # 池外指数走的才是「已验证有深度历史」那条路。
        self.assertEqual(vls.csindex_query("H30533"),
                         "/csindex-home/perf/indexCsiDsPe?indexCode=H30533")

    # ---------- 网络层 ----------

    def test_each_host_gets_its_own_referer(self):
        seen = {}

        def capture(request, timeout=None):
            seen[urllib.parse.urlparse(request.full_url).netloc] = request.get_header("Referer")
            return Resp(json.dumps({"ok": True}).encode())

        with mock.patch("urllib.request.urlopen", capture):
            vls.fetch_json("/api/data/v1/get?reportName=x", vls.VALUATION_HOSTS)
            vls.fetch_json(vls.csindex_query("000300"), vls.CSINDEX_HOSTS)
            vls.fetch_json(vls.quote_query(["1.510300"]), vls.QUOTE_HOSTS[:1])

        self.assertEqual(seen[vls.VALUATION_HOSTS[0]], "https://data.eastmoney.com/")
        self.assertEqual(seen[vls.CSINDEX_HOSTS[0]], "https://www.csindex.com.cn/")
        self.assertEqual(seen[vls.QUOTE_HOSTS[0]],
                         "https://quote.eastmoney.com/center/gridlist.html")

    def test_fetch_json_walks_the_whole_host_chain_then_reports_the_last_error(self):
        attempted = []

        def boom(request, timeout=None):
            attempted.append(request.full_url)
            raise urllib.error.URLError("connection refused")

        with mock.patch("urllib.request.urlopen", boom):
            with self.assertRaises(RuntimeError) as ctx:
                vls.fetch_json(vls.quote_query(["1.510300"]), vls.QUOTE_HOSTS)

        self.assertEqual(len(attempted), len(vls.QUOTE_HOSTS))
        self.assertIn("connection refused", str(ctx.exception))

    def test_http_error_body_is_carried_into_the_message(self):
        # 「参数预处理错误」这行字只在响应体里，不在状态码里，丢了就没法排查。
        def boom(request, timeout=None):
            raise urllib.error.HTTPError(
                request.full_url, 500, "Server Error", {},
                io.BytesIO("参数预处理错误: NoViableAltException".encode()))

        with mock.patch("urllib.request.urlopen", boom):
            with self.assertRaises(RuntimeError) as ctx:
                vls.fetch_json("/api/data/v1/get?reportName=x", vls.VALUATION_HOSTS[:1])

        self.assertIn("NoViableAltException", str(ctx.exception))

    def test_all_hosts_fail_reports_rate_limiting_and_no_traceback(self):
        def boom(request, timeout=None):
            raise urllib.error.URLError("connection refused")

        with mock.patch("urllib.request.urlopen", boom):
            with mock.patch("sys.stdout", new=io.StringIO()) as out:
                code = vls.main([])
        text = out.getvalue()

        self.assertEqual(code, 1)
        self.assertIn("取数失败", text)
        self.assertNotIn("Traceback", text)
        # 被限流的特征要说出来，否则用户会以为是网络故障而反复重试，越试封得越久
        self.assertIn("限流", text)

    def test_one_host_being_down_does_not_cancel_the_other_sections(self):
        # 东财被限流时，中证与蛋卷那几节恰恰是最想看的——不该被一起取消。
        good = self.fake_fetch()

        def fetch(path_query, hosts):
            if "ulist.np/get" in path_query:
                raise RuntimeError("push2.eastmoney.com 等 3 个 host 都没取到数据")
            return good(path_query, hosts)

        code, out = self.run_with(fetch)
        # 仍是 1（有节没跑成就不给绿灯），但后面几节必须照跑
        self.assertEqual(code, 1, out)
        self.assertIn("本节取数失败", out)
        self.assertIn("六、中证官网覆盖边界", out)
        self.assertIn("能取到 PE 历史", out)
        self.assertIn("蛋卷一次回全部指数", out)
        self.assertTrue(any("PE 交叉验证" in n for n in vls.network_failures), vls.network_failures)
        # 而「口径漂移」这类失败一个都不该有：事实没变，只是有一节没查到
        self.assertFalse([f for f in vls.failures if "PE_TTM == f115" in f], vls.failures)

    def test_network_failure_alone_is_not_reported_as_a_口径_problem(self):
        def boom(request, timeout=None):
            raise urllib.error.URLError("connection refused")

        with mock.patch("urllib.request.urlopen", boom):
            with mock.patch("sys.stdout", new=io.StringIO()) as out:
                vls.main([])
        text = out.getvalue()
        self.assertIn("不是口径问题", text)

    # ---------- --skip-quote：冷却期不打行情域名 ----------

    def test_skip_quote_never_touches_the_quote_domain(self):
        # 这就是这个开关存在的全部理由：行情域名在冷却期时**一个字节都不该发过去**。
        quote_calls = []

        def fetch(path_query, hosts):
            if "ulist.np/get" in path_query:
                quote_calls.append(path_query)
            return self.fake_fetch()(path_query, hosts)

        code, out = self.run_with(fetch, args=("--skip-quote",))
        self.assertEqual(code, 0, out)
        self.assertIn("--skip-quote 跳过", out)
        self.assertFalse(quote_calls, f"跳过时仍然打了行情域名：{quote_calls}")

    def test_skip_quote_does_not_claim_the_skipped_section_passed(self):
        # 跳过 ≠ 通过。结论必须写明那一节没有被校验，否则绿灯是假的。
        code, out = self.run_with(self.fake_fetch(), args=("--skip-quote",))
        self.assertEqual(code, 0, out)
        self.assertIn("没有被校验", out)
        # 「除跳过的 N 节外全部通过」是允许的，但不能出现**无限定**的那句绿灯。
        self.assertNotIn("全部通过：三个源的口径", out)
        self.assertTrue(any("PE 交叉验证" in s for s in vls.skipped), vls.skipped)

    def test_a_green_check_does_not_print_its_failure_explanation(self):
        # 通过的一行如果配着「它居然…」这种失败解释，用户几次之后就不再读输出了。
        code, out = self.run_with(self.fake_fetch())
        self.assertEqual(code, 0)
        self.assertNotIn("它居然", out)

    def test_skip_quote_still_reports_real_field_drift(self):
        # 跳过了一节，其余各节的漂移照抓——这个开关不能让脚本变成永远绿。
        code, out = self.run_with(
            self.fake_fetch(csindex={"000300": csindex_points(oldest="2020-01-02"), "399006": []}),
            args=("--skip-quote",))
        self.assertEqual(code, 1, out)
        self.assertIn("历史够长", out)

    def test_without_the_flag_the_quote_domain_is_actually_called(self):
        # 反向：不加开关时必须真的去打行情域名，否则这个开关就是默认跳过，
        # 第四节会永远是摆设。
        calls = []

        def fetch(path_query, hosts):
            if "ulist.np/get" in path_query:
                calls.append(path_query)
            return self.fake_fetch()(path_query, hosts)

        code, _ = self.run_with(fetch)
        self.assertEqual(code, 0)
        self.assertTrue(calls, "不加 --skip-quote 时行情域名必须被调用")


if __name__ == "__main__":
    unittest.main()