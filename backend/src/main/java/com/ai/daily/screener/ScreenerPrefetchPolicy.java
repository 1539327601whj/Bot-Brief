package com.ai.daily.screener;

import java.time.LocalTime;
import java.util.function.BooleanSupplier;

/**
 * 「这一次点击该读预取库、还是该外呼」的判据。**纯函数**，时间与「库里有没有」都由调用方给，
 * 所以不必起 Spring、也不必关心跑测试时是几点——照 {@code should_sync_index_pool} 的测法。
 *
 * <p>判据三项，缺一不可：
 * <ol>
 *   <li><b>开关</b>。关掉就回到纯实时，是不改代码就能回退的那根绳。</li>
 *   <li><b>不在盘中</b>（09:30–15:00）。盘中一律实时——那是「现价位置」这个概念唯一有意义的时段，
 *       读一份要到收盘后才写的数据等于把昨天的结论当今天的。</li>
 *   <li><b>库里确有今天的快照</b>。</li>
 * </ol>
 *
 * <p><b>第三项为什么按「今天」而不是按钟点</b>：预取只在收盘后写，所以正常交易日里
 * 「今天的快照存在」这件事本身就编码了「已过预取时刻」。反过来，盘中永远不存在
 * {@code trade_date == 今天} 的行，天然落回实时——不需要靠时钟猜。
 *
 * <p>第二项与第三项看似重复，其实是两种失效模式的兜底：钟点那一项防的是**有人盘中手动触发预取**，
 * 把未收盘的价当收盘价存下来；第三项防的是**预取失败**，那时库里根本没东西可读。
 * 两个都要，因为「几点」和「有没有」是两个独立的事实。
 *
 * <p><b>第三项为什么是 {@link BooleanSupplier} 而不是 boolean</b>：前两项都在内存里，
 * 而这一项要查一次库。传 boolean 就意味着**每一次点击都先查库、再因为盘中或开关关掉而把结果扔掉**——
 * 关掉 {@code read-enabled} 本该让这条路径彻底不碰库，传 boolean 就做不到。传懒查询则连
 * 「开关关掉时一次库都不查」这件事本身都可以被断言（见 {@code ScreenerPrefetchPolicyTest}）。
 */
final class ScreenerPrefetchPolicy {

    private ScreenerPrefetchPolicy() {
    }

    static boolean shouldReadFromDb(boolean readEnabled, LocalTime now, BooleanSupplier hasTodaySnapshot) {
        if (!readEnabled) return false;
        if (now == null || ScreenerCache.isTradingHours(now)) return false;
        return hasTodaySnapshot.getAsBoolean();
    }
}