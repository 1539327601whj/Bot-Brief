package com.ai.daily.screener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 盘后预取任务。
 *
 * <p>这个任务一天只跑 4 次，跑错一次不是「数据少一点」，是**白白多打一次行情源**——
 * 而撞限流正是整件事的起因。所以这里盯住的全是「本该不发请求的那些分支」，
 * 以及「一次都别真的写一半」。
 */
class ScreenerPrefetchTaskTest {

    private static final LocalTime AFTER_CLOSE = LocalTime.of(16, 0);
    private static final LocalTime BEFORE_CLOSE = LocalTime.of(13, 0);

    /**
     * 全部用例都点名一个交易日（2026-09-30 是星期三）。
     *
     * <p>**不要**改用无参的 {@code runOnce()}：它按测试当天判周末，于是同一个测试
     * 在工作日绿、在周末红——而 CI 什么时候跑不由我们定。
     */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 30);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 3);

    private MarketDataClient client;
    private ScreenerPrefetchService prefetchService;
    private ScreenerCache cache;

    @BeforeEach
    void setUp() {
        client = mock(MarketDataClient.class);
        prefetchService = mock(ScreenerPrefetchService.class);
        cache = new ScreenerCache(15, 20, 60, 10);
        when(client.poolSize()).thenReturn(300);
        when(client.fetchUniverse()).thenReturn(snapshot());
        when(prefetchService.save(any(), anyInt())).thenReturn(1);
    }

    private ScreenerPrefetchTask task(boolean enabled, boolean force) {
        return task(enabled, force, AFTER_CLOSE);
    }

    /**
     * 钟点与日期都钉住的子类。钟点是为了不必等到下午三点半；日期是为了
     * 启动补偿那条路径——它内部走 {@code runOnce()}，会按测试当天判周末，
     * 于是「工作日绿、周末红」。两个缝都得塞住，这条用例才不看日历。
     */
    private ScreenerPrefetchTask task(boolean enabled, boolean force, LocalTime at) {
        return task(enabled, force, at, WEDNESDAY);
    }

    private ScreenerPrefetchTask task(boolean enabled, boolean force, LocalTime at, LocalDate on) {
        return new ScreenerPrefetchTask(client, prefetchService, cache, enabled, force) {
            @Override
            LocalTime localTimeNow() {
                return at;
            }

            @Override
            LocalDate localDateNow() {
                return on;
            }
        };
    }

    // ================= 该跳过的分支：一次外呼都不发 =================

    @Test
    void whenTodayAlreadyRanItDoesNotCallTheSourceAtAll() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(true);

        task(true, false).runOnce(WEDNESDAY);

        verifyNoInteractions(client);
        verify(prefetchService, never()).save(any(), anyInt());
    }

    @Test
    void whileTheSourceIsCoolingDownItSkipsInsteadOfMakingTheBanDeeper() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(false);
        cache.enterCooldown();

        task(true, false).runOnce(WEDNESDAY);

        // 冷却就是「刚被拒过」的标记。这时候再打一次，只会把封禁推得更深——
        // 与点击路径共用同一份冷却，正是为了让两边都别犯这个错。
        verifyNoInteractions(client);
        verify(prefetchService, never()).save(any(), anyInt());
    }

    @Test
    void theSwitchStopsEverythingBeforeAnyDecisionIsMade() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(false);

        task(false, false).runOnce(WEDNESDAY);

        verifyNoInteractions(client, prefetchService);
    }

    // ================= 该跑的分支 =================

    @Test
    void itFetchesOnceAndStoresWhatTheClientActuallyReturns() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(false);

        task(true, false).runOnce(WEDNESDAY);

        verify(client).fetchUniverse();
        // 存的是**同一次取回**的那个对象，不是一个形状相同的新对象
        verify(prefetchService).save(snapshot(), 300);
    }

    @Test
    void theStoredPoolSizeComesFromTheClientNotFromASecondConfigRead() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(false);
        when(client.poolSize()).thenReturn(123);

        task(true, false).runOnce(WEDNESDAY);

        // 头表的 pool_size 存的是「请求了前 N 只」。任务里若再读一遍 screener.pool-size，
        // 就有两个可能不一致的 N，而它俩的分歧没人能查得出来
        verify(prefetchService).save(any(), eq(123));
    }

    @Test
    void forceRerunsEvenThoughTodayAlreadyRan() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(true);

        task(true, true).runOnce(WEDNESDAY);

        // 手工补跑用。**没有 HTTP 端点**，只能靠这个环境变量——
        // 加端点就得多一层鉴权，而这个入口一年也用不上几次
        verify(client).fetchUniverse();
    }

    /**
     * 周末一次都不跑，连「今天跑过没有」都不问。
     *
     * <p>周六周日不是交易日，东财这时给的 latestTradeDate 还是周五，存下来的那一行
     * {@code trade_date} 是周五——而读侧只在周五那天找它（判据里那是「今天」）。
     * 于是周末补出来的数据**永远读不到**，纯属白打 4 次外呼。
     */
    @Test
    void weekendsAreSkippedBeforeTheDatabaseIsEvenConsulted() {
        task(true, false).runOnce(SATURDAY);

        verifyNoInteractions(client, prefetchService);
    }

    @Test
    void anExplicitForcedRunStillWorksOnAWeekend() {
        // force 是手工补跑用的，不该被「今天星期几」挡住——挡住它只会让人以为开关坏了
        task(true, true).runOnce(SATURDAY);

        verify(client).fetchUniverse();
    }

    // ================= 失败：什么都不写，也什么都不冒出去 =================

    @Test
    void beingRateLimitedEntersTheSharedCooldownAndWritesNothing() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(false);
        when(client.fetchUniverse()).thenThrow(new MarketDataException.MarketDataRateLimitedException(
                "东财在限流", null));

        assertThatCode(() -> task(true, false).runOnce(WEDNESDAY)).doesNotThrowAnyException();

        verify(prefetchService, never()).save(any(), anyInt());
        // 记进冷却，管理员紧接着点那一次也不会再打——这才是「共用一份冷却」的意义
        assertThat(cache.inCooldown()).isTrue();
    }

    @Test
    void anyOtherFailureIsSwallowedWithoutEnteringCooldown() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(false);
        when(client.fetchUniverse()).thenThrow(new MarketDataException("行情源返回结构异常"));

        assertThatCode(() -> task(true, false).runOnce(WEDNESDAY)).doesNotThrowAnyException();

        verify(prefetchService, never()).save(any(), anyInt());
        // 取数失败不等于被限流。顺手记一次冷却会让页面对用户说「东财在限流」，
        // 而它其实只是返回了一个坏结构——那句提示从此不可信
        assertThat(cache.inCooldown()).isFalse();
    }

    @Test
    void aWriteFailureIsAlsoSwallowedAndLeavesNoRowBehind() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(false);
        when(prefetchService.save(any(), anyInt()))
                .thenThrow(new IllegalArgumentException("预取明细缺 stockCode"));

        assertThatCode(() -> task(true, false).runOnce(WEDNESDAY)).doesNotThrowAnyException();

        // 失败不留行是**服务层**的保证（头与明细同事务、头最后写），这里只要求任务不把异常带出去：
        // 定时线程带异常结束时，Spring 会一直重试同一个调度点，而这里要的是「等下个整点再来」
    }

    // ================= 启动补偿 =================

    @Test
    void startupCatchesUpWhenTheContainerWasDownAtPrefetchTime() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(false);

        task(true, false, AFTER_CLOSE).run(null);

        verify(client).fetchUniverse();
    }

    @Test
    void startupDoesNotCatchUpOnAWeekendEvenThoughItIsPastPrefetchTime() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(false);

        // 周六周日永远不是交易日，东财这时给的 latestTradeDate 还是周五，
        // 补出来的那一行 trade_date 会是周五，而读侧只在周五找它——等于白打 4 次外呼。
        // 启动补偿必须和定时那两条 cron 一样把周末挡掉。
        task(true, false, AFTER_CLOSE, SATURDAY).run(null);

        verifyNoInteractions(client);
    }

    @Test
    void startupDoesNothingBeforePrefetchTime() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(false);

        task(true, false, BEFORE_CLOSE).run(null);

        // 三点前重启不该顺手取一次：那时的收盘价还没定，取回来的会是一份「准收盘价」，
        // 而它会被当成当天的正式数据一直用到第二天
        verifyNoInteractions(client);
    }

    @Test
    void startupNeverTakesTheApplicationDown() {
        when(prefetchService.hasPrefetchedOn(any())).thenReturn(false);
        when(client.fetchUniverse()).thenThrow(new IllegalStateException("数据库连不上"));

        // 启动路径上让它把应用带崩，等于拿一个可选的优化换掉整个服务的可用性。
        // 预取没跑成的代价只是当天退回实时，与「起不来」根本不是一个量级。
        assertThatCode(() -> task(true, false, AFTER_CLOSE).run(null)).doesNotThrowAnyException();
    }

    private static MarketDataClient.UniverseSnapshot snapshot() {
        StockRow row = new StockRow();
        row.setCode("600519");
        row.setName("贵州茅台");
        row.setMarket(1);
        return new MarketDataClient.UniverseSnapshot(List.of(row), 1, 0,
                LocalDate.of(2026, 9, 30), new BigDecimal("38300000000"));
    }
}