"""backfill_valuation_history 的测试。

这个脚本要往库里写约 8 万行、要打几十次按 IP 限流的中证官网，所以它的每条边界
都得在这里被证明过，而不是上线时才发现：

  * 任一只取数失败 → **一条都不写**（「一部分指数有十年历史、一部分没有」的库
    从页面上看不出来）；
  * 窗口边界：`today-10年-30天` 之前的行必须被裁掉，边界那一行必须留着；
  * 蛋卷要被跳过并说明原因（它没有历史序列，想回填也没有数）；
  * 哨兵按**池子里 csindex 代码集合**失效，不按日期——按日期会让它每天重推 8 万行。
"""

import importlib.util
import io
import os
import sys
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import patch

SCRIPTS_DIR = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

MODULE_PATH = SCRIPTS_DIR / "backfill_valuation_history.py"
SPEC = importlib.util.spec_from_file_location("backfill_valuation_history", MODULE_PATH)
bf = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bf)

report = bf.report

BACKEND_ENV = {"BACKEND_API_URL": "http://backend:8080", "REPORT_INGEST_TOKEN": "secret"}
# 窗口边界按真实 today 算，测试与运行日期无关。
CUTOFF = bf.backfill_cutoff()


def csindex_fund(code, index_code=None):
    return {
        "indexCode": index_code or f"SH{code}",
        "indexName": f"指数{code}",
        "category": "broad",
        "valuationSource": "csindex",
        "percentileMethod": report.CSI_PE_TTM_ROLLING_10Y,
        "csindexCode": code,
        "danjuanCode": None,
        "etfCode": None,
        "market": None,
        "etfName": None,
        "tracking": None,
    }


def danjuan_fund(code, index_code, danjuan_code):
    return {
        "indexCode": index_code,
        "indexName": f"指数{code}",
        "category": "broad",
        "valuationSource": "danjuan",
        "percentileMethod": report.DANJUAN_PE_TTM_PROVIDER,
        "csindexCode": None,
        "danjuanCode": danjuan_code,
        "etfCode": None,
        "market": None,
        "etfName": None,
        "tracking": None,
    }


def days_from_cutoff(offset):
    return (CUTOFF + timedelta(days=offset)).isoformat()


def history(offsets=(0, 1, 2), pe=13.5, percentile=48.8):
    """按「距窗口边界多少天」造历史；offset<0 的行必须被裁掉。"""
    return [{
        "tradeDate": days_from_cutoff(offset),
        "peTtm": pe,
        "pePercentile": percentile,
        "percentileMethod": report.CSI_PE_TTM_ROLLING_10Y,
        "source": f"{report.CSI_PE_SOURCE_LABEL}，滚动10年分位",
    } for offset in offsets]


class BackfillTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.marker = os.path.join(self._tmp.name, "marker")
        patcher = patch.object(bf, "MARKER_PATH", self.marker)
        patcher.start()
        self.addCleanup(patcher.stop)
        env = patch.dict(os.environ, BACKEND_ENV, clear=False)
        env.start()
        self.addCleanup(env.stop)

    def collect_poster(self, fail_on_chunk=None):
        """记下每次推送的 chunk，用来检查切批与内容；可选在某批失败。"""
        chunks = []

        def poster(chunk):
            chunks.append(list(chunk))
            if fail_on_chunk is not None and len(chunks) == fail_on_chunk:
                return False
            return True

        return chunks, poster

    def fetcher_from(self, histories, on_call=None):
        def fetcher(code):
            if on_call:
                on_call(code)
            value = histories.get(code)
            if isinstance(value, Exception):
                raise value
            return value
        return fetcher

    # ---------- 正常路径 ----------

    def test_all_csindex_indices_are_fetched_and_pushed(self):
        funds = [csindex_fund("000300"), csindex_fund("000905")]
        histories = {"000300": history((0, 1, 2)), "000905": history((0, 1))}
        chunks, poster = self.collect_poster()

        ok = bf.run(indices=funds, fetcher=self.fetcher_from(histories), poster=poster)

        self.assertTrue(ok)
        rows = [r for chunk in chunks for r in chunk]
        self.assertEqual(len(rows), 5)
        self.assertEqual({r["indexCode"] for r in rows}, {"SH000300", "SH000905"})
        self.assertTrue(all(r["percentileMethod"] == report.CSI_PE_TTM_ROLLING_10Y for r in rows))
        # 口径必须一路带着来源，章程 §8 要求每行能回答「哪一日、哪一口径」
        self.assertTrue(all(r["source"] for r in rows))
        self.assertTrue(all(r["tradeDate"] for r in rows))

    def test_danjuan_is_skipped_and_the_reason_is_logged(self):
        funds = [csindex_fund("000300"), danjuan_fund("NDX", "NDX", "NDX")]
        fetched = []
        chunks, poster = self.collect_poster()

        with patch.object(bf.logger, "info") as logged:
            ok = bf.run(indices=funds,
                        fetcher=self.fetcher_from({"000300": history()}, on_call=fetched.append),
                        poster=poster)

        self.assertTrue(ok)
        self.assertEqual(fetched, ["000300"], "蛋卷不该被取数")
        self.assertEqual({r["indexCode"] for chunk in chunks for r in chunk}, {"SH000300"})
        self.assertTrue(any("没有历史序列" in str(call) for call in logged.call_args_list),
                        "跳过蛋卷必须说明原因，不能静默过滤")

    def test_a_pool_without_csindex_indices_is_a_no_op_success(self):
        ok = bf.run(indices=[danjuan_fund("NDX", "NDX", "NDX")],
                    fetcher=lambda code: self.fail("不该取数"))
        self.assertTrue(ok)
        self.assertFalse(os.path.exists(self.marker), "无事可做时不该落哨兵")

    # ---------- 窗口边界 ----------

    def test_rows_before_the_cutoff_are_dropped_and_the_boundary_row_is_kept(self):
        rows, dropped = bf.backfill_rows(csindex_fund("000300"),
                                         history((-400, -1, 0, 1)), CUTOFF)
        dates = [r["tradeDate"] for r in rows]
        self.assertEqual(dates, [days_from_cutoff(0), days_from_cutoff(1)])
        self.assertEqual(dropped, 2)

    def test_the_cutoff_is_the_ten_year_window_plus_a_buffer(self):
        # 缓冲是为了让「10 年前当天或之前」那一行能落上。用具体日期钉死，
        # 这样改动窗口年数或缓冲天数都会红，而不是靠读代码才发现。
        self.assertEqual(bf.backfill_cutoff(date(2026, 10, 3)), date(2016, 9, 3))
        # 2/29 的 10 年前没有 2/29：subtract_years 落 28 日，再退 30 天
        self.assertEqual(bf.backfill_cutoff(date(2024, 2, 29)), date(2014, 1, 29))
        self.assertEqual(bf.CUTOFF_BUFFER_DAYS, 30)
        self.assertEqual(report.CSI300_PE_WINDOW_YEARS, 10)

    def test_an_index_with_no_rows_in_the_window_is_not_pushed_silently(self):
        chunks, poster = self.collect_poster()
        with patch.object(bf.logger, "warning") as warned, \
                patch.object(bf.logger, "error") as errored:
            ok = bf.run(indices=[csindex_fund("000688")],
                        fetcher=self.fetcher_from({"000688": history((-5, -1))}),
                        poster=poster)
        # 窗口内一行都没有 → 没有东西可写，是失败而不是「成功写了 0 行」
        self.assertFalse(ok)
        self.assertEqual(chunks, [])
        # 每一只都要单独说清是它没点上，别让人以为是整体故障
        self.assertTrue(any("在 %s 之后没有任何点" in str(c.args[0]) for c in warned.call_args_list),
                        warned.call_args_list)
        self.assertTrue(any("没有任何可回填的行" in str(c.args[0]) for c in errored.call_args_list),
                        errored.call_args_list)

    # ---------- 失败即整体放弃 ----------

    def test_any_fetch_failure_writes_nothing_at_all(self):
        funds = [csindex_fund("000300"), csindex_fund("000905")]
        histories = {"000300": history(), "000905": RuntimeError("中证 429")}
        chunks, poster = self.collect_poster()

        ok = bf.run(indices=funds, fetcher=self.fetcher_from(histories), poster=poster)

        self.assertFalse(ok)
        self.assertEqual(chunks, [], "有一只没取到就一条都不能写")
        self.assertFalse(os.path.exists(self.marker), "失败不该落哨兵")

    def test_push_failure_reports_how_many_rows_already_landed(self):
        # 中途失败时只说「失败」是不够的：用户不知道库里现在是半截状态。
        funds = [csindex_fund("000300")]
        rows = 300
        histories = {"000300": [dict(item) for item in
                                [dict(tradeDate=days_from_cutoff(i), peTtm=13.5,
                                      pePercentile=48.8,
                                      percentileMethod=report.CSI_PE_TTM_ROLLING_10Y,
                                      source="x") for i in range(rows)]]}
        chunks, poster = self.collect_poster(fail_on_chunk=2)

        with patch.object(bf.logger, "error") as errored:
            ok = bf.run(indices=funds, fetcher=self.fetcher_from(histories), poster=poster)

        self.assertFalse(ok)
        self.assertEqual(len(chunks), 2)
        self.assertEqual(len(chunks[0]), bf.VALUATION_INGEST_BATCH_SIZE)
        self.assertEqual(len(chunks[1]), rows - bf.VALUATION_INGEST_BATCH_SIZE)
        # logger 是用 %s 惰性格式化的，被 mock 后 call.args 是 (格式串, *参数)，
        # 所以要按参数比对，而不是去找一句插值好的话
        self.assertTrue(any(str(c.args[0]).startswith("❌ 回填未完成") and c.args[1:] == (250, 300)
                            for c in errored.call_args_list),
                        errored.call_args_list)

    # ---------- 切批 ----------

    def test_chunks_never_exceed_the_backend_limit(self):
        rows = bf.VALUATION_INGEST_BATCH_SIZE * 2 + 7
        histories = {"000300": [dict(item) for item in
                                [dict(tradeDate=days_from_cutoff(i), peTtm=13.5,
                                      pePercentile=48.8,
                                      percentileMethod=report.CSI_PE_TTM_ROLLING_10Y,
                                      source="x") for i in range(rows)]]}
        chunks, poster = self.collect_poster()

        ok = bf.run(indices=[csindex_fund("000300")],
                    fetcher=self.fetcher_from(histories), poster=poster)

        self.assertTrue(ok)
        self.assertEqual(len(chunks), 3)
        self.assertTrue(all(len(c) <= bf.VALUATION_INGEST_BATCH_SIZE for c in chunks))
        self.assertEqual(sum(len(c) for c in chunks), rows)
        # 与后端 @Size(max=250) 对齐，不另立一个数
        self.assertEqual(bf.VALUATION_INGEST_BATCH_SIZE, 250)

    # ---------- 哨兵 ----------

    def test_rerun_is_skipped_by_the_marker_and_payload_is_identical_under_force(self):
        funds = [csindex_fund("000300")]
        histories = {"000300": history((0, 1, 2))}

        chunks1, poster1 = self.collect_poster()
        self.assertTrue(bf.run(indices=funds, fetcher=self.fetcher_from(histories), poster=poster1))

        # 第二次：哨兵命中，连取数都不该发生
        chunks2, poster2 = self.collect_poster()
        ok = bf.run(indices=funds,
                    fetcher=lambda code: self.fail("哨兵命中时不该再取数"),
                    poster=poster2)
        self.assertTrue(ok)
        self.assertEqual(chunks2, [])

        # --force 重跑：payload 必须与第一次逐字相同（顺序稳定，便于对照）
        chunks3, poster3 = self.collect_poster()
        self.assertTrue(bf.run(force=True, indices=funds,
                               fetcher=self.fetcher_from(histories), poster=poster3))
        self.assertEqual(chunks1, chunks3)

    def test_the_marker_key_ignores_the_date_but_follows_the_pool(self):
        # 日期进键 → 每天失效 → 每天重推 8 万行。这条就是防这个的。
        key_a = bf.marker_key(["000300", "000905"])
        self.assertEqual(key_a, bf.marker_key(["000905", "000300"]), "顺序不该影响键")
        self.assertEqual(key_a, bf.marker_key(["000300", "000905", "000300"]), "重复也不该影响")
        self.assertNotEqual(key_a, bf.marker_key(["000300", "000905", "000688"]),
                            "往池子新加中证指数必须让哨兵失效")

    def test_a_stale_marker_from_another_pool_does_not_block_the_run(self):
        with open(self.marker, "w", encoding="utf-8") as handle:
            handle.write("deadbeef\n2020-01-01\n")
        chunks, poster = self.collect_poster()
        ok = bf.run(indices=[csindex_fund("000300")],
                    fetcher=self.fetcher_from({"000300": history()}), poster=poster)
        self.assertTrue(ok)
        self.assertTrue(chunks, "哨兵不匹配时必须照跑")

    # ---------- 干跑 / 参数 ----------

    def test_dry_run_writes_nothing_and_leaves_no_marker(self):
        chunks, poster = self.collect_poster()
        ok = bf.run(dry_run=True, indices=[csindex_fund("000300")],
                    fetcher=self.fetcher_from({"000300": history()}), poster=poster)
        self.assertTrue(ok)
        self.assertEqual(chunks, [], "干跑一条都不能写")
        self.assertFalse(os.path.exists(self.marker), "干跑不该落哨兵")

    def test_dry_run_does_not_need_the_ingest_token(self):
        with patch.dict(os.environ, {"REPORT_INGEST_TOKEN": ""}, clear=False):
            ok = bf.run(dry_run=True, indices=[csindex_fund("000300")],
                        fetcher=self.fetcher_from({"000300": history()}),
                        poster=lambda chunk: self.fail("干跑不该推送"))
        self.assertTrue(ok)

    def test_a_real_run_without_the_ingest_token_fails_before_any_call(self):
        with patch.dict(os.environ, {"REPORT_INGEST_TOKEN": ""}, clear=False):
            ok = bf.run(indices=[csindex_fund("000300")],
                        fetcher=lambda code: self.fail("没有 token 就不该开始取数"))
        self.assertFalse(ok)

    def test_unknown_arguments_are_rejected(self):
        self.assertEqual(bf.main(["--nope"]), 2)
        self.assertEqual(bf.main(["--force", "--dry-run", "extra"]), 2)

    def test_force_can_also_come_from_the_environment(self):
        # 与本仓库其它脚本的开关保持一致（INDEX_POOL_FORCE 就是这么做的）
        with patch.dict(os.environ, {"VALUATION_BACKFILL_FORCE": "1"}, clear=False):
            with patch.object(bf, "run", return_value=True) as ran:
                self.assertEqual(bf.main([]), 0)
        self.assertTrue(ran.call_args.kwargs["force"])


