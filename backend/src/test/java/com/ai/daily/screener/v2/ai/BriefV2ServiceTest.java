package com.ai.daily.screener.v2.ai;

import com.ai.daily.screener.v2.CodeLookupV2DTO;
import com.ai.daily.screener.v2.InstrumentFactsService;
import com.ai.daily.screener.v2.analysis.AnalysisFixtures;
import com.ai.daily.screener.v2.analysis.InvestmentAnalysisService;
import com.ai.daily.service.AiClientService;
import com.ai.daily.warehouse.WarehouseRows;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 解读编排：成功、缓存、未配 key、校验失败回退、数据不足。
 *
 * <p>断言的重点是「失败不改变首屏结论」：每种失败都必须是 200 语义，
 * 并给出可操作的原因，而不是一个 500。
 */
class BriefV2ServiceTest {

    private static final String VALID = """
            {"sections":[
              {"heading":"结论","text":"规则结论偏积极，估值与趋势同向。","evidenceIds":["ev:val:pe_ttm"]},
              {"heading":"观察","text":"若估值继续抬升，需要重新核对数据覆盖率。","evidenceIds":["ev:verdict:coverage"]}
            ]}""";

    private InstrumentFactsService factsService;
    private AnalysisCacheService cache;
    private AiClientService aiClientService;
    private ObjectMapper mapper;
    private BriefV2Service service;

    @BeforeEach
    void setUp() {
        factsService = mock(InstrumentFactsService.class);
        cache = mock(AnalysisCacheService.class);
        aiClientService = mock(AiClientService.class);
        mapper = new ObjectMapper();
        service = new BriefV2Service(factsService, new InvestmentAnalysisService(),
                cache, aiClientService, mapper, 3000, 25000, 1800);
        when(cache.get(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
    }

    private static void healthy(InstrumentFactsService factsService) {
        when(factsService.snapshot("600519")).thenReturn(AnalysisFixtures.healthyStock());
    }

    @Test
    void validOutputIsAvailableAndCached() {
        healthy(factsService);
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(VALID);

        CodeLookupV2DTO.BriefView view = service.brief("600519", "stock-600519");

        assertThat(view.available()).isTrue();
        assertThat(view.fallback()).isFalse();
        assertThat(view.cached()).isFalse();
        assertThat(view.sections()).hasSize(2);
        assertThat(view.reason()).isNull();
        assertThat(view.rulesVersion()).isEqualTo(InvestmentAnalysisService.RULES_VERSION);
        assertThat(view.promptVersion()).isEqualTo(AnalysisPromptV2.VERSION);
        assertThat(view.disclaimer()).isEqualTo(CodeLookupV2DTO.DISCLAIMER);
        org.mockito.ArgumentCaptor<String> code = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> snapshot = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> rules = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cache).get(code.capture(), snapshot.capture(), rules.capture(),
                prompt.capture(), any());
        assertThat(code.getValue()).isEqualTo("STOCK:600519");
        assertThat(snapshot.getValue()).isEqualTo("stock-600519");
        assertThat(rules.getValue()).isEqualTo(InvestmentAnalysisService.RULES_VERSION);
        assertThat(prompt.getValue()).isEqualTo(AnalysisPromptV2.VERSION);
        verify(cache).put(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void cacheHitSkipsTheModelEntirely() throws Exception {
        healthy(factsService);
        CodeLookupV2DTO.BriefView cachedView = new CodeLookupV2DTO.BriefView(
                "600519", "示例科技", "stock-600519", false, true, false, false,
                "AI 解读（证据绑定）",
                List.of(new CodeLookupV2DTO.BriefSectionView("结论", "规则结论偏积极。",
                        List.of("ev:val:pe_ttm"))),
                null, "本段由大模型改写。", InvestmentAnalysisService.RULES_VERSION,
                AnalysisPromptV2.VERSION, "2026-10-18T12:00", CodeLookupV2DTO.DISCLAIMER);
        when(cache.get(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.of(new WarehouseRows.CachedAnalysis(
                        "STOCK:600519", "stock-600519", InvestmentAnalysisService.RULES_VERSION,
                        AnalysisPromptV2.VERSION, mapper.writeValueAsString(cachedView),
                        LocalDateTime.of(2026, 10, 18, 12, 0),
                        LocalDateTime.of(2026, 10, 18, 12, 30))));

        CodeLookupV2DTO.BriefView view = service.brief("600519", "older-id");

        assertThat(view.cached()).isTrue();
        assertThat(view.available()).isTrue();
        assertThat(view.snapshotChanged()).isTrue();
        verifyNoInteractions(aiClientService);
    }

    @Test
    void missingApiKeyReturns200SemanticsAndIsNotCached() {
        healthy(factsService);
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt()))
                .thenReturn("AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。");

        CodeLookupV2DTO.BriefView view = service.brief("600519", null);

        assertThat(view.available()).isFalse();
        assertThat(view.fallback()).isFalse();
        assertThat(view.sections()).isEmpty();
        assertThat(view.reason()).contains("暂未配置");
        assertThat(view.note()).contains("DEEPSEEK_API_KEY");
        verify(cache, never()).put(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void callFailureExplainsThatItIsNotAKeyProblem() {
        healthy(factsService);
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt()))
                .thenReturn("AI 调用失败：Read timed out");

        CodeLookupV2DTO.BriefView view = service.brief("600519", null);

        assertThat(view.available()).isFalse();
        assertThat(view.fallback()).isFalse();
        assertThat(view.reason()).contains("Read timed out");
        assertThat(view.note()).contains("不是「没配 key」");
    }

    @Test
    void invalidModelOutputFallsBackToRuleSections() {
        healthy(factsService);
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt()))
                .thenReturn("{" + "\"sections\":[{\"heading\":\"结论\","
                        + "\"text\":\"综合得分 99。\",\"evidenceIds\":[\"ev:not_real\"]}]}");

        CodeLookupV2DTO.BriefView view = service.brief("600519", null);

        assertThat(view.available()).isFalse();
        assertThat(view.fallback()).isTrue();
        assertThat(view.sections()).isNotEmpty();
        assertThat(view.sections()).extracting(CodeLookupV2DTO.BriefSectionView::heading)
                .containsExactly("规则结论", "主要依据", "风险与观察");
        assertThat(view.reason()).contains("未通过校验");
        assertThat(view.note()).isEqualTo(BriefV2Service.NOTE_FALLBACK);
    }

    @Test
    void insufficientDataDoesNotCallTheModel() {
        when(factsService.snapshot("600009")).thenReturn(AnalysisFixtures.emptyStock());

        CodeLookupV2DTO.BriefView view = service.brief("600009", null);

        assertThat(view.available()).isFalse();
        assertThat(view.fallback()).isFalse();
        assertThat(view.title()).isEqualTo(BriefV2Service.TITLE_WITHHELD);
        assertThat(view.reason()).contains("数据不足");
        verifyNoInteractions(aiClientService);
    }
}
