package com.ai.daily.screener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 新闻聚合管线：**清洗 → 相关性 → 时间窗 → 跨源去重 → 打分截断**（章程 §9.10）。
 *
 * <p>用户的原话是「多加点数据源，然后做好数据清洗和去重，留下最有用的」。
 * 这个类就是那句话的实现——它存在的意义是**不把接口返回值原样倒到页面上**。
 *
 * <h2>「清洗」其实是两件事，方案里混写了一句，这里拆开</h2>
 *
 * <p>方案 §9.10 第 1 条把「全角转半角」列在清洗里。那样做是错的，因为这两个函数
 * 服务的是**两个相反的读者**：
 *
 * <ul>
 *   <li>{@link #cleanse} 服务的读者是**人**。它剥标签、解实体、去零宽字符，
 *       但**保留中文标点**。把「，」改写成 "," 会让人看到一段标点被改坏的中文。</li>
 *   <li>{@link #normalizeTitle} 服务的读者是**去重算法**。它把全角折成半角、
 *       去掉所有标点与空白、统一大小写——越激进越好，因为它的输出不给人看。</li>
 * </ul>
 *
 * <p>混成一个函数，无论怎么选都会错一半：按显示的要求写，去重就漏（「，」与 "," 判成两条）；
 * 按去重的要求写，页面上的中文标点就全成了英文的。
 *
 * <h2>今天是参数，不是 {@code LocalDate.now()}</h2>
 *
 * <p>{@link #digest} 收一个显式的 {@code today}。纯函数里读时钟的后果是
 * **凌晨零点前后同一条用例会随机变红**，而那种红看起来像业务 bug，会耗掉半天的排查。
 * 与 {@link AnalysisRules#analyse} 同一个约定。
 */
@Slf4j
@Service
public class NewsDigestService {

    /**
     * 来源权重，章程 §9.10 第 5 条的「法定披露 &gt; 权威财经媒体 &gt; 门户转载」。
     *
     * <p>数值只用于**组内排序**，不显示在页面上——所以不必是「正确的」，
     * 只要大小关系对、且间距足够拉开档位。写成整数是为了让打分函数返回 int，
     * 断言可以写等号而不用 `isCloseTo(…, within(1e-9))`。
     */
    static final Map<String, Integer> ORIGIN_WEIGHT = Map.of(
            StockNoticeClient.ORIGIN, 100,
            EastMoneyNewsClient.ORIGIN, 60,
            PolicyNewsClient.ORIGIN, 50);

    /** 新闻与政策的时间窗（章程 §9.10 第 3 条）。 */
    static final int NEWS_WINDOW_DAYS = 30;

    /** 公告放宽到 90 天：法定披露本来就稀疏，30 天窗口对安静的公司会几乎恒为空。 */
    static final int NOTICE_WINDOW_DAYS = 90;

    /** 三组各取前 N。条数不足如实写，不凑。 */
    static final int MAX_NOTICE = 8;
    static final int MAX_NEWS = 8;
    static final int MAX_POLICY = 6;

    /**
     * 近似去重的阈值。同一事件被多家转载是常态，**宁可少留不可重复**（§9.10 第 4 条）。
     *
     * <p><b>0.7 而不是 0.8，是实测改的</b>（2026-10-15，在归一化后的标题上算 3-gram Jaccard）：
     * 同事件的几种形态落在 0.706（插入一个字）到 1.000（只差标点）之间，
     * 不同事件最高只有 0.429——**中间是一段空档**，阈值落在哪儿都行，差别只在漏不漏。
     * 0.8 会漏掉「插入一个字」，而那是转载标题最常见的漂移形态。
     *
     * <p><b>注意它和 URL 去重的取向是相反的</b>（见 {@link #TRACKING_PARAMS} 那段）：
     * 标题相似度可以放得松，漏了只是页面重复一条；URL 结构必须取得紧，误合是读者少看到一条真新闻。
     * 两个阈值看起来都是「去重参数」，但它们服务的目标不对称，不要一起调。
     */
    static final double NEAR_DUPLICATE = 0.7;

    private static final int NGRAM = 3;

    /** 太短的标题不是新闻，是导航位或按钮文案。 */
    private static final int MIN_TITLE_LENGTH = 6;

    /**
     * 导航位/推荐位噪声。探针没实测过这个列表——它是**安全网**，不是判据，
     * 所以只放几个几乎不可能出现在真新闻标题里的词。
     */
    static final List<String> NOISE = List.of("相关推荐", "点击查看更多", "查看更多", "广告");

    /**
     * 「它得像一条政策」（§9.10 第 2 条）。政策条目的标题**不含公司名**，
     * 所以类别为政策时改用这条判据。
     *
     * <p>刻意**不收**「发布」「实施」：它们太常见，「阳光电源发布中报」也含「发布」。
     * 收进来等于没有判据。
     */
    static final List<String> POLICY_WORDS = List.of(
            "规划", "通知", "意见", "办法", "试点", "补贴", "税收", "条例", "标准", "方案",
            "指引", "国务院", "发改委", "工信部", "财政部", "央行", "证监会", "能源局",
            "行动方案", "指导意见", "管理办法", "印发", "获批", "政策");

    /** 重要性关键词，只影响**组内排序**（§9.10 第 5 条）。 */
    static final List<String> IMPORTANCE_WORDS = List.of(
            "业绩预告", "业绩快报", "分红", "送转", "回购", "增持", "减持", "解禁",
            "重组", "并购", "中标", "订单", "诉讼", "处罚", "监管", "问询", "激励",
            "质押", "冻结", "退市", "立案", "问询函", "关注函");

    /**
     * URL 里的跟踪参数，去重前要丢掉。**只丢这些，不丢整个 query**——
     * 有些站的正文 ID 就在 query 里（{@code ?id=123}），把 query 整个丢掉会把
     * 两篇不同的文章合并成一条，那是**误删**：读者少看到一条真的新闻，且不会报错。
     * 漏去重只是重复，误合却是丢失，两者不对称，所以这里取保守的一边。
     */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "from", "spm", "share_token", "shareToken", "_t", "timestamp", "tt_from", "wxshare");

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF\\u2060]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern PUNCT = Pattern.compile("[\\p{Punct}\\p{IsPunctuation}\\s]+");

    /**
     * 一次深度分析要用到的「这条标的叫什么」。
     *
     * @param aliases 标题命中其中**任何一个**就算相关。由调用方算好——
     *                它是业务判断（简称、去后缀），不是管线的活儿
     * @param policySubject 政策检索用的词，通常是**行业名**：
     *                      用户问的是「有没有相关的政策扶持」，而扶持政策按行业出，不按公司出
     */
    public record Subject(List<String> aliases, String policySubject) {

        public Subject {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    /**
     * 管线产物。
     *
     * @param notices   公告（已按打分排序、已截断）
     * @param news      新闻
     * @param policies  政策类头条
     * @param notes     **每一路源各一句**，如实写清「源回了几条、窗口内几条、去重后几条、取了几条」。
     *                  章程要求「条数不足如实写『本次只取到 N 条』」——
     *                  没有这句，读者分不清「最近真的没什么事」和「我们的判据把东西滤没了」
     */
    public record Digest(List<NewsItem> notices, List<NewsItem> news, List<NewsItem> policies,
                         Map<NewsItem.Category, String> notes) {

        public Digest {
            notes = notes == null ? Map.of() : Map.copyOf(notes);
        }

        /** 某一组的说明。**取不到是空串，不是 null**——页面上少一句话比印一个 "null" 强。 */
        public String noteOf(NewsItem.Category category) {
            return notes.getOrDefault(category, "");
        }
    }

    /**
     * 跑一遍管线。
     *
     * <p>{@code results} 里每一路**各自成败**（§9.7）：失败的那路只写一句 note，
     * 其余各路照常出结果。这是「五段失败域」在新闻这一层的落地。
     */
    public Digest digest(List<NewsSourceResult> results, Subject subject, LocalDate today) {
        Map<NewsItem.Category, List<NewsSourceResult>> byCategory = new EnumMap<>(NewsItem.Category.class);
        for (NewsSourceResult result : results == null ? List.<NewsSourceResult>of() : results) {
            if (result != null) {
                byCategory.computeIfAbsent(result.category(), k -> new ArrayList<>()).add(result);
            }
        }

        List<NewsItem> notices = new ArrayList<>();
        List<NewsItem> news = new ArrayList<>();
        List<NewsItem> policies = new ArrayList<>();
        Map<NewsItem.Category, String> notes = new EnumMap<>(NewsItem.Category.class);

        for (NewsItem.Category category : NewsItem.Category.values()) {
            Picked picked = oneCategory(byCategory.getOrDefault(category, List.of()),
                    category, subject, today);
            notes.put(category, describe(category, picked));
            switch (category) {
                case NOTICE -> notices.addAll(picked.kept());
                case NEWS -> news.addAll(picked.kept());
                case POLICY -> policies.addAll(picked.kept());
            }
        }

        return new Digest(truncate(notices, MAX_NOTICE),
                truncate(news, MAX_NEWS),
                truncate(policies, MAX_POLICY),
                notes);
    }

    /**
     * 一个类别的中间结果。**计数记在类别上，不记在源上**——因为去重是跨源的
     * （见 {@link #oneCategory}）。
     *
     * @param sourceParts 每路源一句「X源回 N 条」，或失败时那句原文
     * @param anyOk       这一类别里至少有一路源成功。全失败时 note 只说失败，不写「取到 0 条」——
     *                    那会读成「确实没有」，而实际是「我们没取到」
     */
    private record Picked(List<NewsItem> kept, List<String> sourceParts, boolean anyOk,
                          int noise, int outsideWindow, int irrelevant, int duplicate, int max) {
    }

    /**
     * 一个类别的整条管线。
     *
     * <p><b>去重必须跨源</b>。第一版把它放在每一路源内部，于是两路源报同一条新闻时
     * 谁也不知道对方存在，两条都上页面——而章程 §9.10 第 4 条写的正是「**跨源**去重」。
     * 今天每一组只有一路源，所以这个错在单元测试里才暴露（那条用例用两路源构造同一条新闻），
     * 生产上要等接入第二路新闻源才会显形。
     */
    private Picked oneCategory(List<NewsSourceResult> sources, NewsItem.Category category,
                               Subject subject, LocalDate today) {
        long cutoff = category == NewsItem.Category.NOTICE ? NOTICE_WINDOW_DAYS : NEWS_WINDOW_DAYS;

        List<NewsItem> candidates = new ArrayList<>();
        List<String> sourceParts = new ArrayList<>();
        boolean anyOk = false;
        int noise = 0;
        int outsideWindow = 0;
        int irrelevant = 0;

        for (NewsSourceResult result : sources) {
            if (!result.ok()) {
                // 失败**照原话**写进 note，不再加工：那句话里通常已经有「不可达/结构异常」
                // 这类能指向下一步的信息，改写一遍只会把它磨平。各客户端的失败文案
                // 本身就点明了是哪个源（「公告源不可达（…）」），所以这里不必再冠一次名字。
                sourceParts.add(result.failureReason());
                continue;
            }
            anyOk = true;
            sourceParts.add(result.originLabel() + "源回 " + result.rawCount() + " 条");

            for (NewsItem raw : result.items()) {
                String title = cleanse(raw.title(), raw.source());
                if (title == null || title.length() < MIN_TITLE_LENGTH || isNoise(title)) {
                    noise++;
                    continue;
                }
                if (ChronoUnit.DAYS.between(raw.publishedOn(), today) > cutoff) {
                    outsideWindow++;
                    continue;
                }
                if (!relevant(raw.category(), title, subject)) {
                    irrelevant++;
                    continue;
                }
                candidates.add(withTitle(raw, title));
            }
        }

        // 打分排序在去重**之前**：分数高的先被「接受」，于是近似去重天然保留了
        // **来源权重最高的那条**（§9.10 第 4 条），不用事后再挑一遍。
        candidates.sort(Comparator.comparingInt((NewsItem i) -> score(i, subject, today)).reversed()
                .thenComparing(NewsItem::publishedOn, Comparator.reverseOrder())
                .thenComparing(NewsItem::title));

        List<NewsItem> kept = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        Set<String> seenTitles = new LinkedHashSet<>();
        int duplicate = 0;

        for (NewsItem item : candidates) {
            String urlKey = normalizeUrl(item.url());
            String titleKey = normalizeTitle(item.title());
            if (seenUrls.contains(urlKey) || seenTitles.contains(titleKey)
                    || nearDuplicate(kept, titleKey)) {
                duplicate++;
                continue;
            }
            seenUrls.add(urlKey);
            seenTitles.add(titleKey);
            kept.add(item);
        }

        int max = switch (category) {
            case NOTICE -> MAX_NOTICE;
            case NEWS -> MAX_NEWS;
            case POLICY -> MAX_POLICY;
        };
        return new Picked(kept, sourceParts, anyOk, noise, outsideWindow, irrelevant, duplicate, max);
    }

    /**
     * 每张卡片一句实话。**把「筛掉了多少、为什么」写出来**，是因为读者看到一张空卡片时，
     * 分不清那是「最近真的没事」还是「我们的判据写错了」——后者是我们最该被发现的错，
     * 而它恰好长得最像前者。
     */
    private static String describe(NewsItem.Category category, Picked picked) {
        String head = category.label() + "：" + String.join("；", picked.sourceParts());
        if (!picked.anyOk()) {
            // 全失败时说「取到 0 条」是在撒谎：真实情况是「我们没取到」。
            return head;
        }
        if (picked.sourceParts().size() == 1 && picked.sourceParts().get(0).contains("源回 0 条")) {
            return head + "——本次没有取到任何条目（不是失败，源就是这样回的）";
        }

        StringBuilder sb = new StringBuilder(head);
        List<String> drops = new ArrayList<>();
        if (picked.noise() > 0) {
            drops.add("噪声/过短 " + picked.noise());
        }
        if (picked.outsideWindow() > 0) {
            drops.add("超出时间窗 " + picked.outsideWindow());
        }
        if (picked.irrelevant() > 0) {
            drops.add("与标的不相关 " + picked.irrelevant());
        }
        if (picked.duplicate() > 0) {
            drops.add("重复 " + picked.duplicate());
        }
        if (!drops.isEmpty()) {
            sb.append("，丢弃 ").append(String.join("、", drops));
        }
        int shown = Math.min(picked.kept().size(), picked.max());
        sb.append("，本次取到 ").append(shown).append(" 条");
        if (picked.kept().size() > picked.max()) {
            sb.append("（另有 ").append(picked.kept().size() - picked.max()).append(" 条未展示）");
        }
        return sb.toString();
    }

    private static List<NewsItem> truncate(List<NewsItem> items, int max) {
        return items.size() <= max ? List.copyOf(items) : List.copyOf(items.subList(0, max));
    }

    // ==================================================================
    // 清洗
    // ==================================================================

    /**
     * 给人看的清洗。**不改动中文标点**（见类注释里那段拆分的理由）。
     *
     * <p>顺序要紧：**先剥标签，再解实体**。反过来的话，服务端把标签转义成
     * {@code &lt;em&gt;} 时，解完实体会**新长出一个标签**，而那时已经没有剥标签的步骤了。
     */
    static String cleanse(String title, String source) {
        if (title == null) {
            return null;
        }
        String s = title;
        s = TAG.matcher(s).replaceAll("");
        s = unescape(s);
        // 剥标签后再来一次：解实体可能刚刚又吐出标签（见上）。
        s = TAG.matcher(s).replaceAll("");
        s = ZERO_WIDTH.matcher(s).replaceAll("");
        // 把连续空白折成一个普通空格。**这里不做全角转半角**——那是 normalizeTitle 的活儿，
        // 见类注释里那段拆分的理由。
        s = SPACES.matcher(s).replaceAll(" ").strip();
        return dropSourceSuffix(s, source);
    }

    /**
     * 去掉 Google 拼在标题末尾的 {@code " - 媒体名"}。
     *
     * <p><b>当且仅当后缀与 {@code source} 逐字相符时</b>才去。无条件按最后一个「 - 」切
     * 会把 {@code 阳光电源行至"十字路口"：中报业绩双降…} 这类正常标题切坏——
     * 标题里带破折号是常事，而「媒体名」不是。
     */
    static String dropSourceSuffix(String title, String source) {
        if (title == null || source == null || source.isBlank()) {
            return title;
        }
        String suffix = " - " + source.strip();
        return title.endsWith(suffix)
                ? title.substring(0, title.length() - suffix.length()).strip()
                : title;
    }

    private static String unescape(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static boolean isNoise(String title) {
        return NOISE.stream().anyMatch(title::contains);
    }

    /**
     * 去重用的标题键。**这个函数的输出不给人看**，所以可以很激进：
     * 折半角、去标点空白、统一大小写。
     */
    static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(title.length());
        for (char c : title.toCharArray()) {
            // 全角 ASCII（U+FF01–U+FF5E）折半角；表意空格折普通空格
            if (c >= '！' && c <= '～') {
                sb.append((char) (c - 0xFEE0));
            } else if (c == '　') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return PUNCT.matcher(sb.toString().toLowerCase()).replaceAll("");
    }

    /**
     * URL 键。**保留 query 里非跟踪的那些**（理由见 {@link #TRACKING_PARAMS}），
     * 去掉协议与 {@code www.}/{@code m.}/{@code wap.} 前缀——同一篇文章的桌面版与手机版
     * 是两个 URL 一条内容，不比它就会重复。
     *
     * <p>解析不了就退回原串：去重失败只是重复，拿一个坏键去比对可能误合。
     */
    static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url.strip());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            for (String prefix : List.of("www.", "m.", "wap.")) {
                if (host.startsWith(prefix)) {
                    host = host.substring(prefix.length());
                    break;
                }
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            String query = keptQuery(uri.getRawQuery());
            return host + path + query;
        } catch (RuntimeException e) {
            return url.strip();
        }
    }

    /** 丢掉跟踪参数并**排序**，否则 {@code ?a=1&b=2} 与 {@code ?b=2&a=1} 会被当成两个 URL。 */
    private static String keptQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        TreeSet<String> kept = new TreeSet<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!TRACKING_PARAMS.contains(key)) {
                kept.add(pair);
            }
        }
        return kept.isEmpty() ? "" : "?" + String.join("&", kept);
    }

    /** 字符 3-gram 的 Jaccard 相似度。中文没有词边界，按字符切正好；3 是经验值。 */
    static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        Set<String> ga = grams(a);
        Set<String> gb = grams(b);
        if (ga.isEmpty() || gb.isEmpty()) {
            return 0.0;
        }
        int inter = 0;
        for (String g : ga) {
            if (gb.contains(g)) {
                inter++;
            }
        }
        return (double) inter / (ga.size() + gb.size() - inter);
    }

    private static Set<String> grams(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s.length() < NGRAM) {
            if (!s.isEmpty()) {
                out.add(s);
            }
            return out;
        }
        for (int i = 0; i + NGRAM <= s.length(); i++) {
            out.add(s.substring(i, i + NGRAM));
        }
        return out;
    }

    private static boolean nearDuplicate(List<NewsItem> accepted, String titleKey) {
        for (NewsItem item : accepted) {
            if (similarity(normalizeTitle(item.title()), titleKey) >= NEAR_DUPLICATE) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 相关性
    // ==================================================================

    /**
     * 相关性判据**按类别分派**（章程 §9.10 第 2 条那张表）。
     *
     * <p>三类条目的标题形态完全不同，用一条判据必然错一类。政策那一条是被实测逼出来的：
     * 政策标题<b>不含公司名也不含行业名</b>（{@code 2027年开始实施！储能相关国家标准获批！}），
     * 照新闻的判据执行会让政策栏**恒为空**，而且不报错——看起来就像「近期没有相关政策」，
     * 把「判据写错了」伪装成「客观事实是没有」。
     */
    static boolean relevant(NewsItem.Category category, String title, Subject subject) {
        return switch (category) {
            // 公告不做校验：接口本身就是按 stock_list=<code> 取的，天然属于这只标的。
            // 硬套「标题含公司名」会误杀一类真公告——基金公告的标题常常不带公司名。
            case NOTICE -> true;
            case NEWS -> hitAlias(title, subject);
            case POLICY -> POLICY_WORDS.stream().anyMatch(title::contains);
        };
    }

    private static boolean hitAlias(String title, Subject subject) {
        if (subject == null || subject.aliases().isEmpty()) {
            // 没有别名就没法判相关。**返回 false 而不是 true**：宁可少显示，
            // 也不要把一只标的的新闻页变成整个市场的新闻页。
            return false;
        }
        return subject.aliases().stream().anyMatch(title::contains);
    }

    // ==================================================================
    // 打分
    // ==================================================================

    /**
     * 组内排序分。**整数**，便于断言（浮点比较要写容差，那会让边界用例变得不可读）。
     *
     * <p>四个分量相加，量级刻意拉开：来源权重（100/60/50）&gt; 时效（0–40）
     * &gt; 相关性（0–30）&gt; 重要性（0–30）。于是**一条当天的公告永远排在一批
     * 三天前的新闻前面**，这正是章程 §9.10 第 5 条要的顺序。
     *
     * <p>它只决定组内先后，**不显示在页面上**——不显示，就没有「这个分数是什么意思」
     * 的追问，也就不必为它编一个口径名。
     */
    static int score(NewsItem item, Subject subject, LocalDate today) {
        int score = ORIGIN_WEIGHT.getOrDefault(item.origin(), 10);

        long age = ChronoUnit.DAYS.between(item.publishedOn(), today);
        if (age <= 2) {
            score += 40;
        } else if (age <= 7) {
            score += 20;
        }

        if (hitAlias(item.title(), subject)) {
            score += 30;
        }

        int hits = 0;
        for (String word : IMPORTANCE_WORDS) {
            if (item.title().contains(word)) {
                hits++;
            }
        }
        score += 10 * Math.min(hits, 3);
        return score;
    }

    /** 换掉标题，其余字段照旧。{@link NewsItem} 是 record，只能重建一个。 */
    private static NewsItem withTitle(NewsItem raw, String title) {
        return new NewsItem(raw.category(), raw.origin(), raw.originLabel(), title,
                raw.source(), raw.publishedOn(), raw.url(), raw.noticeCategory());
    }
}
