package com.ai.daily.screener;

import com.ai.daily.dto.Result;
import com.ai.daily.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/code-lookup/analysis} 的**可见范围**与**错误码怎么分**。
 *
 * <p>这个端点的错误码与主查询逐字相同，但不是照抄一遍这么简单——这里要钉住的是
 * **哪些失败会走到 HTTP 状态码上、哪些不会**（章程 §9.7）：
 *
 * <ul>
 *   <li>主查询那一层失败（代码写错 / 被限流 / 源不可达）→ 400 / 404 / 429 / 503，
 *       与主查询一模一样：深度分析建在它的结果之上，它拿不到数，分析也无从谈起。</li>
 *   <li><b>后面几段（新闻 / 公告 / 政策 / 大模型）失败不给错误码</b>，整体仍返回 200，
 *       只在各自那张卡上写原因。这一条现在还没接源，但**接口契约要先钉住**——
 *       等接上源再改，前端已经按「整页打不开」写好了。</li>
 * </ul>
 */
class CodeLookupAnalysisControllerTest {

    private CodeLookupService service;
    private CodeLookupAnalysisService analysisService;
    private CodeLookupController controller;

    @BeforeEach
    void setUp() {
        service = mock(CodeLookupService.class);
        analysisService = mock(CodeLookupAnalysisService.class);
        controller = new CodeLookupController(service, analysisService);
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

    private void asAdmin() {
        authenticateAs("ADMIN", "NORMAL");
    }

    /** 一份最小可用的分析结果：判定与体检都是空的，这里测的是状态码不是内容。 */
    private static CodeLookupAnalysisDTO realDto() {
        return new CodeLookupAnalysisDTO("300274", "0.300274", "阳光电源", "STOCK", "深市股票",
                "2026-10-15T10:00:00", false,
                List.of(CodeLookupAnalysisDTO.CALIBER_ABSOLUTE),
                List.of(),
                new AnalysisRules.Checkup(false, List.of(), List.of(), "不作体检"),
                new AnalysisRules.TendencyVerdict(AnalysisRules.Tendency.INSUFFICIENT, 0, 0,
                        List.of("参判维度不足"), List.of()),
                List.of("需核实：这是不是一个问句？"),
                List.of(),
                CodeLookupAnalysisDTO.DISCLAIMER);
    }

    // ------------------------------------------------------------------
    // 可见范围：与主查询一致（管理员 + Demo）
    // ------------------------------------------------------------------

    @Test
    void anonymousCallersGet403AndNothingIsFetched() {
        // 未登录时一次外呼都不能发出去：既是一次越权，也是一次白送的配额消耗。
        assertThat(controller.analysis("300274").getCode()).isEqualTo(403);
        verifyNoInteractions(service, analysisService);
    }

    @Test
    void aNormalPaidUserIsBlockedAndDemoIsNot() {
        authenticateAs("USER", "NORMAL");
        assertThat(controller.analysis("300274").getCode()).isEqualTo(403);

        SecurityContextHolder.clearContext();
        when(analysisService.analyse(any())).thenReturn(realDto());
        authenticateAs("USER", "DEMO");
        Result<CodeLookupAnalysisDTO> r = controller.analysis("300274");
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData().disclaimer()).isEqualTo(CodeLookupAnalysisDTO.DISCLAIMER);
    }

    // ------------------------------------------------------------------
    // 错误码：与主查询逐档对应
    // ------------------------------------------------------------------

    @Test
    void anIllegalCodeShapeIs400BecauseRewritingItFixesIt() {
        asAdmin();
        when(analysisService.analyse(any()))
                .thenThrow(new IllegalArgumentException("代码只能由 1-12 位字母或数字组成"));
        Result<CodeLookupAnalysisDTO> r = controller.analysis("abc-def");
        assertThat(r.getCode()).isEqualTo(400);
        assertThat(r.getMessage()).contains("字母或数字");
    }

    @Test
    void aCodeNoSourceKnowsIs404Not503() {
        // 404 的下一步是「换一个代码」，503 的下一步是「等一会儿再试同一个代码」。
        asAdmin();
        when(analysisService.analyse(any())).thenThrow(
                new CodeLookupService.CodeNotFoundException("查不到代码 999999"));
        assertThat(controller.analysis("999999").getCode()).isEqualTo(404);
    }

    @Test
    void rateLimitingIs429AndCarriesTheWaitingTime() {
        // 前端据此**停掉自动重试**并显示等待时间——深度分析尤其不能自动重试，
        // 它比主查询多花四到五次外呼的额度。
        asAdmin();
        when(analysisService.analyse(any())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("行情源限流冷却中，约 7 分钟后可再试"));
        Result<CodeLookupAnalysisDTO> r = controller.analysis("300274");
        assertThat(r.getCode()).isEqualTo(429);
        assertThat(r.getMessage()).contains("7 分钟");
    }

    @Test
    void aSourceThatIsDownIs503Not404() {
        asAdmin();
        when(analysisService.analyse(any())).thenThrow(
                new MarketDataException("行情源都没问到 300274 的报价"));
        assertThat(controller.analysis("300274").getCode()).isEqualTo(503);
    }

