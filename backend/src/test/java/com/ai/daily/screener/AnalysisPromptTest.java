package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnalysisPrompt} 的拼装。
 *
 * <p>这个类测的是**材料里有没有那句话**。看起来像在测字符串，其实在测四项契约：
 * 材料里不写数据时刻，模型就只能编一个；不写缺口，模型就会用常识补全；
 * 不写「只能复述」，它就会把本地算的结论推翻；不截断标明，它会把截断处当成材料就这么多。
 */
class AnalysisPromptTest {

    private static NewsItem news(NewsItem.Category category, String title, String source,
                                 LocalDate day, String url) {
        return new NewsItem(category, "origin", "来源标签", title, source, day, url, null);
    }

    private static NewsDigestService.Digest digest(List<NewsItem> notices, List<NewsItem> news,
                                                   List<NewsItem> policies) {
        return new NewsDigestService.Digest(notices, news, policies, Map.of(
                NewsItem.Category.NOTICE, "公告：源回 0 条，本次取到 0 条",
                NewsItem.Category.NEWS, "新闻：源回 2 条，本次取到 2 条",
                NewsItem.Category.POLICY, "政策：源回 1 条，本次取到 1 条"));
    }

    private static CodeLookupAnalysisDTO dto() {
        return new CodeLookupAnalysisDTO("300274", "0.300274", "阳光电源", "STOCK", "深市股票",
                "2026-10-15T10:00:00", false,
                List.of(CodeLookupAnalysisDTO.CALIBER_ABSOLUTE),
                List.of(new AnalysisRules.DimensionCard("valuation", "估值",
                        AnalysisRules.Stance.FAVORABLE,
                        List.of(new AnalysisRules.Metric("PE(TTM)", null, "18.0",
                                        AnalysisRules.Stance.FAVORABLE, ">0 且 ≤20"),
                                new AnalysisRules.Metric("股息率", null, null,
                                        AnalysisRules.Stance.INSUFFICIENT, "源未返回该字段")),
                        List.of()),
                        new AnalysisRules.DimensionCard("growth", "成长",
                                AnalysisRules.Stance.NOT_APPLICABLE,
                                List.of(new AnalysisRules.Metric("营收同比", null, null,
                                        AnalysisRules.Stance.NOT_APPLICABLE, "这一类没有财报")),
                                List.of("这一类标的没有财报"))),
                new AnalysisRules.Checkup(true,
                        List.of(new AnalysisRules.CheckupItem("V5", "是否亏损", true, "PE 为负"),
                                new AnalysisRules.CheckupItem("V12", "估值上限", false, "PE 18 ≤ 30")),
                        List.of(), null),
                new AnalysisRules.TendencyVerdict(AnalysisRules.Tendency.WAIT, 1, 4,
                        List.of("当前数据呈现为需等待"), List.of("成长（不适用）")),
                List.of("需核实：该行业近 30 天是否有落地细则？"),
                List.of(),
                CodeLookupAnalysisDTO.DISCLAIMER);
    }

    private static String material() {
        return AnalysisPrompt.material(dto(), digest(
                List.of(),
                List.of(news(NewsItem.Category.NEWS, "阳光电源等在内蒙古成立智算科技公司",
                        "证券时报", LocalDate.of(2026, 10, 15), "https://a/1")),
                List.of(news(NewsItem.Category.POLICY, "国家能源局印发新型储能发展行动方案",
                        "国家能源局", LocalDate.of(2026, 10, 14), "https://b/1"))), 6000);
    }

    // ------------------------------------------------------------------
    // 材料
    // ------------------------------------------------------------------

    @Test
    void theDataMomentIsInTheMaterialBecauseEveryNumberQuotedMustCiteIt() {
        // 系统提示词第 2 条要求「引用数字时写明数据时刻」。材料里没有它，
        // 模型只能编一个时刻或者干脆不写——两种都比不显示更糟。
        assertThat(material()).contains("【数据时刻】2026-10-15T10:00:00");
        assertThat(material()).contains("本次实时取到")
                .as("fromCache=false 时要说清这是刚取到的一份")
                .doesNotContain("取自进程内缓存");
    }

