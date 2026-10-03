package com.ai.daily.screener;

import com.ai.daily.dto.Result;
import com.ai.daily.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CodeLookupController} 的测试：**可见范围**与**错误码怎么分**。
 *
 * <p>错误码不是随手挑的，它们指向不同的下一步动作，所以每一档都要钉住：
 * 400 换写法、404 换代码、429 等一会儿（且不许自动重试）、503 等一会儿再试**同一个**代码。
 * 尤其 404 与 503 混起来最糟：都说成 404，用户会以为代码写错了；都说成 503，
 * 他会一直重试一个根本不存在的代码。
 */
class CodeLookupControllerTest {

    private CodeLookupService service;
    private CodeLookupController controller;

    @BeforeEach
    void setUp() {
        service = mock(CodeLookupService.class);
        controller = new CodeLookupController(service);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String role, String accountType) {
        UserPrincipal up = new UserPrincipal(1L, "u@example.com", role, accountType, "hash", true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(up, null, up.getAuthorities()));
    }

    private static CodeLookupDTO realDto() {
        return new CodeLookupDTO("510300", "SH000300", "沪深300",
                CodeLookupDTO.Kind.INDEX_FUND, "池内指数（输入的是它的代表 ETF）",
                new CodeLookupDTO.QuoteView("eastmoney", "东财", "沪深300ETF",
                        new BigDecimal("4.05"), new BigDecimal("0.52"),
                        null, null, null, null),
                new BigDecimal("4.05"), "2026-09-30", List.of(), null,
                new CodeLookupDTO.ValuationView(true, "csindex", "中证官网（中证口径）",
                        "CSI_PE_TTM_ROLLING_10Y", new BigDecimal("13.55"), new BigDecimal("42.10"),
                        "2026-09-30", 3787, "2011-06-28", "2026-09-30", List.of(), List.of()),
                List.of(), "2026-10-03T10:00:00", false);
    }

    // ------------------------------------------------------------------
    // 可见范围：与「市场观察」「低估精选」一致（管理员 + Demo）
    // ------------------------------------------------------------------

    @Test
    void anonymousCallersGet403AndNoSourceIsTouched() {
        // 未登录时一次外呼都不能发出去——这既是一次越权，也是一次白送的配额消耗。
        assertThat(controller.lookup("510300").getCode()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    void aNormalPaidUserIsBlocked() {
        authenticateAs("USER", "NORMAL");
        assertThat(controller.lookup("510300").getCode()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    void demoAndAdminCanLookUp() {
        when(service.lookup(any())).thenReturn(realDto());

        authenticateAs("USER", "DEMO");
        Result<CodeLookupDTO> demo = controller.lookup("510300");
        assertThat(demo.getCode()).isEqualTo(200);
        assertThat(demo.getData().kind()).isEqualTo(CodeLookupDTO.Kind.INDEX_FUND);

        SecurityContextHolder.clearContext();
        authenticateAs("ADMIN", "NORMAL");
        assertThat(controller.lookup("510300").getCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // 错误码：每一档指向不同的下一步
    // ------------------------------------------------------------------

    /**
     * 这些用例都要先登录。<b>可见范围那一道判定在错误码之前</b>——没登录时任何代码
     * 都是 403，会把下面每一档都盖成 403，于是「404 与 503 分不分得开」这件事
     * 根本没被测到。这不是测试的仪式感，是在确认两件事的先后顺序。
     */
    private void asAdmin() {
        authenticateAs("ADMIN", "NORMAL");
    }

    @Test
    void anIllegalCodeShapeIs400BecauseRewritingItFixesIt() {
        asAdmin();
        when(service.lookup(any())).thenThrow(new IllegalArgumentException("代码只能由 1-12 位字母或数字组成"));
        Result<CodeLookupDTO> r = controller.lookup("abc-def");
        assertThat(r.getCode()).isEqualTo(400);
        assertThat(r.getMessage()).contains("字母或数字");
    }

    @Test
    void aCodeNoSourceKnowsIs404Not503() {
        // 404 与 503 分开是这条测试的全部意义：404 的下一步是「换一个代码」，
        // 503 的下一步是「等一会儿再试同一个代码」。混起来，用户会一直重试一个不存在的代码。
        asAdmin();
        when(service.lookup(any())).thenThrow(
                new CodeLookupService.CodeNotFoundException("查不到代码 999999"));
        Result<CodeLookupDTO> r = controller.lookup("999999");
        assertThat(r.getCode()).isEqualTo(404);
    }

    @Test
    void rateLimitingIs429AndCarriesTheWaitingTime() {
        // 前端要据此**停掉自动重试**并显示等待时间，与「网络不通」的处置完全相反。
        asAdmin();
        when(service.lookup(any())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("行情源限流冷却中，约 7 分钟后可再试"));
        Result<CodeLookupDTO> r = controller.lookup("510300");
        assertThat(r.getCode()).isEqualTo(429);
        assertThat(r.getMessage()).contains("7 分钟");
    }

    @Test
    void aSourceThatIsDownIs503Not404() {
        asAdmin();
        when(service.lookup(any())).thenThrow(new MarketDataException("行情源都没问到 300274 的报价"));
        Result<CodeLookupDTO> r = controller.lookup("300274");
        assertThat(r.getCode()).isEqualTo(503);
    }

    @Test
    void anythingUnexpectedIs500AndStillSaysSomething() {
        asAdmin();
        when(service.lookup(any())).thenThrow(new IllegalStateException("数据库连接池空了"));
        Result<CodeLookupDTO> r = controller.lookup("510300");
        assertThat(r.getCode()).isEqualTo(500);
        // 不能只说「查询失败」——那句话对排查毫无用处
        assertThat(r.getMessage()).contains("数据库连接池空了");
    }
}