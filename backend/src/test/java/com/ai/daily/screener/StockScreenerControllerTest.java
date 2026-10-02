package com.ai.daily.screener;

import com.ai.daily.dto.Result;
import com.ai.daily.entity.ScreenerScanHistory;
import com.ai.daily.security.UserPrincipal;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockScreenerControllerTest {

    private StockScreenerService service;
    private ScreenerHistoryService historyService;
    private StockScreenerController controller;

    @BeforeEach
    void setUp() {
        service = mock(StockScreenerService.class);
        historyService = mock(ScreenerHistoryService.class);
        controller = new StockScreenerController(service, historyService);
    }

    /** 真的 DTO，不是 mock：写历史失败那条路要在它上面挂降级说明。 */
    private static ScreenerResultDTO realResult() {
        return new ScreenerResultDTO(
                "2026-10-02 10:24:15", "2026-09-30",
                ScreenerParams.defaults(),
                new ScreenerResultDTO.Summary(300, 12, 5, 7, 16, List.of(), List.of(), List.of()),
                List.of(), List.of(), List.of(),
                "本页仅为公开行情数据的规则化筛选结果，不构成投资建议。");
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

    // ================= 筛选历史 =================

    @Test
    void aSuccessfulScanLeavesOneHistoryRowWithTheAppliedParams() {
        authenticateAs("ADMIN", "NORMAL");
        ScreenerResultDTO dto = realResult();
        when(service.scan(any())).thenReturn(dto);

        assertThat(controller.scan(new ScreenerParams()).getCode()).isEqualTo(200);

        // 落库用的是**解析过默认值之后**的参数：回填条件面板时要看到当时真正生效的那一套
        verify(historyService).record(eq(dto.appliedParams()), eq(dto), eq(1L), eq("u@example.com"));
    }

    /**
     * 历史写失败**不能**把一次成功的筛选变成错误页——它是审计附属品。
     *
     * <p>但也**不能悄悄算了**：用户下次翻历史找不到这一条时会以为是自己看漏了，
     * 而真正的原因（库写不进去）就这么消失了。所以降级说明里必须出现一句。
     */
    @Test
    void aFailedHistoryWriteStillReturns200AndSaysSoInTheDegradations() {
        authenticateAs("ADMIN", "NORMAL");
        when(service.scan(any())).thenReturn(realResult());
        doThrow(new RuntimeException("表不存在")).when(historyService)
                .record(any(), any(), any(), any());

        Result<ScreenerResultDTO> r = controller.scan(new ScreenerParams());

        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).isNotNull();
        assertThat(r.getData().summary().degradations())
                .anyMatch(line -> line.contains("历史没有存下来"));
        // 结果本身原样保留
        assertThat(r.getData().summary().scanned()).isEqualTo(300);
    }

    @Test
    void historyReadIsGatedToAdminAndDemoOnly() {
        assertThat(controller.history(1, 10).getCode()).isEqualTo(403);

        authenticateAs("USER", "NORMAL");
        assertThat(controller.history(1, 10).getCode()).isEqualTo(403);
        assertThat(controller.historyDetail(1L).getCode()).isEqualTo(403);

        // Demo 能看不能跑：读历史与页面可见范围一致，写入口仍然没有
        authenticateAs("USER", "DEMO");
        when(historyService.page(anyInt(), anyInt())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10));
        assertThat(controller.history(1, 10).getCode()).isEqualTo(200);
        verify(historyService, never()).record(any(), any(), any(), any());
    }

    @Test
    void historyPageSizeIsRejectedInsteadOfBeingSilentlyTruncated() {
        authenticateAs("ADMIN", "NORMAL");
        // MybatisPlusConfig 的分页拦截器上限是 50：超了必须 400，不能让它悄悄截到 50
        assertThat(controller.history(1, 51).getCode()).isEqualTo(400);
        assertThat(controller.history(0, 10).getCode()).isEqualTo(400);
        verifyNoInteractions(historyService);
    }

    @Test
    void historyDetailReturns404ForAnUnknownIdAndTheSnapshotOtherwise() {
        authenticateAs("ADMIN", "NORMAL");
        when(historyService.detail(7L)).thenReturn(Optional.empty());
        assertThat(controller.historyDetail(7L).getCode()).isEqualTo(404);

        ScreenerHistoryDTO.Item item = new ScreenerHistoryDTO.Item(
                7L, "2026-10-02 10:24:15", "u@example.com", "index_first", "both", 3,
                300, 12, 5, 7, 16, 7, 2, List.of(), List.of(), List.of(), null);
        when(historyService.detail(8L)).thenReturn(Optional.of(new ScreenerHistoryDTO.Detail(
                item, new ObjectMapper().createObjectNode().put("dataTime", "2026-10-02 10:24:15"))));

        Result<ScreenerHistoryDTO.Detail> r = controller.historyDetail(8L);
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData().item().scannedAt()).isEqualTo("2026-10-02 10:24:15");
        assertThat(r.getData().result().get("dataTime").asText()).isEqualTo("2026-10-02 10:24:15");
    }

    /** 列表返回体的键名要与「简报历史」页读的那一套一致，前端才能照搬同一段渲染。 */
    @Test
    void historyListCarriesTheEnvelopeShapeTheFrontendAlreadyReads() {
        authenticateAs("ADMIN", "NORMAL");
        Page<ScreenerScanHistory> page = new Page<>(2, 10);
        page.setTotal(23);
        when(historyService.page(2, 10)).thenReturn(page);

        Result<Map<String, Object>> r = controller.history(2, 10);

        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).containsKeys("records", "total", "pages", "current", "size");
        assertThat(r.getData().get("total")).isEqualTo(23L);
    }
}