class PostBatchTests(unittest.TestCase):
    """post_batch 是唯一真的发请求的地方，单独钉一下它的形状。"""

    def setUp(self):
        env = patch.dict(os.environ, BACKEND_ENV, clear=False)
        env.start()
        self.addCleanup(env.stop)

    def test_the_batch_is_posted_with_the_ingest_token(self):
        captured = {}

        class Resp:
            status_code = 200

            @staticmethod
            def json():
                return {"code": 200, "message": "ok", "data": None}

        def fake_post(url, json=None, headers=None, timeout=None):
            captured.update(url=url, json=json, headers=headers)
            return Resp()

        with patch.object(bf.requests, "post", fake_post):
            ok = bf.post_batch([{"indexCode": "SH000300"}])

        self.assertTrue(ok)
        self.assertTrue(captured["url"].endswith("/api/market-valuations/ingest-batch"))
        self.assertEqual(captured["headers"]["X-Ingest-Token"], "secret")
        self.assertEqual(captured["json"], [{"indexCode": "SH000300"}])

    def test_a_transport_error_is_a_false_not_an_exception(self):
        with patch.object(bf.requests, "post", side_effect=OSError("boom")):
            self.assertFalse(bf.post_batch([{"indexCode": "SH000300"}]))


if __name__ == "__main__":
    unittest.main()