package com.ai.daily.screener.v2;

import com.ai.daily.screener.v2.analysis.AnalysisFixtures;
import com.ai.daily.screener.v2.analysis.InvestmentAnalysisService;
import com.ai.daily.warehouse.WarehouseProperties;
import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseSchemaGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 线格式契约：前端只认这组字段。改 DTO 时先看这里，别让页面悄悄变空。
 */
class CodeLookupV2JsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private CodeLookupV2Service service;

    @BeforeEach
    void setUp() {
        InstrumentFactsService factsService = mock(InstrumentFactsService.class);
        WarehouseSchemaGuard guard = mock(WarehouseSchemaGuard.class);
        WarehouseProperties properties = mock(WarehouseProperties.class);
        when(guard.isReady()).thenReturn(true);
        when(properties.isEnabled()).thenReturn(true);
        when(factsService.snapshot("600519")).thenReturn(AnalysisFixtures.healthyStock());
        service = new CodeLookupV2Service(factsService, new InvestmentAnalysisService(),
                mock(WarehouseRepository.class), guard, properties);
    }

    @Test
    void coreJsonCarriesTheDashboardContract() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(service.core("600519")));

        assertThat(json.path("code").asText()).isEqualTo("600519");
        assertThat(json.path("assetType").asText()).isEqualTo("STOCK");
        assertThat(json.path("rulesVersion").asText()).isEqualTo("code-lookup-v2-p5");
        assertThat(json.path("disclaimer").asText()).isEqualTo(CodeLookupV2DTO.DISCLAIMER);
        assertThat(json.path("snapshotId").asText()).isNotBlank();

        JsonNode verdict = json.path("verdict");
        assertThat(verdict.path("label").asText()).isNotBlank();
        assertThat(verdict.path("labelKey").asText()).isNotBlank();
        assertThat(verdict.path("score").isNumber()).isTrue();
        assertThat(verdict.path("scoreLow").isNumber()).isTrue();
        assertThat(verdict.path("scoreHigh").isNumber()).isTrue();
        assertThat(verdict.path("coverage").isNumber()).isTrue();
        assertThat(verdict.path("confidence").isNumber()).isTrue();
        assertThat(verdict.path("hardFlags").isArray()).isTrue();
        assertThat(verdict.path("reasons").isArray()).isTrue();

        JsonNode dimensions = json.path("dimensions");
        assertThat(dimensions.size()).isEqualTo(6);
        for (JsonNode dimension : dimensions) {
            assertThat(dimension.path("key").asText()).isNotBlank();
            assertThat(dimension.path("weight").asInt()).isPositive();
            assertThat(dimension.has("score")).isTrue();
            assertThat(dimension.has("coverage")).isTrue();
            assertThat(dimension.path("status").asText()).isNotBlank();
            assertThat(dimension.path("metrics").isArray()).isTrue();
        }

        JsonNode buyZone = json.path("buyZone");
        assertThat(buyZone.path("status").asText()).isNotBlank();
        assertThat(buyZone.path("notes").isArray()).isTrue();
        assertThat(buyZone.has("low")).isTrue();
        assertThat(buyZone.has("stopLoss")).isTrue();

        for (String key : new String[]{"quote", "prices", "valuations", "financials"}) {
            assertThat(json.path("data").has(key)).isTrue();
        }
    }

    @Test
    void detailJsonCarriesScenariosAndSections() throws Exception {
        CodeLookupV2DTO.CoreView core = service.core("600519");
        JsonNode json = mapper.readTree(mapper.writeValueAsString(
                service.detail("600519", core.snapshotId())));

        assertThat(json.path("snapshotChanged").asBoolean()).isFalse();
        assertThat(json.path("financials").path("status").asText()).isEqualTo("OK");
        assertThat(json.path("financials").path("data").size()).isPositive();

        // 基金档案：个股也带这个段，只是 data 为空。字段整个消失会让前端
        // 分不清「这版后端不支持」和「这只票没有档案」。
        assertThat(json.has("fundProfile")).isTrue();
        assertThat(json.path("fundProfile").path("status").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(json.path("fundProfile").path("data").isNull()).isTrue();

        JsonNode scenarios = json.path("scenarios");
        assertThat(scenarios.size()).isEqualTo(3);
        int triggered = 0;
        for (JsonNode scenario : scenarios) {
            assertThat(scenario.path("key").asText()).isIn("bull", "base", "bear");
            assertThat(scenario.path("condition").asText()).isNotBlank();
            assertThat(scenario.path("narrative").asText()).isNotBlank();
            assertThat(scenario.path("invalidation").asText()).isNotBlank();
            assertThat(scenario.path("evidenceIds").size()).isPositive();
            assertThat(scenario.path("disclaimer").asText()).isEqualTo(
                    com.ai.daily.screener.v2.analysis.Scenario.DISCLAIMER);
            if (scenario.path("triggered").asBoolean()) {
                triggered++;
            }
        }
        assertThat(triggered).isEqualTo(1);
    }
}
