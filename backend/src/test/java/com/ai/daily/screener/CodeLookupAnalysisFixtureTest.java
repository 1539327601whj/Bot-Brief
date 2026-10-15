package com.ai.daily.screener;

import com.ai.daily.service.AiClientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 把「深度分析」的返回体**从真实代码里导出一份**，给前端假接口用。
 *
 * <p>为什么值得单独一个测试：前端要能不看后端就把这一页画出来比对版式
 * （这是这一页一贯的验证方式），而手抄一份 JSON 夹具的宿命是**总有一天与 DTO 对不上**——
 * 对不上的表现还特别不显眼：字段名写错的那个格子会静静地显示成「—」，
 * 看起来像「这次没取到数」，而不是像「夹具坏了」。
 *
 * <p>所以夹具由**真实的 {@link CodeLookupAnalysisService} 导出**（三个源用 Mockito 打桩，
 * 走的编排、判定、清洗、分组一个字都不改），每次 {@code mvn test} 重写一遍，
 * 输出到 {@code target/} 下——不落进版本库，也就不存在「忘了更新夹具」这件事。
 *
 * <h2>两个案例，各自覆盖一半的渲染分支</h2>
 *
 * <ul>
 *   <li><b>个股</b>：七维里刻意凑齐<b>三种颜色</b>（偏正面 / 中性 / 偏负面），
 *       外加一个「未确认」的指标格、一条缺档的价格档、以及三组新闻里的
 *       <b>失败 / 空 / 正常</b>三种状态。缺任何一样，截图比对就看不出它长什么样。</li>
 *   <li><b>指数</b>：没有财报 → 三个维度 {@code NOT_APPLICABLE}，
 *       于是「未计入本次倾向」那一行、以及「指数不发公告那一路所以只有两张新闻卡」
 *       这两件事才有得比。它的倾向按 §9.6 的 D≥3 规则必然是「数据不足」。</li>
 * </ul>
 */
class CodeLookupAnalysisFixtureTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final Instant NOW = Instant.parse("2026-10-15T06:00:00Z");

    /**
     * 源的名字与页面写法**从常数取**，不在夹具里手抄。
     *
     * <p>手抄过一次就出过一次错：口径名写成 {@code PE_TTM_ROLLING_10Y}，
     * 而线上是 {@code EM_VALUEANALYSIS_PE_TTM_ROLLING_10Y}（差一个前缀），
     * 来源写成「中证官网」而线上是「中证官网（中证口径）」。这种错在截图上
     * **看起来完全正常**，只是与真页面差几个字——那正是夹具最不该有的偏差。
     */
    private static final String EM = MarketDataClient.PROVIDER_EASTMONEY;
    private static final String EASTMONEY = CodeLookupService.sourceLabel(EM);

    /**
     * 一个**与线上同形**的 ObjectMapper。
     *
     * <p>只挂 {@code JavaTimeModule} 是不够的：Jackson 默认
     * {@code WRITE_DATES_AS_TIMESTAMPS=true}，于是 {@code LocalDate} 会被写成
     * <b>{@code [2026,10,15]} 这样一个数组</b>。线上不是这样——Spring Boot 的
     * {@code Jackson2ObjectMapperBuilder} 默认把这个特性关掉，日期是 ISO 字符串。
     *
     * <p>这个差别看着无害，其实会把夹具的验证力整段废掉：数组喂给前端，
     * React 会把元素拼起来显示成 {@code 20261015}——不报错、不空白，只是难看，
     * 于是一张「整页都对、日期不对」的截图会被当成版式比对通过了。
     * 所以这里显式关掉时间戳，并在 {@link #theFixtureIsWrittenAndItRoundTrips()} 里
     * 把日期形态断言死。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 夹具落在这儿：构建产物目录，不进版本库。前端脚本默认读这个路径。 */
    static final Path OUTPUT = Path.of("target", "analysis-fixture.json");

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

        when(aiClientService.chat(ArgumentMatchers.anyList(), ArgumentMatchers.anyDouble(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt())).thenReturn(BRIEF);

        service = new CodeLookupAnalysisService(codeLookupService, stockNoticeClient,
                eastMoneyNewsClient, policyNewsClient, new NewsDigestService(), cache,
                aiClientService, 6000, 1800L, 1200, 25000);
    }

    @Test
    void theFixtureIsWrittenAndItRoundTrips() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("stock", scenario(stockDto(), stockNews(), true));
        root.put("index", scenario(indexDto(), indexNews(), true));
        // 池外指数那一张：大模型**故意让它不可用**——这一段是 §9 里写明的降级路径
        // （没配 key / 调用超时），页面上要能把「这一块没生成 + 怎么开」说清楚，
        // 而上面几块照常。这条路径没有别的截图能看到。
        root.put("offpool", scenario(offPoolDto(), offPoolNews(), false));

        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, json, StandardCharsets.UTF_8);

        // 能读回来才算数——写出去一份前端读不了的 JSON，等于把「夹具坏了」
        // 伪装成「这一格没取到数」，而那正是这个夹具要防的事。
        JsonNode read = MAPPER.readTree(Files.readString(OUTPUT, StandardCharsets.UTF_8));

        JsonNode stock = read.get("stock");
        assertThat(stock.get("lookup").get("priceLookbacks")).hasSize(8);
        // 日期必须是字符串。写成数组前端不会报错，只会显示成「20261015」——
        // 那种错**看起来像真的**，所以在这里钉死，而不是等着人去截图里发现。
        JsonNode publishedOn = stock.get("analysis").get("news").get(0).get("items").get(0)
                .get("publishedOn");
        assertThat(publishedOn.isTextual())
                .as("publishedOn 是 ISO 字符串（线上形态），不是 [2026,10,15] 数组")
                .isTrue();
        assertThat(publishedOn.asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(stock.get("lookup").get("valuation").get("lookbacks")).hasSize(8);
        // 三种颜色凑齐：偏正面 / 中性 / 偏负面。少一种，截图上就少一种颜色可比。
        assertThat(stances(stock.get("analysis"))).contains("FAVORABLE", "NEUTRAL", "UNFAVORABLE");
        // 一条「未确认」的指标格：它要显示成「未取到」并且不进净分
        assertThat(metrics(stock.get("analysis"))).contains("INSUFFICIENT");
        // 失败的新闻组出现在个股那张图上（政策源挂了），它要变灰且不影响另外两组
        assertThat(groupStates(stock.get("analysis"))).containsExactly("OK", "OK", "FAILED");
        assertThat(stock.get("brief").get("brief").get("available").asBoolean()).isTrue();
        // 解读里的那句话必须与本地判定**同调**。模型只能复述规则倾向（§9.12），
        // 一份写着「需等待」而徽标写着「偏可关注」的夹具，在截图上看起来就是我们自己打自己——
        // 这就是一条真正会被误读成 bug 的夹具偏差，钉死它。
        assertThat(stock.get("brief").get("brief").get("text").asText())
                .as("解读的结论要复述本地倾向")
                .contains(AnalysisRules.Tendency.WATCH.label());

        JsonNode index = read.get("index");
        // 池内指数**有报价**（拿的是代表 ETF 那一行）：这一条钉住的是那个最容易搞错的
        // 事实——裸代码 000300 的行情表键是 510300。填成 null 的话，整页会显示成
        // 「行情源明确回了『没有这只标的』」，那是线上不会出现的状态。
        assertThat(index.get("lookup").get("quote").isNull())
                .as("池内指数取的是代表 ETF 的行情，不该是 null").isFalse();
        assertThat(index.get("lookup").get("quote").get("name").asText()).isEqualTo("沪深300ETF");
        assertThat(index.get("lookup").get("fundamentals").get("available").asBoolean()).isTrue();
        // ETF 那一行没有财报字段，但换手率/成交额/流通市值是有的——两者必须同时成立，
        // 否则「没有财报」会被误读成「这一块整个没取到」
        assertThat(index.get("lookup").get("fundamentals").get("turnoverRate").isNull())
                .as("ETF 有换手率（f8）").isFalse();
        assertThat(index.get("lookup").get("fundamentals").get("roe").isNull())
                .as("ETF 不该有 ROE——夹具填了就是编的").isTrue();
        // 指数没有财报：三个维度判「不适用」，它们不进卡片但要出现在「未计入」里
        assertThat(stances(index.get("analysis"))).contains("NOT_APPLICABLE");
        // 「源正常回、只是没有条目」出现在指数那张图上。它与 FAILED 必须长得不一样，
        // 所以两种状态要落在同一份夹具里、分两张图看。
        assertThat(groupStates(index.get("analysis"))).contains("EMPTY");
        // 池内指数这次是**有倾向的**（估值 + 规模与流动性 + 技术面都判得出来，
        // D = 3 正好够）——「数据不足」那一支由池外指数那张图覆盖
        assertThat(index.get("analysis").get("tendency").get("countedDimensions").asInt())
                .isEqualTo(3);
        assertThat(index.get("analysis").get("tendency").get("tendency").asText())
                .isNotEqualTo("INSUFFICIENT");
        assertThat(index.get("analysis").get("tendency").get("excluded").size()).isEqualTo(3);
        // 指数不发公告那一路，所以只有两张新闻卡——不是三张空卡
        assertThat(index.get("analysis").get("news")).hasSize(2);

        JsonNode offpool = read.get("offpool");
        // 池外指数不取行情：没有现价，基本面那块是「本次没有取行情」而不是「没有 ROE」
        assertThat(offpool.get("lookup").get("quote").isNull()).isTrue();
        assertThat(offpool.get("lookup").get("fundamentals").get("notes").get(0).asText())
                .contains("没有取行情");
        // 参判维度只有 2 个（估值 + 技术面）→ 按 §9.6 必然「数据不足」。
        // **这不是这一次没取到，是这一类标的永久如此**，截图要能看出这个区别。
        assertThat(offpool.get("analysis").get("tendency").get("tendency").asText())
                .isEqualTo("INSUFFICIENT");
        assertThat(offpool.get("analysis").get("tendency").get("countedDimensions").asInt())
                .isLessThan(3);
        // 体检整体不适用：对着没有行情行的指数跑 V1–V12 只会得到一串「字段缺失」
        assertThat(offpool.get("analysis").get("checkup").get("applicable").asBoolean()).isFalse();
        // 大模型不可用 → 这一块要显示成「未生成 + 怎么开」，而不是一片空白
        assertThat(offpool.get("brief").get("brief").get("available").asBoolean()).isFalse();
        assertThat(offpool.get("brief").get("brief").get("text").asText()).startsWith("AI ");
    }

    /**
     * 一个案例：主查询那一份数字、判定与新闻、以及一份解读。
     *
     * @param aiUp false 时这一次的大模型调用返回失败文案（{@code AI } 开头）——
     *             降级路径要与正常路径**在同一份夹具里各占一格**，否则页面上
     *             「这一块没生成」长什么样只能靠读代码想象
     */
    private Map<String, Object> scenario(CodeLookupDTO lookup, List<NewsSourceResult> sources,
                                         boolean aiUp) {
        when(codeLookupService.lookup(anyString())).thenReturn(lookup);
        stubSources(sources, lookup.kind());
        when(aiClientService.chat(ArgumentMatchers.anyList(), ArgumentMatchers.anyDouble(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
                .thenReturn(aiUp ? BRIEF : AI_NOT_CONFIGURED);
        return new LinkedHashMap<>(Map.of(
                "lookup", lookup,
                "analysis", service.analyse(lookup, TODAY),
                "brief", service.brief(lookup, TODAY, NOW)));
    }

    private void stubSources(List<NewsSourceResult> sources, CodeLookupDTO.Kind kind) {
        when(stockNoticeClient.fetchNotices(anyString())).thenReturn(find(sources, NewsItem.Category.NOTICE));
        when(eastMoneyNewsClient.search(anyString())).thenReturn(find(sources, NewsItem.Category.NEWS));
        when(policyNewsClient.searchPolicy(anyString())).thenReturn(find(sources, NewsItem.Category.POLICY));
    }

    private static NewsSourceResult find(List<NewsSourceResult> sources, NewsItem.Category category) {
        return sources.stream().filter(s -> s.category() == category).findFirst()
                .orElseGet(() -> NewsSourceResult.ok(category, "unused", "未使用", List.of(), 0));
    }

    // ------------------------------------------------------------------
    // 断言用的小工具
    // ------------------------------------------------------------------

    /** **只要维度那一层的判定**，不是每一格指标的判定——两者同名，深度遍历会把两层混在一起。 */
    private static List<String> stances(JsonNode analysis) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode dimension : analysis.get("dimensions")) {
            out.add(dimension.get("stance").asText());
        }
        return out;
    }

    private static List<String> metrics(JsonNode analysis) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode dimension : analysis.get("dimensions")) {
            for (JsonNode metric : dimension.get("metrics")) {
                out.add(metric.get("stance").asText());
            }
        }
        return out;
    }

    private static List<String> groupStates(JsonNode analysis) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode group : analysis.get("news")) {
            out.add(group.get("state").asText());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 案例一：个股
    // ------------------------------------------------------------------

    private static List<NewsSourceResult> stockNews() {
        return List.of(
                NewsSourceResult.ok(NewsItem.Category.NOTICE,
                        StockNoticeClient.ORIGIN, StockNoticeClient.ORIGIN_LABEL,
                        List.of(
                                notice("阳光电源:关于控股股东部分股份解除质押的公告",
                                        "股份质押、冻结", "2026-10-15"),
                                notice("阳光电源:2026年半年度权益分派实施公告", "分红送配", "2026-09-28"),
                                notice("阳光电源:关于回购公司股份的进展公告", "回购", "2026-09-12")),
                        3),
                NewsSourceResult.ok(NewsItem.Category.NEWS,
                        EastMoneyNewsClient.ORIGIN, EastMoneyNewsClient.ORIGIN_LABEL,
                        List.of(
                                news("阳光电源等在内蒙古成立智算科技公司", "证券时报", "2026-10-15",
                                        "https://finance.eastmoney.com/a/202610153171234567.html"),
                                news("阳光电源：上半年储能出货量同比增长四成", "中国证券报", "2026-09-30",
                                        "https://finance.eastmoney.com/a/202609303171234568.html")),
                        2),
                // 政策那一路**失败**：页面要出一张变灰的卡，且不影响另外两张。
                NewsSourceResult.failed(NewsItem.Category.POLICY,
                        PolicyNewsClient.ORIGIN, PolicyNewsClient.ORIGIN_LABEL,
                        "政策源返回的不是 RSS（根元素是 <html>）"));
    }

    /**
     * 一只「有正有负」的个股。
     *
     * <p>几个数是**照着凑三色**挑的，不是随便写的：毛利率 12% 与资产负债率 68%
     * 各让一维判「偏负面」，股息率不填让估值那一维出现一格「未确认」；
     * 价格那一行的「十年」故意缺档，好让 `MissingCells` 那条分支出现在截图里。
     */
    private static CodeLookupDTO stockDto() {
        return new CodeLookupDTO("300274", "0.300274", "阳光电源",
                CodeLookupDTO.Kind.STOCK, "深市股票",
                new CodeLookupDTO.QuoteView(EM, EASTMONEY, "阳光电源",
                        new BigDecimal("85.00"), new BigDecimal("1.20"),
                        new BigDecimal("18"), new BigDecimal("2.2"), "电源设备",
                        new BigDecimal("180000000000")),
                new CodeLookupDTO.FundamentalsView(true, EM, EASTMONEY,
                        new BigDecimal("18"), new BigDecimal("25"), new BigDecimal("30"),
                        new BigDecimal("12"), new BigDecimal("68"), null,
                        // 换手率 0.7% 落在「0.5–1%」的中性带里——三色里必须有一个中性，
                        // 否则截图上看不出中性长什么样
                        new BigDecimal("0.7"), new BigDecimal("1200000000"),
                        new BigDecimal("150000000000"), new BigDecimal("5.0"),
                        new BigDecimal("20"), new BigDecimal("30"), "2011-11-02", List.of()),
                new BigDecimal("85.00"), "2026-10-14",
                List.of(
                        cell("昨", "84.00", "2026-10-14", "1.19"),
                        cell("周", "82.50", "2026-10-08", "3.03"),
                        cell("月", "79.00", "2026-09-15", "7.59"),
                        cell("半年", "91.00", "2026-04-15", "-6.59"),
                        cell("一年", "70.00", "2025-10-15", "21.43"),
                        cell("三年", "120.00", "2023-10-16", "-29.17"),
                        cell("五年", "60.00", "2021-10-15", "41.67"),
                        new CodeLookupDTO.LookbackCell("十年", null, null, null,
                                "本次只有 400 根日线（最早 2025-01-02），超出这个跨度的档位未确认")),
                new CodeLookupDTO.PositionView(true, new BigDecimal("35"), new BigDecimal("-10"),
                        new BigDecimal("2.5"), 400, "2026-10-14", List.of()),
                new CodeLookupDTO.ValuationView(true, CodeLookupService.SOURCE_VALUEANALYSIS,
                        CodeLookupService.LABEL_VALUEANALYSIS_SOURCE,
                        StockValuationClient.METHOD_STOCK_VALUATION_ROLLING_10Y,
                        new BigDecimal("18"), new BigDecimal("25"),
                        "2026-10-14", 2400, "2016-10-14", "2026-10-14",
                        List.of(
                                // **基线是分位（25.40），不是 PE（18.20）。** 估值八档走的是
                                // `ChangeStyle.POINTS`，序列里放的就是每天的分位，变化量是
                                // 「今 − 基线」的百分点差（LookbackCalculator）；填成 PE 的话
                                // 页面上「今 25、昨 18.20、变化 −0.40」三件事互相矛盾，
                                // 而截图上看不出来——正是这个夹具要防的那种错。
                                peCell("昨", "25.40", "2026-10-14", "-0.40"),
                                peCell("周", "26.62", "2026-10-08", "-1.62"),
                                peCell("月", "27.63", "2026-09-15", "-2.63"),
                                peCell("半年", "43.18", "2026-04-15", "-18.18"),
                                peCell("一年", "50.00", "2025-10-15", "-25.00"),
                                peCell("三年", "65.00", "2023-10-16", "-40.00"),
                                peCell("五年", "55.77", "2021-10-15", "-30.77"),
                                peCell("十年", "35.00", "2016-10-14", "-10.00")),
                        List.of("估值历史里有 143 天没有 PE（亏损或未披露），未计入窗口。")),
                List.of(), "2026-10-15T10:00:00", false);
    }

    // ------------------------------------------------------------------
    // 案例二：指数（没有财报，也不发公告那一路）
    // ------------------------------------------------------------------

    private static List<NewsSourceResult> indexNews() {
        return List.of(
                NewsSourceResult.ok(NewsItem.Category.NEWS,
                        EastMoneyNewsClient.ORIGIN, EastMoneyNewsClient.ORIGIN_LABEL,
                        List.of(news("沪深300指数样本股将迎来季度调整", "上海证券报", "2026-10-09",
                                "https://finance.eastmoney.com/a/202610093171234569.html")),
                        1),
                // 政策这一路**源正常、但一条都没匹配上**：这是 `EMPTY`，不是失败。
                // 与个股那张图上的 `FAILED` 摆在一起比，才看得出两者确实长得不一样。
                NewsSourceResult.ok(NewsItem.Category.POLICY,
                        PolicyNewsClient.ORIGIN, PolicyNewsClient.ORIGIN_LABEL, List.of(), 0));
    }

    /** 池外指数：一样的两组（它同样不发公告，那一组不取）。政策这一路这次有两条。 */
    private static List<NewsSourceResult> offPoolNews() {
        return List.of(
                NewsSourceResult.ok(NewsItem.Category.NEWS,
                        EastMoneyNewsClient.ORIGIN, EastMoneyNewsClient.ORIGIN_LABEL,
                        List.of(news("中证指数公司调整部分指数样本", "中国证券报", "2026-10-12",
                                "https://finance.eastmoney.com/a/202610123171234570.html")),
                        1),
                NewsSourceResult.ok(NewsItem.Category.POLICY,
                        PolicyNewsClient.ORIGIN, PolicyNewsClient.ORIGIN_LABEL,
                        List.of(news("关于促进长期资金入市的指导意见发布", "中国政府网", "2026-10-08",
                                "https://news.google.com/rss/articles/CBMiabcdefg")),
                        1));
    }

    /**
     * 一个池内指数（裸代码 {@code 000300}）。
     *
     * <p><b>它有报价，取的是代表 ETF 的行情</b>——这是这一页最容易搞错的一处：
     * {@code CodeLookupService.fetchQuote} 用的是 {@code secid} 点号后面那一段（{@code 1.510300}
     * → {@code 510300}），注释里写着「用 resolvedCode 去查会永远差一个空，表现成
     * 『这个 ETF 没有报价』，而它明明就在池子里」。所以页面上的名称是**ETF 的名字**、
     * 最新价是 ETF 的价格，而标的一栏写着「池内指数」。
     *
     * <p>夹具早先这里填的是 {@code quote = null} + {@code noQuote()}，于是整页变成
     * 「行情源明确回了『没有这只标的』的报价」+ 排雷体检四条「字段缺失」+「疑似停牌」——
     * 那是**线上不会出现的状态**，而截图会把它当成页面长得对。
     *
     * <p>{@code fundamentals} 也不能是 null：服务端从不把它给成 null
     * （{@code quote == null} 时走 {@link CodeLookupDTO.FundamentalsView#noQuote()}），
     * 这里写个 null，前端会因为 `fundamentals.available` 读到 null 而整页白屏。
     * ETF 那一行**没有财报字段**（ROE / 毛利率 / 负债率 / 股息率全空），
     * 但换手率、成交额、流通市值、60 日涨跌、年初至今、上市日是有的。
     */
    private static CodeLookupDTO indexDto() {
        return new CodeLookupDTO("000300", "SH000300", "沪深300ETF",
                CodeLookupDTO.Kind.INDEX, "池内指数",
                new CodeLookupDTO.QuoteView(EM, EASTMONEY, "沪深300ETF",
                        new BigDecimal("4.05"), new BigDecimal("0.52"),
                        null, null, null, new BigDecimal("15000000000")),
                new CodeLookupDTO.FundamentalsView(true, EM, EASTMONEY,
                        null, null, null, null, null, null,
                        new BigDecimal("1.2"), new BigDecimal("860000000"),
                        new BigDecimal("15000000000"), new BigDecimal("3.5"),
                        new BigDecimal("12.0"), null, "2012-05-28", List.of()),
                new BigDecimal("4.05"), "2026-10-14",
                List.of(
                        cell("昨", "4.03", "2026-10-14", "0.50"),
                        cell("周", "4.01", "2026-10-08", "1.00"),
                        cell("月", "3.95", "2026-09-15", "2.53"),
                        cell("半年", "3.80", "2026-04-15", "6.58"),
                        cell("一年", "3.62", "2025-10-15", "11.88"),
                        cell("三年", "3.55", "2023-10-16", "14.08"),
                        cell("五年", "3.30", "2021-10-15", "22.73"),
                        cell("十年", "2.80", "2016-10-14", "44.64")),
                new CodeLookupDTO.PositionView(true, new BigDecimal("40"), new BigDecimal("-8"),
                        new BigDecimal("1.2"), 400, "2026-10-14", List.of()),
                new CodeLookupDTO.ValuationView(true, CodeLookupService.SOURCE_CSINDEX,
                        CodeLookupService.LABEL_CSINDEX_SOURCE,
                        IndexFundPool.METHOD_CSINDEX_ROLLING_10Y,
                        new BigDecimal("14"), new BigDecimal("40"),
                        "2026-10-14", 2400, "2016-10-14", "2026-10-14",
                        List.of(
                                peCell("昨", "40.36", "2026-10-14", "-0.36"),
                                peCell("周", "41.00", "2026-10-08", "-1.00"),
                                peCell("月", "42.00", "2026-09-15", "-2.00"),
                                peCell("半年", "45.00", "2026-04-15", "-5.00"),
                                peCell("一年", "50.00", "2025-10-15", "-10.00"),
                                peCell("三年", "60.00", "2023-10-16", "-20.00"),
                                peCell("五年", "48.00", "2021-10-15", "-8.00"),
                                peCell("十年", "45.00", "2016-10-14", "-5.00")),
                        List.of("指数估值的分位口径为中证官网口径，与其他来源不同，不可横向比较。")),
                List.of("判为池内指数，实际查询代码 SH000300"), "2026-10-15T10:00:00", false);
    }

    // ------------------------------------------------------------------
    // 案例三：池外指数（不取行情，于是没有现价、也没有可跑的体检）
    // ------------------------------------------------------------------

    /**
     * 一个池外指数（{@code 930740}，中证官网口径）。
     *
     * <p>它和池内指数**处处不同**，这也是它值得单独一张图的原因：不取行情（指数代码在报价
     * 接口里没有可靠的 secid 形态），所以最新价那一块整块换成一句「为什么没有」；
     * 技术面与估值还在（日线取自中证那条代码），但**参判维度只有 2 个**，
     * 按 §9.6 的 {@code D ≥ 3} 必然判「数据不足」——这一条是永久成立的，不是这一次没取到。
     *
     * <p>排雷体检整体不适用（{@code applicable = false}）：对着一个没有行情行、
     * 没有财报字段的指数跑 V1–V12，只会得到一串「字段缺失」。
     */
    private static CodeLookupDTO offPoolDto() {
        return new CodeLookupDTO("930740", "930740", null,
                CodeLookupDTO.Kind.OFF_POOL_INDEX, "池外指数（中证口径）",
                null, CodeLookupDTO.FundamentalsView.noQuote(),
                new BigDecimal("6120.50"), "2026-10-14",
                List.of(
                        cell("昨", "6088.20", "2026-10-14", "0.53"),
                        cell("周", "6010.40", "2026-10-08", "1.83"),
                        cell("月", "5890.00", "2026-09-15", "3.91"),
                        cell("半年", "5520.80", "2026-04-15", "10.86"),
                        cell("一年", "5200.00", "2025-10-15", "17.70"),
                        cell("三年", "4800.00", "2023-10-16", "27.51"),
                        cell("五年", "5100.00", "2021-10-15", "20.01"),
                        cell("十年", "3000.00", "2016-10-14", "104.02")),
                new CodeLookupDTO.PositionView(true, new BigDecimal("62"), new BigDecimal("-5"),
                        new BigDecimal("4.8"), 400, "2026-10-14", List.of()),
                new CodeLookupDTO.ValuationView(true, CodeLookupService.SOURCE_CSINDEX,
                        CodeLookupService.LABEL_CSINDEX_SOURCE,
                        IndexFundPool.METHOD_CSINDEX_ROLLING_10Y,
                        new BigDecimal("31"), new BigDecimal("58"),
                        "2026-10-14", 2400, "2016-10-14", "2026-10-14",
                        List.of(
                                peCell("昨", "58.40", "2026-10-14", "-0.40"),
                                peCell("周", "60.00", "2026-10-08", "-2.00"),
                                peCell("月", "64.00", "2026-09-15", "-6.00"),
                                peCell("半年", "52.00", "2026-04-15", "6.00"),
                                peCell("一年", "45.00", "2025-10-15", "13.00"),
                                peCell("三年", "70.00", "2023-10-16", "-12.00"),
                                peCell("五年", "62.00", "2021-10-15", "-4.00"),
                                peCell("十年", "50.00", "2016-10-14", "8.00")),
                        List.of("指数估值的分位口径为中证官网口径，与其他来源不同，不可横向比较。")),
                List.of("判为池外指数（中证口径），实际查询代码 930740",
                        "池外指数不取现价与涨跌幅（指数代码在报价源里没有可靠的形态），"
                                + "价格八档与估值照常"),
                "2026-10-15T10:00:00", false);
    }

    // ------------------------------------------------------------------
    // 素材
    // ------------------------------------------------------------------

    private static NewsItem notice(String title, String category, String day) {
        return new NewsItem(NewsItem.Category.NOTICE, StockNoticeClient.ORIGIN,
                StockNoticeClient.ORIGIN_LABEL, title, null, LocalDate.parse(day),
                "https://data.eastmoney.com/notices/detail/300274/" + day.replace("-", "") + ".html",
                category);
    }

    private static NewsItem news(String title, String source, String day, String url) {
        return new NewsItem(NewsItem.Category.NEWS, EastMoneyNewsClient.ORIGIN,
                EastMoneyNewsClient.ORIGIN_LABEL, title, source, LocalDate.parse(day), url, null);
    }

    private static CodeLookupDTO.LookbackCell cell(String label, String baseline, String date,
                                                   String change) {
        return new CodeLookupDTO.LookbackCell(label, new BigDecimal(baseline), date,
                new BigDecimal(change), null);
    }

    private static CodeLookupDTO.LookbackCell peCell(String label, String baseline, String date,
                                                     String change) {
        return peCell(label, new BigDecimal(baseline), date, change);
    }

    private static CodeLookupDTO.LookbackCell peCell(String label, BigDecimal baseline,
                                                     String date, String change) {
        return new CodeLookupDTO.LookbackCell(label, baseline, date, new BigDecimal(change), null);
    }

    /**
     * 大模型没配 key 时 {@code AiClientServiceImpl} 返回的那**一句文案**（不是异常）。
     *
     * <p>前缀 {@code AI } 是这一段的失败判据（章程 §9.12），所以这里必须逐字对齐——
     * 页面上要显示的是「这一块没生成 + 怎么开」，判据写错的话它会渲染成一个空的解读。
     */
    private static final String AI_NOT_CONFIGURED =
            "AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。";

    /**
     * 一份「像模像样」的解读。**故意留一句带「推断：」的句子**，
     * 页面上要看得出事实与推断是怎么分开的；数字全部取自上面那份夹具，不许自创。
     */
    private static final String BRIEF = """
            ## 一句话结论
            本地规则判为「偏可关注」：估值与成长偏正面，但盈利质量与财务健康各有一条越线。

            ## 各维度怎么摆
            - 估值偏正面：PE(TTM) 18.0，PE 历史分位 25%（数据日 2026-10-14）；股息率未取到。
            - 成长偏正面：营收同比 +25%，净利同比 +30%（行情接口，数据日 2026-10-14）。
            - 盈利质量偏负面：毛利率 12%，低于 15% 的下沿。
            - 财务健康偏负面：资产负债率 68%，高于 60% 的上沿。
            - 技术面偏正面：一年价格分位 35%，对 MA250 +2.5%。
            - 动量偏正面：60 日涨跌 +5.0%。资金面中性：换手率 0.7%。

            ## 主要依据
            - PE 历史分位 25%，窗口 2016-10-14 至 2026-10-14，来源东财估值分析。
            - 价格回看的「今」为 85.000，基线日 2026-10-14。

            ## 反面风险
            - 资产负债率 68.0%，来源行情接口，数据日 2026-10-14。
            - 毛利率 12.0%，同一来源与数据日；本页未与同行业比较。
            - 价格回看的「十年」一档未确认：本次只有 400 根日线（最早 2025-01-02）。
            - 政策那一路本次没有取到，材料里没有可引用的政策头条。
            - 推断：资产负债率高于 60%，若继续上行会落入更明确的风险区间。

            ## 需要你人工核实
            - 该行业近 30 天是否有落地细则或补贴文件？
            - 最近一期业绩说明会是否给出下季度指引？
            - 控股股东股份质押的解除是否已完成登记？
            """;
}
