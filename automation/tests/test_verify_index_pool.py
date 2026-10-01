import contextlib
import importlib.util
import io
import json
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

MODULE_PATH = SCRIPTS_DIR / "verify_index_pool.py"
SPEC = importlib.util.spec_from_file_location("verify_index_pool", MODULE_PATH)
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)

report = verify.report

CSINDEX = verify.CSINDEX
DANJUAN = verify.DANJUAN
CSI_METHOD = report.CSI_PE_TTM_ROLLING_10Y
DANJUAN_METHOD = report.DANJUAN_PE_TTM_PROVIDER


# ----------------------------------------------------------------------
# 合成数据。规模那几个数字是**现场量出来的真实值**，不是凑出来的：
# 「这条纪律还成不成立」只有对着真数才有意义。
# ----------------------------------------------------------------------

def sina_row(code, name, nmc_wan, mktcap_wan):
    return {
        "code": code,
        "symbol": ("sh" if code.startswith("5") else "sz") + code,
        "name": name,
        "nmc": nmc_wan,
        "mktcap": mktcap_wan,
    }


def tencent_fields(market_cap_yi, price=4.0, amount_wan=100000.0, name="沪深300ETF"):
    fields = [""] * verify.TENCENT_FIELD_COUNT
    fields[0] = "1"
    fields[1] = name
    fields[2] = "510300"
    fields[3] = str(price)
    fields[verify.TENCENT_FIELD_AMOUNT_WAN] = str(amount_wan)
    fields[verify.TENCENT_FIELD_MARKET_CAP_YI] = str(market_cap_yi)
    return fields


# `code: (名字, 腾讯总市值(亿), 新浪 nmc(亿), 新浪 mktcap(亿))`。前三个是现场量到的
# 真实值：510300 与 588000 的 nmc 与腾讯完全一致、159915 差 0.58%；mktcap 则三个都差
# 20% 以上——这正是「该用 nmc、不该用 mktcap」那张证据。其余几只按 nmc 一致构造，
# 好让 D 组（池子与行情源一致）也能拿同一份数据跑通。
ETF_SCALE = {
    "510300": ("沪深300ETF", 1068.98, 1068.98, 838.31),
    "510500": ("中证500ETF", 300.00, 300.00, 200.00),
    "510050": ("上证50ETF", 500.00, 500.00, 380.00),
    "159915": ("创业板ETF", 653.84, 650.02, 339.06),
    "588000": ("科创50ETF", 941.14, 941.14, 482.08),
    "512100": ("中证1000ETF", 150.00, 150.00, 100.00),
    "510880": ("红利ETF", 200.00, 200.00, 150.00),
}


def build_quotes():
    sina, tencent = {}, {}
    for code, (name, tencent_yi, nmc_yi, mktcap_yi) in ETF_SCALE.items():
        symbol = ("sh" if code.startswith("5") else "sz") + code
        sina[code] = sina_row(code, name, nmc_yi * verify.NMC_WAN_PER_YI,
                              mktcap_yi * verify.NMC_WAN_PER_YI)
        tencent[symbol] = tencent_fields(tencent_yi, name=name)
    return sina, tencent


HEALTHY_SINA, HEALTHY_QUOTES = build_quotes()


def pool_entry(index_code, name, *, source=CSINDEX, etf=None, market=1,
               csindex_code=None, danjuan_code=None, category="broad"):
    return {
        "indexCode": index_code,
        "indexName": name,
        "category": category,
        "valuationSource": source,
        "percentileMethod": CSI_METHOD if source == CSINDEX else DANJUAN_METHOD,
        "csindexCode": csindex_code,
        "danjuanCode": danjuan_code,
        "etfCode": etf,
        "market": market if etf else None,
        "etfName": f"{name}ETF" if etf else None,
        "tracking": name,
    }


# 7 只旧宽基。创业板指只能走蛋卷——中证官网对 399006 是不通的（实测），
# 这个 fixture 顺手把「为什么池子里会有 danjuan 源」这件事写明白了。
HEALTHY_POOL = [
    pool_entry("SH000300", "沪深300", etf="510300", csindex_code="000300"),
    pool_entry("SH000905", "中证500", etf="510500", csindex_code="000905"),
    pool_entry("SH000016", "上证50", etf="510050", csindex_code="000016"),
    pool_entry("SZ399006", "创业板指", source=DANJUAN, etf="159915", market=0,
               danjuan_code="SZ399006"),
    pool_entry("SH000688", "科创50", etf="588000", csindex_code="000688"),
    pool_entry("SH000852", "中证1000", etf="512100", csindex_code="000852"),
    pool_entry("SH000015", "上证红利", etf="510880", csindex_code="000015"),
]


