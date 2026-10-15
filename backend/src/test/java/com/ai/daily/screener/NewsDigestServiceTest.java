package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新闻聚合管线（章程 §9.10）。**重点是清洗与去重**——用户的原话是
 * 「多加点数据源，然后做好数据清洗和去重，留下最有用的」，这个类就是那句话的实现。
 *
 * <p>为什么这些用例值得写得这么细：这一层的每一种失败都**不报错**。判据写松了页面多几条
 * 别人的新闻，写紧了某一栏恒为空，而空栏看起来和「最近确实没消息」一模一样。
 * 一条都不会抛异常，一条都不会让人去看日志。
 */
class NewsDigestServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    /** 标的：名称 + 代码两个别名。行业名给政策检索用。 */
    private static final NewsDigestService.Subject SUBJECT =
            new NewsDigestService.Subject(List.of("阳光电源", "300274"), "电源设备");

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static NewsItem notice(String title, LocalDate day, String url) {
        return new NewsItem(NewsItem.Category.NOTICE, StockNoticeClient.ORIGIN,
                StockNoticeClient.ORIGIN_LABEL, title, null, day, url, null);
    }

    private static NewsItem news(String title, LocalDate day, String url) {
        return new NewsItem(NewsItem.Category.NEWS, EastMoneyNewsClient.ORIGIN,
                EastMoneyNewsClient.ORIGIN_LABEL, title, "证券时报", day, url, null);
    }

    private static NewsItem policy(String title, LocalDate day, String url) {
        return new NewsItem(NewsItem.Category.POLICY, PolicyNewsClient.ORIGIN,
                PolicyNewsClient.ORIGIN_LABEL, title, "中国政府网", day, url, null);
    }

    private static NewsSourceResult sourceOf(NewsItem.Category category, List<NewsItem> items) {
        String origin = items.isEmpty() ? originOf(category) : items.get(0).origin();
        String label = items.isEmpty() ? labelOf(category) : items.get(0).originLabel();
        return NewsSourceResult.ok(category, origin, label, items, items.size());
    }

    private static String originOf(NewsItem.Category c) {
        return switch (c) {
            case NOTICE -> StockNoticeClient.ORIGIN;
            case NEWS -> EastMoneyNewsClient.ORIGIN;
            case POLICY -> PolicyNewsClient.ORIGIN;
        };
    }

    private static String labelOf(NewsItem.Category c) {
        return switch (c) {
            case NOTICE -> StockNoticeClient.ORIGIN_LABEL;
            case NEWS -> EastMoneyNewsClient.ORIGIN_LABEL;
            case POLICY -> PolicyNewsClient.ORIGIN_LABEL;
        };
    }

    private NewsDigestService.Digest run(List<NewsSourceResult> results) {
        return new NewsDigestService().digest(results, SUBJECT, TODAY);
    }

    // ==================================================================
    // 清洗
    // ==================================================================

    @Test
    void theEmphasisTagTheSearchApiAddsIsStripped() {
        // 探针实测：标题长这样 —— <em>阳光电源</em>等在内蒙古成立智算科技公司。
        // 那是 preTag/postTag 造成的，不剥掉页面上就会显示成一堆尖括号。
        assertThat(NewsDigestService.cleanse("<em>阳光电源</em>等在内蒙古成立智算科技公司", null))
                .isEqualTo("阳光电源等在内蒙古成立智算科技公司");
    }

    @Test
    void entitiesAreDecodedAndCannotSmuggleATagBackIn() {
        // **顺序用例**：服务端把标签转义成 &lt;em&gt; 时，必须先剥标签再解实体。
        // 反过来的话解实体会**新长出一个标签**，而那时已经没有剥标签的步骤了。
        assertThat(NewsDigestService.cleanse("A&amp;B", null)).isEqualTo("A&B");
        assertThat(NewsDigestService.cleanse("&lt;em&gt;阳光电源&lt;/em&gt;", null))
                .isEqualTo("阳光电源");
        assertThat(NewsDigestService.cleanse("阳光&nbsp;电源", null)).isEqualTo("阳光 电源");
    }

    @Test
    void zeroWidthCharactersAreRemoved() {
        // **先断言输入确实带上了这两个零宽字符**，再看清理函数有没有把它们去掉。
        // 它们是看不见的，所以「下一个人手抖删了它」和「本来就没写」在 diff 里
        // 长得一模一样——少了这个断言，用例会静默退化成一句恒真的空转：
        // 清理函数一次都没被验证过，刻度却一直是绿的。
        // （下面那两个字面量之间夹着的就是 U+200B 与 U+FEFF。）
        String dirty = "阳光​电源﻿公告";
        assertThat(dirty).hasSize(8);

        assertThat(NewsDigestService.cleanse(dirty, null)).isEqualTo("阳光电源公告");
    }

    @Test
    void chinesePunctuationSurvivesCleansing() {
        // 这是「清洗」与「归一化」拆成两个函数的全部理由。全角转半角属于去重那一侧，
        // 混进来的话页面上的中文标点会全变成英文的——「，」变 ","，读起来是坏的。
        assertThat(NewsDigestService.cleanse("阳光电源：业绩双降，第三曲线待培育（深度）", null))
                .isEqualTo("阳光电源：业绩双降，第三曲线待培育（深度）");
    }

    @Test
    void aTrailingSourceSuffixIsDroppedOnlyWhenItMatchesTheSourceField() {
        // Google 把媒体名拼在标题末尾，而 <source> 元素已经单独给了它，重复。
        assertThat(NewsDigestService.cleanse("储能标准获批 - 新浪网", "新浪网"))
                .isEqualTo("储能标准获批");

        // **但只能在后缀与 source 逐字相符时去**。无条件按最后一个「 - 」切，
        // 会把下面这种正常标题切坏——标题里带破折号是常事，而「媒体名」不是。
        String title = "阳光电源行至" + "十字路口" + "：中报业绩双降 - 风险与机会";
        assertThat(NewsDigestService.cleanse(title, "证券之星")).isEqualTo(title);
    }

    @Test
    void navigationNoiseAndTooShortTitlesAreDropped() {
        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.NEWS, List.of(
                news("相关推荐：阳光电源", TODAY, "http://a.com/1"),
                news("更多", TODAY, "http://a.com/2"),
                news("阳光电源中标沙特储能项目", TODAY, "http://a.com/3")))));

        assertThat(d.news()).hasSize(1);
        assertThat(d.news().get(0).title()).isEqualTo("阳光电源中标沙特储能项目");
        assertThat(d.notes().values()).anyMatch(s -> s.contains("噪声/过短 2"));
    }

    // ==================================================================
    // 相关性判据按类别分派
    // ==================================================================

    @Test
    void aNewsItemThatDoesNotNameTheSubjectIsDropped() {
        // 按关键词检索必然带误召回——不校验就是把别人的新闻挂在你的标的上。
        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.NEWS, List.of(
                news("宁德时代发布新一代储能电芯", TODAY, "http://a.com/1"),
                news("阳光电源中标沙特储能项目", TODAY, "http://a.com/2")))));

        assertThat(d.news()).hasSize(1);
        assertThat(d.news().get(0).title()).contains("阳光电源");
        assertThat(d.notes().values()).anyMatch(s -> s.contains("与标的不相关 1"));
    }

    @Test
    void theStockCodeAlsoCountsAsAHit() {
        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.NEWS,
                List.of(news("阳光电源(300274)获机构调研", TODAY, "http://a.com/1")))));

        assertThat(d.news()).hasSize(1);
    }

    @Test
    void withoutAnyAliasNothingFromTheNewsSourceIsShown() {
        // 没有别名就没法判相关。**返回 false 而不是 true**：宁可少显示，
        // 也不要把一只标的的新闻页变成整个市场的新闻页。
        NewsDigestService.Digest d = new NewsDigestService().digest(
                List.of(sourceOf(NewsItem.Category.NEWS,
                        List.of(news("阳光电源中标沙特储能项目", TODAY, "http://a.com/1")))),
                new NewsDigestService.Subject(List.of(), "电源设备"), TODAY);

        assertThat(d.news()).isEmpty();
    }

    @Test
    void noticesSkipTheRelevanceCheckEntirely() {
        // 公告接口本身就是按 stock_list=<code> 取的，天然属于这只标的。
        // 硬套「标题含公司名」会误杀一类真公告——基金公告的标题常常不带公司名。
        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.NOTICE, List.of(
                notice("关于旗下基金投资关联方承销证券的公告", TODAY, "http://a.com/1")))));

        assertThat(d.notices()).hasSize(1);
    }

    @Test
    void policyItemsNeedToLookLikePolicyNotToNameTheSubject() {
        // **这条是被实测逼出来的。** 政策标题不含公司名也不含行业名：
        // 探针拿到的真标题就是「2027年开始实施！储能相关国家标准获批！」。
        // 照新闻那条判据执行，政策栏会恒为空——而且不报错，看起来就像「近期没有相关政策」，
        // 把「判据写错了」伪装成「客观事实是没有」。
        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.POLICY, List.of(
                policy("2027年开始实施！储能相关国家标准获批！", TODAY, "http://a.com/1"),
                policy("阳光电源行至十字路口：中报业绩双降", TODAY, "http://a.com/2")))));

        assertThat(d.policies()).hasSize(1);
        assertThat(d.policies().get(0).title()).contains("国家标准获批");
        assertThat(d.notes().values()).anyMatch(s -> s.contains("与标的不相关 1"));
    }

    // ==================================================================
    // 时间窗
    // ==================================================================

    @Test
    void newsOlderThanThirtyDaysIsDroppedAndNoticesGetNinety() {
        // 公告放宽到 90 天是刻意的：法定披露本来就稀疏，30 天窗口对安静的公司会几乎恒为空。
        List<NewsItem> mixed = new ArrayList<>();
        mixed.add(notice("阳光电源关于控股股东部分股份解除质押的公告", TODAY.minusDays(89), "http://n/1"));
        mixed.add(notice("阳光电源关于董事会决议的公告", TODAY.minusDays(91), "http://n/2"));

        NewsDigestService.Digest d = run(List.of(
                sourceOf(NewsItem.Category.NOTICE, mixed),
                sourceOf(NewsItem.Category.NEWS, List.of(
                        news("阳光电源中标沙特储能项目", TODAY.minusDays(29), "http://a/1"),
                        news("阳光电源海外订单增长明显", TODAY.minusDays(31), "http://a/2")))));

        assertThat(d.notices()).hasSize(1);
        assertThat(d.notices().get(0).url()).isEqualTo("http://n/1");
        assertThat(d.news()).hasSize(1);
        assertThat(d.news().get(0).url()).isEqualTo("http://a/1");
        assertThat(d.notes().values()).anyMatch(s -> s.contains("超出时间窗 1"));
    }

    // ==================================================================
    // 去重（三层）
    // ==================================================================

    @Test
    void theSameUrlWithDifferentTrackingParametersIsOneItem() {
        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.NEWS, List.of(
                news("阳光电源中标沙特储能项目", TODAY, "http://a.com/x.html?utm_source=wx&spm=abc"),
                news("阳光电源中标沙特储能项目", TODAY, "http://a.com/x.html?from=timeline")))));

        assertThat(d.news()).hasSize(1);
        assertThat(d.notes().values()).anyMatch(s -> s.contains("重复 1"));
    }

    @Test
    void desktopAndMobileUrlsForTheSameArticleAreOneItem() {
        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.NEWS, List.of(
                news("阳光电源中标沙特储能项目", TODAY, "https://www.a.com/x.html"),
                news("阳光电源中标沙特储能项目", TODAY, "http://m.a.com/x.html")))));

        assertThat(d.news()).hasSize(1);
    }

    @Test
    void aQueryParameterThatIsNotTrackingIsKeptSoTwoDifferentArticlesAreNotMerged() {
        // **误合的代价比漏去重大得多。** 漏去重只是页面重复一条；误合是读者
        // 少看到一条真新闻，而且不会报错。所以只丢跟踪参数，不丢整个 query——
        // 有些站的正文 ID 就在 query 里。
        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.NEWS, List.of(
                news("阳光电源中标沙特储能项目", TODAY, "http://a.com/view?id=1"),
                news("阳光电源获机构调研关注", TODAY, "http://a.com/view?id=2")))));

        assertThat(d.news()).hasSize(2);
    }

    @Test
    void titlesDifferingOnlyInFullWidthAndPunctuationAreOneItem() {
        // 归一化（全角折半角、去标点）只在去重这一侧发生，与显示用的清洗分开——
        // 见 cleanse 那组用例里"中文标点必须活下来"那一条。
        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.NEWS, List.of(
                news("阳光电源：中标沙特储能项目！", TODAY, "http://a.com/1"),
                news("阳光电源:中标沙特储能项目", TODAY, "http://a.com/2")))));

        assertThat(d.news()).hasSize(1);
    }

    @Test
    void twoOutletsCarryingTheSameStoryKeepOnlyOne() {
        // 同一事件被多家转载是常态，**宁可少留不可重复**（§9.10 第 4 条）。
        // 今天每一组只有一路源，所以这条覆盖的是**同一路源里回多条转载**——
        // 东财资讯一次就能回 20 条，里面同一件事的转载很常见。
        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.NEWS, List.of(
                news("阳光电源中标沙特储能项目", TODAY, "http://a.com/1"),
                news("【独家】阳光电源中标沙特储能项目", TODAY, "http://b.com/2")))));

        assertThat(d.news()).hasSize(1);
        assertThat(d.notes().values()).anyMatch(s -> s.contains("重复 1"));
    }

    @Test
    void aTitleWithOneCharacterInsertedIsStillTheSameStoryButTwoStoriesAreNotMerged() {
        // **这条钉的是阈值本身**，两个方向都要钉住——只测「该合的合了」会让
        // 阈值被一路调低到「什么都合」而无人发现。
        //
        // 实测（归一化后的 3-gram Jaccard）：插入一个字 0.706，而不同的两件事
        // 最高只到 0.429。章程定为 0.7，正好落在那段空档里。
        // 插入一个字是转载标题最常见的漂移形态——**0.8 会漏掉它**，这就是 0.7 的由来。
        NewsDigestService.Digest same = run(List.of(sourceOf(NewsItem.Category.NEWS, List.of(
                news("阳光电源在内蒙古成立智算科技公司", TODAY, "http://a.com/1"),
                news("阳光电源等在内蒙古成立智算科技公司", TODAY, "http://b.com/2")))));
        assertThat(same.news()).hasSize(1);

        // 反方向：标题很像但确实是两件事，绝不能合
        NewsDigestService.Digest different = run(List.of(sourceOf(NewsItem.Category.NEWS, List.of(
                news("阳光电源中标沙特储能项目", TODAY, "http://a.com/1"),
                news("阳光电源中标沙特光伏项目", TODAY, "http://b.com/2")))));
        assertThat(different.news()).hasSize(2);
    }

    @Test
    void theThresholdSitsInTheMeasuredGap() {
        // 把实测的分布直接钉在这个常量旁边：同事件最低 0.706、不同事件最高 0.429。
        // 以后有人想动这个数，先看这两个实测值，别凭「0.8 看着更稳妥」改回去——
        // 0.8 会把「插入一个字」那种转载漏成两条。
        assertThat(NewsDigestService.NEAR_DUPLICATE).isEqualTo(0.7);
        assertThat(NewsDigestService.similarity(
                NewsDigestService.normalizeTitle("阳光电源在内蒙古成立智算科技公司"),
                NewsDigestService.normalizeTitle("阳光电源等在内蒙古成立智算科技公司")))
                .isGreaterThanOrEqualTo(NewsDigestService.NEAR_DUPLICATE);
        assertThat(NewsDigestService.similarity(
                NewsDigestService.normalizeTitle("阳光电源中标沙特储能项目"),
                NewsDigestService.normalizeTitle("阳光电源中标沙特光伏项目")))
                .isLessThan(NewsDigestService.NEAR_DUPLICATE);
    }

    @Test
    void genuinelyDifferentStoriesWithTheSameSubjectAreAllKept() {
        // 去重写松了会把不同的事合并掉——那比重复更糟。这条是那条风险的反向保险。
        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.NEWS, List.of(
                news("阳光电源中标沙特储能项目", TODAY, "http://a.com/1"),
                news("阳光电源发布年度业绩预告", TODAY, "http://a.com/2"),
                news("阳光电源股东减持计划完成", TODAY, "http://a.com/3")))));

        assertThat(d.news()).hasSize(3);
    }

    @Test
    void whenTwoSourcesCarryTheSameStoryTheHigherWeightedOneWins() {
        // 今天每一组只有一路源，所以这条现在**走不到**——它钉的是机制：
        // 打分高的先进"已接受"集合，近似去重因此在挑选时就完成了，
        // 不需要事后再排一遍。将来加第二路新闻源时，这条保证来源权重说了算。
        List<NewsItem> fromEm = List.of(news("阳光电源在内蒙古成立智算科技公司", TODAY, "http://a.com/1"));
        NewsSourceResult em = sourceOf(NewsItem.Category.NEWS, fromEm);

        NewsItem other = new NewsItem(NewsItem.Category.NEWS, "some-other-news", "另一家",
                "阳光电源等在内蒙古成立智算科技公司", "自媒体", TODAY, "http://b.com/2", null);
        NewsSourceResult otherResult = NewsSourceResult.ok(
                NewsItem.Category.NEWS, "some-other-news", "另一家", List.of(other), 1);

        NewsDigestService.Digest d = run(List.of(otherResult, em));

        assertThat(d.news()).hasSize(1);
        assertThat(d.news().get(0).origin()).isEqualTo(EastMoneyNewsClient.ORIGIN);
    }

    // ==================================================================
    // 打分与排序
    // ==================================================================

    @Test
    void aFreshNoticeOutranksAFreshNewsItem() {
        // 章程 §9.10 第 5 条：法定披露 > 权威财经媒体。同一组内比较才有意义——
        // 公告与新闻本来就不在一组里，这里比的是 score() 本身。
        NewsItem n = notice("阳光电源关于控股股东部分股份解除质押的公告", TODAY, "http://n/1");
        NewsItem s = news("阳光电源中标沙特储能项目", TODAY, "http://a/1");

        assertThat(NewsDigestService.score(n, SUBJECT, TODAY))
                .isGreaterThan(NewsDigestService.score(s, SUBJECT, TODAY));
    }

    @Test
    void freshnessBeatsStalenessWithinTheSameSource() {
        NewsItem fresh = news("阳光电源中标沙特储能项目", TODAY, "http://a/1");
        NewsItem stale = news("阳光电源海外订单增长明显", TODAY.minusDays(25), "http://a/2");

        assertThat(NewsDigestService.score(fresh, SUBJECT, TODAY))
                .isGreaterThan(NewsDigestService.score(stale, SUBJECT, TODAY));
    }

    @Test
    void importanceKeywordsOnlyReorderTheyDoNotIncludeOrExclude() {
        // 重要性关键词只影响**组内排序**，不是准入判据——一条没有关键词的新闻照样显示，
        // 只是排在后面。这条钉住这个分工，免得以后有人把它挪进过滤。
        NewsItem important = news("阳光电源发布年度业绩预告", TODAY, "http://a/1");
        NewsItem plain = news("阳光电源董事长谈行业周期", TODAY, "http://a/2");

        assertThat(NewsDigestService.score(important, SUBJECT, TODAY))
                .isGreaterThan(NewsDigestService.score(plain, SUBJECT, TODAY));

        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.NEWS,
                List.of(important, plain))));
        assertThat(d.news()).hasSize(2);
        assertThat(d.news().get(0).title()).contains("业绩预告");
    }

    // ==================================================================
    // 截断与如实记账
    // ==================================================================

    @Test
    void eachGroupIsCappedAndSaysHowManyWereHeldBack() {
        List<NewsItem> many = new ArrayList<>();
        List<String> topics = List.of("中标沙特储能项目", "发布年度业绩预告", "股东减持计划完成",
                "获机构调研关注", "拟回购公司股份", "海外订单增长明显", "董事长谈行业周期",
                "完成可转债付息", "新增专利授权公告", "参与制定行业标准");
        for (int i = 0; i < topics.size(); i++) {
            many.add(news("阳光电源" + topics.get(i), TODAY, "http://a.com/" + i));
        }

        NewsDigestService.Digest d = run(List.of(sourceOf(NewsItem.Category.NEWS, many)));

        assertThat(d.news()).hasSize(NewsDigestService.MAX_NEWS);
        assertThat(d.notes().values()).anyMatch(s ->
                s.contains("本次取到 " + NewsDigestService.MAX_NEWS + " 条")
                        && s.contains("另有 2 条未展示"));
    }

    @Test
    void aFailedSourceOnlyCostsItsOwnGroup() {
        // 五段失败域在新闻这一层的落地：公告源挂了，新闻与政策照常出。
        NewsSourceResult failed = NewsSourceResult.failed(NewsItem.Category.NOTICE,
                StockNoticeClient.ORIGIN, StockNoticeClient.ORIGIN_LABEL, "公告源不可达（连接超时）");

        NewsDigestService.Digest d = run(List.of(failed,
                sourceOf(NewsItem.Category.NEWS,
                        List.of(news("阳光电源中标沙特储能项目", TODAY, "http://a/1")))));

        assertThat(d.notices()).isEmpty();
        assertThat(d.news()).hasSize(1);
        assertThat(d.notes().values()).anyMatch(s -> s.contains("公告源不可达（连接超时）"));
    }

    @Test
    void anEmptySourceIsReportedAsEmptyNotAsFailure() {
        // **空 ≠ 坏。** ETF 的公告栏恒为空（探针实测 total_hits=0），
        // 页面的措辞必须能区分「源就是这样回的」与「我们取数失败了」。
        NewsSourceResult empty = sourceOf(NewsItem.Category.NOTICE, List.of());

        NewsDigestService.Digest d = run(List.of(empty));

        assertThat(d.notes().values()).anyMatch(s -> s.contains("本次没有取到任何条目")
                && s.contains("不是失败"));
    }

    @Test
    void everyGroupGetsExactlyOneHonestNote() {
        // 每路源一句。没有这句，读者分不清「最近真的没什么事」和「我们的判据把东西滤没了」——
        // 后者是我们最该被发现的错，而它恰好长得最像前者。
        NewsDigestService.Digest d = run(List.of(
                sourceOf(NewsItem.Category.NOTICE,
                        List.of(notice("阳光电源关于控股股东部分股份解除质押的公告", TODAY, "http://n/1"))),
                sourceOf(NewsItem.Category.NEWS,
                        List.of(news("阳光电源中标沙特储能项目", TODAY, "http://a/1"))),
                sourceOf(NewsItem.Category.POLICY,
                        List.of(policy("储能相关国家标准获批", TODAY, "http://p/1")))));

        assertThat(d.notes().values()).hasSize(3);
        assertThat(d.notes().values()).allMatch(s -> s.contains("源回 ") && s.contains("本次取到 "));
    }
}
