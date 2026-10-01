import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

SCRIPTS_DIR = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

MODULE_PATH = SCRIPTS_DIR / "build_index_pool.py"
SPEC = importlib.util.spec_from_file_location("build_index_pool", MODULE_PATH)
build = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build)

report = build.report


def etf(code, name, nmc_wan):
    return {
        "code": code,
        "symbol": ("sh" if code.startswith("5") else "sz") + code,
        "name": name,
        "nmc": nmc_wan,
        "mktcap": nmc_wan,
    }


def seed_entry(name, aliases=None, **kwargs):
    entry = {
        "indexName": name,
        "aliases": aliases or [],
        "category": "broad",
        "csindexCode": "",
        "csindexVerifiedOn": "",
        "danjuanCode": "",
        "etfCode": "",
    }
    entry.update(kwargs)
    return entry


DANJUAN = {"byCode": {}, "byName": {}}
DANJUAN_WITH_CSI300 = {"byCode": {"SH000300": {}}, "byName": {"沪深300": "SH000300"}}
VERIFIED = {"csindexVerifiedOn": "2026-10-01"}


class MatchingTests(unittest.TestCase):
    """匹配规则要防的是「看着像、其实是另一个指数」——那是比空白更糟的错。"""

    def test_matching_is_a_prefix_not_a_substring(self):
        # 512260 的主题是「中证500低波动」：里面确实含「500低波」，但它开头不是，
        # 用「包含」会把它认成 500低波；真要认它，就得在 seed 里写全名
        etfs = [etf("512260", "中证500低波动ETF华安", 3800)]
        self.assertEqual(build.match_etfs(seed_entry("500低波", ["500低波"]), etfs), [])
        full = build.match_etfs(seed_entry("500低波", ["中证500低波动"]), etfs)
        self.assertEqual(full[0][1]["code"], "512260")

    def test_the_longest_matching_token_wins_a_contested_etf(self):
        etfs = [etf("512890", "红利低波ETF华泰柏瑞", 31254000)]
        seed = [seed_entry("上证红利", ["上证红利", "红利"]), seed_entry("300红利低波", ["红利低波"])]

        proposals = build.propose_representative_etfs(seed, etfs)

        self.assertIsNone(proposals[0]["etf"])
        self.assertEqual(proposals[1]["etf"]["code"], "512890")
        self.assertIn("归给了「300红利低波」", proposals[0]["warnings"][0])

    def test_within_one_index_the_biggest_fund_is_proposed(self):
        # 「深证100 / 深100」是同一个指数的两种叫法，长度只说明谁更具体，不代表更值得当代表
        etfs = [etf("159716", "深证100E", 5700), etf("159901", "深100ETF", 3593000)]
        proposals = build.propose_representative_etfs([seed_entry("深证100", ["深100"])], etfs)
        self.assertEqual(proposals[0]["etf"]["code"], "159901")

    def test_one_etf_is_never_handed_to_two_indices(self):
        etfs = [etf("512890", "红利低波ETF华泰柏瑞", 31254000)]
        seed = [seed_entry("红利低波", ["红利低波"]), seed_entry("红利低波100", ["红利低波"])]

        proposals = build.propose_representative_etfs(seed, etfs)

        handed_out = [index for index in proposals if proposals[index]["etf"]]
        self.assertEqual(len(handed_out), 1)

    def test_a_wide_match_is_flagged_for_a_human(self):
        etfs = [etf(f"51200{i}", f"医药{i}", 100000 + i) for i in range(6)]
        proposals = build.propose_representative_etfs([seed_entry("医药", ["医药"])], etfs)
        self.assertEqual(proposals[0]["confidence"], "low")
        self.assertTrue(any("人工确认" in warning for warning in proposals[0]["warnings"]))

    def test_a_confirmed_etf_that_vanished_is_reported_not_dropped_silently(self):
        proposals = build.propose_representative_etfs(
            [seed_entry("沪深300", etfCode="510300")], [etf("510301", "别的ETF", 100)]
        )
        self.assertEqual(proposals[0]["confidence"], "confirmed")
        self.assertIsNone(proposals[0]["etf"])
        self.assertIn("找不到", proposals[0]["warnings"][0])