def recent_days(count, end=None):
    """日期跟着「今天」走：写死 2026-09-30 的 fixture 过半年就会变成「数据过旧」。"""
    end = end or report.now_beijing().date()
    return [end - timedelta(days=offset) for offset in range(count - 1, -1, -1)]


def csindex_body(index_name, points=None, code="200", success=True):
    # indexName **只在行里**，顶层没有——这是实测出来的形状，别照「顶层应该有」去补，
    # 补上就等于把「从行里读」这个实现细节从测试里藏掉了（真实响应曾经因此读成空串）
    return {
        "code": code,
        "msg": "Success" if success else "boom",
        "success": success,
        "data": [
            {"tradeDate": day.strftime("%Y%m%d"), "indexName": index_name, "peg": pe}
            for day, pe in (points or [(day, 13.15) for day in recent_days(300)])
        ],
    }


def bodies_for(pool, points=None):
    return {entry["csindexCode"]: csindex_body(entry["indexName"], points)
            for entry in pool if entry.get("csindexCode")}


# 蛋卷用毫秒时间戳报日期。跟着「昨天」走，fixture 才不会过几天就变成「估值过旧」。
YESTERDAY_TS = int((report.now_beijing() - timedelta(days=1)).timestamp() * 1000)


def danjuan_items(*, pe=13.18, percentile=0.488, name="沪深300"):
    items = {"SZ399006": {"index_code": "SZ399006", "name": "创业板指", "pe": 40.0,
                          "pe_percentile": 0.42, "ts": YESTERDAY_TS}}
    # C2 那四个探针的蛋卷一侧；数值取现场量到的 13.18 / 31.92 / 41.95 / 125.06
    for code, each_pe in (("SH000300", pe), ("SH000905", 31.92),
                          ("SH000852", 41.95), ("SH000688", 125.06)):
        items[code] = {"index_code": code, "name": name, "pe": each_pe,
                       "pe_percentile": percentile, "ts": YESTERDAY_TS}
    return items


# C2 那一侧：csindex 量到的 13.15 / 25.09 / 29.59 / 71.37
HEALTHY_CSINDEX_PE = {"000300": 13.15, "000905": 25.09, "000852": 29.59, "000688": 71.37}


def run(check, *args, **kwargs):
    """跑一个 check 组，返回 (失败列表, 提示列表, 打印出来的字)。

    打印出来的那份也要断言：有些检查（两种口径差多少、腾讯抽样抽了哪几只）
    不给结论，只给数字，而数字本身就是要看的东西。
    """
    checker = verify.Checker()
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        check(*args, checker=checker, **kwargs)
    return checker.failures, checker.notes, buffer.getvalue()


# ----------------------------------------------------------------------
# A. 池子自洽
# ----------------------------------------------------------------------

