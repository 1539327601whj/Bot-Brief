package com.ai.daily.screener.v2;

import com.ai.daily.dto.Result;
import com.ai.daily.screener.MarketDataException;
import com.ai.daily.screener.v2.ai.BriefV2Service;
import com.ai.daily.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * v2 的可见范围与错误码。
 *
 * <p>错误码不是装饰：400 换写法、404 换代码、429 **等一会儿且不要自动重试**、
 * 503 等一会儿重试**同一个**代码、500 才是真 bug。v2 比 v1 多一档「底座未就绪 → 503」，
 * 因为那是部署问题，让用户点重试永远不会好。
 */
class CodeLookupV2ControllerTest {

    private CodeLookupV2Service service;
    private SeriesService seriesService;
    private BriefV2Service briefService;
    private CodeLookupV2Controller controller;

    @BeforeEach
    void setUp() {
        service = mock(CodeLookupV2Service.class);
        seriesService = mock(SeriesService.class);
        briefService = mock(BriefV2Service.class);
        controller = new CodeLookupV2Controller(service, seriesService, briefService,
                new CodeLookupMetrics());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAsAdmin() {
        UserPrincipal up = new UserPrincipal(1L, "admin@example.com", "ADMIN", "USER", "hash", true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(up, null, up.getAuthorities()));
    }

    @Test
    void anonymousGets403BeforeAnyWork() {
        Result<CodeLookupV2DTO.CoreView> result = controller.core("600519");
        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).contains("仅管理员");
        verifyNoInteractions(service);
    }

    @Test
    void badInputIs400() {
        authenticateAsAdmin();
        when(service.core(anyString())).thenThrow(new IllegalArgumentException("请输入代码"));
        assertThat(controller.core("").getCode()).isEqualTo(400);
    }

    @Test
    void unknownCodeIs404AndNotConfusedWithSourceFailure() {
        authenticateAsAdmin();
        when(service.core(anyString()))
                .thenThrow(new InstrumentNotFoundException("行情源查不到代码 999999"));
        assertThat(controller.core("999999").getCode()).isEqualTo(404);
    }

    @Test
    void rateLimitIs429() {
        authenticateAsAdmin();
        when(service.core(anyString())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("冷却中，约 5 分钟后再试"));
        Result<CodeLookupV2DTO.CoreView> result = controller.core("600519");
        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).contains("冷却");
    }

    @Test
    void missingWarehouseSchemaIs503WithMigrationHint() {
        authenticateAsAdmin();
        when(service.core(anyString())).thenThrow(
                new WarehouseUnavailableException("请先执行 V15__investment_warehouse.sql"));
        Result<CodeLookupV2DTO.CoreView> result = controller.core("600519");
        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).contains("V15");
    }

    @Test
    void unreachableSourceIs503AndUnexpectedErrorIs500() {
        authenticateAsAdmin();
        when(service.detail(anyString(), any())).thenThrow(new MarketDataException("源不可达"));
        assertThat(controller.detail("600519", null).getCode()).isEqualTo(503);

        when(service.search(any())).thenThrow(new IllegalStateException("boom"));
        assertThat(controller.search("茅台").getCode()).isEqualTo(500);
    }

    @Test
    void successKeepsTheUnifiedResultEnvelope() {
        authenticateAsAdmin();
        CodeLookupV2DTO.CoreView core = new CodeLookupV2DTO.CoreView("600519", "600519", "贵州茅台",
                "STOCK", "SH", "酿酒行业", "GENERAL", null, "abc123", "2026-10-18T10:00:00",
                true, false, 0, null, null, null, null, java.util.List.of(), null,
                java.util.List.of(), java.util.List.of(),
                java.util.Map.of(), java.util.List.of(),
                "code-lookup-v2-p4", CodeLookupV2DTO.DISCLAIMER);
        when(service.core("600519")).thenReturn(core);

        Result<CodeLookupV2DTO.CoreView> result = controller.core("600519");

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().snapshotId()).isEqualTo("abc123");
        assertThat(result.getData().disclaimer()).isEqualTo(CodeLookupV2DTO.DISCLAIMER);
    }

    @Test
    void briefIsAlsoBehindTheSameGate() {
        Result<CodeLookupV2DTO.BriefView> result = controller.brief("600519", null);

        assertThat(result.getCode()).isEqualTo(403);
        verifyNoInteractions(briefService);
    }

    @Test
    void briefFailureIsStill200WithAvailableFalse() {
        authenticateAsAdmin();
        CodeLookupV2DTO.BriefView view = new CodeLookupV2DTO.BriefView(
                "600519", "贵州茅台", "abc123", false, false, false, false,
                "AI 解读（本次未生成）", List.of(), "AI 服务暂未配置。",
                "配置 DEEPSEEK_API_KEY。", "code-lookup-v2-p4",
                "code-lookup-v2-prompt-1", "2026-10-18T10:00:00",
                CodeLookupV2DTO.DISCLAIMER);
        when(briefService.brief("600519", "abc123")).thenReturn(view);

        Result<CodeLookupV2DTO.BriefView> result = controller.brief("600519", "abc123");

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().available()).isFalse();
        assertThat(result.getData().reason()).contains("暂未配置");
    }
}