class CandidateTests(unittest.TestCase):
    def test_the_seven_existing_codes_are_unchanged(self):
        # 现有 7 条的 indexCode 必须一字不差，否则老数据要迁移。
        # 注意这跟「有 danjuanCode 就用它」是两回事：000905 恰好两种算法结果一样，
        # 但 930740 那种就不一样了（见下一条）。
        for csindex_code, danjuan_code, want in (
            ("000300", "SH000300", "SH000300"),
            ("000905", "SH000905", "SH000905"),
            ("000016", "SH000016", "SH000016"),
            ("000688", "SH000688", "SH000688"),
            ("000852", "SH000852", "SH000852"),
            ("000015", "SH000015", "SH000015"),
            ("", "SZ399006", "SZ399006"),
        ):
            entry = seed_entry("x", csindexCode=csindex_code, danjuanCode=danjuan_code)
            self.assertEqual(build.derived_index_code(entry), want, csindex_code or danjuan_code)

    def test_a_danjuan_code_is_not_copied_verbatim(self):
        """
        蛋卷对同一个号段有两种写法。照抄的话，同一个指数换一条路径进来就会在库里
        多长出一个 key，两份估值并存、两张卡都显示正常。
        """
        danjuan_style = seed_entry("红利低波", csindexCode="930740", danjuanCode="CSI930740")
        self.assertEqual(build.derived_index_code(danjuan_style), "CSI930740")
        # 同一号段、蛋卷写成 SH 的那种，也必须落到同一个前缀上
        self.assertEqual(build.derived_index_code(seed_entry("动漫游戏", csindexCode="930901")),
                         "CSI930901")
        self.assertEqual(build.derived_index_code(seed_entry("动漫游戏", csindexCode="930901",
                                                            danjuanCode="SH930901")),
                         "CSI930901")

    def test_the_prefix_comes_from_the_segment(self):
        # 中证自编（930/931/716/H30）不是沪深交易所公布的代码，写 SH 会读成「上交所的 930713」
        self.assertEqual(build.index_code_prefix("930713"), "CSI")
        self.assertEqual(build.index_code_prefix("931151"), "CSI")
        self.assertEqual(build.index_code_prefix("716567"), "CSI")
        self.assertEqual(build.index_code_prefix("H30533"), "CSI")
        self.assertEqual(build.index_code_prefix("000300"), "SH")
        self.assertEqual(build.index_code_prefix("950090"), "SH")
        self.assertEqual(build.index_code_prefix("399967"), "SZ")
        self.assertEqual(build.index_code_prefix("980017"), "SZ")
        self.assertEqual(build.derived_index_code(seed_entry("军工", csindexCode="399967")), "SZ399967")
        self.assertEqual(build.derived_index_code(seed_entry("中概互联50", csindexCode="H30533")),
                         "CSIH30533")

    def test_an_overseas_index_keeps_its_own_identifier(self):
        # 港股/美股没有沪深代码，强行套一个前缀等于编造一个交易所归属
        entry = seed_entry("恒生指数", category="overseas", danjuanCode="HKHSI")
        self.assertEqual(build.derived_index_code(entry), "HKHSI")
        entry = seed_entry("标普500", category="overseas", danjuanCode="SP500")
        self.assertEqual(build.derived_index_code(entry), "SP500")

    def test_a_pool_code_that_means_something_else_is_a_warning(self):
        # 同一个 indexCode 两个名字 = 同一个 code 底下两套估值，页面上看不出来
        entry = seed_entry("别的指数", csindexCode="000300", danjuanCode="SH000300")
        candidate = build.build_candidate(entry, {"etf": None, "confidence": "none", "why": [], "warnings": []},
                                         DANJUAN, {"SH000300": "沪深300"})
        self.assertTrue(any("两份估值" in warning for warning in candidate["warnings"]))
        self.assertTrue(candidate["alreadyInPool"])

    def test_the_same_code_with_the_same_name_is_not_a_warning(self):
        entry = seed_entry("沪深300", csindexCode="000300", danjuanCode="SH000300", **VERIFIED)
        candidate = build.build_candidate(entry, {"etf": None, "confidence": "none", "why": [], "warnings": []},
                                         DANJUAN_WITH_CSI300, {"SH000300": "沪深300"})
        self.assertEqual(candidate["warnings"], [])

    def test_an_unverified_csindex_code_is_flagged(self):
        entry = seed_entry("没验过", csindexCode="000000")
        candidate = build.build_candidate(entry, {"etf": None, "confidence": "none", "why": [], "warnings": []},
                                         DANJUAN, set())
        self.assertTrue(any("还没验过" in warning for warning in candidate["warnings"]))

    def test_a_danjuan_code_missing_from_the_catalogue_is_flagged(self):
        entry = seed_entry("纳指100", danjuanCode="NDX")
        candidate = build.build_candidate(entry, {"etf": None, "confidence": "none", "why": [], "warnings": []},
                                         {"byCode": {}, "byName": {}}, set())
        self.assertTrue(any("蛋卷目录里没有" in warning for warning in candidate["warnings"]))

    def test_the_scale_uses_nmc_not_mktcap(self):
        self.assertEqual(build.fund_scale_yi({"nmc": 10689801.26864}), 1068.98)

    def test_a_candidate_already_in_the_pool_is_marked(self):
        entry = seed_entry("沪深300", csindexCode="000300", danjuanCode="SH000300")
        candidate = build.build_candidate(entry, {"etf": None, "confidence": "none", "why": [], "warnings": []},
                                         DANJUAN, {"SH000300": "沪深300"})
        self.assertTrue(candidate["alreadyInPool"])


