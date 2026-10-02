package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这次点击该读预取库还是该外呼」的判据。
 *
 * <p>纯函数，时间由调用方给，所以**不必起 Spring、也不必挑钟点跑测试**——
 * 这条判据最怕的恰恰是「靠跑测试的时刻来回归」：它在盘中与盘后给出的答案不同，
 * 而 CI 在哪个钟点跑是随机的。
 */
class ScreenerPrefetchPolicyTest {

    /** 盘中：一次点击落在这个区间里，就必须是最新价。 */
    private static final LocalTime IN_SESSION = LocalTime.of(10, 30);
    /** 盘后：收盘后 30 分钟那个点之后。 */
    private static final LocalTime AFTER_CLOSE = LocalTime.of(16, 0);

    @Test
    void afterCloseWithTodaysSnapshotInTheDatabaseReadsFromIt() {
        assertThat(ScreenerPrefetchPolicy.shouldReadFromDb(true, AFTER_CLOSE, () -> true)).isTrue();
    }

    @Test
    void inSessionAlwaysGoesLiveEvenWithTodaysSnapshot() {
        // 今天是今天的数据、库里有今天的行，看着完全对——但那是 15:00 的收盘价，
        // 而用户是在 10:30 点开的，他问的是「现价在什么位置」。这一条错了最难被发现：
        // 页面一切正常，结论是昨天的。
        assertThat(ScreenerPrefetchPolicy.shouldReadFromDb(true, IN_SESSION, () -> true)).isFalse();
    }

    @Test
    void afterCloseWithoutTodaysSnapshotGoesLive() {
        // 预取失败 / 还没跑 / 今天休市（东财给的还是上一个交易日），都得退回实时
        assertThat(ScreenerPrefetchPolicy.shouldReadFromDb(true, AFTER_CLOSE, () -> false)).isFalse();
    }

    @Test
    void theSwitchAloneDisablesTheWholePath() {
        // 回退绳：关掉就回到改动前，且**连库都不查**
        assertThat(ScreenerPrefetchPolicy.shouldReadFromDb(false, AFTER_CLOSE, () -> true)).isFalse();
    }

    @Test
    void aNullClockGoesLiveInsteadOfThrowing() {
        // 判据本身不该有能力把一次点击变成错误页
        assertThat(ScreenerPrefetchPolicy.shouldReadFromDb(true, null, () -> true)).isFalse();
    }

    @Test
    void theDatabaseIsNotEvenQueriedWhenTheAnswerIsAlreadyNo() {
        AtomicBoolean queried = new AtomicBoolean(false);

        assertThat(ScreenerPrefetchPolicy.shouldReadFromDb(false, AFTER_CLOSE, () -> {
            queried.set(true);
            return true;
        })).isFalse();
        assertThat(ScreenerPrefetchPolicy.shouldReadFromDb(true, IN_SESSION, () -> {
            queried.set(true);
            return true;
        })).isFalse();

        // 关掉开关或盘中时**一次库都不该查**。这不是省那一次 COUNT：
        // 关掉 read-enabled 的语义是「这条路径彻底不碰预取库」，
        // 若还留着一次查询，就等于回退绳只断了一半。
        assertThat(queried).isFalse();
    }

    @Test
    void theSessionBoundariesAreInclusiveSoBothEdgesBehaveAsWritten() {
        // isTradingHours 的定义是 09:30–15:00 闭区间（见 ScreenerCache）。这里把它钉在
        // 判据这一侧：两处若哪天不一致，盘中最后一刻会读到收盘后的快照。
        assertThat(ScreenerPrefetchPolicy.shouldReadFromDb(true, LocalTime.of(9, 30), () -> true)).isFalse();
        assertThat(ScreenerPrefetchPolicy.shouldReadFromDb(true, LocalTime.of(15, 0), () -> true)).isFalse();
        assertThat(ScreenerPrefetchPolicy.shouldReadFromDb(true, LocalTime.of(15, 1), () -> true)).isTrue();
        assertThat(ScreenerPrefetchPolicy.shouldReadFromDb(true, LocalTime.of(9, 29), () -> true)).isTrue();
    }
}