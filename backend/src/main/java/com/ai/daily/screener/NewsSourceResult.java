package com.ai.daily.screener;

import java.util.List;

/**
 * 一路源这一次的结果。**每路源各自成败，互不牵连**（章程 §9.7 的五段失败域）。
 *
 * <p>形态照 {@link StockValuationClient.History} 与 {@code KlineOutcome}：失败**不抛异常**，
 * 塞在返回值里。理由是这几路源全都在深度分析的后半段，任何一路抽风都不该让整页打不开——
 * 主查询那一层（行情估值）已经用 429/503 表过态了，后面这几段再抛异常就等于把
 * 「新闻源抽风」升级成「这只票查不了」，那是纯粹的降级放大。
 *
 * <p><b>唯一会抛的还是被限流</b>：那说明这个源在按 IP 计时封我们，必须让上层进冷却
 * 并停止后续请求，不能吞掉。与 {@link MarketDataClient#fetchJson} 的约定一致。
 *
 * <p>{@code rawCount} 是源**回了多少条**，{@code items} 是其中字段齐全、能上页面的。
 * 两个数分开是为了让「本次只取到 N 条」这句如实的话能带上原因——
 * 是源本来就只回了 3 条，还是回了 20 条有 17 条缺日期被丢掉？处置完全不同，
 * 前者只能写「最近确实没有」，后者要去看解析是不是坏了。
 *
 * @param failureReason 非 null = 这一路失败，页面渲染成灰色卡片并写明这句话
 */
public record NewsSourceResult(
        NewsItem.Category category,
        String origin,
        String originLabel,
        List<NewsItem> items,
        int rawCount,
        String failureReason) {

    public NewsSourceResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean ok() {
        return failureReason == null;
    }

    /** 源一条都没回。**与失败是两回事**，页面的措辞也不同（空 ≠ 坏）。 */
    public boolean empty() {
        return ok() && items.isEmpty();
    }

    /** 源回了数据，但全被字段校验丢掉了——这一路其实是坏的，别报成「最近没有」。 */
    public boolean allDropped() {
        return ok() && items.isEmpty() && rawCount > 0;
    }

    static NewsSourceResult ok(NewsItem.Category category, String origin, String originLabel,
                               List<NewsItem> items, int rawCount) {
        return new NewsSourceResult(category, origin, originLabel, items, rawCount, null);
    }

    static NewsSourceResult failed(NewsItem.Category category, String origin, String originLabel,
                                   String reason) {
        return new NewsSourceResult(category, origin, originLabel, List.of(), 0, reason);
    }
}
