package com.ai.daily.screener.v2.ai;

import com.ai.daily.screener.v2.FactsSnapshot;
import com.ai.daily.screener.v2.analysis.AnalysisFixtures;
import com.ai.daily.screener.v2.analysis.AnalysisResult;
import com.ai.daily.screener.v2.analysis.InvestmentAnalysisService;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证据包：编号唯一、覆盖评分/区间/价格、来源随数字一起走。
 */
class EvidencePackBuilderTest {

    @Test
    void packContainsEveryDimensionMetricWithUniqueIds() {
        FactsSnapshot facts = AnalysisFixtures.healthyStock();
        AnalysisResult analysis = InvestmentAnalysisService.analyze(
                facts, AnalysisFixtures.TODAY);
        EvidencePack pack = EvidencePackBuilder.build(facts, analysis);

        assertThat(pack.snapshotId()).isEqualTo(facts.snapshotId());
        assertThat(pack.rulesVersion()).isEqualTo(InvestmentAnalysisService.RULES_VERSION);
        assertThat(pack.ids()).contains("ev:val:pe_ttm", "ev:val:pe_percentile",
                "ev:grow:revenue_yoy", "ev:qual:roe", "ev:health:debt",
                "ev:tech:ma250", "ev:verdict:score", "ev:quote:price");

        Set<String> seen = new HashSet<>();
        assertThat(pack.items()).allSatisfy(item -> {
            assertThat(item.id()).isNotBlank();
            assertThat(seen.add(item.id())).as("重复编号 " + item.id()).isTrue();
        });
    }

    @Test
    void sourceAndAsOfFollowTheEvidencePrefix() {
        FactsSnapshot facts = AnalysisFixtures.healthyStock();
        AnalysisResult analysis = InvestmentAnalysisService.analyze(
                facts, AnalysisFixtures.TODAY);
        EvidencePack pack = EvidencePackBuilder.build(facts, analysis);

        Evidence valuation = pack.find("ev:val:pe_ttm").orElseThrow();
        assertThat(valuation.source()).isEqualTo("eastmoney-valueanalysis");
        assertThat(valuation.asOf()).isEqualTo(AnalysisFixtures.PRICE_AS_OF.toString());

        Evidence technical = pack.find("ev:tech:ma250").orElseThrow();
        assertThat(technical.source()).isEqualTo("eastmoney");
        assertThat(technical.asOf()).isEqualTo(AnalysisFixtures.PRICE_AS_OF.toString());

        Evidence quote = pack.find("ev:quote:price").orElseThrow();
        assertThat(quote.source()).isEqualTo("东方财富");
        assertThat(quote.value()).isEqualByComparingTo("20");
    }

    @Test
    void buyZoneValuesBecomeEvidenceOnlyWhenTheyExist() {
        FactsSnapshot facts = AnalysisFixtures.healthyStock();
        AnalysisResult analysis = InvestmentAnalysisService.analyze(
                facts, AnalysisFixtures.TODAY);
        EvidencePack pack = EvidencePackBuilder.build(facts, analysis);

        assertThat(analysis.buyZone().status()).isNotEqualTo("WITHHELD");
        assertThat(pack.contains("ev:buyzone:low")).isTrue();
        assertThat(pack.contains("ev:buyzone:high")).isTrue();
        assertThat(pack.contains("ev:buyzone:stop")).isTrue();
    }

    @Test
    void materialListsEveryEvidenceOnItsOwnLine() {
        FactsSnapshot facts = AnalysisFixtures.healthyStock();
        AnalysisResult analysis = InvestmentAnalysisService.analyze(
                facts, AnalysisFixtures.TODAY);
        EvidencePack pack = EvidencePackBuilder.build(facts, analysis);

        String material = pack.material();
        assertThat(material).contains("只允许引用下列编号");
        long lines = material.lines().filter(line -> line.startsWith("- ")).count();
        assertThat(lines).isEqualTo(pack.items().size());
    }
}