class PoolConsistencyTests(unittest.TestCase):
    def test_a_healthy_pool_passes(self):
        failures, _, _ = run(verify.check_pool, HEALTHY_POOL)
        self.assertEqual(failures, [])

    def test_an_index_code_that_does_not_follow_the_segment_rule_is_a_failure(self):
        """
        `indexCode` 是 `market_valuation_history.index_code` 的键。前缀抄错（照抄蛋卷的
        `SH930901`、或者把中证自编的 930740 写成 SH）会让同一个指数换条路径进来就多长
        一个 key——两份估值并存，两张卡都正常显示，页面上看不出是错的。
        """
        pool = [pool_entry("SH930713", "人工智能", etf="515070", csindex_code="930713")]
        failures, _, _ = run(verify.check_pool, pool)
        self.assertTrue(any("由号段推出" in text and "CSI930713" in text for text in failures), failures)

    def test_a_zhongzheng_self_published_code_gets_the_csi_prefix(self):
        # 930/931/716/H30 不是沪深交易所公布的代码，写 SH 会读成「上交所的 930713」
        pool = HEALTHY_POOL + [
            pool_entry("CSI930713", "人工智能", etf="515070", csindex_code="930713"),
            pool_entry("CSI931151", "光伏产业", etf="515790", csindex_code="931151"),
            pool_entry("CSIH30533", "中概互联50", etf="513050", csindex_code="H30533"),
            pool_entry("SZ399967", "中证军工", etf="512660", market=1, csindex_code="399967"),
        ]
        failures, _, _ = run(verify.check_pool, pool)
        self.assertEqual([f for f in failures if "由号段推出" in f], [])

    def test_a_danjuan_index_code_is_not_copied_verbatim(self):
        # 蛋卷自己把这个号段写成 `SH930901`，而规则要求 CSI——照抄就多一个 key
        pool = [pool_entry("SH930901", "动漫游戏", source=DANJUAN, etf="159869", market=0,
                           danjuan_code="SH930901")]
        failures, _, _ = run(verify.check_pool, pool)
        self.assertTrue(any("CSI930901" in text for text in failures), failures)

    def test_an_overseas_index_needs_no_shanghai_shenzhen_code(self):
        # 港股/美股没有沪深代码，套一个前缀等于编造一个交易所归属
        pool = HEALTHY_POOL + [pool_entry("HKHSI", "恒生指数", category="overseas", source=DANJUAN,
                                          etf="513330", danjuan_code="HKHSI")]
        failures, _, _ = run(verify.check_pool, pool)
        self.assertEqual(failures, [])

    def test_an_overseas_index_code_that_contradicts_its_danjuan_code_is_a_failure(self):
        # 两者不一致时，估值写在这个 key 下、同步却按那个 key 去查，卡片就永远是空的
        pool = [pool_entry("HKHSI", "恒生指数", category="overseas", source=DANJUAN,
                           etf="513330", danjuan_code="HKHSITECH")]
        failures, _, _ = run(verify.check_pool, pool)
        self.assertTrue(any("就是它的 danjuanCode" in text for text in failures), failures)

    def test_a_duplicate_index_code_is_a_failure(self):
        pool = HEALTHY_POOL + [pool_entry("SH000300", "另一个沪深300", csindex_code="000301")]
        failures, _, _ = run(verify.check_pool, pool)
        self.assertTrue(any("会写出两份估值" in text for text in failures), failures)

    def test_two_indices_may_not_share_one_etf(self):
        # 同一只 ETF 代表两个指数 = 两张卡片显示同一份价格位置，页面上看不出是错的
        pool = HEALTHY_POOL + [pool_entry("SH000999", "假指数", etf="510300", csindex_code="000999")]
        failures, _, _ = run(verify.check_pool, pool)
        self.assertTrue(any("同一份价格位置" in text for text in failures), failures)

    def test_two_indices_may_not_share_a_csindex_code(self):
        pool = HEALTHY_POOL + [pool_entry("SH000999", "假指数", etf="512999", csindex_code="000300")]
        failures, _, _ = run(verify.check_pool, pool)
        self.assertTrue(any("csindexCode 未被别的指数占用" in text for text in failures), failures)

    def test_a_source_without_its_code_is_a_failure(self):
        pool = [pool_entry("SH000300", "沪深300", etf="510300", csindex_code="")]
        failures, _, _ = run(verify.check_pool, pool)
        self.assertTrue(any("csindexCode" in text for text in failures), failures)

    def test_a_danjuan_source_without_its_code_is_a_failure(self):
        pool = [pool_entry("SZ399006", "创业板指", source=DANJUAN, etf="159915", market=0)]
        failures, _, _ = run(verify.check_pool, pool)
        self.assertTrue(any("danjuanCode" in text for text in failures), failures)

    def test_a_method_that_does_not_match_its_source_is_a_failure(self):
        entry = pool_entry("SH000300", "沪深300", etf="510300", csindex_code="000300")
        entry["percentileMethod"] = DANJUAN_METHOD
        failures, _, _ = run(verify.check_pool, [entry])
        self.assertTrue(any("一一对应" in text for text in failures), failures)

    def test_an_entry_without_a_valuation_source_is_a_failure(self):
        # 这不是「可以以后再说」的状态：来源为空 = 这个指数的 PE 与分位一定长空白
        entry = pool_entry("SH000300", "沪深300", etf="510300", csindex_code="000300")
        entry["valuationSource"] = None
        entry["percentileMethod"] = None
        failures, _, _ = run(verify.check_pool, [entry])
        self.assertTrue(any("长出空白" in text for text in failures), failures)

    def test_a_half_filled_entry_without_a_source_is_a_failure(self):
        # 填了代码却没填来源 = 改了一半。不报的话这条永远静默不生效（Java 侧也会拒绝启动）
        entry = pool_entry("SH000300", "沪深300", etf="510300", csindex_code="000300")
        entry["valuationSource"] = None
        entry["percentileMethod"] = None
        failures, _, _ = run(verify.check_pool, [entry])
        self.assertTrue(any("半截字段" in text for text in failures), failures)

    def test_an_entry_without_an_etf_may_not_carry_a_market_or_a_name(self):
        entry = pool_entry("SH000300", "沪深300", csindex_code="000300")
        entry["market"] = 1
        entry["etfName"] = "沪深300ETF"
        failures, _, _ = run(verify.check_pool, [entry])
        self.assertTrue(any("market/etfName 也留空" in text for text in failures), failures)

    def test_a_csindex_code_that_is_not_six_characters_is_a_failure(self):
        entry = pool_entry("SH000300", "沪深300", etf="510300", csindex_code="300")
        failures, _, _ = run(verify.check_pool, [entry])
        self.assertTrue(any("csindexCode 是 6 位代码" in text for text in failures), failures)

    def test_a_letter_bearing_csindex_code_is_accepted(self):
        # H30533 这种带字母的也是合法入参，别把校验写成「六位数字」
        entry = pool_entry("SHH30533", "中证互联网50", etf="513050", csindex_code="H30533")
        failures, _, _ = run(verify.check_pool, [entry])
        self.assertEqual([text for text in failures if "6 位代码" in text], [])

    def test_a_bad_category_is_a_failure(self):
        entry = pool_entry("SH000300", "沪深300", etf="510300", csindex_code="000300")
        entry["category"] = "宽基"
        failures, _, _ = run(verify.check_pool, [entry])
        self.assertTrue(any("category 合法" in text for text in failures), failures)

    def test_a_market_that_contradicts_the_etf_code_is_a_failure(self):
        entry = pool_entry("SZ399006", "创业板指", etf="159915", market=1, csindex_code="399006")
        failures, _, _ = run(verify.check_pool, [entry])
        self.assertTrue(any("market" in text for text in failures), failures)

    def test_a_legacy_index_disappearing_is_reported(self):
        failures, _, _ = run(verify.check_pool, HEALTHY_POOL[:5])
        self.assertTrue(any("7 只旧宽基" in text for text in failures), failures)

    def test_a_pool_without_etfs_is_a_note_not_a_failure(self):
        # DTO 容忍 etfCode 为空（带原因串），所以这是提示不是失败——
        # 但出货的池子不该长这样，提示得说得清楚
        pool = [dict(entry, etfCode=None, market=None, etfName=None) for entry in HEALTHY_POOL]
        failures, notes, _ = run(verify.check_pool, pool)
        self.assertEqual(failures, [])
        self.assertTrue(any("没有代表 ETF" in text for text in notes), notes)

    def test_the_local_group_needs_no_network(self):
        source = MODULE_PATH.read_text(encoding="utf-8")
        body = source.split("def check_pool(")[1].split("\ndef ")[0]
        self.assertNotIn("fetch_", body)