    @Test
    void theMaterialSaysWhenTheNumbersCameFromACachedSnapshot() {
        // 反过来也要说。缓存命中的那份可能是几分钟前的，而系统提示词要求模型
        // 「引用数字时写明数据时刻」——不告诉它这是缓存，它会把一个旧时刻说成「当前」。
        CodeLookupAnalysisDTO base = dto();
        CodeLookupAnalysisDTO cached = new CodeLookupAnalysisDTO(base.code(), base.resolvedCode(),
                base.name(), base.kind(), base.kindLabel(), base.snapshotAt(), true, base.notes(),
                base.dimensions(), base.checkup(), base.tendency(), base.pendingChecks(),
                base.news(), base.disclaimer());

        assertThat(AnalysisPrompt.material(cached, digest(List.of(), List.of(), List.of()), 6000))
                .contains("取自进程内缓存");
    }

    @Test
    void theRuleVerdictIsMarkedAsSomethingTheModelMayOnlyRestate() {
        String text = material();

        assertThat(text).contains("【规则判定（本地程序算好的，只能复述，不许改动）】");
        assertThat(text).contains("【倾向（本地规则算出，不许推翻）】需等待");
        assertThat(text).contains("净分 +1");
        assertThat(text).contains("参判维度 4");
    }

    @Test
    void aMetricThatWasNotFetchedIsWrittenAsMissingWithItsReason() {
        // 「未取到」不能省：省了的话模型看到估值那一栏有 PE 有股息率的名字却不知道
        // 股息率是空的，会顺手补一个行业平均。
        String text = material();

        assertThat(text).contains("股息率 = 未取到");
        assertThat(text).contains("源未返回该字段");
    }

    @Test
    void aDimensionThatDoesNotApplyIsMarkedAsSuchAndIsNotCountedAsAGap() {
        // **NOT_APPLICABLE 不是缺口。** 把它算进缺口清单，模型会对着一只指数写
        // 「缺少财务数据」——而指数字段本来就该缺，那句话是错的。
        String text = material();

        assertThat(text).contains("成长 [不适用]（未计入倾向）");
        assertThat(text).contains("规则判定有 1 格没取到");
        assertThat(text).contains("估值 · 股息率");
        // 缺口清单里**不能出现**不适用那一维的名字。
        assertThat(text).doesNotContain("成长 · 营收同比：这一类没有财报");
    }

    @Test
    void theExcludedListIsCarriedBecauseOtherwiseMissingDataLooksLikeNoProblem() {
        assertThat(material()).contains("未计入本次倾向：成长（不适用）");
    }

    @Test
    void hitCheckupRulesAreListedAndAZeroHitRunSaysWhatThatDoesAndDoesNotMean() {
        String text = material();

        assertThat(text).contains("[命中] V5 是否亏损：PE 为负");
        // 没命中的那条规则不出现在材料里——列出来只会让模型以为它是结论的一部分
        assertThat(text).doesNotContain("V12");
    }

    @Test
    void aCheckupWithNoHitsSaysItIsNotACleanBillOfHealth() {
        CodeLookupAnalysisDTO base = dto();
        CodeLookupAnalysisDTO none = new CodeLookupAnalysisDTO(base.code(), base.resolvedCode(),
                base.name(), base.kind(), base.kindLabel(), base.snapshotAt(), false, base.notes(),
                base.dimensions(),
                new AnalysisRules.Checkup(true,
                        List.of(new AnalysisRules.CheckupItem("V5", "是否亏损", false, "PE 为正")),
                        List.of(), null),
                base.tendency(), base.pendingChecks(), base.news(), base.disclaimer());

        String text = AnalysisPrompt.material(none, digest(List.of(), List.of(), List.of()), 6000);

        assertThat(text).contains("这不代表没问题");
    }

    @Test
    void theNewsBlocksCarryTitleSourceAndDateButNeverABody() {
        String text = material();

        assertThat(text).contains("【公告】本次没有可列出的条目");
        assertThat(text).contains("【新闻】共 1 条");
        assertThat(text).contains("2026-10-15 证券时报：阳光电源等在内蒙古成立智算科技公司");
        assertThat(text).contains("【政策】共 1 条");
        assertThat(text).contains("2026-10-14 国家能源局：国家能源局印发新型储能发展行动方案");
    }

    @Test
    void theGapListCarriesBothTheRuleGapsAndThePerSourceNotes() {
        // 两半都要有：规则层说「哪几格没取到」，新闻层说「每路源各回了什么」。
        // 只有一半的话，模型会在另一半上用常识补。
        String text = material();

        assertThat(text).contains("新闻：源回 2 条，本次取到 2 条");
        assertThat(text).contains("公告：源回 0 条，本次取到 0 条");
        assertThat(text).contains("需核实：该行业近 30 天是否有落地细则？");
    }

