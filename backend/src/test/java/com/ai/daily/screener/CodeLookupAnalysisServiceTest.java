package com.ai.daily.screener;

import com.ai.daily.service.AiClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CodeLookupAnalysisService} 的编排。
 *
 * <p>两条主线：
 *
 * <ul>
 *   <li><b>行情那一份数字只取一次、只来自主查询</b>（章程 §9.7、§9.8）——
 *       深度分析所有的数字都来自 {@link CodeLookupService#lookup} 那一份带缓存的快照，
 *       于是同一交易日重复点击不打行情源，且两个端点的数字逐字同一份；</li>
 *   <li><b>三路新闻源每路只发一次，各自成败</b>——冷却期内一次都不发；
 *       某一路失败只让那一组变灰，估值卡片照常。</li>
 * </ul>
 *
 * <p>{@link NewsDigestService} 用**真身**而不是 mock：这条测试要验的正是「编排有没有
 * 把三路结果正确喂给管线、再把产物正确拆成三组」，把管线换成 mock 就正好把要测的那段跳过去了。
 */
class CodeLookupAnalysisServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final Instant NOW = Instant.parse("2026-10-15T06:00:00Z");

    private CodeLookupService codeLookupService;
    private StockNoticeClient stockNoticeClient;
    private EastMoneyNewsClient eastMoneyNewsClient;
    private PolicyNewsClient policyNewsClient;
    private ScreenerCache cache;
    private AiClientService aiClientService;
    private CodeLookupAnalysisService service;

    @BeforeEach
    void setUp() {
        codeLookupService = mock(CodeLookupService.class);
        stockNoticeClient = mock(StockNoticeClient.class);
        eastMoneyNewsClient = mock(EastMoneyNewsClient.class);
        policyNewsClient = mock(PolicyNewsClient.class);
        cache = mock(ScreenerCache.class);
        aiClientService = mock(AiClientService.class);

        // 默认：三路都成功但一条都没有。单条测试按需覆盖。
        when(stockNoticeClient.fetchNotices(anyString())).thenReturn(empty(NewsItem.Category.NOTICE,
                StockNoticeClient.ORIGIN, StockNoticeClient.ORIGIN_LABEL));
        when(eastMoneyNewsClient.search(anyString())).thenReturn(empty(NewsItem.Category.NEWS,
                EastMoneyNewsClient.ORIGIN, EastMoneyNewsClient.ORIGIN_LABEL));
        when(policyNewsClient.searchPolicy(anyString())).thenReturn(empty(NewsItem.Category.POLICY,
                PolicyNewsClient.ORIGIN, PolicyNewsClient.ORIGIN_LABEL));

        service = new CodeLookupAnalysisService(codeLookupService, stockNoticeClient,
                eastMoneyNewsClient, policyNewsClient, new NewsDigestService(), cache,
                aiClientService, 6000, 1800L, 1200, 25000);
    }

    private static NewsSourceResult empty(NewsItem.Category category, String origin, String label) {
        return NewsSourceResult.ok(category, origin, label, List.of(), 0);
    }

    private static CodeLookupDTO dto(boolean fromCache) {
        return rebuild(dto(CodeLookupDTO.Kind.STOCK), "2026-10-15T10:00:00", fromCache);
    }

    private static CodeLookupDTO dto(CodeLookupDTO.Kind kind) {
        return new CodeLookupDTO("300274", "0.300274", "阳光电源",
                kind, "深市股票",
                new CodeLookupDTO.QuoteView("eastmoney", "东财", "阳光电源",
                        new BigDecimal("85.00"), new BigDecimal("1.20"),
                        new BigDecimal("18"), new BigDecimal("2.2"), "电源设备",
                        new BigDecimal("180000000000")),
                new CodeLookupDTO.FundamentalsView(true, "eastmoney", "东财",
                        new BigDecimal("18"), new BigDecimal("25"), new BigDecimal("30"),
                        new BigDecimal("32"), new BigDecimal("45"), new BigDecimal("1.5"),
                        new BigDecimal("3.0"), new BigDecimal("1200000000"),
                        new BigDecimal("150000000000"), new BigDecimal("5.0"),
                        new BigDecimal("20"), new BigDecimal("30"), "2011-11-02", List.of()),
                new BigDecimal("85.00"), "2026-10-14", List.of(),
                new CodeLookupDTO.PositionView(true, new BigDecimal("35"), new BigDecimal("-10"),
                        new BigDecimal("2.5"), 400, "2026-10-14", List.of()),
                new CodeLookupDTO.ValuationView(true, "eastmoney-valueanalysis", "东财估值分析",
                        "PE_TTM_ROLLING_10Y", new BigDecimal("18"), new BigDecimal("25"),
                        "2026-10-14", 2400, "2016-10-14", "2026-10-14", List.of(), List.of()),
                List.of(), "2026-10-15T10:00:00", false);
    }

    /** 不带报价的一类：指数没有财报，也没有 quote。 */
    private static CodeLookupDTO indexDto(CodeLookupDTO.Kind kind) {
        return new CodeLookupDTO("000300", "SH000300", "沪深300",
                kind, "池内指数（沪深300）",
                null, null, null, null, null, null,
                new CodeLookupDTO.ValuationView(true, "csindex", "中证官网",
                        "PE_TTM_ROLLING_10Y", new BigDecimal("14"), new BigDecimal("40"),
                        "2026-10-14", 2400, "2016-10-14", "2026-10-14", List.of(), List.of()),
                List.of(), "2026-10-15T10:00:00", false);
    }

    // ==================================================================
    // 一、行情那一份数字
    // ==================================================================

    @Test
    void itAsksTheMainLookupExactlyOnceAndNeverDialsTheQuoteSourcesItself() {
        when(codeLookupService.lookup(any())).thenReturn(dto(false));

        service.analyse("300274");

        verify(codeLookupService, times(1)).lookup("300274");
        verifyNoMoreInteractions(codeLookupService);
    }

    @Test
    void theVerdictIsBuiltOnTheSameSnapshotTheMainQueryJustReturned() {
        when(codeLookupService.lookup(any())).thenReturn(dto(true));

        CodeLookupAnalysisDTO out = service.analyse("300274");

        assertThat(out.snapshotAt()).isEqualTo("2026-10-15T10:00:00");
        assertThat(out.fromCache()).isTrue();
        assertThat(out.code()).isEqualTo("300274");
        assertThat(out.kind()).isEqualTo("STOCK");
        assertThat(out.notes()).anyMatch(s -> s.contains("2026-10-15T10:00:00"));
        assertThat(out.notes()).anyMatch(s -> s.contains("本段没有重新外呼行情源"));
    }

    @Test
    void theAbsoluteCaliberWarningTravelsWithEveryResult() {
        when(codeLookupService.lookup(any())).thenReturn(dto(false));

        CodeLookupAnalysisDTO out = service.analyse("300274");

        assertThat(out.notes()).contains(CodeLookupAnalysisDTO.CALIBER_ABSOLUTE);
        assertThat(out.notes()).contains(CodeLookupAnalysisDTO.CALIBER_CHECKUP_BUCKET);
        assertThat(out.disclaimer()).isEqualTo(CodeLookupAnalysisDTO.DISCLAIMER);
    }

    @Test
    void aFailedLookupPropagatesUnchangedSoTheErrorCodeStaysIdentical() {
        when(codeLookupService.lookup(any())).thenThrow(
                new CodeLookupService.CodeNotFoundException("查不到代码 999999"));

        assertThatThrownBy(() -> service.analyse("999999"))
                .isInstanceOf(CodeLookupService.CodeNotFoundException.class)
                .hasMessageContaining("999999");
    }

    @Test
    void theRuleVerdictIsCarriedThroughUntouched() {
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        AnalysisRules.Analysis expected = AnalysisRules.analyse(dto(false), TODAY);

        CodeLookupAnalysisDTO out = service.analyse(dto(false), TODAY);

        assertThat(out.tendency()).isEqualTo(expected.tendency());
        assertThat(out.dimensions()).isEqualTo(expected.dimensions());
        assertThat(out.checkup()).isEqualTo(expected.checkup());
    }

    @Test
    void aQuoteFailureDoesNotStopTheNewsSourcesFromBeingAsked() {
        // 顺序上：主查询先跑，新闻后跑。主查询抛出去之后新闻就不发了——
        // 这条断言把这个顺序钉住，因为「先取新闻再取行情」会让失败的代价翻倍
        // （四条外呼都发出去，然后整页 404）。
        when(codeLookupService.lookup(any())).thenThrow(
                new CodeLookupService.CodeNotFoundException("查不到"));

        assertThatThrownBy(() -> service.analyse("999999"))
                .isInstanceOf(CodeLookupService.CodeNotFoundException.class);
        verifyNoInteractions(stockNoticeClient, eastMoneyNewsClient, policyNewsClient);
    }

    // ==================================================================
    // 二、三路源：每路一次、各自成败
    // ==================================================================

    @Test
    void everySourceIsDialledExactlyOncePerClick() {
        // 章程 §9.8：每源只发一次，不翻页、不换关键词重搜、不重试。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));

        service.analyse("300274");

        verify(stockNoticeClient, times(1)).fetchNotices("300274");
        verify(eastMoneyNewsClient, times(1)).search("阳光电源");
        verify(policyNewsClient, times(1)).searchPolicy("电源设备");
    }

    @Test
    void theNoticeSourceGetsTheBareCodeNotTheMarketPrefixedSecid() {
        // **这条挡的是一个真踩过的坑。** 公告接口要的是 `stock_list=300274`（纯数字），
        // 而 resolvedCode 对个股是 `0.300274`、对池内 ETF 是 `SH510300`——两者都过不了
        // StockNoticeClient 的代码校验，于是公告那一路会**永远**回一句
        // 「公告源只接受数字或字母代码」，卡片一直是灰的。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));

        service.analyse("300274");

        verify(stockNoticeClient, times(1)).fetchNotices("300274");
        verify(stockNoticeClient, never()).fetchNotices("0.300274");
    }

    @Test
    void aSourceInCooldownIsNotDialledAtAll() {
        // 「冷却期内一次都不发」这条纪律的落点就在这里。发出去的话，
        // 我们会在明知对方正在按 IP 封我们的情况下继续打。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(cache.inCooldown(EastMoneyNewsClient.ORIGIN)).thenReturn(true);

        CodeLookupAnalysisDTO out = service.analyse("300274");

        verify(eastMoneyNewsClient, never()).search(anyString());
        // 另外两路照常发：冷却是按源的，不是一刀切。
        verify(stockNoticeClient, times(1)).fetchNotices(anyString());
        verify(policyNewsClient, times(1)).searchPolicy(anyString());
        assertThat(groupOf(out, NewsItem.Category.NEWS).state())
                .isEqualTo(CodeLookupAnalysisDTO.NewsGroupView.State.FAILED);
        assertThat(groupOf(out, NewsItem.Category.NEWS).note()).contains("冷却期内");
    }

    @Test
    void aThrottledSourceEntersCooldownAndOnlyThatCardTurnsGrey() {
        // 被限流时**必须进冷却**：否则下一次点击会接着打一个正在封我们的源。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(policyNewsClient.searchPolicy(anyString())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("被限流"));

        CodeLookupAnalysisDTO out = service.analyse("300274");

        verify(cache, times(1)).enterCooldown(PolicyNewsClient.ORIGIN);
        assertThat(groupOf(out, NewsItem.Category.POLICY).state())
                .isEqualTo(CodeLookupAnalysisDTO.NewsGroupView.State.FAILED);
        // 估值那一块完全没受影响——九维判定照常出。
        assertThat(out.dimensions()).isNotEmpty();
        assertThat(out.tendency()).isNotNull();
    }

    @Test
    void aSourceThatReturnsNothingIsEmptyNotFailed() {
        // **空 ≠ 坏。** 一只 ETF 的公告在这个接口上永远是 0 条（探针实测）。
        // 报成失败的话，页面会一直挂着一张灰卡说「源不可达」，而源其实是好的。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));

        CodeLookupAnalysisDTO out = service.analyse("300274");

        assertThat(groupOf(out, NewsItem.Category.NOTICE).state())
                .isEqualTo(CodeLookupAnalysisDTO.NewsGroupView.State.EMPTY);
        assertThat(groupOf(out, NewsItem.Category.NOTICE).note()).contains("源回 ");
    }

    @Test
    void anIndexGetsNoNoticeGroupAtAllRatherThanAnEmptyOne() {
        // 指数没有公告这个概念。**发一次再拿回空列表**会渲染出一张永远空着的卡，
        // 而「不发、也不出这张卡」才说得通：我们根本没去问，就不该假装问过。
        when(codeLookupService.lookup(any())).thenReturn(indexDto(CodeLookupDTO.Kind.INDEX));

        CodeLookupAnalysisDTO out = service.analyse("000300");

        assertThat(out.news()).extracting(CodeLookupAnalysisDTO.NewsGroupView::category)
                .containsExactly("NEWS", "POLICY");
        verifyNoInteractions(stockNoticeClient);
        // 指数照样查资讯与政策：它们是按名字/行业检索的，与有没有财报无关。
        verify(eastMoneyNewsClient, times(1)).search("沪深300");
    }

    @Test
    void anEtfStillAsksForNoticesBecauseEmptyIsInformative() {
        // 与上一条相对：ETF 的公告同样恒为空，但**要问**——
        // 不问的话，「基金公告不从这条路出」这件事我们永远不会知道。
        when(codeLookupService.lookup(any())).thenReturn(dto(CodeLookupDTO.Kind.INDEX_FUND));

        service.analyse("300274");

        verify(stockNoticeClient, times(1)).fetchNotices(anyString());
        assertThat(CodeLookupAnalysisService.wantsNotices(CodeLookupDTO.Kind.INDEX_FUND)).isTrue();
        assertThat(CodeLookupAnalysisService.wantsNotices(CodeLookupDTO.Kind.FUND)).isTrue();
        assertThat(CodeLookupAnalysisService.wantsNotices(CodeLookupDTO.Kind.STOCK)).isTrue();
        assertThat(CodeLookupAnalysisService.wantsNotices(CodeLookupDTO.Kind.INDEX)).isFalse();
        assertThat(CodeLookupAnalysisService.wantsNotices(CodeLookupDTO.Kind.OFF_POOL_INDEX)).isFalse();
    }

    @Test
    void storiesPickedByTheDigestReachTheGroupTheFrontendRenders() {
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(eastMoneyNewsClient.search(anyString())).thenReturn(NewsSourceResult.ok(
                NewsItem.Category.NEWS, EastMoneyNewsClient.ORIGIN, EastMoneyNewsClient.ORIGIN_LABEL,
                List.of(new NewsItem(NewsItem.Category.NEWS, EastMoneyNewsClient.ORIGIN,
                        EastMoneyNewsClient.ORIGIN_LABEL,
                        "阳光电源等在内蒙古成立智算科技公司", "证券时报",
                        LocalDate.of(2026, 10, 15), "https://a/1", null)), 1));

        CodeLookupAnalysisDTO out = service.analyse("300274");

        CodeLookupAnalysisDTO.NewsGroupView news = groupOf(out, NewsItem.Category.NEWS);
        assertThat(news.state()).isEqualTo(CodeLookupAnalysisDTO.NewsGroupView.State.OK);
        assertThat(news.items()).hasSize(1);
        assertThat(news.items().get(0).title()).isEqualTo("阳光电源等在内蒙古成立智算科技公司");
    }

    @Test
    void twoTitlesCarryingTheSameStoryLeaveOneItemInTheGroup() {
        // 同一事件被两家转载是常态，管线里靠 3-gram 近似判同。
        // **两条必须来自同一路源**：新闻这一路在本服务里只有一个源（东财资讯），
        // 想造「跨源」的场面得先有第二个源，而那是将来接入时才有的东西。
        // 写成两个不同源的返回体，测的其实是「两路各自回一条」——那样去重根本没被触发，
        // 而结果是 2 条，看起来却像是「去重没生效」。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(eastMoneyNewsClient.search(anyString())).thenReturn(NewsSourceResult.ok(
                NewsItem.Category.NEWS, EastMoneyNewsClient.ORIGIN, EastMoneyNewsClient.ORIGIN_LABEL,
                List.of(
                        news("阳光电源：拟在内蒙古投资建设智算中心项目", "证券时报", "https://a/1"),
                        news("阳光电源拟在内蒙古投资建设智算中心项目", "每日经济新闻", "https://a/2")), 2));

        CodeLookupAnalysisDTO out = service.analyse("300274");

        assertThat(groupOf(out, NewsItem.Category.NEWS).items()).hasSize(1);
        assertThat(groupOf(out, NewsItem.Category.NEWS).note()).contains("重复 1");
        // 留下的是权重高的那一路（东财资讯对两路一视同仁，那就比时效与标题分，
        // 这里两条同日、分相同——总之必须只剩一条，而不是「两条都在」。
    }

    private static NewsItem news(String title, String source, String url) {
        return new NewsItem(NewsItem.Category.NEWS, EastMoneyNewsClient.ORIGIN,
                EastMoneyNewsClient.ORIGIN_LABEL, title, source,
                LocalDate.of(2026, 10, 15), url, null);
    }

    private static CodeLookupAnalysisDTO.NewsGroupView groupOf(
            CodeLookupAnalysisDTO dto, NewsItem.Category category) {
        return dto.news().stream()
                .filter(g -> g.category().equals(category.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果里没有这一组：" + category));
    }

    // ==================================================================
    // 三、检索词
    // ==================================================================

    @Test
    void thePolicySubjectIsTheIndustryNotTheCompany() {
        // 扶持政策按行业出，不按公司出（章程 §9.12）。拿公司名去搜政策，
        // 召回的是这家公司的公司新闻，不是政策。
        assertThat(CodeLookupAnalysisService.policySubject(dto(false))).isEqualTo("电源设备");
    }

    @Test
    void indicesFallBackToTheirNameBecauseTheyHaveNoIndustry() {
        // 指数字段里没有行业（也没有 quote）。退回名称去搜「沪深300 政策」召回很杂，
        // 但比不发好，而且管线的相关性判据会把明显不相关的滤掉。
        assertThat(CodeLookupAnalysisService.policySubject(
                indexDto(CodeLookupDTO.Kind.INDEX))).isEqualTo("沪深300");
    }

    @Test
    void aOneCharacterNameIsNotUsedAsARelevanceAlias() {
        // 「A」这种名字会让「标题含 A」恒真，相关性判据等于没做。
        // 短于 2 个字的名字不进别名表，于是别名表里只剩代码。
        CodeLookupDTO oneChar = new CodeLookupDTO("600000", "1.600000", "A",
                CodeLookupDTO.Kind.STOCK, "沪市股票", null, null, null, null, null, null, null,
                List.of(), "2026-10-15T10:00:00", false);

        NewsDigestService.Subject subject = CodeLookupAnalysisService.subject(oneChar);

        assertThat(subject.aliases()).contains("600000");
        assertThat(subject.aliases()).doesNotContain("A");
    }

    @Test
    void theAliasListCarriesBothTheNameAndTheCodes() {
        NewsDigestService.Subject subject = CodeLookupAnalysisService.subject(dto(false));

        // 名称在前（命名的权重更高），两个代码都在——用户可能按代码搜到的新闻里
        // 只出现代码而不出现名字。
        assertThat(subject.aliases()).startsWith("阳光电源");
        assertThat(subject.aliases()).contains("0.300274", "300274");
    }

    // ==================================================================
    // 四、大模型解读
    // ==================================================================

    @Test
    void theBriefIsNotGeneratedUnlessAskedFor() {
        // 深度解读是**单独一次点击**：非流式、要花钱、未配 key 时永远空着。
        // 自动跑会把首屏从 1-3 秒拖到十几秒。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));

        service.analyse("300274");

        verifyNoInteractions(aiClientService);
    }

    @Test
    void theBriefIsBuiltOnTheSameSnapshotAndCitesItsDataMoment() {
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt()))
                .thenReturn("## 一句话结论\n当前数据呈现为偏正面。");

        AnalysisBriefDTO out = service.brief(dto(false), TODAY, NOW);

        assertThat(out.snapshotAt()).isEqualTo("2026-10-15T10:00:00");
        assertThat(out.brief().available()).isTrue();
        assertThat(out.brief().title()).isEqualTo(AnalysisBrief.TITLE_OK);

        // 材料里必须带数据时刻——模型被要求「引用时写明这个时刻」，
        // 材料里没有它的话模型只能编一个或者干脆不写。
        ArgumentCaptor<List<AiClientService.AiMessage>> captor = ArgumentCaptor.captor();
        verify(aiClientService).chat(captor.capture(), anyDouble(), anyInt(), anyInt());
        String user = captor.getValue().stream()
                .filter(m -> "user".equals(m.role())).findFirst().orElseThrow().content();
        assertThat(user).contains("2026-10-15T10:00:00");
        assertThat(user).contains("阳光电源");
        assertThat(captor.getValue().get(0).role()).isEqualTo("system");
    }

    @Test
    void aKeyMissingFailureIsNotRenderedAsAModelAnswer() {
        // AiClientServiceImpl 失败时不抛异常，而是返回一句以 `AI ` 开头的文案。
        // 认不出来的话，页面上会显示一段「AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。」
        // 并**把它当成模型的解读正文**排版——看起来像是模型说的。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt()))
                .thenReturn("AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。");

        AnalysisBriefDTO out = service.brief(dto(false), TODAY, NOW);

        assertThat(out.brief().available()).isFalse();
        assertThat(out.brief().title()).isEqualTo(AnalysisBrief.TITLE_UNAVAILABLE);
        // 原文留着——它是要给管理员看的排查信息
        assertThat(out.brief().text()).contains("DEEPSEEK_API_KEY");
        assertThat(out.brief().note()).contains("规则判定与新闻列表不受影响");
    }

    @Test
    void aCallFailureIsAlsoNotRenderedAsAModelAnswer() {
        // 另一种失败前缀。**两种都要认**：只认其中一句的话，另一种会漏成正文。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt()))
                .thenReturn("AI 调用失败：Connection timed out");

        AnalysisBriefDTO out = service.brief(dto(false), TODAY, NOW);

        assertThat(out.brief().available()).isFalse();
        assertThat(out.brief().title()).isEqualTo(AnalysisBrief.TITLE_UNAVAILABLE);
    }

    @Test
    void anEmptyAnswerCountsAsUnavailableNotAsAnEmptyBrief() {
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt())).thenReturn("   ");

        AnalysisBriefDTO out = service.brief(dto(false), TODAY, NOW);

        assertThat(out.brief().available()).isFalse();
    }

    @Test
    void clickingTwiceOnTheSameSnapshotDoesNotPayTwiceAndDoesNotDialTheNewsSources() {
        // 缓存键是「代码 + 快照取数时刻」，所以同一份快照重复点击不再计费，
        // **而且不再打那三路新闻源**——重跑一遍管线只为拿一份已经算过的材料，是纯浪费。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt()))
                .thenReturn("## 一句话结论\n偏正面。");

        service.brief(dto(false), TODAY, NOW);
        service.brief(dto(false), TODAY, NOW.plusSeconds(60));

        verify(aiClientService, times(1)).chat(anyList(), anyDouble(), anyInt(), anyInt());
        verify(stockNoticeClient, times(1)).fetchNotices(anyString());
        verify(eastMoneyNewsClient, times(1)).search(anyString());
        verify(policyNewsClient, times(1)).searchPolicy(anyString());
    }

    @Test
    void aDifferentSnapshotMissesTheCacheEvenADayLaterAndEvenForTheSameCode() {
        // 只按代码缓存的话，隔天点开同一只票会拿到昨天的解读，而解读里的每个数字
        // 都来自昨天——两个日期对不上的页面比没有页面更糟（章程 §8）。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt()))
                .thenReturn("## 一句话结论\n偏正面。");

        service.brief(dto(false), TODAY, NOW);

        CodeLookupDTO nextDay = withSnapshot(dto(false), "2026-10-16T10:00:00");
        service.brief(nextDay, TODAY.plusDays(1), NOW.plusSeconds(86400));

        verify(aiClientService, times(2)).chat(anyList(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    void aFailureIsCachedTooSoFiveClicksDoNotBuyFiveIdenticalFailures() {
        // 未配置 key、模型超时这些都不是「过一秒就好」的状态。不缓存的话，
        // 用户连点五次按钮就打五次接口换回同一句话。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt()))
                .thenReturn("AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。");

        for (int i = 0; i < 5; i++) {
            service.brief(dto(false), TODAY, NOW.plusSeconds(i));
        }

        verify(aiClientService, times(1)).chat(anyList(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    void theBriefUsesTheConfiguredTimeoutAndTokenBudget() {
        // 这两个值不传下去的话，这一段会退回「Apache 默认 = 无限超时」——
        // 一次卡住就把整页拖到浏览器先超时，而页面上看不出是哪一段慢。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt()))
                .thenReturn("## 一句话结论\n偏正面。");

        service.brief(dto(false), TODAY, NOW);

        verify(aiClientService).chat(anyList(), eq(0.3), eq(1200), eq(25000));
    }

    @Test
    void theBriefAndTheVerdictAreBuiltFromTheSameRuleResult() {
        // 判定卡与大模型材料里的判定必须同源：模型只被允许复述它。
        // 两边各算一次的话，改了阈值就可能出现「卡片说偏负面、解读说偏正面」。
        when(codeLookupService.lookup(any())).thenReturn(dto(false));
        when(aiClientService.chat(anyList(), anyDouble(), anyInt(), anyInt()))
                .thenReturn("## 一句话结论\n偏正面。");

        service.brief(dto(false), TODAY, NOW);

        ArgumentCaptor<List<AiClientService.AiMessage>> captor = ArgumentCaptor.captor();
        verify(aiClientService).chat(captor.capture(), anyDouble(), anyInt(), anyInt());
        String user = captor.getValue().get(1).content();
        AnalysisRules.TendencyVerdict expected = AnalysisRules.analyse(dto(false), TODAY).tendency();
        assertThat(user).contains(expected.tendency().label());
    }

    /**
     * 只换快照时刻与缓存标记，其余原样抄。放在这里而不是让每条测试自己 new 一遍，
     * 是因为 {@link CodeLookupDTO} 有 15 个分量——手写一遍漏一个就是一条测不到东西的测试。
     */
    private static CodeLookupDTO rebuild(CodeLookupDTO d, String snapshotAt, boolean fromCache) {
        return new CodeLookupDTO(d.code(), d.resolvedCode(), d.name(), d.kind(),
                d.kindLabel(), d.quote(), d.fundamentals(), d.currentPrice(),
                d.currentPriceDate(), d.priceLookbacks(), d.position(), d.valuation(),
                d.notes(), snapshotAt, fromCache);
    }

    private static CodeLookupDTO withSnapshot(CodeLookupDTO d, String snapshotAt) {
        return rebuild(d, snapshotAt, d.fromCache());
    }
}
