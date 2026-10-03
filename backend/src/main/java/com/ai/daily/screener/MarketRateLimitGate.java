package com.ai.daily.screener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同一个源两次外呼之间的**最小间隔**——事前限速。
 *
 * <h2>为什么要有它（冷却不够用）</h2>
 *
 * <p>{@link ScreenerCache#enterCooldown(String)} 是**事后**的：被打回来了才停手。
 * 它漏掉的正是最致命的那一段——「还没被打回来，但已经在几秒内打了几十次」。
 * 一次筛选扫描约 21 次外呼（清单 1 + 行情 3 批 + 日线 16 条并发 5 线程），
 * 页面一开就跑一遍，机房 IP 的阈值远低于这个瞬时密度。等冷却触发时，封禁已经开始了。
 *
 * <h2>语义是「间隔」不是「并发上限」</h2>
 *
 * <p>目标是**把突发抹匀**，不是把并发打回串行。{@code screener-fetch-N} 那几个线程
 * 照样并发，只是每个源的发车时刻被排开：不是「同时醒来抢一把锁」，而是每个请求
 * 在锁内**预订**一个发车时刻，然后各自在锁外睡到那个点。临界区只做「读-算-写一个 long」，
 * 因此不同源之间不会互相阻塞，同一个源的并发也不会退化成串行。
 *
 * <h2>两个刻意的决定</h2>
 *
 * <p><b>排队超时就如实失败，且不占槽</b>：等太久说明这一批本来就打不完了，
 * 与其让用户对着一个转圈的页面（还占着后面请求的额度），不如按「本次未外呼」失败。
 * 上限必须小于 {@code screener.http.read-timeout}——否则用户点一下会先卡在本地排队、
 * 再撞上读超时，两层时间都白花。
 *
 * <p><b>不做冷却抢占</b>：这里是「发车前排队」，不是「发现被限流就拦下」。
 * 冷却该由已经做了这件事的调用方去判（{@link CodeLookupService#lookup}、
 * {@link StockScreenerService} 的几条链都有前置判断）。在闸门里再抛一次限流异常会两头出错：
 * 一是页面上出现第二套 429 文案，比调用方那句「约 N 分钟后可再试」更泛；
 * 二是那个异常会顺着日线链被翻译成 {@code KlineOutcome.throttled} 再进
 * {@code enterCooldown}，而后者在「新的时刻更晚」时**总是推后**——冷却期内每点一次
 * 就再续 10 分钟，正是要消灭的「越点越封」。
 *
 * <p><b>只用 {@link System#nanoTime()}</b>，不碰墙钟：这里量的是时间差，与日期/时区无关，
 * 于是它在哪台机器、哪个时区跑都一样。别改成 {@code currentTimeMillis}——那会把它
 * 重新绑回墙上时间，也会在时间被回拨时算出一个负的等待。
 */
@Slf4j
@Component
public class MarketRateLimitGate {

    /** 时钟缝。测试注入假时钟，绝不真睡，也不会因为机器慢而抖。 */
    @FunctionalInterface
    public interface NanoClock {
        long nanos();
    }

    /** 睡眠缝。测试用它记录「被要求等多久」，而不是真的等。 */
    @FunctionalInterface
    public interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final long minIntervalNanos;
    private final long maxWaitNanos;
    private final NanoClock clock;
    private final Sleeper sleeper;

    /** provider → 锁对象。按源分开：锁的粒度必须与额度的粒度一致，否则一个源排队会拖住另一个。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /** provider → 下一个允许发车的时刻（纳秒，{@link System#nanoTime()} 的基准）。 */
    private final Map<String, Long> nextAllowedNanos = new ConcurrentHashMap<>();

    /**
     * <b>{@code @Autowired} 不能省</b>：这个类有两个构造器（这个和下面那个测试用的），
     * 而不标注时 Spring 会退回去找**无参构造器**，找不到就抛
     * {@code NoDefaultConstructorFoundException}——那是**启动时**才报的错，
     * 本地全量测试一样绿。{@link MarketRateLimitGateTest} 里有一条用例专门钉住这件事。
     */
    @Autowired
    public MarketRateLimitGate(
            @Value("${screener.rate-limit-min-interval-ms:150}") long minIntervalMs,
            @Value("${screener.rate-limit-max-wait-ms:8000}") long maxWaitMs) {
        this(minIntervalMs, maxWaitMs, System::nanoTime, MarketRateLimitGate::realSleep);
    }

    /** 测试用构造：换成假时钟与记录型 sleeper，间隔置 0 时 {@link #acquire} 一步都不走。 */
    MarketRateLimitGate(long minIntervalMs, long maxWaitMs, NanoClock clock, Sleeper sleeper) {
        this.minIntervalNanos = Math.max(0, minIntervalMs) * NANOS_PER_MILLI;
        this.maxWaitNanos = Math.max(0, maxWaitMs) * NANOS_PER_MILLI;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * 为一次即将发出的请求排队。返回时说明轮到你了，调用方随即发请求。
     *
     * @throws MarketDataException 排队超时或被中断。两种情况都**没有**外呼，
     *                             所以上层把它当「本次未完成」处理是对的。
     */
    public void acquire(String provider) {
        if (minIntervalNanos <= 0) {
            return;
        }
        String key = provider == null || provider.isBlank() ? "unknown" : provider;
        long waitNanos;
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            long now = clock.nanos();
            Long prior = nextAllowedNanos.get(key);
            long fireAt = prior == null ? now : Math.max(now, prior);
            waitNanos = fireAt - now;
            if (waitNanos > maxWaitNanos) {
                // 先判超时、再占槽：超时的这次不许把后面所有人的发车时刻往后推
                throw new MarketDataException(
                        "行情源本地限速排队超时（" + key + "），本次未外呼");
            }
            nextAllowedNanos.put(key, fireAt + minIntervalNanos);
        }
        if (waitNanos > 0) {
            try {
                sleeper.sleepNanos(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MarketDataException("等待行情源限速额度时被中断（" + key + "）");
            }
        }
    }

    private static void realSleep(long nanos) throws InterruptedException {
        Thread.sleep(nanos / NANOS_PER_MILLI, (int) (nanos % NANOS_PER_MILLI));
    }
}