    @Test
    void aNoticeWithACategoryShowsItSoHoldingEventsHaveAProvenance() {
        CodeLookupAnalysisDTO base = dto();
        NewsDigestService.Digest d = digest(List.of(new NewsItem(NewsItem.Category.NOTICE,
                "eastmoney-notice", "东财公告", "阳光电源:关于控股股东部分股份解除质押的公告", null,
                LocalDate.of(2026, 10, 15), "https://n/1", "股份质押、冻结")),
                List.of(), List.of());

        String text = AnalysisPrompt.material(base, d, 6000);

        // 公告这个源的条目 source 常常是空的（公告没有「媒体名」），
        // 这时退回源标签，不能印一个 "null"，也不能因此丢掉分类——
        // 分类是「这是不是持股类事件」的唯一线索。
        assertThat(text).contains("2026-10-15 东财公告：阳光电源:关于控股股东部分股份解除质押的公告"
                + "（分类：股份质押、冻结）");
    }

    // ------------------------------------------------------------------
    // 截断
    // ------------------------------------------------------------------

    @Test
    void everythingUnderTheLimitPassesThroughUntouched() {
        String text = "短材料";
        assertThat(AnalysisPrompt.truncate(text, 100)).isEqualTo(text);
        assertThat(AnalysisPrompt.truncate(text, text.length())).isEqualTo(text);
    }

    @Test
    void truncationLeavesAMarkerSayingWhatWasLost() {
        // 不留标记的话，模型会把「材料到此为止」当成「材料就这么多」——
        // 而**恰恰是被截掉的那几路源**是它最该说「材料未提供」的地方。
        String text = AnalysisPrompt.truncate("x".repeat(500), 200);

        assertThat(text).contains("【材料已截断】");
        assertThat(text).contains("材料未提供");
        assertThat(text.length()).isLessThanOrEqualTo(200);
    }

    @Test
    void theMarkerItselfFitsInsideTheLimit() {
        // 截断后拼上标记会超出上限的话，那个「上限」就是假的——
        // 而这里是照着字符数扣了 token 预算的。
        for (int limit : new int[]{50, 120, 200, 1000}) {
            assertThat(AnalysisPrompt.truncate("y".repeat(5000), limit).length())
                    .as("上限 %d", limit).isLessThanOrEqualTo(limit);
        }
    }

    @Test
    void aRealMaterialIsTruncatedWhenTheBudgetIsSmall() {
        String text = AnalysisPrompt.material(dto(), digest(List.of(), List.of(), List.of()), 300);

        assertThat(text).contains("【材料已截断】");
        // 头部那几行是最要紧的，必须留在截断之前
        assertThat(text).contains("【标的】300274 阳光电源");
        assertThat(text).contains("【数据时刻】");
    }

    // ------------------------------------------------------------------
    // 系统提示词
    // ------------------------------------------------------------------

    @Test
    void theSystemPromptForbidsPredictionsTargetsAndPositionAdvice() {
        // 章程 §7 明确不许推断未来价格与业绩，也不许给目标价、买卖、仓位建议。
        // 而预测正是模型的默认行为——不逐条禁掉，它一定会写。
        String system = AnalysisPrompt.system();

        assertThat(system).contains("禁止预测未来股价、涨跌、业绩");
        assertThat(system).contains("目标价");
        assertThat(system).contains("仓位");
    }

    @Test
    void theSystemPromptForcesFactsAndInferencesApartAndFixesTheSections() {
        String system = AnalysisPrompt.system();

        assertThat(system).contains("推断：");
        assertThat(system).contains("材料未提供");
        for (String section : List.of("## 一句话结论", "## 各维度怎么摆", "## 主要依据",
                "## 反面风险", "## 需要你人工核实")) {
            assertThat(system).contains(section);
        }
    }

    @Test
    void theSystemPromptTellsTheModelNotToOverturnTheLocalRules() {
        // 不写这一条，模型会「综合来看其实更乐观」，把本地算的结论推翻，
        // 而页面上两张卡会互相矛盾——一张说需等待，一张说看好。
        assertThat(AnalysisPrompt.system()).contains("只能复述，不得改动");
    }
}
