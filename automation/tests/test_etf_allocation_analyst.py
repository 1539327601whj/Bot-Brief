import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).parents[1] / "scripts" / "etf_allocation_analyst.py"
SPEC = importlib.util.spec_from_file_location("etf_allocation_analyst", MODULE_PATH)
analyst = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analyst)


def memo(**overrides):
    payload = {
        "code": "510300",
        "name": "沪深300ETF 华泰柏瑞",
        "pe_percentile": 45,
        "pe_ttm": 12.5,
        "distance_from_month_high": -4.65,
        "month_range_position": 60,
        "premium_rate": 0.2,
        "premium_reference_only": False,
        "pe_method": "CSI_PE_TTM_ROLLING_10Y",
        "pe_source": "估值测试源",
        "pe_as_of": "2026-07-27",
        "price_source": "前复权测试源",
        "price_as_of": "2026-07-27 15:00:00",
        "premium_source": "溢价测试源",
        "premium_as_of": "2026-07-27 15:00:00",
    }
    payload.update(overrides)
    return analyst.analyze_allocation(**payload)


class AllocationRuleTests(unittest.TestCase):
    def test_neutral_inputs_follow_the_plan(self):
        result = memo()
        self.assertEqual(result["action"], analyst.ACTION_BUY_PLAN)
        self.assertEqual(result["short_name"], "沪深300ETF")
        self.assertEqual(result["used_dimensions"], ["PE", "价格", "溢价"])
        self.assertFalse(result["degraded"])
        self.assertEqual(len(result["reasons"]), 3)
        self.assertIn("中证 PE(TTM) 滚动10年分位", result["reasons"][0])
        self.assertIn("估值日 2026-07-27", result["reasons"][0])
        self.assertIn("指数估值，不是 ETF 成交价", result["reasons"][0])

    def test_cheap_pe_pullback_and_discount_buys_more(self):
        result = memo(pe_percentile=18, distance_from_month_high=-12, premium_rate=-0.4)
        self.assertEqual(result["action"], analyst.ACTION_BUY_MORE)
        self.assertIn("偏低", result["reasons"][0])
        self.assertIn("回撤较大", result["reasons"][1])

    def test_expensive_pe_buys_less(self):
        result = memo(pe_percentile=75, distance_from_month_high=-1, premium_rate=1.0)
        self.assertEqual(result["action"], analyst.ACTION_BUY_LESS)

    def test_very_expensive_pe_pauses(self):
        result = memo(pe_percentile=88, distance_from_month_high=0, premium_rate=1.2)
        self.assertEqual(result["action"], analyst.ACTION_PAUSE)

    def test_missing_pe_does_not_invent_an_action(self):
        result = memo(pe_percentile=None)
        self.assertEqual(result["action"], analyst.ACTION_UNAVAILABLE)
        self.assertTrue(result["degraded"])
        self.assertIn("PE分位缺失", result["vetoes"])

    def test_price_is_confirmation_not_enough_to_buy_more(self):
        result = memo(pe_percentile=55, distance_from_month_high=-16, premium_rate=0.1)
        self.assertNotEqual(result["action"], analyst.ACTION_BUY_MORE)
        self.assertIn(result["action"], (analyst.ACTION_BUY_PLAN, analyst.ACTION_BUY_LESS))

    def test_csi300_high_premium_blocks_buy_more(self):
        result = memo(pe_percentile=18, distance_from_month_high=-12, premium_rate=2.4)
        self.assertEqual(result["action"], analyst.ACTION_BUY_PLAN)
        self.assertTrue(any("不能升到多买" in item for item in result["vetoes"]))

    def test_csi300_extreme_premium_forces_pause(self):
        result = memo(pe_percentile=40, distance_from_month_high=-6, premium_rate=3.4)
        self.assertEqual(result["action"], analyst.ACTION_PAUSE)
        self.assertTrue(any("强制暂停买" in item for item in result["vetoes"]))

    def test_nasdaq_missing_premium_cannot_upgrade_to_buy_more(self):
        result = memo(
            code="513100",
            name="纳指100ETF 国泰",
            pe_percentile=18,
            distance_from_month_high=-12,
            premium_rate=None,
            premium_reference_only=True,
        )
        self.assertEqual(result["action"], analyst.ACTION_BUY_PLAN)
        self.assertTrue(result["degraded"])
        self.assertTrue(any("不能升到多买" in item for item in result["vetoes"]))
        self.assertIn("跨境IOPV", result["reasons"][2])

    def test_nasdaq_allows_buy_more_when_premium_is_mild(self):
        result = memo(
            code="513100",
            name="纳指100ETF 国泰",
            pe_percentile=18,
            distance_from_month_high=-12,
            premium_rate=1.2,
            premium_reference_only=True,
        )
        self.assertEqual(result["action"], analyst.ACTION_BUY_MORE)

    def test_sp500_follows_qdii_premium_rules(self):
        result = memo(
            code="513500",
            name="标普500ETF 博时",
            pe_percentile=18,
            distance_from_month_high=-12,
            premium_rate=None,
            premium_reference_only=True,
        )
        self.assertEqual(result["action"], analyst.ACTION_BUY_PLAN)
        self.assertTrue(any("不能升到多买" in item for item in result["vetoes"]))
        self.assertIn("跨境IOPV", result["reasons"][2])

    def test_nasdaq_premium_threshold_is_wider_than_csi300(self):
        shared = dict(pe_percentile=18, distance_from_month_high=-12, premium_rate=2.4)
        csi = memo(**shared)
        nasdaq = memo(code="513100", name="纳指100ETF 国泰", premium_reference_only=True, **shared)
        self.assertEqual(csi["action"], analyst.ACTION_BUY_PLAN)
        self.assertEqual(nasdaq["action"], analyst.ACTION_BUY_MORE)

    def test_extreme_rich_valuation_can_suggest_trim(self):
        result = memo(pe_percentile=92, distance_from_month_high=-1, premium_rate=1.0)
        self.assertEqual(result["action"], analyst.ACTION_TRIM)

    def test_high_pe_alone_does_not_trim(self):
        result = memo(pe_percentile=92, distance_from_month_high=-12, premium_rate=-0.4)
        self.assertNotEqual(result["action"], analyst.ACTION_TRIM)

    def test_two_etfs_are_scored_independently(self):
        cheap = memo(pe_percentile=18, distance_from_month_high=-12, premium_rate=-0.4)
        rich = memo(
            code="513100",
            name="纳指100ETF 国泰",
            pe_percentile=88,
            distance_from_month_high=0,
            premium_rate=1.2,
            premium_reference_only=True,
        )
        self.assertEqual(cheap["action"], analyst.ACTION_BUY_MORE)
        self.assertEqual(rich["action"], analyst.ACTION_BUY_LESS)
        self.assertNotEqual(cheap["action"], rich["action"])

    def test_section_keeps_research_wording(self):
        text = "\n".join(analyst.format_allocation_section([memo()]))
        self.assertIn("仓位备忘", text)
        self.assertIn("按计划买", text)
        self.assertIn("定额配置官", text)
        self.assertIn("不构成投资建议", text)
        self.assertEqual(
            analyst.format_allocation_conclusion([memo()]),
            "仓位备忘：沪深300ETF 按计划买。",
        )
        for forbidden in ("加仓", "减仓", "今日动作"):
            self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main()
