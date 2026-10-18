package com.ai.daily.screener.v2.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 三情景：触发条件、唯一触发、失效条件与禁用词。 */
class ScenarioGeneratorTest {

    @Test
    void healthyStockTriggersExactlyOneScenario() {
        AnalysisResult result = InvestmentAnalysisService.analyze(
                AnalysisFixtures.healthyStock(), AnalysisFixtures.TODAY);

        assertThat(result.scenarios()).hasSize(3);
        assertThat(result.scenarios()).extracting(Scenario::key)
                .containsExactly("bull", "base", "bear");
        long triggered = result.scenarios().stream().filter(Scenario::triggered).count();
        assertThat(triggered).isEqualTo(1);
    }

    @Test
    void hardFlagsForceTheBearScenarioEvenWhenTheScoreIsHigh() {
        AnalysisResult result = InvestmentAnalysisService.analyze(
                AnalysisFixtures.lossStock(), AnalysisFixtures.TODAY);

        assertThat(result.scenarios()).hasSize(3);
        assertThat(result.scenarios()).anySatisfy(scenario -> {
            if ("bear".equals(scenario.key())) {
                assertThat(scenario.triggered()).isTrue();
            }
        });
    }

    @Test
    void withheldVerdictProducesNoScenarios() {
        AnalysisResult result = InvestmentAnalysisService.analyze(
                AnalysisFixtures.emptyStock(), AnalysisFixtures.TODAY);

        assertThat(result.verdict().withheld()).isTrue();
        assertThat(result.scenarios()).isEmpty();
    }

    @Test
    void everyScenarioCarriesInvalidationAndDisclaimer() {
        AnalysisResult result = InvestmentAnalysisService.analyze(
                AnalysisFixtures.etf(), AnalysisFixtures.TODAY);

        for (Scenario scenario : result.scenarios()) {
            assertThat(scenario.invalidation()).isNotBlank();
            assertThat(scenario.condition()).isNotBlank();
            assertThat(scenario.narrative()).isNotBlank();
            assertThat(scenario.evidenceIds()).isNotEmpty();
            assertThat(scenario.disclaimer()).isEqualTo(Scenario.DISCLAIMER);
        }
    }

    @Test
    void scenariosContainNoProbabilitiesOrTargetPrices() {
        List<String> banned = List.of("概率", "目标价", "预计涨幅", "上涨空间");
        AnalysisResult result = InvestmentAnalysisService.analyze(
                AnalysisFixtures.healthyStock(), AnalysisFixtures.TODAY);
        for (Scenario scenario : result.scenarios()) {
            String text = scenario.condition() + scenario.narrative() + scenario.invalidation();
            for (String word : banned) {
                assertThat(text).doesNotContain(word);
            }
        }
    }
}