# ----------------------------------------------------------------------
# B. 来源可达
# ----------------------------------------------------------------------

class SourceReachabilityTests(unittest.TestCase):
    def test_the_index_name_comes_from_the_rows(self):
        """顶层没有 indexName——照着「顶层应该有」写，名字会读成空串，自证就失效了。"""
        body = csindex_body("沪深300")
        self.assertNotIn("indexName", body)

        name, points = verify.parse_csindex_points(body)

        self.assertEqual(name, "沪深300")
        self.assertTrue(points)

    def test_a_healthy_pool_passes(self):
        failures, _, _ = run(verify.check_sources, HEALTHY_POOL, danjuan_items(),
                             bodies_for(HEALTHY_POOL))
        self.assertEqual(failures, [])

    def test_a_danjuan_code_missing_from_the_catalogue_is_a_failure(self):
        pool = [pool_entry("SZ399006", "创业板指", source=DANJUAN, etf="159915", market=0,
                           danjuan_code="SZ999999")]
        failures, _, _ = run(verify.check_sources, pool, danjuan_items(), {})
        self.assertTrue(any("蛋卷目录里查得到" in text for text in failures), failures)

    def test_a_placeholder_record_from_danjuan_is_reported(self):
        # 蛋卷对个别指数回的是 PE=0 的占位（实测 SZ399393 国证地产），同步时会被跳过，
        # 那个指数于是永远空白——这里要提前看见
        items = danjuan_items()
        items["SZ399006"] = {"index_code": "SZ399006", "name": "国证地产", "pe": 0,
                             "pe_percentile": 0, "ts": 1759219200000}
        pool = [pool_entry("SZ399006", "创业板指", source=DANJUAN, etf="159915", market=0,
                           danjuan_code="SZ399006")]
        failures, _, _ = run(verify.check_sources, pool, items, {})
        self.assertTrue(any("一直空白" in text for text in failures), failures)

    def test_the_csindex_name_must_agree_with_the_pool(self):
        pool = [pool_entry("SH000300", "沪深300", etf="510300", csindex_code="000300")]
        bodies = {"000300": csindex_body("中证500")}
        failures, _, _ = run(verify.check_sources, pool, danjuan_items(), bodies)
        self.assertTrue(any("对不上" in text for text in failures), failures)

    def test_a_matching_name_passes_even_when_the_wording_differs(self):
        # 官网回「中证红利指数」、池子里写「中证红利」是正常的，不该报错
        pool = [pool_entry("SH000922", "中证红利", etf="515080", csindex_code="000922")]
        bodies = {"000922": csindex_body("中证红利指数")}
        failures, _, _ = run(verify.check_sources, pool, danjuan_items(), bodies)
        self.assertEqual(failures, [])

    def test_a_stale_csindex_row_is_a_failure(self):
        pool = [pool_entry("SH000300", "沪深300", etf="510300", csindex_code="000300")]
        body = csindex_body("沪深300", [(day, 13.0) for day in recent_days(300, end=date(2020, 1, 2))])
        failures, _, _ = run(verify.check_sources, pool, danjuan_items(), {"000300": body})
        self.assertTrue(any("最新数据够新" in text for text in failures), failures)

    def test_a_short_history_is_a_note_not_a_failure(self):
        # 科创100 实测只有 843 个点。硬门槛卡 1000 会把「历史短」误报成「取数失败」
        pool = [pool_entry("SH000698", "科创100", etf="588190", csindex_code="000698")]
        body = csindex_body("科创100", [(day, 30.0) for day in recent_days(600)])
        failures, notes, _ = run(verify.check_sources, pool, danjuan_items(), {"000698": body})
        self.assertEqual(failures, [])
        self.assertTrue(any("历史长度" in text for text in notes), notes)

    def test_too_few_points_is_a_failure(self):
        pool = [pool_entry("SH000300", "沪深300", etf="510300", csindex_code="000300")]
        body = csindex_body("沪深300", [(day, 13.0) for day in recent_days(50)])
        failures, _, _ = run(verify.check_sources, pool, danjuan_items(), {"000300": body})
        self.assertTrue(any("点数够算分位" in text for text in failures), failures)

    def test_a_business_code_other_than_200_is_a_failure(self):
        pool = [pool_entry("SH000300", "沪深300", etf="510300", csindex_code="000300")]
        bodies = {"000300": csindex_body("沪深300", code="500", success=False)}
        failures, _, _ = run(verify.check_sources, pool, danjuan_items(), bodies)
        self.assertTrue(any("业务码" in text for text in failures), failures)

    def test_a_failed_fetch_is_a_failure_not_a_skip(self):
        pool = [pool_entry("SH000300", "沪深300", etf="510300", csindex_code="000300")]
        failures, _, _ = run(verify.check_sources, pool, danjuan_items(), {"000300": None})
        self.assertTrue(any("取得到" in text for text in failures), failures)

    def test_a_danjuan_record_that_stopped_moving_is_a_note(self):
        items = danjuan_items()
        items["SZ399006"] = dict(items["SZ399006"], ts=1600000000000)  # 2020 年
        pool = [pool_entry("SZ399006", "创业板指", source=DANJUAN, etf="159915", market=0,
                           danjuan_code="SZ399006")]
        failures, notes, _ = run(verify.check_sources, pool, items, {})
        self.assertEqual(failures, [])
        self.assertTrue(any("没人维护" in text for text in notes), notes)

    def test_an_official_name_reached_through_an_alias_passes(self):
        # 池子里叫「上证红利」、中证官网回「红利指数」——这不是取错代码，只是项目用了俗名
        pool = [pool_entry("SH000015", "上证红利", etf="510880", csindex_code="000015")]
        bodies = {"000015": csindex_body("红利指数")}
        failures, _, _ = run(verify.check_sources, pool, danjuan_items(), bodies,
                             aliases={"上证红利": {"上证红利", "红利指数"}})
        self.assertEqual(failures, [])

    def test_the_same_name_without_the_alias_is_a_failure(self):
        # 别名表是这句话的存放处；没有它就该报出来让人补，而不是放松比较
        pool = [pool_entry("SH000015", "上证红利", etf="510880", csindex_code="000015")]
        failures, _, _ = run(verify.check_sources, pool, danjuan_items(),
                             {"000015": csindex_body("红利指数")})
        self.assertTrue(any("别名表" in text for text in failures), failures)

    def test_an_official_name_that_merely_contains_the_pool_name_is_a_failure(self):
        """
        包含比会放过 000300「沪深300」与 000306「沪深300金融」——而「代码指到了另一个
        指数」正是这条检查唯一能自动抓到的东西，放它过去等于这条检查白写。
        """
        pool = [pool_entry("SH000300", "沪深300", etf="510300", csindex_code="000300")]
        failures, _, _ = run(verify.check_sources, pool, danjuan_items(),
                             {"000300": csindex_body("沪深300金融")})
        self.assertTrue(any("对得上" in text for text in failures), failures)

    def test_an_official_name_with_the_index_suffix_still_matches(self):
        # 000922 官网回「中证红利指数」，池子里叫「中证红利」
        self.assertTrue(verify.name_matches("中证红利指数", {"中证红利"}))
        self.assertTrue(verify.name_matches("中证红利", {"中证红利指数"}))
        self.assertTrue(verify.name_matches("中证500", {"中证500"}))
        self.assertTrue(verify.name_matches("中国互联网50", {"中概互联50", "中国互联网50"}))

    def test_the_index_suffix_rule_does_not_swallow_a_different_index(self):
        # 「沪深300金融」去掉后缀还是「沪深300金融」，不等于「沪深300」
        self.assertFalse(verify.name_matches("沪深300金融", {"沪深300"}))
        self.assertFalse(verify.name_matches("中证500", {"沪深300"}))
        self.assertFalse(verify.name_matches("中证军工指数", {"中证军工龙头"}))

    def test_load_seed_aliases_includes_the_index_name_itself(self):
        path = os.path.join(tempfile.mkdtemp(), "seed.json")
        with open(path, "w", encoding="utf-8") as handle:
            json.dump({"seed": [{"indexName": "上证红利", "aliases": ["红利指数"]},
                                {"indexName": "没有别名的", "aliases": []}]}, handle)
        aliases = verify.load_seed_aliases(path)
        self.assertEqual(aliases["上证红利"], {"上证红利", "红利指数"})
        self.assertEqual(aliases["没有别名的"], {"没有别名的"})

    def test_an_unreadable_alias_table_is_an_empty_map_not_a_crash(self):
        self.assertEqual(verify.load_seed_aliases(os.path.join(tempfile.mkdtemp(), "nope.json")), {})

    def test_the_production_path_must_return_a_percentile(self):
        history = [{"tradeDate": "2026-09-30", "peTtm": 13.15, "pePercentile": 48.8,
                    "percentileMethod": CSI_METHOD}]
        failures, _, _ = run(verify.check_csindex_production_path, history)
        self.assertEqual(failures, [])

        failures, _, _ = run(verify.check_csindex_production_path, [])
        self.assertTrue(failures)


