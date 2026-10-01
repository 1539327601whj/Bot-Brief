package com.ai.daily.screener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「低估精选」的全部筛选规则。<b>纯函数，无网络、无 Spring、无状态</b>，可直接单测。
 *
 * <p>规则来源与每一条阈值的理由写在 {@code automation/agents/stock_screening_rules.md}。
 * <b>改规则先改那份文档，再改这个类。</b>
 *
 * <p>流程：{@link #vetoes} 排雷 → {@link #shortlist} 用基本面出短名单 →
 * 抓日线 → {@link #select} 带价格位置定稿。
 * 两次打分用的是同一套权重，短名单阶段只是因为还没有日线而丢掉「价格位置」维（权重重新归一）。
 */
public final class ScreeningRules {

    /** 分档。稳健低估要求上市更久、市值更大、杠杆更低；低估成长放宽准入但要求成长为正。 */
    public enum Bucket {
        STEADY("稳健低估", "中低"),
        GROWTH("低估成长", "中");

        private final String label;
        private final String riskLevel;

        Bucket(String label, String riskLevel) {
            this.label = label;
            this.riskLevel = riskLevel;
        }

        public String label() { return label; }

        public String riskLevel() { return riskLevel; }

        public String key() { return this == STEADY ? "steady" : "growth"; }
    }

    /** 参与打分（也参与池内分位）的因子。{@code lowerIsBetter} 决定分位方向。 */
    public enum Factor {
        PE("PE(TTM)", true),
        PB("市净率", true),
        DIVIDEND_YIELD("股息率", false),
        ROE("ROE(加权)", false),
        DEBT_RATIO("资产负债率", true),
        GROSS_MARGIN("毛利率", false),
        MARKET_CAP("总市值", false),
        VOLATILITY("年化波动", true),
        PRICE_PERCENTILE("一年价格分位", true),
        DRAWDOWN("距 52 周高点回撤", false),
        REVENUE_GROWTH("营收同比", false),
        PROFIT_GROWTH("净利同比", false);

        private final String label;
        private final boolean lowerIsBetter;

        Factor(String label, boolean lowerIsBetter) {
            this.label = label;
            this.lowerIsBetter = lowerIsBetter;
        }

        public String label() { return label; }

        public boolean lowerIsBetter() { return lowerIsBetter; }
    }

    /** 一条排雷命中记录。 */
    public record Veto(String rule, String label) {}

    /** 一个维度的得分，权重是重新归一后实际生效的权重。 */
    public record DimensionScore(String key, String label, double weight, BigDecimal score) {}

    /** 一个入选标的的完整可复核结果。 */
    public record Selected(
            StockRow row,
            Bucket bucket,
            BigDecimal score,
            List<DimensionScore> dimensions,
            Map<String, BigDecimal> factorPercentiles,
            List<String> reasons,
            List<String> risks,
            List<String> degradations,
            PricePosition position
    ) {}

    /** 一次扫描里每条排雷规则命中的数量（按首次命中的规则计）。 */
    public record VetoCount(String rule, String label, int count) {}

    /** 选择时的额外约束：跨档去重、行业分散。 */
    public record Constraints(
            int perBucketLimit,
            Set<String> excludedCodes,
            Map<String, Integer> industryUsed,
            int industryMaxPerBucket,
            int industryMaxTotal
    ) {}

    /**
     * 一次选择的完整结果。**被丢掉的数量必须能说清楚**：
     * 分数下限与行业分散都可能让结果变少，静默变少会让用户以为「今天就是这样」。
     */
    public record Selection(List<Selected> picked, int belowFloor, int skippedByIndustry) {}

    /** 分数下限。低于它的不进结果，宁可少给。 */
    public static final BigDecimal SCORE_FLOOR = new BigDecimal("55");
    /** 短名单每档上限（决定抓多少次日线）。 */
    public static final int SHORTLIST_PER_BUCKET = 8;

    private ScreeningRules() {}

    // ==================================================================
    // 一、排雷 V1–V12
    // ==================================================================

    /**
     * 逐条跑 V1–V12。返回空列表 = 通过。
     * 字段缺失一律剔除（V4 的换手率、V8 的部分同比允许降级，在别处记录）。
     */
    public static List<Veto> vetoes(StockRow row, Bucket bucket, ScreenerParams p, LocalDate today) {
        List<Veto> out = new ArrayList<>();
        boolean steady = bucket == Bucket.STEADY;

        // V1 退市 / ST / 次新
        String name = row.getName() == null ? "" : row.getName().replace(" ", "");
        if (name.isEmpty()) {
            out.add(new Veto("V1", "名称为空，无法确认是否 ST/退市"));
        } else {
            String upper = name.toUpperCase();
            // 只判前缀：ST 股一定是 *ST / ST 开头，用 contains 会误伤名称里带 ST 的正常公司。
            if (upper.startsWith("ST") || upper.startsWith("*ST") || name.contains("退")) {
                out.add(new Veto("V1", "名称含 ST / 退市标记"));
            } else if (upper.charAt(0) == 'N' || upper.charAt(0) == 'C') {
                out.add(new Veto("V1", "次新股（N / C 前缀），无足够交易历史"));
            }
        }

        // V2 上市年限
        long years = row.listedYears(today);
        int minYears = steady ? 3 : 2;
        if (years < 0) {
            out.add(new Veto("V2", "上市日期缺失，无法确认上市年限"));
        } else if (years < minYears) {
            out.add(new Veto("V2", "上市不满 " + minYears + " 年"));
        }

        // V3 市值（用户填了下限则以用户为准）
        BigDecimal capFloorYi = p.getMarketCapMinYi() != null
                ? p.getMarketCapMinYi()
                : (steady ? new BigDecimal("200") : new BigDecimal("50"));
        BigDecimal cap = row.effectiveMarketCap();
        if (cap == null) {
            out.add(new Veto("V3", "总市值缺失"));
        } else if (cap.compareTo(yiToYuan(capFloorYi)) < 0) {
            out.add(new Veto("V3", "市值低于 " + strip(capFloorYi) + " 亿"));
        }

        // V4 流动性
        BigDecimal amount = row.getAmount();
        BigDecimal amountFloorYi = steady ? new BigDecimal("1") : new BigDecimal("0.5");
        if (amount == null) {
            out.add(new Veto("V4", "成交额缺失，无法确认可交易"));
        } else if (amount.compareTo(yiToYuan(amountFloorYi)) < 0) {
            out.add(new Veto("V4", "成交额低于 " + strip(amountFloorYi) + " 亿"));
        }
        BigDecimal turnover = row.getTurnoverRate();
        if (turnover != null
                && (turnover.compareTo(new BigDecimal("0.2")) < 0
                || turnover.compareTo(new BigDecimal("15")) > 0)) {
            out.add(new Veto("V4", "换手率 " + strip(turnover) + "% 不在 0.2%–15% 区间"));
        }

        // V5 亏损 —— 负 PE 看起来「很小很便宜」，是陷阱，必须明确判掉
        BigDecimal pe = row.getPeTtm();
        if (pe == null) {
            if (row.getProfitGrowth() == null) {
                out.add(new Veto("V5", "PE(TTM) 与净利同比均缺失，无法确认是否盈利"));
            }
        } else if (pe.signum() <= 0) {
            out.add(new Veto("V5", "PE(TTM) " + strip(pe) + " ≤ 0，亏损或数据异常"));
        }

        // V6 资不抵债 / 高 PB
        BigDecimal pbFloor = p.getPbMax() != null ? p.getPbMax() : (steady ? new BigDecimal("8") : new BigDecimal("12"));
        BigDecimal pb = row.getPb();
        if (pb == null) {
            out.add(new Veto("V6", "市净率缺失"));
        } else if (pb.signum() <= 0) {
            out.add(new Veto("V6", "市净率 " + strip(pb) + " ≤ 0，净资产异常"));
        } else if (pb.compareTo(pbFloor) > 0) {
            out.add(new Veto("V6", "市净率高于 " + strip(pbFloor)));
        }

        // V7 高杠杆
        BigDecimal debtFloor = p.getDebtRatioMax() != null
                ? p.getDebtRatioMax()
                : (steady ? new BigDecimal("65") : new BigDecimal("75"));
        BigDecimal debt = row.getDebtRatio();
        if (debt == null) {
            out.add(new Veto("V7", "资产负债率缺失"));
        } else if (debt.compareTo(debtFloor) > 0) {
            out.add(new Veto("V7", "资产负债率 " + strip(debt) + "% 高于 " + strip(debtFloor) + "%"));
        }

        // V8 业绩崩塌
        BigDecimal rev = row.getRevenueGrowth();
        BigDecimal profit = row.getProfitGrowth();
        if (steady) {
            if (rev == null && profit == null) {
                out.add(new Veto("V8", "营收同比与净利同比均缺失"));
            } else {
                if (profit != null && profit.compareTo(new BigDecimal("-20")) < 0) {
                    out.add(new Veto("V8", "净利同比 " + strip(profit) + "% 低于 -20%"));
                }
                if (rev != null && rev.compareTo(new BigDecimal("-15")) < 0) {
                    out.add(new Veto("V8", "营收同比 " + strip(rev) + "% 低于 -15%"));
                }
            }
        } else {
            if (rev == null || profit == null) {
                out.add(new Veto("V8", "成长档要求营收与净利同比均可确认，当前缺失"));
            } else {
                if (profit.signum() < 0) {
                    out.add(new Veto("V8", "净利同比为负（" + strip(profit) + "%）"));
                }
                if (rev.signum() < 0) {
                    out.add(new Veto("V8", "营收同比为负（" + strip(rev) + "%）"));
                }
            }
        }

        // V9 低价股
        BigDecimal price = row.getPrice();
        if (price != null && price.signum() > 0 && price.compareTo(new BigDecimal("3")) < 0) {
            out.add(new Veto("V9", "股价低于 3 元"));
        }

        // V10 停牌 / 不可交易
        if (price == null || price.signum() <= 0) {
            out.add(new Veto("V10", "最新价缺失或为 0，疑似停牌"));
        }
        if (amount == null || amount.signum() <= 0) {
            out.add(new Veto("V10", "成交额为 0，疑似停牌"));
        }

        // V11 金融地产口径不可比（负债率对银行是存款负债比，不是一回事）
        if (row.isFinancialOrRealEstate()) {
            out.add(new Veto("V11", "金融 / 地产，资产负债率口径与制造业不可比"));
        }

        // V12 绝对估值上限 —— 池内分位只说「在这批里相对便宜」，没有绝对上限会漏出整体高估行业
        BigDecimal peFloor = p.getPeMax() != null ? p.getPeMax() : (steady ? new BigDecimal("30") : new BigDecimal("45"));
        if (pe != null && pe.signum() > 0 && pe.compareTo(peFloor) > 0) {
            out.add(new Veto("V12", "PE(TTM) " + strip(pe) + " 高于 " + strip(peFloor)));
        }

        // 高级：用户自选下限
        if (p.isIndustryExcluded(row.getIndustry())) {
            out.add(new Veto("EX", "你排除了行业「" + row.getIndustry() + "」"));
        }
        if (p.getDividendYieldMin() != null
                && (row.getDividendYield() == null
                || row.getDividendYield().compareTo(p.getDividendYieldMin()) < 0)) {
            out.add(new Veto("EX", "股息率低于你设定的 " + strip(p.getDividendYieldMin()) + "%"));
        }
        if (p.getRoeMin() != null
                && (row.getRoe() == null || row.getRoe().compareTo(p.getRoeMin()) < 0)) {
            out.add(new Veto("EX", "ROE 低于你设定的 " + strip(p.getRoeMin()) + "%"));
        }
        if (p.getRevenueGrowthMin() != null
                && (rev == null || rev.compareTo(p.getRevenueGrowthMin()) < 0)) {
            out.add(new Veto("EX", "营收同比低于你设定的 " + strip(p.getRevenueGrowthMin()) + "%"));
        }
        if (p.getProfitGrowthMin() != null
                && (profit == null || profit.compareTo(p.getProfitGrowthMin()) < 0)) {
            out.add(new Veto("EX", "净利同比低于你设定的 " + strip(p.getProfitGrowthMin()) + "%"));
        }

        return out;
    }

    // ==================================================================
    // 二、权重表
    // ==================================================================

    record Sub(Factor factor, double weight) {}

    record Dim(String key, String label, double weight, List<Sub> subs) {}

    /** 维度权重定义。主导维度为空即判不合格——那一维就是这一档的定义。 */
    static List<Dim> dimensions(Bucket bucket) {
        if (bucket == Bucket.STEADY) {
            return List.of(
                    new Dim("valuation", "估值便宜", 0.40, List.of(
                            new Sub(Factor.PE, 0.50), new Sub(Factor.PB, 0.30), new Sub(Factor.DIVIDEND_YIELD, 0.20))),
                    new Dim("quality", "盈利质量", 0.30, List.of(
                            new Sub(Factor.ROE, 0.50), new Sub(Factor.DEBT_RATIO, 0.30), new Sub(Factor.GROSS_MARGIN, 0.20))),
                    new Dim("scale", "规模稳定", 0.20, List.of(
                            new Sub(Factor.MARKET_CAP, 0.60), new Sub(Factor.VOLATILITY, 0.40))),
                    new Dim("position", "价格位置", 0.15, List.of(
                            new Sub(Factor.PRICE_PERCENTILE, 0.60), new Sub(Factor.DRAWDOWN, 0.40))));
        }
        return List.of(
                new Dim("valuation", "估值便宜", 0.30, List.of(
                        new Sub(Factor.PE, 0.60), new Sub(Factor.PB, 0.40))),
                new Dim("growth", "成长", 0.40, List.of(
                        new Sub(Factor.REVENUE_GROWTH, 0.40), new Sub(Factor.PROFIT_GROWTH, 0.60))),
                new Dim("quality", "质量与生存", 0.20, List.of(
                        new Sub(Factor.ROE, 0.50), new Sub(Factor.DEBT_RATIO, 0.30), new Sub(Factor.GROSS_MARGIN, 0.20))),
                new Dim("position", "价格位置", 0.10, List.of(
                        new Sub(Factor.PRICE_PERCENTILE, 0.60), new Sub(Factor.DRAWDOWN, 0.40))));
    }

    /** 该档的主导维度 key：为空即不合格。 */
    public static String dominantDim(Bucket bucket) {
        return bucket == Bucket.STEADY ? "valuation" : "growth";
    }

    // ==================================================================
    // 三、池内分位
    // ==================================================================

    /** 从标的取因子原值；取不到返回 null（该因子在这只上就是缺失）。 */
    static BigDecimal factorValue(Factor f, StockRow row, PricePosition pos) {
        return switch (f) {
            case PE -> row.getPeTtm();
            case PB -> row.getPb();
            case DIVIDEND_YIELD -> row.getDividendYield();
            case ROE -> row.getRoe();
            case DEBT_RATIO -> row.getDebtRatio();
            case GROSS_MARGIN -> row.getGrossMargin();
            case MARKET_CAP -> row.effectiveMarketCap();
            case VOLATILITY -> pos == null ? null : pos.getAnnualizedVolatility();
            case PRICE_PERCENTILE -> pos == null ? null : pos.getPricePercentile();
            case DRAWDOWN -> pos == null ? null : pos.getDrawdownFromHigh();
            case REVENUE_GROWTH -> row.getRevenueGrowth();
            case PROFIT_GROWTH -> row.getProfitGrowth();
        };
    }

    /**
     * 池内分位排名，0–100，**100 = 该因子最好**（方向已按 {@code lowerIsBetter} 翻转）。
     * 2%/98% 缩尾，避免单只极端值把整条分位轴拉平。
     */
    public static Map<String, Map<Factor, BigDecimal>> percentiles(
            List<StockRow> pool, Map<String, PricePosition> positions) {
        Map<String, Map<Factor, BigDecimal>> out = new HashMap<>();
        for (StockRow row : pool) {
            out.put(row.getCode(), new LinkedHashMap<>());
        }
        PricePosition empty = null;
        for (Factor f : Factor.values()) {
            List<Map.Entry<String, BigDecimal>> values = new ArrayList<>();
            for (StockRow row : pool) {
                PricePosition pos = positions == null ? empty : positions.get(row.getCode());
                BigDecimal v = factorValue(f, row, pos);
                if (v != null) values.add(Map.entry(row.getCode(), v));
            }
            if (values.isEmpty()) continue;
            BigDecimal[] sorted = values.stream().map(Map.Entry::getValue)
                    .sorted().toArray(BigDecimal[]::new);
            int n = sorted.length;
            for (Map.Entry<String, BigDecimal> e : values) {
                BigDecimal raw = percentileOf(e.getValue(), sorted, n);
                BigDecimal directed = f.lowerIsBetter()
                        ? new BigDecimal("100").subtract(raw)
                        : raw;
                out.get(e.getKey()).put(f, winsorize(directed));
            }
        }
        return out;
    }

    private static BigDecimal percentileOf(BigDecimal v, BigDecimal[] sorted, int n) {
        if (n <= 1) return new BigDecimal("50");
        int less = 0;
        int equal = 0;
        for (BigDecimal s : sorted) {
            int c = s.compareTo(v);
            if (c < 0) less++;
            else if (c == 0) equal++;
        }
        double pct = (less + 0.5 * equal) / n * 100.0;
        return BigDecimal.valueOf(pct).setScale(2, RoundingMode.HALF_UP);
    }

    /** 缩尾到 [2, 98]。 */
    static BigDecimal winsorize(BigDecimal v) {
        if (v.compareTo(new BigDecimal("2")) < 0) return new BigDecimal("2.00");
        if (v.compareTo(new BigDecimal("98")) > 0) return new BigDecimal("98.00");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    // ==================================================================
    // 四、打分
    // ==================================================================

    record Scored(BigDecimal score, List<DimensionScore> dims, List<String> degradations) {}

    /**
     * 计算一档的综合分。主导维度全空 → 返回 null（不合格）。
     * 子因子缺失丢子因子并按剩余子权重归一；整维为空丢该维并重新归一维度权重。
     */
    static Scored score(StockRow row, Bucket bucket, Map<Factor, BigDecimal> pct) {
        List<DimensionScore> dims = new ArrayList<>();
        List<String> degradations = new ArrayList<>();
        String dominant = dominantDim(bucket);
        boolean dominantMissing = false;

        double totalWeight = 0;
        double total = 0;
        for (Dim d : dimensions(bucket)) {
            double subWeightSum = 0;
            double subAcc = 0;
            int present = 0;
            for (Sub s : d.subs()) {
                BigDecimal p = pct.get(s.factor());
                if (p == null) continue;
                subWeightSum += s.weight();
                subAcc += s.weight() * p.doubleValue();
                present++;
            }
            if (present == 0 || subWeightSum <= 0) {
                if (d.key().equals(dominant)) dominantMissing = true;
                else degradations.add("「" + d.label() + "」维度数据缺失，已从本次计分中移除，权重已重新归一");
                continue;
            }
            BigDecimal dimScore = BigDecimal.valueOf(subAcc / subWeightSum).setScale(2, RoundingMode.HALF_UP);
            dims.add(new DimensionScore(d.key(), d.label(), d.weight(), dimScore));
            total += d.weight() * dimScore.doubleValue();
            totalWeight += d.weight();
        }

        if (dominantMissing || totalWeight <= 0) return null;
        BigDecimal score = BigDecimal.valueOf(total / totalWeight).setScale(2, RoundingMode.HALF_UP);
        // 页面按「实际生效权重」展示，避免读者以为被丢掉的维度还占了权重
        List<DimensionScore> normalized = new ArrayList<>();
        for (DimensionScore d : dims) {
            double w = d.weight() / totalWeight;
            normalized.add(new DimensionScore(d.key(), d.label(),
                    BigDecimal.valueOf(w).setScale(4, RoundingMode.HALF_UP).doubleValue(), d.score()));
        }
        return new Scored(score, normalized, degradations);
    }

    // ==================================================================
    // 五、短名单 / 定稿
    // ==================================================================

    /**
     * 用**基本面**打一版分（还没有日线，所以价格位置维自然被丢掉），
     * 取前 {@value #SHORTLIST_PER_BUCKET} 只去抓日线。行业集中度在这里也先卡一遍，
     * 免得把日线配额浪费在同一行业的 8 只上。
     */
    public static List<StockRow> shortlist(List<StockRow> pool, Bucket bucket, ScreenerParams p) {
        return select(pool, bucket, p, Map.of(),
                new Constraints(SHORTLIST_PER_BUCKET, Set.of(), new HashMap<>(), 1, Integer.MAX_VALUE))
                .picked().stream().map(Selected::row).toList();
    }

    /** 定稿：带价格位置打分、行业分散、分数下限、按稳定顺序排序。 */
    public static Selection select(
            List<StockRow> pool, Bucket bucket, ScreenerParams p,
            Map<String, PricePosition> positions, Constraints constraints) {

        Map<String, Map<Factor, BigDecimal>> pcts = percentiles(pool, positions);
        List<Selected> candidates = new ArrayList<>();
        int belowFloor = 0;
        for (StockRow row : pool) {
            if (constraints.excludedCodes().contains(row.getCode())) continue;
            Scored scored = score(row, bucket, pcts.getOrDefault(row.getCode(), Map.of()));
            if (scored == null) continue;
            if (scored.score().compareTo(SCORE_FLOOR) < 0) {
                belowFloor++;
                continue;
            }
            candidates.add(build(row, bucket, scored, p, positions, pcts.get(row.getCode())));
        }

        candidates.sort(comparator(bucket));

        List<Selected> picked = new ArrayList<>();
        Map<String, Integer> perBucketIndustry = new HashMap<>();
        int skippedByIndustry = 0;
        for (Selected s : candidates) {
            if (picked.size() >= constraints.perBucketLimit()) break;
            String industry = industryKey(s.row());
            if (perBucketIndustry.getOrDefault(industry, 0) >= constraints.industryMaxPerBucket()) {
                skippedByIndustry++;
                continue;
            }
            if (constraints.industryUsed() != null
                    && constraints.industryUsed().getOrDefault(industry, 0) >= constraints.industryMaxTotal()) {
                skippedByIndustry++;
                continue;
            }
            perBucketIndustry.merge(industry, 1, Integer::sum);
            picked.add(s);
        }
        return new Selection(picked, belowFloor, skippedByIndustry);
    }

    /** 排序：总分 → 总市值 → 股息率(稳健)/净利同比(成长) → 代码升序，保证同输入同输出。 */
    static Comparator<Selected> comparator(Bucket bucket) {
        return (a, b) -> {
            int c = b.score().compareTo(a.score());
            if (c != 0) return c;
            c = nullSafeDesc(a.row().effectiveMarketCap(), b.row().effectiveMarketCap());
            if (c != 0) return c;
            BigDecimal tieA = bucket == Bucket.STEADY ? a.row().getDividendYield() : a.row().getProfitGrowth();
            BigDecimal tieB = bucket == Bucket.STEADY ? b.row().getDividendYield() : b.row().getProfitGrowth();
            c = nullSafeDesc(tieA, tieB);
            if (c != 0) return c;
            return a.row().getCode().compareTo(b.row().getCode());
        };
    }

    private static int nullSafeDesc(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return b.compareTo(a);
    }

    /** 行业缺省归到「行业不可确认」，避免一堆未知行业的标的绕过行业分散。 */
    public static String industryKey(StockRow row) {
        String v = row.getIndustry();
        return v == null || v.isBlank() ? "行业不可确认" : v;
    }

    // ==================================================================
    // 六、可读的理由与风险（全部由数字生成，不含任何定性预测）
    // ==================================================================

    private static Selected build(StockRow row, Bucket bucket, Scored scored, ScreenerParams p,
                                  Map<String, PricePosition> positions, Map<Factor, BigDecimal> pct) {
        List<String> reasons = new ArrayList<>();
        for (DimensionScore d : scored.dims()) {
            reasons.add("「" + d.label() + "」在同批候选中处于第 " + fmt(d.score()) + " 分位");
        }
        if (row.getPeTtm() != null) reasons.add("PE(TTM) " + strip(row.getPeTtm()));
        if (row.getPb() != null) reasons.add("市净率 " + strip(row.getPb()));
        if (row.getDividendYield() != null) reasons.add("股息率 " + strip(row.getDividendYield()) + "%");
        if (row.getRoe() != null) reasons.add("ROE(加权) " + strip(row.getRoe()) + "%");
        if (row.getDebtRatio() != null) reasons.add("资产负债率 " + strip(row.getDebtRatio()) + "%");
        if (row.getGrossMargin() != null) reasons.add("毛利率 " + strip(row.getGrossMargin()) + "%");
        BigDecimal cap = row.effectiveMarketCap();
        if (cap != null) reasons.add("总市值约 " + strip(cap.divide(BigDecimal.valueOf(100000000L), 1, RoundingMode.HALF_UP)) + " 亿元");
        if (row.getAmount() != null) reasons.add("当日成交额约 " + strip(row.getAmount().divide(BigDecimal.valueOf(100000000L), 2, RoundingMode.HALF_UP)) + " 亿元");
        if (row.getRevenueGrowth() != null) reasons.add("营收同比 " + strip(row.getRevenueGrowth()) + "%");
        if (row.getProfitGrowth() != null) reasons.add("净利同比 " + strip(row.getProfitGrowth()) + "%");

        List<String> risks = new ArrayList<>();
        if (row.isIndustryUnknown()) {
            risks.add("行业字段缺失，无法确认是否属于金融/地产等负债率口径不同的行业");
        }
        PricePosition pos = positions == null ? null : positions.get(row.getCode());
        if (pos != null) {
            if (!pos.isAvailable()) {
                risks.add("未取到日线，价格位置维未计入本次得分");
            } else if (pct != null && !pct.containsKey(Factor.PRICE_PERCENTILE)) {
                risks.add("价格分位不可用，价格位置维未计入本次得分");
            }
            if (pos.getLastTradeDate() != null) {
                risks.add("日线最后一根为 " + pos.getLastTradeDate() + "，价格位置基于该日之前的数据");
            }
            for (String d : pos.getDegradations()) risks.add(d);
        }
        if (bucket == Bucket.GROWTH) {
            risks.add("成长档放宽了市值与杠杆门槛，波动与经营不确定性高于稳健档");
        }
        if (p.getDividendYieldMin() != null && row.getDividendYield() == null) {
            risks.add("股息率字段缺失");
        }
        risks.add("商誉、质押、诉讼、减持等事项无对应数据源，本页不做判断");

        return new Selected(row, bucket, scored.score(), scored.dims(),
                toStringKeyed(pct), reasons, risks, scored.degradations(), pos);
    }

    private static Map<String, BigDecimal> toStringKeyed(Map<Factor, BigDecimal> pct) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (pct != null) {
            pct.forEach((k, v) -> out.put(k.name(), v));
        }
        return out;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    static BigDecimal yiToYuan(BigDecimal yi) {
        return yi.multiply(BigDecimal.valueOf(100000000L));
    }

    static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    static String fmt(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}