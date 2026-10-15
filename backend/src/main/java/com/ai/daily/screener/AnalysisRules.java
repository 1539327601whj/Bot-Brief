package com.ai.daily.screener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「代码查询 · 深度分析」的全部判定规则。<b>纯函数、无网络、无 Spring、无状态</b>，可直接单测。
 *
 * <p>规则来源与每一条阈值的理由写在 {@code automation/agents/stock_screening_rules.md} §9。
 * <b>改规则先改那份文档，再改这个类。</b>
 *
 * <p>这个类与 {@link ScreeningRules} 的关系是三句话：
 *
 * <ol>
 *   <li><b>排雷体检直接复用 V1–V12</b>（{@link ScreeningRules#vetoes}），一条阈值都不另立——
 *       它在章程 §3 里有论证，重写一遍就等于多出一份会各自漂移的规则。</li>
 *   <li><b>维度判定不走池内分位</b>。{@link ScreeningRules#percentiles} 算的是「在这批候选里排第几」，
 *       而这一页**只有一只标的、没有池子**，所以一律走 §9.5 的**绝对阈值带**。
 *       两套数不可互相印证，页面上必须写明（章程 §9.2）。</li>
 *   <li><b>准入阈值不许当好坏阈值用</b>。§3 的 V 系列答的是「能不能进池」，
 *       不是「多少算好」：V12 的 {@code PE ≤ 30} 不等于「PE 30 以下算便宜」。
 *       所以 §9.5 另立了一套**更紧的**判定带，两者不是同一个数。</li>
 * </ol>
 *
 * <p><b>一条贯穿全类的纪律（章程 §6 第 4 条）：不许把「没去取到」写成「没有」。</b>
 * 于是有第五态 {@link Stance#NOT_APPLICABLE}——它是「这个类别根本没有这一维」（指数没有财报），
 * 与 {@link Stance#INSUFFICIENT}（该有、这次没拿到）是两件事，页面上前者不渲染卡片、后者要渲染。
 */
public final class AnalysisRules {

    private AnalysisRules() {}

    /**
     * 维度/指标的判定态。四态照 {@link ScreeningRules.IndexQualification} 立法，
     * 只多一个 {@link #NOT_APPLICABLE}（章程 §9.4）。
     */
    public enum Stance {
        FAVORABLE("偏正面"),
        NEUTRAL("中性"),
        /** 必须给出 {@code reasons}。 */
        UNFAVORABLE("偏负面"),
        /** 拿不到值，或有值但判不了。必须给出 {@code reasons}。 */
        INSUFFICIENT("未确认"),
        /** 该类别没有这一维，**卡片整个不渲染**。 */
        NOT_APPLICABLE("不适用");

        private final String label;

        Stance(String label) { this.label = label; }

        public String label() { return label; }

        /** 参不参与 §9.6 的净分。{@code INSUFFICIENT} 与 {@code NOT_APPLICABLE} 既不加分也不减分。 */
        public boolean counts() {
            return this == FAVORABLE || this == NEUTRAL || this == UNFAVORABLE;
        }

        /** 净分权重：偏正面 +1 / 中性 0 / 偏负面 −1。 */
        public int score() {
            return switch (this) {
                case FAVORABLE -> 1;
                case UNFAVORABLE -> -1;
                default -> 0;
            };
        }
    }

    /**
     * 一个指标格。
     *
     * @param label   指标名，与章程 §9.5 表里的写法一致
     * @param value   原始数值，**留着给大模型材料用**（页面用 {@code display}）；缺失为 null
     * @param display 页面写法，如 {@code 26.4}、{@code 12.3%}；缺失为 null
     * @param stance  这一格的判定
     * @param basis   **凭什么这么判**：正常时是阈值带，缺失时是「为什么没取到」。
     *                页面必须显示它——没有它，格子里就只是一个孤零零的颜色
     */
    public record Metric(String label, BigDecimal value, String display, Stance stance, String basis) {}

    /**
     * 一个维度卡。
     *
     * <p><b>不变式（照 {@link ScreeningRules.IndexVerdict} 立法）：{@code stance} 为
     * {@link Stance#FAVORABLE} 或 {@link Stance#NEUTRAL} ⟺ {@code reasons} 为空。</b>
     * 另外三态都必须说清凭什么——{@code NOT_APPLICABLE} 说的是「为什么这一维不存在」。
     *
     * <p>三态的合成规则写在 {@link #card}，一句话是**负面一票否决**。
     */
    public record DimensionCard(String key, String label, Stance stance,
                                List<Metric> metrics, List<String> reasons) {

        public boolean counts() { return stance.counts(); }

        public int score() { return stance.score(); }
    }

    /** 倾向标签词表，**封闭**（章程 §9.1）。禁止词表不因这一页而放宽。 */
    public enum Tendency {
        WATCH("偏可关注"),
        WAIT("需等待"),
        HIGH_RISK("风险偏高"),
        INSUFFICIENT("数据不足（本次不给倾向）");

        private final String label;

        Tendency(String label) { this.label = label; }

        public String label() { return label; }
    }

    /**
     * 倾向结论。
     *
     * <p><b>不变式：{@code tendency} 为 {@link Tendency#INSUFFICIENT} ⟺
     * {@code countedDimensions < }{@value #MIN_COUNTED_DIMENSIONS}。</b>
     *
     * @param netScore           净分 = Σ 参判维度（偏正面 +1 / 中性 0 / 偏负面 −1）
     * @param countedDimensions  参判维度数 D。{@code INSUFFICIENT} 与 {@code NOT_APPLICABLE} 不计入
     * @param reasons            结论文案。**只复述规则算出的态与数字**，不含任何「未来会怎样」
     * @param excluded           「未计入」清单。**非空 ⟺ 至少一个维度没参判**——
     *                           不显示这一项，「指数没有财报」就会被静默当成「没问题」
     */
    public record TendencyVerdict(Tendency tendency, int netScore, int countedDimensions,
                                  List<String> reasons, List<String> excluded) {}

    /** 排雷体检的一条。{@code hit} 为真时 {@code detail} 是命中说明（内含实际值与阈值）。 */
    public record CheckupItem(String rule, String title, boolean hit, String detail) {}

    /**
     * 排雷体检。
     *
     * @param applicable 这一类标的跑不跑体检。指数类为 false——它没有行情行、没有财报字段，
     *                   对着它跑 V1–V12 只会得到一串「字段缺失」，那不是体检结果，是噪声
     * @param items      逐条结果，**只列适用的规则**
     * @param hardVetoes 硬否决理由（章程 §9.6）。**非空 ⟺ 倾向直接判「风险偏高」**
     * @param note       不适用的原因，{@code applicable == false} 时非空
     */
    public record Checkup(boolean applicable, List<CheckupItem> items,
                          List<String> hardVetoes, String note) {}

    /** 一份完整的分析结果。 */
    public record Analysis(List<DimensionCard> dimensions, Checkup checkup,
                           TendencyVerdict tendency, List<String> pendingChecks) {}

    /** 参判维度数低于它就不给倾向（章程 §9.6「不许凑数」）。 */
    public static final int MIN_COUNTED_DIMENSIONS = 3;

    // ==================================================================
    // 一、入口
    // ==================================================================

    /**
     * 对一份已经取好的查询结果做判定。
     *
     * <p><b>入参就是 {@link CodeLookupDTO}，不另取任何数</b>：深度分析的数字与页面主查询
     * 逐字同一份快照，所以两个区块之间不可能出现「同一只票两个 PE」（章程 §8）。
     *
     * @param today 判定「上市年限」用的「今天」。显式传入而不是内部读时钟——
     *              读时钟的纯函数在跨零点时会随机红
     */
    public static Analysis analyse(CodeLookupDTO dto, LocalDate today) {
        StockRow row = rowOf(dto);
        List<DimensionCard> dims = dimensions(dto, row);
        Checkup checkup = checkup(dto, row, today);
        TendencyVerdict verdict = tendency(dims, checkup);
        return new Analysis(dims, checkup, verdict, pendingChecks(dto, row));
    }

    // ==================================================================
    // 二、维度卡
    // ==================================================================

    private static List<DimensionCard> dimensions(CodeLookupDTO dto, StockRow row) {
        return switch (dto.kind()) {
            // 池内指数的裸代码（000300）取的就是代表 ETF 的行情，所以它与 INDEX_FUND 同一套。
            case STOCK -> stockDimensions(dto, row);
            case FUND, INDEX_FUND, INDEX -> fundDimensions(dto);
            // 池外指数**不取行情**，没有成交额与换手率，只剩估值与技术面两维。
            case OFF_POOL_INDEX -> indexDimensions(dto);
        };
    }

    private static List<DimensionCard> stockDimensions(CodeLookupDTO dto, StockRow row) {
        CodeLookupDTO.FundamentalsView f = fundamentals(dto);
        List<DimensionCard> out = new ArrayList<>();
        out.add(card("valuation", "估值", List.of(
                peMetric(dto.valuation()),
                pePercentileMetric(dto.valuation()),
                dividendMetric(f))));
        out.add(card("growth", "成长", List.of(
                growthMetric("营收同比", f.revenueGrowth()),
                growthMetric("净利同比", f.profitGrowth()))));
        out.add(card("quality", "盈利质量", List.of(
                roeMetric(f), grossMarginMetric(f))));
        out.add(healthCard(row, f));
        out.add(card("liquidity", "资金面", List.of(turnoverMetric(f))));
        out.add(card("technical", "技术面", List.of(
                pricePercentileMetric(dto.position(), "一年价格分位"),
                vsMa250Metric(dto.position()))));
        out.add(card("momentum", "动量", List.of(change60dMetric(f))));
        return out;
    }

    /**
     * ETF 与池内指数：**没有财报，不许因为缺 ROE 就编一个**（章程 §9.5）。
     * 成长 / 盈利质量 / 财务健康三维整个不生成卡片。
     *
     * <p>估值走的是**所跟踪指数**的 PE 与分位（{@code valuation} 那一段），不是 ETF 自己行情行里的
     * PE——ETF 那一格通常是空的，而指数口径才是这一页想说的东西；口径名与数据日随卡片给出。
     */
    private static List<DimensionCard> fundDimensions(CodeLookupDTO dto) {
        CodeLookupDTO.FundamentalsView f = fundamentals(dto);
        List<DimensionCard> out = new ArrayList<>();
        out.add(card("valuation", "估值（跟踪指数口径）", List.of(
                peMetric(dto.valuation()),
                pePercentileMetric(dto.valuation()))));
        out.add(scaleCard(f));
        out.add(card("technical", "技术面", List.of(
                pricePercentileMetric(dto.position(), "一年价格分位"),
                vsMa250Metric(dto.position()))));
        out.addAll(noFinancials());
        return out;
    }

    /** 池外指数：不取行情，所以连换手率都没有。 */
    private static List<DimensionCard> indexDimensions(CodeLookupDTO dto) {
        List<DimensionCard> out = new ArrayList<>();
        out.add(card("valuation", "估值", List.of(
                peMetric(dto.valuation()),
                pePercentileMetric(dto.valuation()))));
        out.add(card("technical", "技术面", List.of(
                pricePercentileMetric(dto.position(), "一年价格分位"),
                vsMa250Metric(dto.position()))));
        out.addAll(noFinancials());
        return out;
    }

    /**
     * ETF 与指数**没有财报**，这三维不存在。
     *
     * <p>为什么还是把它们列出来（而不是干脆不生成）：章程 §9.4 说 {@code NOT_APPLICABLE}
     * 「卡片不渲染」，指的是**不渲染成一张判定卡**。但「未计入」这件事必须留痕——
     * 否则页面上「成长 / 盈利质量 / 财务健康」三行凭空消失，用户分不清是**这个类别没有这一维**
     * 还是**我们这次没去取**（章程 §6 第 4 条）。所以它们以一句话脚注的形式出现。
     */
    private static List<DimensionCard> noFinancials() {
        String why = "指数与 ETF 没有财报，本章程不为它们编造成长 / 盈利 / 杠杆的口径";
        return List.of(
                new DimensionCard("growth", "成长", Stance.NOT_APPLICABLE, List.of(), List.of(why)),
                new DimensionCard("quality", "盈利质量", Stance.NOT_APPLICABLE, List.of(), List.of(why)),
                new DimensionCard("health", "财务健康", Stance.NOT_APPLICABLE, List.of(), List.of(why)));
    }

    /**
     * 财务健康。**金融地产整维判 {@code NOT_APPLICABLE}**——{@code f57} 对银行是存款负债比，
     * 天然 90%+，与制造业不是一回事（章程 §3 V11、§9.3）。
     *
     * <p>这里不是「剔除」，是「这一维判不了」：本节不做剔除这件事（章程 §9.3）。
     */
    private static DimensionCard healthCard(StockRow row, CodeLookupDTO.FundamentalsView f) {
        if (row.isFinancialOrRealEstate()) {
            return new DimensionCard("health", "财务健康", Stance.NOT_APPLICABLE, List.of(),
                    List.of("属金融 / 地产，资产负债率口径与制造业不可比（V11），本维不判定"));
        }
        return card("health", "财务健康", List.of(debtRatioMetric(f)));
    }

    /**
     * ETF 的「规模与流动性」。
     *
     * <p><b>只判换手率一格</b>：基金规模与成交额在本章程里**没有判定带**——
     * §3 里 V3/V4 那两个数是**准入**门槛（能不能进池），拿它当「多少算好」就是凭空造规则（章程 §9.2）。
     * 那两个数在页面的数字明细里照常展示，只是不参与判定。
     */
    private static DimensionCard scaleCard(CodeLookupDTO.FundamentalsView f) {
        return card("scale", "规模与流动性", List.of(turnoverMetric(f)));
    }

    /**
     * 由指标格合成维度态。<b>规则只有三条，按顺序判：</b>
     *
     * <ol>
     *   <li>任一指标偏负面 → 整维偏负面；</li>
     *   <li>否则任一指标偏正面 → 整维偏正面；</li>
     *   <li>否则（全是中性，或一个都没判出来）→ 中性；一个判出来的都没有 → 未确认。</li>
     * </ol>
     *
     * <p><b>为什么是「负面一票否决」而不是多数决</b>：一开始写的是多数决，写测试时发现
     * 一只 PE 为负（= 亏损）的票，估值维里「PE 偏负面、分位偏正面、股息中性」三票打平，
     * 整维被判成**中性**——一个亏损的公司，估值卡上写着「中性」。多数决在指标方向不一致时
     * 会把最该看见的那一条淹没，而这一页的用途恰恰是**把风险摆到台面上**。
     *
     * <p>顺序也不能反：先判正面的话，「PE 便宜 + 分位 95%」会整维偏正面，
     * 那是拿两个数里好看的那个当结论。
     */
    private static DimensionCard card(String key, String label, List<Metric> metrics) {
        boolean anyJudged = metrics.stream().anyMatch(m -> m.stance().counts());
        Stance stance;
        if (!anyJudged) {
            stance = Stance.INSUFFICIENT;
        } else if (metrics.stream().anyMatch(m -> m.stance() == Stance.UNFAVORABLE)) {
            stance = Stance.UNFAVORABLE;
        } else if (metrics.stream().anyMatch(m -> m.stance() == Stance.FAVORABLE)) {
            stance = Stance.FAVORABLE;
        } else {
            stance = Stance.NEUTRAL;
        }

        List<String> reasons = new ArrayList<>();
        if (stance == Stance.UNFAVORABLE) {
            for (Metric m : metrics) {
                if (m.stance() == Stance.UNFAVORABLE) {
                    reasons.add(m.label() + " " + m.display() + "（" + m.basis() + "）");
                }
            }
        } else if (stance == Stance.INSUFFICIENT) {
            if (metrics.isEmpty()) {
                reasons.add("该类别没有这一维的可判指标");
            }
            for (Metric m : metrics) {
                if (m.stance() == Stance.INSUFFICIENT) {
                    reasons.add(m.label() + "：" + m.basis());
                }
            }
        }
        return new DimensionCard(key, label, stance, List.copyOf(metrics), List.copyOf(reasons));
    }

    // ==================================================================
    // 三、指标格（每格的阈值来自章程 §9.5，文本里必须带上阈值本身）
    // ==================================================================

    private static Metric missing(String label, String why) {
        return new Metric(label, null, null, Stance.INSUFFICIENT, why);
    }

    /** PE(TTM)：**必须整段取自估值源**，不回落行情行里的 f115。 */
    private static Metric peMetric(CodeLookupDTO.ValuationView v) {
        if (v == null || !v.available() || v.peTtm() == null) {
            return missing("PE(TTM)", valuationGap(v));
        }
        BigDecimal pe = v.peTtm();
        Stance s;
        String basis;
        if (pe.signum() <= 0) {
            // 负 PE 看起来「很小很便宜」，是最典型的陷阱（章程 §3 V5），不许当便宜。
            s = Stance.UNFAVORABLE;
            basis = "≤0 判为亏损或数据异常（章程 §3 V5），负 PE 不许当便宜";
        } else if (pe.compareTo(B("20")) <= 0) {
            s = Stance.FAVORABLE;
            basis = ">0 且 ≤20 偏正面（§9.5，比 V12 的准入上限更紧）";
        } else if (pe.compareTo(B("35")) <= 0) {
            s = Stance.NEUTRAL;
            basis = "20–35 中性（§9.5）";
        } else {
            s = Stance.UNFAVORABLE;
            basis = ">35 偏负面（§9.5）";
        }
        return new Metric("PE(TTM)", pe, strip(pe), s, basis + "｜口径 " + methodOf(v)
                + "｜数据日 " + orDash(v.tradeDate()) + "｜来源 " + orDash(v.sourceLabel()));
    }

    /**
     * PE 历史分位。
     *
     * <p><b>历史跨度不足 3 年一律判 {@code INSUFFICIENT}，不许用短期窗口冒充分位</b>（章程 §9.5）。
     * 判据用 {@code historyFrom}/{@code historyTo} 的**真实跨度**，而不是 {@code historyLength}
     * 那个观测条数——条数在日频与周频下差 5 倍，拿它判「几年」必错。
     */
    private static Metric pePercentileMetric(CodeLookupDTO.ValuationView v) {
        if (v == null || !v.available() || v.pePercentile() == null) {
            return missing("PE 历史分位", valuationGap(v));
        }
        BigDecimal pct = v.pePercentile();
        LocalDate from = parseDate(v.historyFrom());
        LocalDate to = parseDate(v.historyTo());
        String spanNote = "";
        if (from != null && to != null) {
            long years = ChronoUnit.YEARS.between(from, to);
            if (years < 3) {
                return missing("PE 历史分位", "估值源只给到 " + years + " 年历史（" + v.historyFrom()
                        + " 起，共 " + v.historyLength() + " 个观测），不足 3 年，分位不可用");
            }
            spanNote = "｜历史跨度 " + years + " 年（" + v.historyFrom() + " 起）";
        } else {
            spanNote = "｜估值源未给出历史区间，未能确认跨度是否满 3 年";
        }
        Stance s;
        String basis;
        if (pct.compareTo(B("30")) <= 0) {
            s = Stance.FAVORABLE;
            basis = "≤30% 偏正面（§9.5）";
        } else if (pct.compareTo(B("70")) <= 0) {
            s = Stance.NEUTRAL;
            basis = "30–70% 中性（§9.5）";
        } else {
            s = Stance.UNFAVORABLE;
            basis = ">70% 偏负面（§9.5）";
        }
        return new Metric("PE 历史分位", pct, pct(pct), s,
                basis + "｜口径 " + methodOf(v) + spanNote + "｜历史条数 " + v.historyLength());
    }

    private static Metric dividendMetric(CodeLookupDTO.FundamentalsView f) {
        if (f.dividendYield() == null) {
            return missing("股息率", "行情源这一行没有返回股息率（f133）");
        }
        BigDecimal v = f.dividendYield();
        Stance s;
        String basis;
        if (v.compareTo(B("3")) >= 0) {
            s = Stance.FAVORABLE;
            basis = "≥3% 偏正面";
        } else if (v.compareTo(B("1")) >= 0) {
            s = Stance.NEUTRAL;
            basis = "1–3% 中性";
        } else {
            s = Stance.UNFAVORABLE;
            basis = "<1% 偏负面";
        }
        return new Metric("股息率", v, pct(v), s,
                basis + "（§9.5；章程此前未定此带，V 系列里没有这一条）｜过去分红不代表未来分红");
    }

    /**
     * 营收 / 净利同比。
     *
     * <p><b>源不返回所属报告期</b>，所以无法确认是季报还是年报——章程 §6 第 2 条明令禁止
     * 季报与年报混当同一期。这里如实写出来，**不为填这一格而新增一次外呼**。
     */
    private static Metric growthMetric(String label, BigDecimal v) {
        if (v == null) {
            return missing(label, "行情源这一行没有返回" + label + "（f41 / f46）");
        }
        Stance s;
        String basis;
        if (v.compareTo(B("10")) >= 0) {
            s = Stance.FAVORABLE;
            basis = "≥10% 偏正面（§9.5）";
        } else if (v.signum() >= 0) {
            s = Stance.NEUTRAL;
            basis = "0–10% 中性（§9.5）";
        } else {
            s = Stance.UNFAVORABLE;
            basis = "<0 偏负面（§9.5；V8 的 -15% 是准入线，比这条宽松）";
        }
        return new Metric(label, v, pct(v), s,
                basis + "｜报告期未经源给出，无法确认是季报还是年报｜基数效应会让同比失真");
    }

    private static Metric roeMetric(CodeLookupDTO.FundamentalsView f) {
        if (f.roe() == null) {
            return missing("ROE(加权)", "行情源这一行没有返回 ROE（f37）");
        }
        BigDecimal v = f.roe();
        Stance s;
        String basis;
        if (v.compareTo(B("12")) >= 0) {
            s = Stance.FAVORABLE;
            basis = "≥12% 偏正面";
        } else if (v.compareTo(B("6")) >= 0) {
            s = Stance.NEUTRAL;
            basis = "6–12% 中性";
        } else {
            s = Stance.UNFAVORABLE;
            basis = "<6% 偏负面";
        }
        return new Metric("ROE(加权)", v, pct(v), s,
                basis + "（§9.5）｜高杠杆能把 ROE 抬上去，要与资产负债率一起看");
    }

    private static Metric grossMarginMetric(CodeLookupDTO.FundamentalsView f) {
        if (f.grossMargin() == null) {
            return missing("毛利率", "行情源这一行没有返回毛利率（f49）");
        }
        BigDecimal v = f.grossMargin();
        Stance s;
        String basis;
        if (v.compareTo(B("30")) >= 0) {
            s = Stance.FAVORABLE;
            basis = "≥30% 偏正面";
        } else if (v.compareTo(B("15")) >= 0) {
            s = Stance.NEUTRAL;
            basis = "15–30% 中性";
        } else {
            s = Stance.UNFAVORABLE;
            basis = "<15% 偏负面";
        }
        return new Metric("毛利率", v, pct(v), s,
                basis + "（§9.5）｜绝对口径，未与同行业比较：不同行业毛利率差异极大");
    }

    private static Metric debtRatioMetric(CodeLookupDTO.FundamentalsView f) {
        if (f.debtRatio() == null) {
            return missing("资产负债率", "行情源这一行没有返回资产负债率（f57）");
        }
        BigDecimal v = f.debtRatio();
        Stance s;
        String basis;
        if (v.compareTo(B("40")) <= 0) {
            s = Stance.FAVORABLE;
            basis = "≤40% 偏正面（§9.5，比 V7 的 65% 准入线紧得多）";
        } else if (v.compareTo(B("60")) <= 0) {
            s = Stance.NEUTRAL;
            basis = "40–60% 中性（§9.5）";
        } else {
            s = Stance.UNFAVORABLE;
            basis = ">60% 偏负面（§9.5）";
        }
        return new Metric("资产负债率", v, pct(v), s,
                basis + "｜对银行是存款负债比，与制造业不是同一个口径");
    }

    /**
     * 换手率。<b>章程 §9.5 那三档在 0.2–0.5 与 10–15 两处留了空</b>，
     * 这里按 §3 V4 的可交易区间把它们补成中性——补空不是新立阈值，是把已有的
     * 「0.2%–15% 之外不可交易」这条线接上，basis 里如实写出来。
     */
    private static Metric turnoverMetric(CodeLookupDTO.FundamentalsView f) {
        if (f.turnoverRate() == null) {
            return missing("换手率", "行情源这一行没有返回换手率（f8）");
        }
        BigDecimal v = f.turnoverRate();
        Stance s;
        String basis;
        if (v.compareTo(B("1")) >= 0 && v.compareTo(B("5")) <= 0) {
            s = Stance.FAVORABLE;
            basis = "1–5% 偏正面（§9.5）";
        } else if (v.compareTo(B("0.2")) < 0 || v.compareTo(B("15")) > 0) {
            s = Stance.UNFAVORABLE;
            basis = "<0.2% 或 >15% 偏负面（§9.5；这两端也已落在 V4 的不可交易区间外）";
        } else {
            s = Stance.NEUTRAL;
            basis = "0.2–1% 或 5–15% 中性（§9.5 只写了 0.5–1% 与 5–10%，两侧留空处按 V4 的 0.2%–15% 可交易区间补成中性）";
        }
        return new Metric("换手率", v, pct(v), s, basis);
    }

    private static Metric pricePercentileMetric(CodeLookupDTO.PositionView p, String label) {
        if (p == null || !p.available() || p.pricePercentile() == null) {
            return missing(label, positionGap(p));
        }
        BigDecimal v = p.pricePercentile();
        Stance s;
        String basis;
        if (v.compareTo(B("40")) <= 0) {
            s = Stance.FAVORABLE;
            basis = "≤40% 偏正面（§9.5）";
        } else if (v.compareTo(B("70")) <= 0) {
            s = Stance.NEUTRAL;
            basis = "40–70% 中性（§9.5）";
        } else {
            s = Stance.UNFAVORABLE;
            basis = ">70% 偏负面（§9.5）";
        }
        return new Metric(label, v, pct(v), s,
                basis + "｜注意：这是价格位置、不是估值，它说现价在近一年高低区间里的位置，"
                        + "与 PE 分位是两件事｜日线最后一根 " + orDash(p.lastTradeDate()));
    }

    private static Metric vsMa250Metric(CodeLookupDTO.PositionView p) {
        if (p == null || !p.available() || p.vsMa250() == null) {
            return missing("对 MA250 偏离", positionGap(p));
        }
        BigDecimal v = p.vsMa250();
        Stance s;
        String basis;
        if (v.signum() >= 0) {
            s = Stance.FAVORABLE;
            basis = "≥0% 偏正面（站在年线上方，§9.5）";
        } else if (v.compareTo(B("-10")) >= 0) {
            s = Stance.NEUTRAL;
            basis = "-10–0% 中性（§9.5）";
        } else {
            s = Stance.UNFAVORABLE;
            basis = "<-10% 偏负面（§9.5）";
        }
        return new Metric("对 MA250 偏离", v, pct(v), s,
                basis + "｜均线是**过去价格的平均**，不代表支撑或阻力");
    }

    private static Metric change60dMetric(CodeLookupDTO.FundamentalsView f) {
        if (f.change60d() == null) {
            return missing("60 日涨跌", "行情源这一行没有返回 60 日涨跌（f24）");
        }
        BigDecimal v = f.change60d();
        Stance s;
        String basis;
        if (v.signum() >= 0) {
            s = Stance.FAVORABLE;
            basis = "≥0% 偏正面（§9.5）";
        } else if (v.compareTo(B("-10")) >= 0) {
            s = Stance.NEUTRAL;
            basis = "-10–0% 中性（§9.5）";
        } else {
            s = Stance.UNFAVORABLE;
            basis = "<-10% 偏负面（§9.5）";
        }
        // 「跌得多」永远不是正面理由（章程 §1）。这条要在格子里就写死，不能只写在别处。
        return new Metric("60 日涨跌", v, pct(v), s,
                basis + "｜跌得多是负面信号，不是「便宜」的理由（章程 §1）");
    }

    // ==================================================================
    // 四、排雷体检（V1–V12）
    // ==================================================================

    /**
     * 体检**按标的类别分派适用规则**。
     *
     * <p>为什么不无脑全跑：V5/V6/V7/V8 读的是财报字段（PE、PB、负债率、同比），
     * ETF 与指数的行情行里**根本没有这几个数**，全跑的结果是每只 ETF 都稳定拿到
     * 四条「字段缺失」——那不是体检结果，是一串噪声，还会把每一只 ETF 无差别地
     * 推向「风险偏高」。V9（股价 < 3 元）对 ETF 是「净值 < 3 元」，同样不成立。
     *
     * <p>个股跑全集；ETF 只跑不依赖财报的那五条；指数类没有行情行，整体不适用。
     */
    private static Set<String> applicableRules(CodeLookupDTO.Kind kind) {
        return switch (kind) {
            case STOCK -> Set.of("V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "V10", "V11", "V12");
            case FUND, INDEX_FUND, INDEX -> Set.of("V1", "V2", "V3", "V4", "V10");
            case OFF_POOL_INDEX -> Set.of();
        };
    }

    /** 规则名照章程 §3 的表头写，不另起名字，否则页面上对不上号。 */
    private static final Map<String, String> RULE_TITLES = Map.ofEntries(
            Map.entry("V1", "退市 / ST / 次新"),
            Map.entry("V2", "上市年限"),
            Map.entry("V3", "市值（对 ETF 是基金规模）"),
            Map.entry("V4", "流动性"),
            Map.entry("V5", "亏损"),
            Map.entry("V6", "资不抵债 / 高 PB"),
            Map.entry("V7", "高杠杆"),
            Map.entry("V8", "业绩崩塌"),
            Map.entry("V9", "低价股"),
            Map.entry("V10", "停牌 / 不可交易"),
            Map.entry("V11", "金融地产口径不可比"),
            Map.entry("V12", "绝对估值上限"));

    private static final List<String> RULE_ORDER = List.of(
            "V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "V10", "V11", "V12");

    private static Checkup checkup(CodeLookupDTO dto, StockRow row, LocalDate today) {
        Set<String> applicable = applicableRules(dto.kind());
        if (applicable.isEmpty()) {
            return new Checkup(false, List.of(), List.of(),
                    "该标的没有行情行，章程 §3 的 V1–V12 逐条都读不到字段，整体不适用");
        }

        // 体检按**稳健档**跑。这不是替用户选档：本页没有档位这个输入，
        // 而稳健档的阈值严格强于成长档，用严的那一套做体检是保守的一侧，
        // 且页面上写明了用的是哪一档（见 note）。
        List<ScreeningRules.Veto> vetoes = ScreeningRules.vetoes(
                row, ScreeningRules.Bucket.STEADY, ScreenerParams.defaults(), today);

        Map<String, List<String>> byRule = new LinkedHashMap<>();
        for (ScreeningRules.Veto v : vetoes) {
            byRule.computeIfAbsent(v.rule(), k -> new ArrayList<>()).add(v.label());
        }

        List<CheckupItem> items = new ArrayList<>();
        for (String rule : RULE_ORDER) {
            if (!applicable.contains(rule)) continue;
            List<String> hits = byRule.getOrDefault(rule, List.of());
            items.add(new CheckupItem(rule, RULE_TITLES.get(rule), !hits.isEmpty(),
                    String.join("；", hits)));
        }

        List<String> hard = hardVetoes(row, items);
        String note = "体检按章程 §3 的稳健档阈值执行（本页没有档位输入，取严的一档）"
                + (applicable.size() < RULE_ORDER.size()
                ? "；ETF / 指数的行情行没有财报字段，V5–V9、V11、V12 对本类别不适用，未列出"
                : "");
        return new Checkup(true, List.copyOf(items), List.copyOf(hard), note);
    }

    /**
     * 硬否决：**只有这四条会把倾向直接压成「风险偏高」**（章程 §9.6）。
     *
     * <p><b>为什么不直接拿 {@code vetoes()} 的返回值当硬否决</b>：那套输出把「缺值」也记成命中
     * （「市净率缺失」也是一条 V6），而在本节里「没取到」必须是 {@code INSUFFICIENT}，
     * 不是「踩线」。所以这里按**实际值**重判一遍——判据与 V1/V5/V10 逐条对应，
     * 只在「有值且越线」时才算数。
     *
     * <p>V1 里的**次新前缀不算硬否决**：§9.6 列的是「ST / 退市 / 负 PE / 停牌」，
     * 次新股只是交易历史短，它已经由「参判维度不足 3 个」那条路兜住。
     */
    private static List<String> hardVetoes(StockRow row, List<CheckupItem> items) {
        List<String> out = new ArrayList<>();
        for (CheckupItem item : items) {
            switch (item.rule()) {
                case "V1" -> {
                    String name = row.getName();
                    if (name != null && !name.isBlank() && isStOrDelisting(name)) {
                        out.add("V1 命中（硬否决）：" + name + " 名称带 ST / 退市标记");
                    }
                }
                case "V5" -> {
                    BigDecimal pe = row.getPeTtm();
                    if (pe != null && pe.signum() <= 0) {
                        out.add("V5 命中（硬否决）：PE(TTM) " + strip(pe) + " ≤ 0，亏损或数据异常");
                    }
                }
                case "V10" -> {
                    BigDecimal price = row.getPrice();
                    BigDecimal amount = row.getAmount();
                    // **必须是「有值且为 0」，不是「null」。** V10 在 vetoes() 里把 null 也记成命中
                    // （「成交额为 0，疑似停牌」），那是筛选器的口径——对它来说取不到就没法交易。
                    // 但本节里 null 的含义是「这次没取到」（腾讯兜底只给价格与成交额里的价格、
                    // 估值源抽风时整块为空），把它读成「停牌」就是拿缺数据编结论（章程 §6）。
                    if (price != null && price.signum() <= 0) {
                        out.add("V10 命中（硬否决）：最新价为 0，疑似停牌");
                    }
                    if (amount != null && amount.signum() <= 0) {
                        out.add("V10 命中（硬否决）：成交额为 0，疑似停牌");
                    }
                }
                default -> { }
            }
        }
        return out;
    }

    /** 与 §3 V1 的判据逐字一致。留在这里是因为硬否决要在**有值**时才触发，见 {@link #hardVetoes}。 */
    static boolean isStOrDelisting(String name) {
        String upper = name.replace(" ", "").toUpperCase();
        return upper.startsWith("ST") || upper.startsWith("*ST") || upper.contains("退");
    }

    // ==================================================================
    // 五、倾向合成（章程 §9.6）
    // ==================================================================

    private static TendencyVerdict tendency(List<DimensionCard> dims, Checkup checkup) {
        int counted = 0;
        int net = 0;
        List<String> excluded = new ArrayList<>();
        List<String> lines = new ArrayList<>();

        for (DimensionCard d : dims) {
            if (d.counts()) {
                counted++;
                net += d.score();
                lines.add("「" + d.label() + "」" + d.stance().label() + "（"
                        + summary(d) + "）");
            } else {
                // 「未计入」必须显示出来：不写，「指数没有财报」就会被静默当成「没问题」（§9.6 第 1 条）。
                excluded.add("「" + d.label() + "」未计入：" + d.stance().label()
                        + (d.reasons().isEmpty() ? "" : "（" + String.join("；", d.reasons()) + "）"));
            }
        }

        List<String> reasons = new ArrayList<>();
        for (String hard : checkup.hardVetoes()) {
            reasons.add(hard);
        }
        reasons.addAll(lines);
        reasons.add("净分 " + net + "（偏正面 +1 / 中性 0 / 偏负面 −1，共 " + counted + " 个维度计入）");

        Tendency t;
        if (!checkup.hardVetoes().isEmpty()) {
            // 硬否决命中就不看净分：一条 ST 不该被十个「偏正面」抵掉。
            t = Tendency.HIGH_RISK;
        } else if (counted < MIN_COUNTED_DIMENSIONS) {
            t = Tendency.INSUFFICIENT;
            reasons.add("参判维度只有 " + counted + " 个，少于 " + MIN_COUNTED_DIMENSIONS
                    + " 个，按章程 §9.6 不给倾向——凑数比不给更危险");
        } else if (net >= 2) {
            t = Tendency.WATCH;
        } else if (net <= -2) {
            t = Tendency.HIGH_RISK;
        } else {
            t = Tendency.WAIT;
        }
        return new TendencyVerdict(t, net, counted, List.copyOf(reasons), List.copyOf(excluded));
    }

    /** 一个维度参判的指标，压成一行「PE(TTM) 26.4 / PE 历史分位 58.2%」。 */
    private static String summary(DimensionCard d) {
        List<String> parts = new ArrayList<>();
        for (Metric m : d.metrics()) {
            if (m.stance().counts()) {
                parts.add(m.label() + " " + m.display());
            }
        }
        return String.join(" / ", parts);
    }

    // ==================================================================
    // 六、「需要你人工核实的」清单（章程 §9.12：提问，不回答）
    // ==================================================================

    private static List<String> pendingChecks(CodeLookupDTO dto, StockRow row) {
        List<String> out = new ArrayList<>();
        switch (dto.kind()) {
            case STOCK -> {
                out.add("需核实：营收与净利同比所属的报告期（源未给出，无法确认是季报还是年报）是否有季节性或基数效应？");
                out.add("需核实：该行业近 30 天是否有落地细则 / 补贴文件，是否影响公司主营？");
                out.add("需核实：最近一期业绩说明会是否给出下一季度指引？");
                out.add("需核实：是否存在股权质押、限售解禁、大股东减持计划？");
                out.add("需核实：商誉规模与减值风险、是否有未决诉讼或监管问询？");
                if (row.isFinancialOrRealEstate()) {
                    out.add("需核实：属金融 / 地产，资产负债率口径与制造业不可比（V11），"
                            + "是否另有不良率 / 拨备覆盖率 / 核心一级资本充足率可看？");
                }
                if (row.isIndustryUnknown()) {
                    out.add("需核实：行业字段缺失，该公司主营究竟属于哪个行业？");
                }
            }
            case FUND, INDEX_FUND, INDEX -> {
                out.add("需核实：该 ETF 的折溢价率、跟踪误差与费率水平？");
                out.add("需核实：成份股集中度与前十大权重占比？");
                out.add("需核实：该行业 / 主题近 30 天是否有落地细则或规划文件？");
            }
            case OFF_POOL_INDEX -> {
                out.add("需核实：成份股构成与权重分布？");
                out.add("需核实：该行业 / 主题近 30 天是否有落地细则或规划文件？");
            }
        }
        // ETF 与指数的基金规模 / 成交额在章程里没有判定带，那就只能问，不能判（§9.2）。
        if (dto.kind() != CodeLookupDTO.Kind.STOCK) {
            out.add("需核实：基金规模与成交额是否够大（本章程未给这两格定判定带，只展示不判定）？");
        }
        out.add("需核实：以上数字与「低估精选」的池内分位不是同一口径，两者不可互相印证？");
        return List.copyOf(out);
    }

    // ==================================================================
    // 七、把 DTO 还原成 StockRow（只读，专供体检）
    // ==================================================================

    /**
     * 由 {@link CodeLookupDTO} 还原一个 {@link StockRow}，**只为跑 {@link ScreeningRules#vetoes}**。
     *
     * <p>为什么不干脆改造 {@code CodeLookupService} 把原始 {@code StockRow} 一起传出来：
     * 那会让主查询的缓存被迫留着行情行（缓存只该存返回体），而深度分析**必须**走那条 300 秒缓存——
     * 不走就得每次点击重新外呼行情源（章程 §9.7、§9.8）。
     *
     * <p>还原是**逐字段的**，不是有损的：{@link CodeLookupDTO.QuoteView} 与
     * {@link CodeLookupDTO.FundamentalsView} 合起来装着 {@code StockRow} 里除 {@code market}
     * 与 {@code etf} 之外的每一个字段，而后两者 {@code vetoes()} 一个都不读。
     * <b>这条是这份映射唯一需要守的东西</b>——{@code StockRow} 以后要是加了 {@code vetoes()}
     * 会读的新字段，这里必须同步，否则体检会静默地拿 null 去判。
     */
    static StockRow rowOf(CodeLookupDTO dto) {
        CodeLookupDTO.QuoteView q = dto.quote();
        CodeLookupDTO.FundamentalsView f = fundamentals(dto);
        StockRow row = new StockRow();
        row.setCode(dto.resolvedCode());
        row.setName(dto.name());
        if (q != null) {
            row.setIndustry(q.industry());
            row.setPrice(q.price());
            row.setPctChange(q.pctChange());
            row.setTotalMarketCap(q.totalMarketCap());
            row.setPb(q.pb());
            row.setPeTtm(q.peTtm());
        }
        row.setAmount(f.amount());
        row.setTurnoverRate(f.turnoverRate());
        row.setFloatMarketCap(f.floatMarketCap());
        row.setRoe(f.roe());
        row.setRevenueGrowth(f.revenueGrowth());
        row.setProfitGrowth(f.profitGrowth());
        row.setGrossMargin(f.grossMargin());
        row.setDebtRatio(f.debtRatio());
        row.setDividendYield(f.dividendYield());
        row.setBps(f.bps());
        row.setChange60d(f.change60d());
        row.setYtdChange(f.ytdChange());
        row.setListDate(parseDate(f.listDate()));
        // 行业未知时 V11 不会命中，但章程 §3 要求把「行业不可确认」标出来，靠的就是 industry 为 null。
        return row;
    }

    private static CodeLookupDTO.FundamentalsView fundamentals(CodeLookupDTO dto) {
        return dto.fundamentals() == null
                ? CodeLookupDTO.FundamentalsView.noQuote()
                : dto.fundamentals();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static BigDecimal B(String v) {
        return new BigDecimal(v);
    }

    private static String strip(BigDecimal v) {
        return ScreeningRules.strip(v);
    }

    private static String pct(BigDecimal v) {
        return strip(v) + "%";
    }

    private static String orDash(String v) {
        return v == null || v.isBlank() ? "未给出" : v;
    }

    private static String methodOf(CodeLookupDTO.ValuationView v) {
        return v.percentileMethod() == null ? "口径未标明" : v.percentileMethod();
    }

    private static String valuationGap(CodeLookupDTO.ValuationView v) {
        if (v == null || !v.available()) {
            List<String> notes = v == null ? List.of() : v.notes();
            return notes.isEmpty() ? "本次没有取到估值口径" : String.join("；", notes);
        }
        return "估值源这一次没有给出这一格";
    }

    private static String positionGap(CodeLookupDTO.PositionView p) {
        if (p == null || !p.available()) {
            List<String> notes = p == null ? List.of() : p.notes();
            return notes.isEmpty() ? "本次没有取到日线，价格位置无从算起" : String.join("；", notes);
        }
        return "这一格未算出（多为日线根数不足，均线与分位需要更长的序列）";
    }

    private static LocalDate parseDate(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return LocalDate.parse(v.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