# ----------------------------------------------------------------------
# C. 反向检查
# ----------------------------------------------------------------------

class ScaleCaliberTests(unittest.TestCase):
    def test_it_passes_on_the_numbers_measured_today(self):
        failures, _, text = run(verify.check_scale_caliber, HEALTHY_SINA, HEALTHY_QUOTES)
        self.assertEqual(failures, [])
        self.assertIn("21.6%", text)  # 510300 的 mktcap 与腾讯的差，量出来的
        self.assertIn("0.6%", text)   # 159915 的 nmc 与腾讯的差，量出来的

    def test_it_fails_when_nmc_is_swapped_for_mktcap(self):
        """把 nmc 换成 mktcap——这正是当初差点写错的那一处。它必须报错。"""
        swapped = {code: dict(row, nmc=row["mktcap"]) for code, row in HEALTHY_SINA.items()}

        failures, _, _ = run(verify.check_scale_caliber, swapped, HEALTHY_QUOTES)

        self.assertTrue(any("nmc 与腾讯总市值一致" in text for text in failures), failures)

    def test_it_fails_when_the_evidence_is_gone(self):
        # 探针全取不到时不能静默通过：「这条纪律没有证据」本身就是失败
        failures, _, _ = run(verify.check_scale_caliber, {}, {})
        self.assertTrue(any("没有证据支撑" in text for text in failures), failures)

    def test_it_fails_when_mktcap_stops_being_wrong(self):
        """mktcap 一旦变得和腾讯一致，说明新浪改了口径——「用 nmc」就失去依据了。"""
        aligned = {code: dict(row, mktcap=row["nmc"]) for code, row in HEALTHY_SINA.items()}
        failures, _, _ = run(verify.check_scale_caliber, aligned, HEALTHY_QUOTES)

        self.assertTrue(any("新浪改了口径" in text for text in failures), failures)

    def test_a_silently_dropped_symbol_is_a_failure(self):
        # 腾讯对市场前缀写错的代码是**静默丢弃**，不报错也不回空值
        failures, _, _ = run(verify.check_scale_caliber, HEALTHY_SINA, {})

        self.assertTrue(any("腾讯回了完整字段" in text for text in failures), failures)

    def test_a_short_field_list_is_a_failure(self):
        truncated = {"sh510300": tencent_fields(1068.98)[:40]}
        failures, _, _ = run(verify.check_scale_caliber, HEALTHY_SINA, truncated)
        self.assertTrue(any("腾讯回了完整字段" in text for text in failures), failures)


