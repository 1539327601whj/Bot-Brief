package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则引擎的单测。这是「不要推荐垃圾股」这条要求的唯一防线，所以 V1–V12
 * **每条都要测命中和字段缺失两个分支**。
 */
class ScreeningRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final ScreenerParams DEFAULTS = ScreenerParams.defaults();

    /** 一只「各项都合格」的股票。每个用例只改要测的那一项。 */
    private static StockRow row(String code, String name, String industry) {
        StockRow r = new StockRow();
        r.setCode(code);
        r.setName(name);
        r.setMarket(code.startsWith("6") ? 1 : 0);
        r.setIndustry(industry);
        r.setPrice(new BigDecimal("12.50"));
        r.setAmount(new BigDecimal("500000000"));          // 5 亿
        r.setTurnoverRate(new BigDecimal("2"));
        r.setTotalMarketCap(new BigDecimal("50000000000")); // 500 亿
        r.setFloatMarketCap(new BigDecimal("40000000000"));
        r.setPb(new BigDecimal("1.5"));
        r.setPeTtm(new BigDecimal("14"));
        r.setRoe(new BigDecimal("12"));
        r.setRevenueGrowth(new BigDecimal("8"));
        r.setProfitGrowth(new BigDecimal("10"));
        r.setGrossMargin(new BigDecimal("30"));
        r.setDebtRatio(new BigDecimal("45"));
        r.setDividendYield(new BigDecimal("2.5"));
        r.setBps(new BigDecimal("8"));
        r.setListDate(LocalDate.of(2010, 1, 1));
        return r;
    }

    private static List<ScreeningRules.Veto> steady(StockRow r) {
        return ScreeningRules.vetoes(r, ScreeningRules.Bucket.STEADY, DEFAULTS, TODAY);
    }

    private static List<ScreeningRules.Veto> growth(StockRow r) {
        return ScreeningRules.vetoes(r, ScreeningRules.Bucket.GROWTH, DEFAULTS, TODAY);
    }

    private static boolean hit(List<ScreeningRules.Veto> vetos, String rule) {
        return vetos.stream().anyMatch(v -> v.rule().equals(rule));
    }

    // ================= V1 退市 / ST / 次新 =================

    @Test
    void baselineRowPassesBothBuckets() {
        StockRow r = row("600519", "贵州茅台", "酿酒行业");
        assertThat(steady(r)).isEmpty();
        assertThat(growth(r)).isEmpty();
    }

    @Test
    void v1RejectsStAndDelistingAndNewlyListed() {
        assertThat(hit(steady(row("600001", "ST中珠", "综合")), "V1")).isTrue();
        assertThat(hit(steady(row("600002", "*ST海航", "航空")), "V1")).isTrue();
        assertThat(hit(steady(row("600003", "海润退", "电气")), "V1")).isTrue();
        assertThat(hit(steady(row("688001", "N华虹", "半导体")), "V1")).isTrue();
        assertThat(hit(steady(row("300001", "C华测", "仪器")), "V1")).isTrue();
    }

    @Test
    void v1DoesNotRejectNormalNamesWithLatinLetters() {
        // "TCL科技" 里有字母，但不是 ST 前缀——用 contains("ST") 会误伤这类名字
        assertThat(hit(steady(row("000100", "TCL科技", "家电")), "V1")).isFalse();
        assertThat(hit(steady(row("000063", "中兴通讯", "通信")), "V1")).isFalse();
    }

    @Test
    void v1RejectsEmptyName() {
        StockRow r = row("600004", null, "综合");
        assertThat(hit(steady(r), "V1")).isTrue();
    }

    // ================= V2 上市年限 =================

    @Test
    void v2RejectsMissingListDate() {
        StockRow r = row("600005", "某公司", "综合");
        r.setListDate(null);
        assertThat(hit(steady(r), "V2")).isTrue();
    }

    @Test
    void v2BucketsHaveDifferentMinimumAge() {
        StockRow r = row("600006", "某公司", "综合");
        r.setListDate(TODAY.minusYears(2).minusDays(1)); // 刚过 2 年，不满 3 年
        assertThat(hit(steady(r), "V2")).isTrue();
        assertThat(hit(growth(r), "V2")).isFalse();
    }

    // ================= V3 市值 =================

    @Test
    void v3RejectsMissingMarketCapAndFallsBackToFloatCap() {
        StockRow noCap = row("600007", "某公司", "综合");
        noCap.setTotalMarketCap(null);
        noCap.setFloatMarketCap(null);
        assertThat(hit(steady(noCap), "V3")).isTrue();

        StockRow onlyFloat = row("600008", "某公司", "综合");
        onlyFloat.setTotalMarketCap(null); // 回退到 400 亿流通市值 → 通过
        assertThat(hit(steady(onlyFloat), "V3")).isFalse();
    }

    @Test
    void v3ThresholdsDifferByBucket() {
        StockRow r = row("600009", "某公司", "综合");
        r.setTotalMarketCap(new BigDecimal("15000000000")); // 150 亿
        assertThat(hit(steady(r), "V3")).isTrue();
        assertThat(hit(growth(r), "V3")).isFalse();
    }

    @Test
    void v3UserFloorOverridesBucketDefault() {
        StockRow r = row("600010", "某公司", "综合"); // 500 亿
        ScreenerParams p = new ScreenerParams();
        p.setMarketCapMinYi(new BigDecimal("800"));
        assertThat(ScreeningRules.vetoes(r, ScreeningRules.Bucket.STEADY, p.normalized(), TODAY))
                .anyMatch(v -> v.rule().equals("V3"));
    }

    // ================= V4 流动性 =================

    @Test
    void v4RejectsMissingOrThinAmount() {
        StockRow missing = row("600011", "某公司", "综合");
        missing.setAmount(null);
        assertThat(hit(steady(missing), "V4")).isTrue();

        StockRow thin = row("600012", "某公司", "综合");
        thin.setAmount(new BigDecimal("80000000")); // 0.8 亿
        assertThat(hit(steady(thin), "V4")).isTrue();
        assertThat(hit(growth(thin), "V4")).isFalse(); // 成长档门槛 0.5 亿
    }

    @Test
    void v4TurnoverBoundsBothEndsButMissingTurnoverOnlyDegrades() {
        StockRow low = row("600013", "某公司", "综合");
        low.setTurnoverRate(new BigDecimal("0.1"));
        assertThat(hit(steady(low), "V4")).isTrue();

        StockRow churn = row("600014", "某公司", "综合");
        churn.setTurnoverRate(new BigDecimal("20"));
        assertThat(hit(steady(churn), "V4")).isTrue();

        StockRow unknown = row("600015", "某公司", "综合");
        unknown.setTurnoverRate(null);
        assertThat(steady(unknown)).isEmpty(); // 换手率缺失允许降级，不剔除
    }

    // ================= V5 亏损 =================

    @Test
    void v5RejectsNegativePe() {
        StockRow r = row("600016", "某公司", "综合");
        r.setPeTtm(new BigDecimal("-5"));
        assertThat(steady(r)).anyMatch(v -> v.rule().equals("V5"));
    }

    @Test
    void v5RejectsZeroPe() {
        StockRow r = row("600017", "某公司", "综合");
        r.setPeTtm(BigDecimal.ZERO);
        assertThat(hit(steady(r), "V5")).isTrue();
    }

    @Test
    void v5RejectsWhenNeitherPeNorProfitGrowthCanConfirmProfitability() {
        StockRow r = row("600018", "某公司", "综合");
        r.setPeTtm(null);
        r.setProfitGrowth(null);
        assertThat(hit(steady(r), "V5")).isTrue();
    }

    @Test
    void v5AcceptsMissingPeWhenProfitGrowthConfirmsProfit() {
        StockRow r = row("600019", "某公司", "综合");
        r.setPeTtm(null); // 净利同比 +10%，能确认盈利
        assertThat(hit(steady(r), "V5")).isFalse();
    }

    // ================= V6 资不抵债 / 高 PB =================

    @Test
    void v6RejectsMissingZeroAndHighPb() {
        StockRow missing = row("600020", "某公司", "综合");
        missing.setPb(null);
        assertThat(hit(steady(missing), "V6")).isTrue();

        StockRow broken = row("600021", "某公司", "综合");
        broken.setPb(new BigDecimal("-1"));
        assertThat(hit(steady(broken), "V6")).isTrue();

        StockRow rich = row("600022", "某公司", "综合");
        rich.setPb(new BigDecimal("9"));
        assertThat(hit(steady(rich), "V6")).isTrue();
        assertThat(hit(growth(rich), "V6")).isFalse(); // 成长档上限 12
    }

    // ================= V7 高杠杆：65 / 75 的分界 =================

    @Test
    void v7LeverageBoundaryIs65ForSteadyAnd75ForGrowth() {
        StockRow mid = row("600023", "某公司", "综合");
        mid.setDebtRatio(new BigDecimal("65"));
        assertThat(hit(steady(mid), "V7")).isFalse();  // 含边界
        assertThat(hit(growth(mid), "V7")).isFalse();

        StockRow above65 = row("600024", "某公司", "综合");
        above65.setDebtRatio(new BigDecimal("66"));
        assertThat(hit(steady(above65), "V7")).isTrue();
        assertThat(hit(growth(above65), "V7")).isFalse();

        StockRow above75 = row("600025", "某公司", "综合");
        above75.setDebtRatio(new BigDecimal("76"));
        assertThat(hit(steady(above75), "V7")).isTrue();
        assertThat(hit(growth(above75), "V7")).isTrue();
    }

    @Test
    void v7RejectsMissingDebtRatio() {
        StockRow r = row("600026", "某公司", "综合");
        r.setDebtRatio(null);
        assertThat(hit(steady(r), "V7")).isTrue();
    }

    // ================= V8 业绩崩塌 =================

    @Test
    void v8SteadyToleralthresholds() {
        StockRow ok = row("600027", "某公司", "综合");
        ok.setProfitGrowth(new BigDecimal("-20"));
        ok.setRevenueGrowth(new BigDecimal("-15"));
        assertThat(hit(steady(ok), "V8")).isFalse(); // 含边界

        StockRow badProfit = row("600028", "某公司", "综合");
        badProfit.setProfitGrowth(new BigDecimal("-20.1"));
        assertThat(hit(steady(badProfit), "V8")).isTrue();

        StockRow badRev = row("600029", "某公司", "综合");
        badRev.setRevenueGrowth(new BigDecimal("-15.1"));
        assertThat(hit(steady(badRev), "V8")).isTrue();
    }

    @Test
    void v8SteadyRejectsOnlyWhenBothGrowthNumbersMissing() {
        StockRow oneMissing = row("600030", "某公司", "综合");
        oneMissing.setProfitGrowth(null);
        assertThat(hit(steady(oneMissing), "V8")).isFalse(); // 允许降级

        StockRow bothMissing = row("600031", "某公司", "综合");
        bothMissing.setProfitGrowth(null);
        bothMissing.setRevenueGrowth(null);
        assertThat(hit(steady(bothMissing), "V8")).isTrue();
    }

    @Test
    void v8GrowthRequiresBothPositive() {
        StockRow negative = row("600032", "某公司", "综合");
        negative.setProfitGrowth(new BigDecimal("-1"));
        assertThat(hit(growth(negative), "V8")).isTrue();

        StockRow negativeRev = row("600033", "某公司", "综合");
        negativeRev.setRevenueGrowth(new BigDecimal("-0.5"));
        assertThat(hit(growth(negativeRev), "V8")).isTrue();

        StockRow flat = row("600034", "某公司", "综合");
        flat.setProfitGrowth(BigDecimal.ZERO);
        flat.setRevenueGrowth(BigDecimal.ZERO);
        assertThat(hit(growth(flat), "V8")).isFalse();
    }

    @Test
    void v8GrowthRejectsWhenEitherGrowthNumberIsMissing() {
        StockRow r = row("600035", "某公司", "综合");
        r.setRevenueGrowth(null);
        assertThat(hit(growth(r), "V8")).isTrue();
        assertThat(hit(steady(r), "V8")).isFalse();
    }

    // ================= V9 / V10 低价与停牌 =================

    @Test
    void v9RejectsPennyStock() {
        StockRow r = row("600036", "某公司", "综合");
        r.setPrice(new BigDecimal("2.99"));
        assertThat(hit(steady(r), "V9")).isTrue();
    }

    @Test
    void v10RejectsSuspendedOrUntradeable() {
        StockRow noPrice = row("600037", "某公司", "综合");
        noPrice.setPrice(null);
        assertThat(hit(steady(noPrice), "V10")).isTrue();

        StockRow zeroPrice = row("600038", "某公司", "综合");
        zeroPrice.setPrice(BigDecimal.ZERO);
        assertThat(hit(steady(zeroPrice), "V10")).isTrue();

        StockRow noVolume = row("600039", "某公司", "综合");
        noVolume.setAmount(BigDecimal.ZERO);
        assertThat(hit(steady(noVolume), "V10")).isTrue();
    }

    // ================= V11 金融地产 =================

    @Test
    void v11RejectsFinancialAndRealEstateIndustries() {
        for (String industry : List.of("银行", "保险", "证券", "多元金融", "信托", "房地产开发")) {
            StockRow r = row("600040", "某公司", industry);
            assertThat(hit(steady(r), "V11"))
                    .as("行业 %s 应被排除", industry)
                    .isTrue();
        }
    }

    @Test
    void v11DoesNotTouchManufacturing() {
        assertThat(hit(steady(row("600041", "某公司", "电子元件")), "V11")).isFalse();
        assertThat(hit(steady(row("600042", "某公司", "汽车行业")), "V11")).isFalse();
    }

    @Test
    void v11MissingIndustryIsKeptButMarked() {
        StockRow r = row("600043", "某公司", null);
        assertThat(hit(steady(r), "V11")).isFalse();
        assertThat(r.isIndustryUnknown()).isTrue();
    }

    // ================= V12 绝对估值上限 =================

    @Test
    void v12ThresholdsDifferByBucket() {
        StockRow r = row("600044", "某公司", "综合");
        r.setPeTtm(new BigDecimal("35"));
        assertThat(hit(steady(r), "V12")).isTrue();
        assertThat(hit(growth(r), "V12")).isFalse();

        StockRow high = row("600045", "某公司", "综合");
        high.setPeTtm(new BigDecimal("50"));
        assertThat(hit(steady(high), "V12")).isTrue();
        assertThat(hit(growth(high), "V12")).isTrue();
    }

    @Test
    void v12UserCeilingOverridesBucketDefault() {
        StockRow r = row("600046", "某公司", "综合"); // PE 14
        ScreenerParams p = new ScreenerParams();
        p.setPeMax(new BigDecimal("10"));
        assertThat(ScreeningRules.vetoes(r, ScreeningRules.Bucket.STEADY, p.normalized(), TODAY))
                .anyMatch(v -> v.rule().equals("V12"));
    }

    // ================= 高级条件 =================

    @Test
    void advancedFiltersVetoClearly() {
        ScreenerParams p = new ScreenerParams();
        p.setExcludedIndustries(List.of("酿酒行业"));
        assertThat(ScreeningRules.vetoes(row("600519", "贵州茅台", "酿酒行业"),
                ScreeningRules.Bucket.STEADY, p.normalized(), TODAY))
                .anyMatch(v -> v.rule().equals("EX"));

        ScreenerParams dividend = new ScreenerParams();
        dividend.setDividendYieldMin(new BigDecimal("5"));
        assertThat(ScreeningRules.vetoes(row("600047", "某公司", "综合"),
                ScreeningRules.Bucket.STEADY, dividend.normalized(), TODAY))
                .anyMatch(v -> v.rule().equals("EX"));

        // 门槛设为 0 就不是门槛了，不该剔除
        ScreenerParams zero = new ScreenerParams();
        zero.setDividendYieldMin(BigDecimal.ZERO);
        assertThat(ScreeningRules.vetoes(row("600048", "某公司", "综合"), // 股息率 2.5%
                ScreeningRules.Bucket.STEADY, zero.normalized(), TODAY))
                .noneMatch(v -> v.rule().equals("EX"));
    }

    // ================= 参数校验 =================

    @Test
    void paramsRejectIllegalValues() {
        assertThatThrownBy(() -> paramsWithMode("bogus")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> {
            ScreenerParams p = new ScreenerParams();
            p.setBucket("bogus");
            p.normalized();
        }).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> {
            ScreenerParams p = new ScreenerParams();
            p.setPerBucket(4);
            p.normalized();
        }).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> {
            ScreenerParams p = new ScreenerParams();
            p.setPricePercentileMax(new BigDecimal("150"));
            p.normalized();
        }).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> {
            ScreenerParams p = new ScreenerParams();
            p.setPeMax(new BigDecimal("-1"));
            p.normalized();
        }).isInstanceOf(IllegalArgumentException.class);
    }

    private static ScreenerParams paramsWithMode(String mode) {
        ScreenerParams p = new ScreenerParams();
        p.setMode(mode);
        p.normalized();
        return p;
    }

    @Test
    void paramsDefaultsAreTheOnesAdvertised() {
        ScreenerParams p = ScreenerParams.defaults();
        assertThat(p.getMode()).isEqualTo("index_first");
        assertThat(p.getBucket()).isEqualTo("both");
        assertThat(p.getPerBucket()).isEqualTo(3);
        assertThat(p.getPeMax()).isNull();
        assertThat(p.wantsIndexFunds()).isTrue();
        assertThat(p.wantsStocks()).isTrue();
        assertThat(p.wantsSteady()).isTrue();
        assertThat(p.wantsGrowth()).isTrue();
    }

    @Test
    void paramsModeGatesWhichBlocksRun() {
        ScreenerParams onlyIndex = new ScreenerParams();
        onlyIndex.setMode("index_only");
        onlyIndex = onlyIndex.normalized();
        assertThat(onlyIndex.wantsIndexFunds()).isTrue();
        assertThat(onlyIndex.wantsStocks()).isFalse();
    }

    // ================= 池内分位方向 =================

    @Test
    void percentileDirectionIsFlippedForLowerIsBetterFactors() {
        List<StockRow> pool = List.of(
                withPe(row("600100", "A", "综合"), "10"),
                withPe(row("600101", "B", "综合"), "20"),
                withPe(row("600102", "C", "综合"), "30"));
        Map<String, Map<ScreeningRules.Factor, BigDecimal>> pct =
                ScreeningRules.percentiles(pool, Map.of());

        // PE 越低 → 分位越高（越「好」）
        assertThat(pct.get("600100").get(ScreeningRules.Factor.PE))
                .isGreaterThan(pct.get("600102").get(ScreeningRules.Factor.PE));
        assertThat(pct.get("600100").get(ScreeningRules.Factor.PE))
                .isEqualByComparingTo("83.33");
    }

    @Test
    void percentileDirectionKeepsHigherIsBetterForDividend() {
        List<StockRow> pool = new ArrayList<>();
        for (String[] v : new String[][]{{"600110", "1"}, {"600111", "2"}, {"600112", "3"}}) {
            StockRow r = row(v[0], "X", "综合");
            r.setDividendYield(new BigDecimal(v[1]));
            pool.add(r);
        }
        Map<String, Map<ScreeningRules.Factor, BigDecimal>> pct =
                ScreeningRules.percentiles(pool, Map.of());
        assertThat(pct.get("600112").get(ScreeningRules.Factor.DIVIDEND_YIELD))
                .isGreaterThan(pct.get("600110").get(ScreeningRules.Factor.DIVIDEND_YIELD));
    }

    @Test
    void percentilesAreWinsorizedAndSingleMemberPoolIsNeutral() {
        List<StockRow> pool = List.of(
                withPe(row("600120", "A", "综合"), "5"),
                withPe(row("600121", "B", "综合"), "10"),
                withPe(row("600122", "C", "综合"), "15"),
                withPe(row("600123", "D", "综合"), "20"),
                withPe(row("600124", "E", "综合"), "200"));
        Map<String, Map<ScreeningRules.Factor, BigDecimal>> pct =
                ScreeningRules.percentiles(pool, Map.of());
        for (StockRow r : pool) {
            BigDecimal v = pct.get(r.getCode()).get(ScreeningRules.Factor.PE);
            assertThat(v).isBetween(new BigDecimal("2"), new BigDecimal("98"));
        }
        // 只有一只时没有「同批」可言，给中位而不是编一个高低
        Map<String, Map<ScreeningRules.Factor, BigDecimal>> single =
                ScreeningRules.percentiles(List.of(pool.get(0)), Map.of());
        assertThat(single.get("600120").get(ScreeningRules.Factor.PE))
                .isEqualByComparingTo("50");
    }

    private static StockRow withPe(StockRow r, String pe) {
        r.setPeTtm(new BigDecimal(pe));
        return r;
    }

    // ================= 打分与降级 =================

    @Test
    void missingSubFactorIsDroppedAndWeightsRenormalized() {
        StockRow full = row("600200", "A", "综合");
        Map<ScreeningRules.Factor, BigDecimal> complete = new HashMap<>();
        for (ScreeningRules.Factor f : ScreeningRules.Factor.values()) {
            complete.put(f, new BigDecimal("70"));
        }

        // 全部因子都是 70 分位 → 无论怎么归一，总分都是 70
        ScreeningRules.Scored base = ScreeningRules.score(full, ScreeningRules.Bucket.STEADY, complete);
        assertThat(base.score()).isEqualByComparingTo("70.00");

        Map<ScreeningRules.Factor, BigDecimal> missingGross = new HashMap<>(complete);
        missingGross.remove(ScreeningRules.Factor.GROSS_MARGIN);
        ScreeningRules.Scored degraded = ScreeningRules.score(full, ScreeningRules.Bucket.STEADY, missingGross);
        // 剩余子因子仍然都是 70 → 重新归一后还是 70，不能因为少一项就掉分
        assertThat(degraded.score()).isEqualByComparingTo("70.00");
        assertThat(degraded.dims()).isNotEmpty();
    }

    @Test
    void droppingAWholeDimensionRenormalizesDimensionWeights() {
        Map<ScreeningRules.Factor, BigDecimal> pct = new HashMap<>();
        // 只给「估值便宜」和「盈利质量」，不给规模与价格位置
        pct.put(ScreeningRules.Factor.PE, new BigDecimal("80"));
        pct.put(ScreeningRules.Factor.PB, new BigDecimal("80"));
        pct.put(ScreeningRules.Factor.DIVIDEND_YIELD, new BigDecimal("80"));
        pct.put(ScreeningRules.Factor.ROE, new BigDecimal("60"));
        pct.put(ScreeningRules.Factor.DEBT_RATIO, new BigDecimal("60"));
        pct.put(ScreeningRules.Factor.GROSS_MARGIN, new BigDecimal("60"));

        ScreeningRules.Scored scored = ScreeningRules.score(
                row("600201", "A", "综合"), ScreeningRules.Bucket.STEADY, pct);
        assertThat(scored).isNotNull();
        // 估值 0.40、质量 0.30 → 归一为 0.5714 / 0.4286
        assertThat(scored.score()).isEqualByComparingTo("71.43");
        double weightSum = scored.dims().stream().mapToDouble(ScreeningRules.DimensionScore::weight).sum();
        assertThat(weightSum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void dominantDimensionMissingDisqualifies() {
        Map<ScreeningRules.Factor, BigDecimal> withoutValuation = new HashMap<>();
        withoutValuation.put(ScreeningRules.Factor.ROE, new BigDecimal("90"));
        withoutValuation.put(ScreeningRules.Factor.DEBT_RATIO, new BigDecimal("90"));
        withoutValuation.put(ScreeningRules.Factor.GROSS_MARGIN, new BigDecimal("90"));

        assertThat(ScreeningRules.score(row("600202", "A", "综合"),
                ScreeningRules.Bucket.STEADY, withoutValuation)).isNull();

        Map<ScreeningRules.Factor, BigDecimal> withoutGrowth = new HashMap<>(withoutValuation);
        assertThat(ScreeningRules.score(row("600203", "A", "综合"),
                ScreeningRules.Bucket.GROWTH, withoutGrowth)).isNull();
    }

    // ================= 选择、分散、少给 =================

    /** 只改「总分 = 50」这一个变量没法构造，所以直接测比较器：同分时按代码升序，保证可复现。 */
    @Test
    void comparatorBreaksTiesByMarketCapThenYieldThenCode() {
        ScreeningRules.Selected a = fake("600900", "50.00", "100000000000", "1.0");
        ScreeningRules.Selected b = fake("600901", "50.00", "100000000000", "1.0");
        assertThat(List.of(a, b).stream()
                .sorted(ScreeningRules.comparator(ScreeningRules.Bucket.STEADY)).toList())
                .extracting(s -> s.row().getCode()).containsExactly("600900", "600901");

        // 分数相同时市值大的在前
        ScreeningRules.Selected big = fake("600902", "50.00", "900000000000", "1.0");
        ScreeningRules.Selected small = fake("600903", "50.00", "100000000000", "1.0");
        assertThat(List.of(small, big).stream()
                .sorted(ScreeningRules.comparator(ScreeningRules.Bucket.STEADY)).toList())
                .extracting(s -> s.row().getCode()).containsExactly("600902", "600903");

        // 分数与市值都相同时股息率高的在前（稳健档）
        ScreeningRules.Selected highYield = fake("600904", "50.00", "100000000000", "4.0");
        ScreeningRules.Selected lowYield = fake("600905", "50.00", "100000000000", "1.0");
        assertThat(List.of(lowYield, highYield).stream()
                .sorted(ScreeningRules.comparator(ScreeningRules.Bucket.STEADY)).toList())
                .extracting(s -> s.row().getCode()).containsExactly("600904", "600905");

        // 分数不同时分数说话
        ScreeningRules.Selected better = fake("600906", "70.00", "1", "1.0");
        assertThat(List.of(a, better).stream()
                .sorted(ScreeningRules.comparator(ScreeningRules.Bucket.STEADY)).toList())
                .extracting(s -> s.row().getCode()).containsExactly("600906", "600900");
    }

    private static ScreeningRules.Selected fake(String code, String score, String marketCap, String dividend) {
        StockRow r = row(code, "X", "综合");
        r.setTotalMarketCap(new BigDecimal(marketCap));
        r.setDividendYield(new BigDecimal(dividend));
        return new ScreeningRules.Selected(r, ScreeningRules.Bucket.STEADY,
                new BigDecimal(score), List.of(), Map.of(), List.of(), List.of(), List.of(), null);
    }

    @Test
    void selectionAppliesScoreFloorAndExplainsHowManyItDropped() {
        List<StockRow> pool = List.of(
                withPe(row("600300", "便宜", "综合"), "10"),
                withPe(row("600301", "很贵", "综合"), "90"));
        ScreeningRules.Selection sel = ScreeningRules.select(pool,
                ScreeningRules.Bucket.STEADY, DEFAULTS, Map.of(),
                new ScreeningRules.Constraints(3, Set.of(), new HashMap<>(), 1, 2));
        // 池里只有两只，池内分位下较差的那只一定低于下限；不凑数，而且要说清丢了几只
        assertThat(sel.picked()).hasSize(1);
        assertThat(sel.picked().get(0).row().getCode()).isEqualTo("600300");
        assertThat(sel.belowFloor()).isEqualTo(1);
    }

    @Test
    void uniformPoolYieldsNothingAndSaysEveryoneWasBelowFloor() {
        // 八只所有指标完全相同的股票：池内分位全是 50，谁都谈不上「明显好于本批中位」
        List<StockRow> pool = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            pool.add(row(String.format("6008%02d", i), "一样" + i, "行业" + i));
        }
        ScreeningRules.Selection sel = ScreeningRules.select(pool,
                ScreeningRules.Bucket.STEADY, DEFAULTS, Map.of(),
                new ScreeningRules.Constraints(3, Set.of(), new HashMap<>(), 1, 2));
        assertThat(sel.picked()).isEmpty();
        assertThat(sel.belowFloor()).isEqualTo(8);
    }

    @Test
    void selectionLimitsOnePerIndustryWithinBucket() {
        List<StockRow> pool = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            pool.add(withPe(row(String.format("6003%02d", i), "同行业" + i, "电子元件"),
                    String.valueOf(5 + i)));
        }
        pool.add(withPe(row("600320", "别的行业", "汽车行业"), "5.5"));

        ScreeningRules.Selection sel = ScreeningRules.select(pool,
                ScreeningRules.Bucket.STEADY, DEFAULTS, Map.of(),
                new ScreeningRules.Constraints(3, Set.of(), new HashMap<>(), 1, 2));
        assertThat(sel.picked()).hasSize(2);
        assertThat(sel.picked()).extracting(s -> s.row().getIndustry())
                .containsExactlyInAnyOrder("电子元件", "汽车行业");
        assertThat(sel.skippedByIndustry()).isPositive();
    }

    @Test
    void selectionRespectsGlobalIndustryCapAndExcludedCodes() {
        List<StockRow> pool = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pool.add(withPe(row("60033" + i, "同行业" + i, "电子元件"), String.valueOf(10 + i)));
        }
        Map<String, Integer> used = new HashMap<>();
        used.put("电子元件", 2); // 全结果上限已经用满
        ScreeningRules.Selection sel = ScreeningRules.select(pool,
                ScreeningRules.Bucket.GROWTH, DEFAULTS, Map.of(),
                new ScreeningRules.Constraints(3, Set.of(), used, 1, 2));
        assertThat(sel.picked()).isEmpty();

        ScreeningRules.Selection excluded = ScreeningRules.select(pool,
                ScreeningRules.Bucket.GROWTH, DEFAULTS, Map.of(),
                new ScreeningRules.Constraints(3, Set.of("600330"), new HashMap<>(), 1, 2));
        assertThat(excluded.picked()).extracting(s -> s.row().getCode()).doesNotContain("600330");
    }

    @Test
    void shortlistIsCappedAndDropsPositionDimensionWhenNoKlinesYet() {
        List<StockRow> pool = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            pool.add(withPe(row(String.format("6004%02d", i), "X" + i, "行业" + i), String.valueOf(6 + i)));
        }
        List<StockRow> shortlist = ScreeningRules.shortlist(pool, ScreeningRules.Bucket.STEADY, DEFAULTS);
        // 上限是 8，而且必须先过分数下限——20 只的池子只会有 6 只够格
        assertThat(shortlist).hasSize(ScreeningRules.SHORTLIST_PER_BUCKET);

        List<StockRow> smallPool = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            smallPool.add(withPe(row(String.format("6005%02d", i), "Y" + i, "行业" + i), String.valueOf(8 + i)));
        }
        assertThat(ScreeningRules.shortlist(smallPool, ScreeningRules.Bucket.STEADY, DEFAULTS))
                .hasSizeLessThan(ScreeningRules.SHORTLIST_PER_BUCKET);
    }

    @Test
    void pricePositionFactorsOnlyComeFromKlineData() {
        StockRow r = row("600500", "A", "综合");
        assertThat(ScreeningRules.factorValue(ScreeningRules.Factor.PRICE_PERCENTILE, r, null)).isNull();
        assertThat(ScreeningRules.factorValue(ScreeningRules.Factor.VOLATILITY, r, null)).isNull();

        PricePosition pos = new PricePosition();
        pos.setPricePercentile(new BigDecimal("22.5"));
        pos.setDrawdownFromHigh(new BigDecimal("31.0"));
        pos.setAnnualizedVolatility(new BigDecimal("24.0"));
        assertThat(ScreeningRules.factorValue(ScreeningRules.Factor.PRICE_PERCENTILE, r, pos))
                .isEqualByComparingTo("22.5");
        assertThat(ScreeningRules.factorValue(ScreeningRules.Factor.DRAWDOWN, r, pos))
                .isEqualByComparingTo("31.0");
    }

    @Test
    void klineFailureKeepsTheStockInsteadOfDroppingIt() {
        List<StockRow> pool = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pool.add(withPe(row(String.format("6006%02d", i), "X" + i, "行业" + i), String.valueOf(9 + i)));
        }
        Map<String, PricePosition> onlyFirst = new HashMap<>();
        onlyFirst.put("600600", PricePosition.unavailable("日线不可达，价格位置未确认"));

        List<ScreeningRules.Selected> picked = ScreeningRules.select(pool,
                ScreeningRules.Bucket.STEADY, DEFAULTS, onlyFirst,
                new ScreeningRules.Constraints(3, Set.of(), new HashMap<>(), 1, 2)).picked();
        // 没有价格位置的标的仍然参与，只是少一个维度
        assertThat(picked).isNotEmpty();
        assertThat(picked).extracting(s -> s.row().getCode()).contains("600601");
    }

    // ================= 输出纪律 =================

    @Test
    void outputNeverContainsAdviceWords() {
        List<StockRow> pool = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pool.add(withPe(row(String.format("6007%02d", i), "X" + i, "行业" + i), String.valueOf(9 + i)));
        }
        List<ScreeningRules.Selected> picked = ScreeningRules.select(pool,
                ScreeningRules.Bucket.GROWTH, DEFAULTS, Map.of(),
                new ScreeningRules.Constraints(3, Set.of(), new HashMap<>(), 1, 2)).picked();
        assertThat(picked).isNotEmpty();

        String[] banned = {"买入", "卖出", "加仓", "减仓", "建议持仓", "目标价", "必涨", "建议关注"};
        for (ScreeningRules.Selected s : picked) {
            String text = String.join("|", s.reasons()) + "|" + String.join("|", s.risks());
            for (String word : banned) {
                assertThat(text).as("输出里出现了禁用词「%s」", word).doesNotContain(word);
            }
        }
        assertThat(StockScreenerService.DISCLAIMER).contains("不构成投资建议");
    }

    @Test
    void reasonsAndRisksAlwaysCarryNumbersOrExplicitGaps() {
        List<StockRow> pool = List.of(
                withPe(row("600800", "A", "电子元件"), "10"),
                withPe(row("600801", "B", "汽车行业"), "12"));
        List<ScreeningRules.Selected> picked = ScreeningRules.select(pool,
                ScreeningRules.Bucket.STEADY, DEFAULTS, Map.of(),
                new ScreeningRules.Constraints(3, Set.of(), new HashMap<>(), 1, 2)).picked();
        assertThat(picked).isNotEmpty();
        assertThat(picked.get(0).reasons()).isNotEmpty();
        assertThat(picked.get(0).risks())
                .anyMatch(r -> r.contains("商誉")); // 已知盲区必须出现
    }

    @Test
    void degradeNoteAppearsWhenPriceDimensionIsDropped() {
        List<StockRow> pool = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pool.add(withPe(row(String.format("6009%02d", i), "X" + i, "行业" + i), String.valueOf(9 + i)));
        }
        List<ScreeningRules.Selected> picked = ScreeningRules.select(pool,
                ScreeningRules.Bucket.STEADY, DEFAULTS, Map.of(),
                new ScreeningRules.Constraints(1, Set.of(), new HashMap<>(), 1, 2)).picked();
        assertThat(picked).hasSize(1);
        assertThat(picked.get(0).degradations())
                .anyMatch(d -> d.contains("价格位置"));
    }
}