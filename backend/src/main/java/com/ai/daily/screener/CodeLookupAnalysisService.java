package com.ai.daily.screener;

import com.ai.daily.service.AiClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 「代码查询 · 深度分析」的编排。
 *
 * <p><b>它只做三件事：拉主查询的结果、把结果交给 {@link AnalysisRules}、把结论装进 DTO。</b>
 * 一行数字都不自己算——算数的地方只有 {@link AnalysisRules} 一处，
 * 「这个数是谁算的」于是永远只有一个答案（章程 §9.1、§9.6）。
 *
 * <p><b>它调用 {@link CodeLookupService#lookup}，而不是自己取数。</b>这不是图省事：
 * {@code lookup} 带 300 秒进程内缓存，深度分析因此**不额外打一次行情源**，
 * 而且判定用的数字与页面主查询**逐字同一份快照**——两份结果之间不可能出现
 * 「同一只票两个 PE」（章程 §8）。
 *
 * <p><b>失败域分开是刻意的</b>（章程 §9.7）。这里只承接主查询的失败（404 / 429 / 503），
 * 原样抛给 controller 照旧映射；后面几段（新闻 / 公告 / 政策 / 大模型）各自独立失败、
 * 各自灰掉自己那张卡，**整体仍返回 200**，不用 HTTP 错误码。
 * 把独立失败域合进主链的代价是具体的：主查询的缓存**只缓存未降级的结果**，
 * 一次新闻源抽风就会把整条主链标成降级，于是每次点击都重新外呼行情源。
 */
@Slf4j
@Service
public class CodeLookupAnalysisService {

    /** 与 {@link CodeLookupService} 同一个时区口径：判定「今天」用的是上海时间，不是服务器本地时间。 */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 标的名的兜底下限：名字短于这个长度时只用它当别名会把误召回放大。 */
    private static final int MIN_USABLE_ALIAS = 2;

    private final CodeLookupService codeLookupService;
    private final StockNoticeClient stockNoticeClient;
    private final EastMoneyNewsClient eastMoneyNewsClient;
    private final PolicyNewsClient policyNewsClient;
    private final NewsDigestService newsDigestService;
    private final ScreenerCache cache;
    private final AiClientService aiClientService;
    private final BriefCache briefCache;

    private final int maxMaterialChars;
    private final int briefMaxTokens;
    private final int llmTimeoutMs;

    public CodeLookupAnalysisService(
            CodeLookupService codeLookupService,
            StockNoticeClient stockNoticeClient,
            EastMoneyNewsClient eastMoneyNewsClient,
            PolicyNewsClient policyNewsClient,
            NewsDigestService newsDigestService,
            ScreenerCache cache,
            AiClientService aiClientService,
            @Value("${screener.analysis.max-material-chars:6000}") int maxMaterialChars,
            @Value("${screener.analysis.brief-cache-seconds:1800}") long briefCacheSeconds,
            @Value("${screener.analysis.brief-max-tokens:1200}") int briefMaxTokens,
            @Value("${deepseek.timeout-ms:25000}") int llmTimeoutMs) {
        this.codeLookupService = codeLookupService;
        this.stockNoticeClient = stockNoticeClient;
        this.eastMoneyNewsClient = eastMoneyNewsClient;
        this.policyNewsClient = policyNewsClient;
        this.newsDigestService = newsDigestService;
        this.cache = cache;
        this.aiClientService = aiClientService;
        this.briefCache = new BriefCache(Duration.ofSeconds(briefCacheSeconds));
        this.maxMaterialChars = maxMaterialChars;
        this.briefMaxTokens = briefMaxTokens;
        this.llmTimeoutMs = llmTimeoutMs;
    }

    // ==================================================================
    // 一、判定 + 新闻（不调用大模型）
    // ==================================================================

    public CodeLookupAnalysisDTO analyse(String rawCode) {
        CodeLookupDTO dto = codeLookupService.lookup(rawCode);
        return analyse(dto, LocalDate.now(SHANGHAI));
    }

    /** 已经拿到 DTO 时走这条。日期显式传入，好让调用方能钉死「今天」再断言。 */
    public CodeLookupAnalysisDTO analyse(CodeLookupDTO dto, LocalDate today) {
        AnalysisRules.Analysis analysis = AnalysisRules.analyse(dto, today);

        List<NewsSourceResult> results = fetchNews(dto);
        NewsDigestService.Digest digest = newsDigestService.digest(results, subject(dto), today);

        return new CodeLookupAnalysisDTO(
                dto.code(), dto.resolvedCode(), dto.name(),
                dto.kind() == null ? null : dto.kind().name(), dto.kindLabel(),
                dto.snapshotAt(), dto.fromCache(), notes(dto),
                analysis.dimensions(), analysis.checkup(), analysis.tendency(),
                analysis.pendingChecks(), groups(dto, results, digest),
                CodeLookupAnalysisDTO.DISCLAIMER);
    }

    private List<String> notes(CodeLookupDTO dto) {
        List<String> notes = new ArrayList<>();
        notes.add(CodeLookupAnalysisDTO.CALIBER_ABSOLUTE);
        notes.add(CodeLookupAnalysisDTO.CALIBER_CHECKUP_BUCKET);
        // 「数据时间必须随结果给出」（章程 §8）。这一句要说清两件事：
        // 判定建在哪一刻的数字上，以及**这一段没有再去打行情源**。
        notes.add("判定基于 " + dto.snapshotAt() + " 取到的那一份数字"
                + (dto.fromCache() ? "（取自进程内缓存，不是现在重新取的）" : "")
                + "；本段没有重新外呼行情源。");
        return List.copyOf(notes);
    }

    // ==================================================================
    // 二、三路新闻源：每路只发一次，各自成败
    // ==================================================================

    /**
     * 三路源并联。<b>每路只发一次，不翻页、不换关键词重搜、不重试</b>（章程 §9.8）。
     *
     * <p>指数类标的**不发公告那一路**：公告接口按 {@code stock_list=<code>} 取，
     * 对指数代码没有意义，发出去只是白占一次额度。少发的那一组不出现在结果里，
     * 页面也就不渲染那张卡——这比发一次、拿回一个空列表、再渲染一张空卡诚实。
     */
    private List<NewsSourceResult> fetchNews(CodeLookupDTO dto) {
        List<NewsSourceResult> results = new ArrayList<>();
        if (wantsNotices(dto.kind())) {
            results.add(notices(dto));
        }
        results.add(news(dto));
        results.add(policy(dto));
        return results;
    }

    /**
     * 哪几类标的要取公告。
     *
     * <p>{@code INDEX} / {@code OFF_POOL_INDEX} 不要——那是指数，没有公告这个概念。
     * 注意 ETF（{@code INDEX_FUND} / {@code FUND}）**要**：探针实测基金代码走这个接口
     * 回的是 {@code list: []}（真空，不是失败），页面上就写「本次取到 0 条」。
     * 换成「不发」的话，我们连「基金公告不从这条路出」这件事都不会知道。
     */
    static boolean wantsNotices(CodeLookupDTO.Kind kind) {
        return kind == CodeLookupDTO.Kind.STOCK
                || kind == CodeLookupDTO.Kind.INDEX_FUND
                || kind == CodeLookupDTO.Kind.FUND;
    }

    private NewsSourceResult notices(CodeLookupDTO dto) {
        // **用 code，不用 resolvedCode。** resolvedCode 是「实际拿去查询的代码」，
        // 而它对个股是东财的 secid 形态（`0.300274`），对池内 ETF 是 `SH510300`——
        // 两者都不是公告接口要的东西：探针打的那条是 `stock_list=300274`，纯数字。
        //
        // 用 resolvedCode 的后果很具体：`0.300274` 过不了 StockNoticeClient 的代码校验，
        // 于是公告那一路**永远**回一句「公告源只接受数字或字母代码」，
        // 卡片一直是灰的，而排查会一路往网络和接口上去找。
        String code = clean(dto.code());
        if (code == null) {
            return NewsSourceResult.failed(NewsItem.Category.NOTICE,
                    StockNoticeClient.ORIGIN, StockNoticeClient.ORIGIN_LABEL,
                    "这条标的没有可用的代码，无法检索公告");
        }
        return guard(StockNoticeClient.ORIGIN, NewsItem.Category.NOTICE,
                StockNoticeClient.ORIGIN_LABEL, "公告",
                () -> stockNoticeClient.fetchNotices(code));
    }

    private NewsSourceResult news(CodeLookupDTO dto) {
        String keyword = searchKeyword(dto);
        if (keyword == null) {
            return NewsSourceResult.failed(NewsItem.Category.NEWS,
                    EastMoneyNewsClient.ORIGIN, EastMoneyNewsClient.ORIGIN_LABEL,
                    "这条标的没有可用的名称，无法检索资讯");
        }
        return guard(EastMoneyNewsClient.ORIGIN, NewsItem.Category.NEWS,
                EastMoneyNewsClient.ORIGIN_LABEL, "资讯",
                () -> eastMoneyNewsClient.search(keyword));
    }

    private NewsSourceResult policy(CodeLookupDTO dto) {
        String subject = policySubject(dto);
        if (subject == null) {
            return NewsSourceResult.failed(NewsItem.Category.POLICY,
                    PolicyNewsClient.ORIGIN, PolicyNewsClient.ORIGIN_LABEL,
                    "这条标的没有可用的行业名或名称，无法检索政策");
        }
        return guard(PolicyNewsClient.ORIGIN, NewsItem.Category.POLICY,
                PolicyNewsClient.ORIGIN_LABEL, "政策",
                () -> policyNewsClient.searchPolicy(subject));
    }

    /**
     * 一路源的外壳：**冷却期内一次都不发，被限流则进冷却并如实报失败**。
     *
     * <p>两件事都在这里做，是因为它们必须成对：只发不进冷却，下一次点击会接着打一个
     * 正在封我们的源；只进冷却不发，第一路的失败就永远不会被记录下来。
     *
     * <p>冷却判断也是「零外呼」这条纪律的落点——它不是靠调用方自觉，
     * 而是靠这里在发之前就返回。
     */
    private NewsSourceResult guard(String provider, NewsItem.Category category,
                                   String originLabel, String label, Supplier<NewsSourceResult> call) {
        if (cache.inCooldown(provider)) {
            long remain = cache.cooldownRemainingMinutes(provider);
            log.debug("{}源在冷却期内，本次未外呼 provider={} 剩余{}分钟", label, provider, remain);
            return NewsSourceResult.failed(category, provider, originLabel,
                    label + "源在冷却期内（还剩约 " + remain + " 分钟），本次未外呼");
        }
        try {
            return call.get();
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            cache.enterCooldown(provider);
            log.warn("{}源被限流，已进入冷却 provider={}：{}", label, provider,
                    MarketDataClient.shortReason(e));
            return NewsSourceResult.failed(category, provider, originLabel,
                    label + "源本次被限流，已停止对该源的后续请求");
        }
    }

    // ==================================================================
    // 三、检索用什么词
    // ==================================================================

    /**
     * 资讯检索词。**优先用名字**：探针验证过能用的形态就是公司简称（「阳光电源」），
     * 而按代码检索回来的东西里混着一堆只是提到这串数字的文章。
     */
    static String searchKeyword(CodeLookupDTO dto) {
        String name = clean(dto.name());
        if (name != null) {
            return name;
        }
        // 名字缺失时退回**用户输入的那个代码**，不是 resolvedCode：后者可能是
        // `0.300274` 这种带行情源点号的内部形态，拿它去搜只会搜到一堆无关内容。
        String code = clean(dto.code());
        return code != null ? code : clean(dto.resolvedCode());
    }

    /**
     * 政策检索词。<b>优先用行业名</b>——扶持政策按行业出，不按公司出（章程 §9.12）。
     *
     * <p>行业名只从 {@code quote.industry()} 拿。**指数与兜底源没有这一项**，
     * 那时退回标的名称：检索「沪深300 政策」召回的东西很杂，但比不发好，
     * 而且管线的相关性判据会把明显不相关的滤掉。
     */
    static String policySubject(CodeLookupDTO dto) {
        if (dto.quote() != null) {
            String industry = clean(dto.quote().industry());
            if (industry != null) {
                return industry;
            }
        }
        return searchKeyword(dto);
    }

    /**
     * 相关性判据用的别名表：名称、查询代码、实际代码。
     *
     * <p>单个字符的名称**不当别名**——「A」这种名字会让「标题含 A」恒真，
     * 相关性判据等于没做。名字本来就缺时，别名表里就只剩代码，
     * 而按代码判相关性是弱的（数字串容易出现在无关标题里），这一点由打分的
     * 「命中别名加 30 分」承担——弱证据给弱权重，不给强判据。
     */
    static NewsDigestService.Subject subject(CodeLookupDTO dto) {
        Set<String> aliases = new LinkedHashSet<>();
        String name = clean(dto.name());
        if (name != null && name.length() >= MIN_USABLE_ALIAS) {
            aliases.add(name);
        }
        addIfPresent(aliases, dto.resolvedCode());
        addIfPresent(aliases, dto.code());
        return new NewsDigestService.Subject(List.copyOf(aliases), policySubject(dto));
    }

    private static void addIfPresent(Set<String> target, String raw) {
        String value = clean(raw);
        if (value != null) {
            target.add(value);
        }
    }

    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        return value.isEmpty() ? null : value;
    }

    // ==================================================================
    // 四、组装成三组卡片
    // ==================================================================

    /**
     * 把「每路源各自的成败」与「管线选出来的条目」拼成页面要的三组。
     *
     * <p>{@code FAILED} 与 {@code EMPTY} 的分界照 {@link NewsSourceResult} 那条：
     * 只要这一组**有一路源真的成功回了**（哪怕回的是 0 条），就是正常结果；
     * 全失败（含冷却期内没发）才变灰。理由很具体：一只 ETF 的公告永远是 0 条，
     * 报成失败的话页面会一直挂着一张灰卡说「源不可达」，而源其实是好的。
     */
    private List<CodeLookupAnalysisDTO.NewsGroupView> groups(
            CodeLookupDTO dto, List<NewsSourceResult> results, NewsDigestService.Digest digest) {
        Map<NewsItem.Category, List<NewsSourceResult>> byCategory = new EnumMap<>(NewsItem.Category.class);
        for (NewsSourceResult result : results) {
            byCategory.computeIfAbsent(result.category(), k -> new ArrayList<>()).add(result);
        }

        List<CodeLookupAnalysisDTO.NewsGroupView> groups = new ArrayList<>();
        for (NewsItem.Category category : NewsItem.Category.values()) {
            List<NewsSourceResult> ofCategory = byCategory.get(category);
            if (ofCategory == null || ofCategory.isEmpty()) {
                // 这一路本次根本没发（指数的公告）。不出一张卡片，比出一张空卡诚实。
                continue;
            }
            List<NewsItem> items = switch (category) {
                case NOTICE -> digest.notices();
                case NEWS -> digest.news();
                case POLICY -> digest.policies();
            };
            boolean anyOk = ofCategory.stream().anyMatch(NewsSourceResult::ok);
            CodeLookupAnalysisDTO.NewsGroupView.State state =
                    !anyOk ? CodeLookupAnalysisDTO.NewsGroupView.State.FAILED
                            : items.isEmpty() ? CodeLookupAnalysisDTO.NewsGroupView.State.EMPTY
                                    : CodeLookupAnalysisDTO.NewsGroupView.State.OK;
            groups.add(new CodeLookupAnalysisDTO.NewsGroupView(
                    category.name(), category.label(), items, state, digest.noteOf(category)));
        }
        return List.copyOf(groups);
    }

    // ==================================================================
    // 五、大模型解读（单独一个端点，单独一次调用）
    // ==================================================================

    /**
     * 生成深度解读。**手动触发，不跟查询自动跑**：
     * 非流式、要花钱、未配 key 时永远空着，而它一次要几秒到几十秒。
     * 自动跑会把首屏从 1-3 秒拖到十几秒，主查询就不再是秒回了。
     *
     * <p>缓存键是「代码 + 底层快照的取数时刻」，所以同一份快照重复点击不再计费；
     * 快照换了自然失效。**命中缓存时不再打任何源**，包括新闻那三路。
     */
    public AnalysisBriefDTO brief(String rawCode) {
        CodeLookupDTO dto = codeLookupService.lookup(rawCode);
        return brief(dto, LocalDate.now(SHANGHAI), Instant.now());
    }

    /** 已拿到 DTO 时走这条。{@code today} 与 {@code now} 显式传入，好让测试钉死时间。 */
    public AnalysisBriefDTO brief(CodeLookupDTO dto, LocalDate today, Instant now) {
        AnalysisBrief cached = briefCache.get(dto.code(), dto.snapshotAt(), now);
        AnalysisBrief brief = cached != null ? cached : generate(dto, today, now);
        return new AnalysisBriefDTO(dto.code(), dto.resolvedCode(), dto.name(),
                dto.snapshotAt(), dto.fromCache(), brief);
    }

    private AnalysisBrief generate(CodeLookupDTO dto, LocalDate today, Instant now) {
        AnalysisRules.Analysis analysis = AnalysisRules.analyse(dto, today);
        // 这里**重新跑一遍新闻管线**而不是复用 analyse 的结果：解读是另一次点击，
        // 与判定卡那次可能隔着几分钟。把它做成「先调 analyse 再调这个」会让解读误用
        // 一份过时的新闻列表，而材料里的日期是真的、看不出来。
        List<NewsSourceResult> results = fetchNews(dto);
        NewsDigestService.Digest digest = newsDigestService.digest(results, subject(dto), today);

        // 材料要的是判定结果，而 AnalysisRules 的产物与 DTO 是两回事。
        // 这里直接复用 analyse 那条路径组装出的 DTO，避免同一份材料有两种拼法。
        CodeLookupAnalysisDTO material = new CodeLookupAnalysisDTO(
                dto.code(), dto.resolvedCode(), dto.name(),
                dto.kind() == null ? null : dto.kind().name(), dto.kindLabel(),
                dto.snapshotAt(), dto.fromCache(), notes(dto),
                analysis.dimensions(), analysis.checkup(), analysis.tendency(),
                analysis.pendingChecks(), groups(dto, results, digest),
                CodeLookupAnalysisDTO.DISCLAIMER);

        String text = aiClientService.chat(
                List.of(new AiClientService.AiMessage("system", AnalysisPrompt.system()),
                        new AiClientService.AiMessage("user",
                                AnalysisPrompt.material(material, digest, maxMaterialChars))),
                0.3, briefMaxTokens, llmTimeoutMs);

        AnalysisBrief brief = AnalysisBrief.fromAi(text);
        if (!brief.available()) {
            log.warn("深度解读未生成 code={} reason={}", dto.code(), brief.text());
        }
        briefCache.put(dto.code(), dto.snapshotAt(), brief, now);
        return brief;
    }
}