class PeCaliberTests(unittest.TestCase):
    def test_it_passes_on_the_numbers_measured_today(self):
        failures, _, text = run(verify.check_pe_caliber, HEALTHY_CSINDEX_PE, danjuan_items())
        self.assertEqual(failures, [])
        self.assertIn("75.2%", text)  # 科创50 两边差 75%，量出来的
        self.assertIn("0.2%", text)   # 沪深300 两边差 0.2%，量出来的

    def test_it_fails_when_the_two_sources_agree(self):
        """两个源要是收敛了，「不同来源不可横向比较」就成了没有依据的话。"""
        items = {code: dict(item, pe=HEALTHY_CSINDEX_PE[code[-6:]])
                 for code, item in danjuan_items().items() if code in HEALTHY_CSINDEX_PE
                 or code[-6:] in HEALTHY_CSINDEX_PE}

        failures, _, _ = run(verify.check_pe_caliber, dict(HEALTHY_CSINDEX_PE), items)

        self.assertTrue(any("明显背离" in text for text in failures), failures)
        self.assertTrue(any("失去了依据" in text for text in failures), failures)

    def test_the_shanghai_three_hundred_probe_still_has_to_agree(self):
        # 这一条是防「本组恒真」的：如果连沪深300 都对不上，说明有一边取错数了
        items = danjuan_items()
        items["SH000300"] = dict(items["SH000300"], pe=20.0)
        failures, _, _ = run(verify.check_pe_caliber, HEALTHY_CSINDEX_PE, items)
        self.assertTrue(any("基本吻合" in text for text in failures), failures)

    def test_a_missing_side_is_a_failure(self):
        failures, _, _ = run(verify.check_pe_caliber, {}, {})
        self.assertTrue(any("两边都有 PE" in text for text in failures), failures)

    def test_a_danjuan_pe_of_zero_is_not_silently_treated_as_agreement(self):
        items = {code: dict(item, pe=0) for code, item in danjuan_items().items()}
        failures, _, _ = run(verify.check_pe_caliber, HEALTHY_CSINDEX_PE, items)
        self.assertTrue(any("两边都有 PE" in text for text in failures), failures)


