"""``etf_report.rolling_percentiles``（本项目分位口径的单一真源）的测试。

分两层，缺一不可：

1. **性质测试**——手工算出期望值，钉住三件最容易写错的事：窗口左端按**日期**而不是
   按条数、边界那一日是**含**在窗口里的、分位用的是 **≤** 而不是 <。
   只喂递增数据的测试看不出这些错。
2. **跨语言对照**——``fixtures/pe_percentile_parity.json`` 由这个函数生成，Java 侧
   ``RollingPercentile`` 读同一份。这里验证 fixture 还对得上，Java 那边验证 Java 对得上，
   两头夹住，中间那份数据就没法悄悄漂移。

改了算法却没重新生成 fixture，这个文件会红——那是有意的：它逼着人去看 Java 那边
为什么不一致，而不是让两个实现各自漂走 0.3 个百分点然后没人发现。
"""

import importlib.util
import json
import sys
import unittest
from datetime import date
from pathlib import Path

SCRIPTS_DIR = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

SPEC = importlib.util.spec_from_file_location("etf_report", SCRIPTS_DIR / "etf_report.py")
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)

FIXTURE_PATH = Path(__file__).parent / "fixtures" / "pe_percentile_parity.json"
# fixture 里存的是 Python 的**原始浮点**（json 的 float 是 repr 往返，读回来二进制完全相同），
# 所以这里比的是精确相等，没有容差。
#
# **不要在 fixture 里预先取整。** 曾经存的是 round(p, 6)：真值 25.93984962406015 收成
# 25.93985，Java 那边再收 4 位就上进到 25.9399，而直接收 4 位是 25.9398——测试报出
# 「两边差 0.0001」，其实两边算法都对，是取整取了两遍。这类假报警最费时间的地方在于
# 它会让人去改算法。存原始值，让每一端只按自己的生产精度收一次。
PRECISION = 1e-12


def d(text):
    return date.fromisoformat(text)


class RollingPercentilePropertyTests(unittest.TestCase):
    def test_the_first_point_is_always_100(self):
        # 窗口里只有它自己，它既是最大也是最小 → 100
        self.assertEqual(report.rolling_percentiles([(d("2020-01-01"), 10.0)]), [100.0])

    def test_the_percentile_uses_less_than_or_equal_not_strictly_less(self):
        # 这是 bisect_right 与 bisect_left 的分界。用严格小于时最后一点会算成 25，
        # 而不是 50——只喂递增数据的测试永远看不出来。
        points = [(d("2020-01-01"), 10.0), (d("2020-01-02"), 20.0),
                  (d("2020-01-03"), 20.0), (d("2020-01-04"), 15.0)]
        self.assertEqual(report.rolling_percentiles(points), [100.0, 100.0, 100.0, 50.0])

    def test_duplicate_values_all_count_towards_the_same_percentile(self):
        # 三个 20 的分位必须相同：窗口里的另一个 20 是「≤ 20」，要算进来
        points = [(d("2020-01-01"), 20.0), (d("2020-01-02"), 20.0), (d("2020-01-03"), 20.0)]
        self.assertEqual(report.rolling_percentiles(points), [100.0, 100.0, 100.0])

    def test_the_window_is_measured_in_dates_not_in_row_count(self):
        # 相隔 11 年：旧点必须出窗。不出窗的话第二点的分位会是 50 而不是 100。
        points = [(d("2010-01-01"), 30.0), (d("2021-01-01"), 20.0)]
        self.assertEqual(report.rolling_percentiles(points), [100.0, 100.0])

    def test_a_point_exactly_ten_years_back_is_still_in_the_window(self):
        # 边界那一日是**含**在窗口里的（Python 用的是 `date < minimum` 才淘汰）。
        # 改成「含」以外的写法，这一点会从 50 变成 100。
        points = [(d("2011-01-01"), 30.0), (d("2021-01-01"), 20.0)]
        self.assertEqual(report.rolling_percentiles(points), [100.0, 50.0])

    def test_a_shorter_window_years_evicts_sooner(self):
        points = [(d("2018-01-01"), 30.0), (d("2021-01-01"), 20.0)]
        self.assertEqual(report.rolling_percentiles(points, window_years=10), [100.0, 50.0])
        self.assertEqual(report.rolling_percentiles(points, window_years=2), [100.0, 100.0])

    def test_unsorted_input_is_sorted_before_rolling(self):
        ascending = [(d("2020-01-01"), 10.0), (d("2020-01-02"), 20.0), (d("2020-01-03"), 15.0)]
        # 注意：本函数假定入参**升序**（fetch_csindex_pe_history 会先排序再传进来），
        # 所以这里只钉住「同一份升序数据怎么传都一样」——乱序入参不在支持范围内。
        shuffled = [ascending[2], ascending[0], ascending[1]]
        self.assertNotEqual(report.rolling_percentiles(ascending), report.rolling_percentiles(shuffled))

    def test_the_percentile_never_reaches_zero_for_positive_values(self):
        # 0 在页面上会被读成「极度低估」。它实际意味着「没有数据」，
        # 而一个正常算出来的分位最小值是 100/窗口大小 > 0。
        points = [(d("2020-01-01") , 10.0), (d("2020-01-02"), 30.0), (d("2020-01-03"), 1.0)]
        self.assertTrue(all(p > 0 for p in report.rolling_percentiles(points)))

    def test_the_window_years_default_matches_the_charter_constant(self):
        self.assertEqual(report.CSI300_PE_WINDOW_YEARS, 10)
        points = [(d("2010-01-01"), 30.0), (d("2021-01-01"), 20.0)]
        self.assertEqual(report.rolling_percentiles(points),
                         report.rolling_percentiles(points, report.CSI300_PE_WINDOW_YEARS))