    @Test
    void anythingUnexpectedIs500AndStillSaysSomething() {
        asAdmin();
        when(analysisService.analyse(any())).thenThrow(new IllegalStateException("数据库连接池空了"));
        Result<CodeLookupAnalysisDTO> r = controller.analysis("300274");
        assertThat(r.getCode()).isEqualTo(500);
        assertThat(r.getMessage()).contains("数据库连接池空了");
    }

    @Test
    void theHappyPathIs200AndCarriesEverythingThePageNeeds() {
        asAdmin();
        when(analysisService.analyse(any())).thenReturn(realDto());
        Result<CodeLookupAnalysisDTO> r = controller.analysis("300274");
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData().notes()).contains(CodeLookupAnalysisDTO.CALIBER_ABSOLUTE);
        assertThat(r.getData().pendingChecks()).allMatch(s -> s.endsWith("？"));
        assertThat(r.getData().tendency().tendency())
                .isEqualTo(AnalysisRules.Tendency.INSUFFICIENT);
    }

    // ------------------------------------------------------------------
    // /analysis/brief
    // ------------------------------------------------------------------

    private static AnalysisBriefDTO briefDto(boolean available) {
        AnalysisBrief brief = available
                ? AnalysisBrief.ok("## 一句话结论\n当前数据呈现为偏正面。")
                : AnalysisBrief.unavailable("AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。");
        return new AnalysisBriefDTO("300274", "0.300274", "阳光电源",
                "2026-10-15T10:00:00", false, brief);
    }

    @Test
    void templateBriefEndpointIsAlsoAdminOrDemoOnly() {
        // 与另外两个端点一致。**不能因为「这只是段文字」就放松**：
        // 它是唯一一个要花钱的端点，未登录能打等于给了一个免费消耗出口。
        Result<AnalysisBriefDTO> anonymous = controller.brief("300274");

        assertThat(anonymous.getCode()).isEqualTo(403);
        verifyNoInteractions(analysisService);

        authenticateAs("USER", "PAID");
        assertThat(controller.brief("300274").getCode()).isEqualTo(403);
        verifyNoInteractions(analysisService);

        authenticateAs("USER", "DEMO");
        when(analysisService.brief(any())).thenReturn(briefDto(true));
        assertThat(controller.brief("300274").getCode()).isEqualTo(200);
    }

    @Test
    void aModelThatIsNotConfiguredIs200NotAnErrorPage() {
        // **这条是这一段的核心契约。** 大模型自己失败不抛到这里——那一层返回的是一句
        // `AI ` 开头的文案。页面上要显示的是「这一块没生成 + 怎么开」，
        // 而上面四块照常。给它一个 5xx 的话，用户会以为整个分析坏了。
        asAdmin();
        when(analysisService.brief(any())).thenReturn(briefDto(false));

        Result<AnalysisBriefDTO> r = controller.brief("300274");

        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData().brief().available()).isFalse();
        assertThat(r.getData().brief().title()).isEqualTo(AnalysisBrief.TITLE_UNAVAILABLE);
        // 数字那一份快照照旧带出来：解读没生成，不代表「这些数字是哪一刻的」也不用说了
        assertThat(r.getData().snapshotAt()).isEqualTo("2026-10-15T10:00:00");
    }

    @Test
    void theBriefEndpointMapsTheMainQueryFailuresExactlyLikeTheOtherTwo() {
        // 主查询那一层的失败照旧走错误码，三个端点逐字相同。
        //
        // 这里用 `doThrow().when(...)` 而不是 `when(...).thenThrow(...)`：后者的写法
        // 会**真的调用一次 mock**，而上一次打桩的答案正是「抛异常」——于是异常从
        // `when()` 的实参求值处冒出来，测试挂在打桩那一行，看起来像是业务代码没兜住。
        asAdmin();

        doThrow(new IllegalArgumentException("代码只能由 1-12 位字母或数字组成"))
                .when(analysisService).brief(any());
        assertThat(controller.brief("!!").getCode()).isEqualTo(400);

        doThrow(new CodeLookupService.CodeNotFoundException("查不到代码 999999"))
                .when(analysisService).brief(any());
        assertThat(controller.brief("999999").getCode()).isEqualTo(404);

        doThrow(new MarketDataException.MarketDataRateLimitedException("被限流"))
                .when(analysisService).brief(any());
        assertThat(controller.brief("300274").getCode()).isEqualTo(429);

        doThrow(new MarketDataException("源不可达")).when(analysisService).brief(any());
        assertThat(controller.brief("300274").getCode()).isEqualTo(503);

        doThrow(new IllegalStateException("炸了")).when(analysisService).brief(any());
        Result<AnalysisBriefDTO> r = controller.brief("300274");
        assertThat(r.getCode()).isEqualTo(500);
        // 「500 也要说点什么」——否则页面上只有一句「未知错误」，
        // 而这一层的 message 是唯一一条能贴给用户看的线索。
        assertThat(r.getMessage()).contains("解读失败");
    }
}
