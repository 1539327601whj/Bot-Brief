package com.ai.daily.screener;

import java.time.LocalDate;

/**
 * 一条已解析、**尚未清洗与打分**的新闻/公告/政策条目。
 *
 * <p><b>不变式：三个字段非空——{@code title}、{@code url}、{@code publishedOn}。</b>
 * 理由不是洁癖，是章程 §9.10 只允许输出「标题 + 来源 + 日期 + 链接」这四样：
 * 缺了其中任何一个，这条就没法满足 §8「每个数据都要能回答哪一日、哪个来源」，
 * 留在页面上只会是一行点不动也查不了的字。所以缺字段的行**在客户端解析时就被丢掉**，
 * 并且把丢掉的条数如实报上去（见 {@link NewsSourceResult#rawCount}），
 * 而不是让它带着 null 一路流到页面上。
 *
 * <p><b>{@code publishedOn} 已经在上海时区</b>。这不是显示层的活儿：Google News 的
 * {@code pubDate} 是 GMT，换算晚一步，20:00 GMT 之后的文章就全变成「第二天」
 * （探针 {@code verify_analysis_sources.py} 实测过）。时区换算放在**解析的那一处**，
 * 是因为那是唯一知道源用的是哪个时区的地方；放到后面就没法判断该不该换了。
 *
 * @param category       公告 / 新闻 / 政策，决定它进哪一组
 * @param origin         来源键，用于打分权重查表（见 {@link NewsDigestService#ORIGIN_WEIGHT}）
 * @param originLabel    给人看的来源名（「东财公告」「Google 新闻」）
 * @param title          **原始标题**，可能带 {@code <em>} 强调标签——清洗是
 *                       {@link NewsDigestService} 的活儿，客户端只管把字段取出来
 * @param source         媒体名，可为 null（公告这类法定披露没有媒体署名）
 * @param publishedOn    上海时区的那一天
 * @param url            可点击复核的地址
 * @param noticeCategory 公告分类（{@code columns[].column_name}，实测出现过「股份质押、冻结」），
 *                       非公告为 null。**它把「筹码与预期」从纯提问升级成有出处的列举**
 *                       （章程 §9.12）
 */
public record NewsItem(
        Category category,
        String origin,
        String originLabel,
        String title,
        String source,
        LocalDate publishedOn,
        String url,
        String noticeCategory) {

    /**
     * 三组输出的分组键。{@code label()} 直接上页面，所以它是中文。
     */
    public enum Category {
        NOTICE("公告"),
        NEWS("新闻"),
        POLICY("政策");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public NewsItem {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("新闻条目的标题不能为空");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("新闻条目的链接不能为空：" + title);
        }
        if (publishedOn == null) {
            throw new IllegalArgumentException("新闻条目必须有日期（章程 §8）：" + title);
        }
        if (category == null) {
            throw new IllegalArgumentException("新闻条目必须归到一组：" + title);
        }
    }
}