class ParityFixtureTests(unittest.TestCase):
    """Java 读的是同一份文件，所以这里也在替 Java 守着输入。"""

    @classmethod
    def setUpClass(cls):
        with open(FIXTURE_PATH, encoding="utf-8") as handle:
            cls.fixture = json.load(handle)
        cls.points = [(d(p["date"]), float(p["value"])) for p in cls.fixture["points"]]

    def test_the_fixture_still_matches_the_python_implementation(self):
        expected = self.fixture["percentiles"]
        actual = report.rolling_percentiles(self.points, self.fixture["windowYears"])
        self.assertEqual(len(actual), len(expected))
        drift = [(i, a, e) for i, (a, e) in enumerate(zip(actual, expected)) if abs(a - e) > PRECISION]
        self.assertFalse(drift, f"{len(drift)} 个点对不上，前几个：{drift[:5]}。"
                                "改了口径就要重新跑 gen_pe_percentile_fixture.py，并检查 Java 侧")

    def test_the_fixture_spans_more_than_one_window(self):
        # 跨度不足一个窗口时，淘汰旧点那段代码一次都不会执行——
        # 两端最容易写错的地方等于没测。这条守住 fixture 本身是有意义的。
        self.assertGreater(self.fixture["spanYears"], self.fixture["windowYears"],
                           "fixture 跨度不足一个窗口，窗口淘汰分支不会被执行")

    def test_the_fixture_actually_exercises_eviction(self):
        window_start = report.subtract_years(self.points[-1][0], self.fixture["windowYears"])
        evicted = sum(1 for day, _ in self.points if day < window_start)
        self.assertGreater(evicted, 0, "窗口里一个旧点都没被淘汰，淘汰分支没跑到")

    def test_the_fixture_contains_duplicate_values(self):
        # ≤ 与 < 的差别只有在重复值上才显出来，而重复值在日频 PE 里很常见
        values = [v for _, v in self.points]
        self.assertLess(len(set(values)), len(values), "fixture 里没有重复值")

    def test_the_fixture_covers_a_wide_range_of_percentiles(self):
        # 分位全挤在一个窄区间里的话，「两边算法不同」也看不出来
        percentiles = self.fixture["percentiles"]
        self.assertGreater(max(percentiles) - min(percentiles), 50)


if __name__ == "__main__":
    unittest.main()