# ----------------------------------------------------------------------
# D. 池子与行情源一致
# ----------------------------------------------------------------------

class QuoteSourceTests(unittest.TestCase):
    def test_a_healthy_pool_passes(self):
        failures, _, _ = run(verify.check_quote_sources, HEALTHY_POOL, HEALTHY_SINA, HEALTHY_QUOTES)
        self.assertEqual(failures, [])

    def test_a_pool_etf_missing_from_the_sina_list_is_a_failure(self):
        slim = {"510300": HEALTHY_SINA["510300"]}
        failures, _, _ = run(verify.check_quote_sources, HEALTHY_POOL, slim, HEALTHY_QUOTES)
        self.assertTrue(any("512100" in text for text in failures), failures)

    def test_a_pool_etf_missing_from_the_tencent_response_is_a_failure(self):
        quotes = {key: value for key, value in HEALTHY_QUOTES.items() if key != "sz159915"}
        failures, _, _ = run(verify.check_quote_sources, HEALTHY_POOL, HEALTHY_SINA, quotes)
        self.assertTrue(any("静默丢弃" in text for text in failures), failures)

    def test_the_tencent_field_count_is_checked(self):
        quotes = dict(HEALTHY_QUOTES)
        quotes["sh510300"] = tencent_fields(1068.98)[:60]
        failures, _, _ = run(verify.check_quote_sources, HEALTHY_POOL, HEALTHY_SINA, quotes)
        self.assertTrue(any("字段数没变" in text for text in failures), failures)

    def test_the_symbol_prefix_follows_the_pool_market_field(self):
        _, _, text = run(verify.check_quote_sources, HEALTHY_POOL, HEALTHY_SINA, HEALTHY_QUOTES)
        self.assertIn("sz159915", text)
        self.assertIn("sh510300", text)


