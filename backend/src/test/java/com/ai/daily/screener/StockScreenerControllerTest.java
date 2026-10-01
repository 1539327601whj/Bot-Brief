package com.ai.daily.screener;

import com.ai.daily.dto.Result;
import com.ai.daily.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockScreenerControllerTest {

    private StockScreenerService service;
    private StockScreenerController controller;

    @BeforeEach
    void setUp() {
        service = mock(StockScreenerService.class);
        controller = new StockScreenerController(service);
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

    @Test
    void anonymousCallersGet403AndNothingRuns() {
        Result<ScreenerResultDTO> r = controller.scan(null);
        assertThat(r.getCode()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    void aNormalPaidUserIsBlockedBecauseOnlyAdminCanRun() {
        authenticateAs("USER", "NORMAL");
        assertThat(controller.scan(null).getCode()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    /**
     * Demo 能**看**这个页面，但不能**跑**。
     *
     * <p>这条以前断言 200（demo 允许），是故意改掉的：一次筛选要向东财发二十多次请求，
     * 出口 IP 会被按 IP 限流，多一个能触发的人就多一个消耗配额的入口。前端把按钮置灰只是提示，
     * 拦住 demo 的是这一层——他拿着自己的 token 直接 POST 过来同样得是 403。
     */
    @Test
    void demoAccountCanSeeThePageButIsRejectedHere() {
        authenticateAs("USER", "DEMO");
        assertThat(controller.scan(null).getCode()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    void adminIsAllowedAndParamsArePassedThroughVerbatim() {
        authenticateAs("ADMIN", "NORMAL");
        ScreenerParams params = new ScreenerParams();
        params.setMode("index_only");
        ScreenerResultDTO dto = mock(ScreenerResultDTO.class);
        when(service.scan(params)).thenReturn(dto);

        Result<ScreenerResultDTO> r = controller.scan(params);
        assertThat(r.getCode()).isEqualTo(200);
        verify(service).scan(params);
    }

    @Test
    void illegalParamsBecome400AndUnreachableMarketSourceBecomes503() {
        authenticateAs("ADMIN", "NORMAL");
        // 必须用 doThrow：when(mock.method()) 会真的调用一次 mock，
        // 一旦之前已经 stub 过抛异常，第二次 stub 自己就会把异常抛出来
        doThrow(new IllegalArgumentException("PE 上限必须大于 0")).when(service).scan(any());
        assertThat(controller.scan(new ScreenerParams()).getCode()).isEqualTo(400);

        doThrow(new MarketDataException("行情源不可达，未能取到全市场快照")).when(service).scan(any());
        Result<ScreenerResultDTO> r = controller.scan(new ScreenerParams());
        assertThat(r.getCode()).isEqualTo(503);
        assertThat(r.getMessage()).contains("不可达");
        assertThat(r.getData()).isNull(); // 不能返回空列表假装「今天没有候选」
    }

    @Test
    void rateLimitedGetsItsOwnStatusCodeSoThePageCanStopRetrying() {
        authenticateAs("ADMIN", "NORMAL");
        doThrow(new MarketDataException.MarketDataRateLimitedException(
                "东财行情接口正在限流这个 IP，需要等大约 8 分钟再试。", null))
                .when(service).scan(any());

        Result<ScreenerResultDTO> r = controller.scan(new ScreenerParams());

        // 与「网络不通」分开：前端要据此停掉自动刷新，而不是立刻重试
        assertThat(r.getCode()).isEqualTo(429);
        assertThat(r.getMessage()).contains("限流");
        assertThat(r.getData()).isNull();
    }
}