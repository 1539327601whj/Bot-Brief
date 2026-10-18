package com.ai.daily.screener.v2.ai;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 四道闸门：JSON 结构、证据编号、数字来源、禁用表达。 */
class AiAnalysisValidatorTest {

    private static final EvidencePack PACK = new EvidencePack(
            "示例科技（STOCK:600519）", "snap-1", "code-lookup-v2-p4",
            List.of(
                    new Evidence("ev:val:pe_ttm", "PE(TTM)", "14.5",
                            new BigDecimal("14.5"), "东方财富估值分析", "2026-10-16",
                            "最近一个正 PE 点"),
                    new Evidence("ev:verdict:score", "综合得分", "62",
                            new BigDecimal("62"), "规则计算", "2026-10-16",
                            "六维加权后按覆盖率收缩"),
                    new Evidence("ev:tech:ma250", "收盘/MA250", "8.30%",
                            new BigDecimal("8.30"), "eastmoney", "2026-10-16",
                            "相对年线的偏离")));

    private static String json(String text, String... ids) {
        StringBuilder quoteIds = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                quoteIds.append(',');
            }
            quoteIds.append('"').append(ids[i]).append('"');
        }
        return "{\"sections\":[{\"heading\":\"结论\",\"text\":\"" + text
                + "\",\"evidenceIds\":[" + quoteIds + "]}]}";
    }

    @Test
    void validJsonWithKnownEvidenceAndNumbersPasses() {
        AiAnalysisValidator.Validation result = AiAnalysisValidator.validate(
                json("PE 为 14.5，综合得分 62。", "ev:val:pe_ttm", "ev:verdict:score"), PACK);

        assertThat(result.ok()).isTrue();
        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().get(0).evidenceIds())
                .containsExactly("ev:val:pe_ttm", "ev:verdict:score");
    }

    @Test
    void markdownFenceIsTolerated() {
        String raw = "```json\n" + json("综合得分 62。", "ev:verdict:score") + "\n```";
        assertThat(AiAnalysisValidator.validate(raw, PACK).ok()).isTrue();
    }

    @Test
    void unknownEvidenceIdIsRejected() {
        AiAnalysisValidator.Validation result = AiAnalysisValidator.validate(
                json("综合得分 62。", "ev:val:not_real"), PACK);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("证据包之外");
    }

    @Test
    void numberNotInEvidenceIsRejected() {
        AiAnalysisValidator.Validation result = AiAnalysisValidator.validate(
                json("净利润增长 37%。", "ev:verdict:score"), PACK);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("37");
    }

    @Test
    void equivalentNumberFormattingIsAccepted() {
        // 14.50 与 14.5 是同一个事实；按字符串比会误杀。
        assertThat(AiAnalysisValidator.validate(
                json("PE 为 14.50。", "ev:val:pe_ttm"), PACK).ok()).isTrue();
    }

    @Test
    void bannedWordsAreRejected() {
        AiAnalysisValidator.Validation result = AiAnalysisValidator.validate(
                json("给出目标价后建议持有。", "ev:verdict:score"), PACK);

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).contains("禁用表达");
    }

    @Test
    void probabilityAndTargetPriceShapesAreRejected() {
        assertThat(AiAnalysisValidator.validate(
                json("上涨概率 70%。", "ev:verdict:score"), PACK).ok()).isFalse();
        assertThat(AiAnalysisValidator.validate(
                json("预计涨到 30 元。", "ev:verdict:score"), PACK).ok()).isFalse();
    }

    @Test
    void malformedOrIncompleteJsonIsRejected() {
        assertThat(AiAnalysisValidator.validate("不是 JSON", PACK).ok()).isFalse();
        assertThat(AiAnalysisValidator.validate("{\"sections\":[]}", PACK).ok()).isFalse();
        assertThat(AiAnalysisValidator.validate(
                "{\"sections\":[{\"heading\":\"结论\",\"text\":\"还行\"}]}", PACK).ok())
                .isFalse();
        assertThat(AiAnalysisValidator.validate(
                "{\"sections\":[{\"heading\":\"结论\",\"text\":\"还行\",\"evidenceIds\":[]}]}",
                PACK).ok()).isFalse();
    }

    @Test
    void blankOutputIsRejected() {
        assertThat(AiAnalysisValidator.validate(null, PACK).ok()).isFalse();
        assertThat(AiAnalysisValidator.validate("  ", PACK).ok()).isFalse();
    }
}