class SeedTableTests(unittest.TestCase):
    def test_the_real_seed_only_uses_verified_csindex_codes(self):
        """知识表里的每个 csindex 代码都要有验证日期——没验过的代码写进池子就是静默取错数。"""
        for entry in build.load_seed():
            if entry.get("csindexCode"):
                self.assertTrue(
                    entry.get("csindexVerifiedOn"),
                    f"{entry['indexName']} 的 csindexCode 没有 csindexVerifiedOn",
                )

    def test_the_real_seed_has_no_duplicate_index_codes(self):
        codes = [build.derived_index_code(entry) for entry in build.load_seed()]
        self.assertEqual(len(codes), len(set(codes)), "seed 里有重复的 indexCode")

    def test_every_real_seed_entry_has_a_valuation_source(self):
        for entry in build.load_seed():
            self.assertTrue(entry.get("csindexCode") or entry.get("danjuanCode"), entry["indexName"])

    def test_an_entry_with_neither_code_is_rejected(self):
        handle, path = tempfile.mkstemp(suffix=".json")
        os.close(handle)
        self.addCleanup(lambda: os.path.exists(path) and os.unlink(path))
        Path(path).write_text(json.dumps({"seed": [seed_entry("没有代码的指数")]}), encoding="utf-8")

        with self.assertRaises(RuntimeError) as caught:
            build.load_seed(Path(path))
        self.assertIn("估值", str(caught.exception))

    def test_a_bad_category_is_rejected(self):
        handle, path = tempfile.mkstemp(suffix=".json")
        os.close(handle)
        self.addCleanup(lambda: os.path.exists(path) and os.unlink(path))
        Path(path).write_text(
            json.dumps({"seed": [seed_entry("乱类", category="宽基", danjuanCode="X")]}), encoding="utf-8")

        with self.assertRaises(RuntimeError) as caught:
            build.load_seed(Path(path))
        self.assertIn("category", str(caught.exception))


class NeverWritesThePoolTests(unittest.TestCase):
    def test_the_candidate_file_is_not_the_pool_file(self):
        self.assertNotEqual(os.path.abspath(build.OUTPUT_PATH), os.path.abspath(build.POOL_PATH))

    def test_the_module_never_opens_the_pool_for_writing(self):
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertNotIn("open(POOL_PATH", source)
        self.assertNotIn("\"w\"", source.split("def read_pool_index_codes")[1].split("def ")[0])

    def test_missing_pool_file_means_no_marking_not_a_crash(self):
        self.assertEqual(build.read_pool_index_codes(Path("不存在.json")), {})

    def test_the_pool_is_read_only_and_reports_what_is_in_it(self):
        handle, path = tempfile.mkstemp(suffix=".json")
        os.close(handle)
        self.addCleanup(lambda: os.path.exists(path) and os.unlink(path))
        Path(path).write_text(
            json.dumps({"indices": [{"indexCode": "SH000300", "indexName": "沪深300"}]}), encoding="utf-8")
        self.assertEqual(build.read_pool_index_codes(Path(path)), {"SH000300": "沪深300"})


class SinaListTests(unittest.TestCase):
    def test_pagination_stops_at_the_first_empty_page(self):
        pages = {
            1: [etf("510300", "沪深300ETF", 100)],
            2: [],
        }

        def fake_get(url, **kwargs):
            response = Mock()
            response.raise_for_status.side_effect = None
            response.json.return_value = pages.get(kwargs["params"]["page"], [])
            return response

        with patch.object(report, "http_get", side_effect=fake_get) as get:
            rows = build.fetch_sina_etfs(pages=10)
        self.assertEqual([row["code"] for row in rows], ["510300"])
        self.assertEqual(get.call_count, 2)

    def test_an_empty_list_is_an_error_not_an_empty_proposal_set(self):
        response = Mock()
        response.raise_for_status.side_effect = None
        response.json.return_value = []
        with patch.object(report, "http_get", Mock(return_value=response)):
            with self.assertRaises(RuntimeError):
                build.fetch_sina_etfs(pages=3)


if __name__ == "__main__":
    unittest.main()