# ----------------------------------------------------------------------
# 编排：抽样、退出码、只读
# ----------------------------------------------------------------------

class OrchestrationTests(unittest.TestCase):
    def write_pool(self, pool):
        handle, path = tempfile.mkstemp(suffix=".json")
        os.close(handle)
        self.addCleanup(lambda: os.path.exists(path) and os.unlink(path))
        Path(path).write_text(json.dumps({"indices": pool}, ensure_ascii=False), encoding="utf-8")
        return path

    def run_main(self, argv, path):
        patcher = patch.object(verify, "POOL_PATH", path)
        patcher.start()
        self.addCleanup(patcher.stop)
        with contextlib.redirect_stdout(io.StringIO()):
            return verify.main(argv)

    def test_local_only_passes_on_a_healthy_pool_without_touching_the_network(self):
        def explode(*args, **kwargs):  # pragma: no cover - 走到这里就是失败
            raise AssertionError("--local-only 不该联网")

        with patch.object(verify, "fetch_danjuan", explode), \
                patch.object(verify, "fetch_csindex_raw", explode), \
                patch.object(verify, "fetch_sina_etfs", explode), \
                patch.object(verify, "fetch_tencent_quotes", explode):
            self.assertEqual(self.run_main(["--local-only"], self.write_pool(HEALTHY_POOL)), 0)

    def test_local_only_fails_on_a_broken_pool(self):
        pool = [pool_entry("SH000300", "沪深300", etf="510300", csindex_code="000300")]
        self.assertEqual(self.run_main(["--local-only"], self.write_pool(pool)), 1)

    def test_a_broken_pool_file_is_a_failure_not_a_traceback(self):
        handle, path = tempfile.mkstemp(suffix=".json")
        os.close(handle)
        self.addCleanup(lambda: os.path.exists(path) and os.unlink(path))
        Path(path).write_text("{ 这不是 JSON", encoding="utf-8")

        self.assertEqual(self.run_main(["--local-only"], path), 1)

    def test_sampling_takes_a_stable_prefix_and_can_take_everything(self):
        pool = [pool_entry(f"SH00030{i}", f"指数{i}", csindex_code=f"00030{i}")
                for i in range(4)]

        self.assertEqual(verify.sample_csindex_codes(pool, 2), ["000300", "000301"])
        self.assertEqual(len(verify.sample_csindex_codes(pool, 0)), 4)
        # 同一份池子永远给同一批样本：失败要可复现
        self.assertEqual(verify.sample_csindex_codes(pool, 2), verify.sample_csindex_codes(pool, 2))

    def test_sampling_ignores_entries_that_are_not_csindex_sourced(self):
        pool = HEALTHY_POOL
        self.assertEqual(verify.sample_csindex_codes(pool, 0),
                         ["000015", "000016", "000300", "000688", "000852", "000905"])

    def test_the_verifier_never_writes_anything(self):
        """只读是这个脚本的契约：它要能在生产上随时跑。"""
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertNotIn("import requests", source)
        self.assertNotIn('"w"', source)
        self.assertNotIn("json.dump", source)
        self.assertNotIn(".post(", source)
        self.assertNotIn("urllib.request.Request(url, method=", source)


class FixtureSanityTests(unittest.TestCase):
    """这些 fixture 一旦写错，「数据对时通过」那半边就没有意义了。"""

    def test_the_healthy_fixture_actually_exercises_both_sources(self):
        sources = {entry["valuationSource"] for entry in HEALTHY_POOL}
        self.assertEqual(sources, {CSINDEX, DANJUAN})

    def test_the_healthy_fixture_matches_the_measured_mktcap_gaps(self):
        for code, row in HEALTHY_SINA.items():
            tencent_yi = float(HEALTHY_QUOTES[("sh" if code.startswith("5") else "sz") + code]
                               [verify.TENCENT_FIELD_MARKET_CAP_YI])
            nmc_gap = abs(row["nmc"] / verify.NMC_WAN_PER_YI - tencent_yi) / tencent_yi
            mktcap_gap = abs(row["mktcap"] / verify.NMC_WAN_PER_YI - tencent_yi) / tencent_yi
            self.assertLess(nmc_gap, 0.02, code)
            self.assertGreater(mktcap_gap, 0.20, code)


if __name__ == "__main__":
    unittest.main()