package com.ai.daily.screener.v2;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 代码查询 v2 的进程内运行指标。
 *
 * <p><b>为什么不用 Micrometer</b>：项目当前没有 actuator，引入一整套指标栈只为五个数
 * 不划算；这里只记聚合值，不记用户、不记代码——{@code md_sync_job} 和本类都不留
 * 用户级查询日志，这一点与页面「按需查询」的定位一致。
 *
 * <p>指标口径：
 * <ul>
 *   <li>{@code coreRequests / coreOk}：首屏请求数与成功数；</li>
 *   <li>{@code cacheHitRate}：首屏命中本地仓库（本轮零外呼）的比例；</li>
 *   <li>{@code p95Ms / avgMs}：最近 256 次成功首屏的耗时；</li>
 *   <li>{@code rateLimited}：被行情源限流的次数；</li>
 *   <li>{@code failures}：除限流外的失败次数。</li>
 * </ul>
 *
 * <p>这些数字只反映**单个进程、最近一段窗口**，重启即清零；要看趋势应接入日志平台。
 */
@Component
public class CodeLookupMetrics {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int WINDOW = 256;

    private final LongAdder coreRequests = new LongAdder();
    private final LongAdder coreOk = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder syncPending = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder failures = new LongAdder();

    private final long[] latenciesNanos = new long[WINDOW];
    private final AtomicInteger cursor = new AtomicInteger();

    /** 首屏请求进入时调用一次；失败也会计入，用来算成功率和缓存命中率的分母。 */
    public void recordCoreRequest() {
        coreRequests.increment();
    }

    public void recordCore(long elapsedNanos, boolean fromCache, boolean pending) {
        coreOk.increment();
        if (fromCache) {
            cacheHits.increment();
        }
        if (pending) {
            syncPending.increment();
        }
        int slot = cursor.getAndIncrement();
        synchronized (latenciesNanos) {
            latenciesNanos[Math.floorMod(slot, WINDOW)] = Math.max(0, elapsedNanos);
        }
    }

    public void recordRateLimited() {
        rateLimited.increment();
    }

    public void recordFailure() {
        failures.increment();
    }

    public MetricsView snapshot() {
        long total = coreRequests.sum();
        long ok = coreOk.sum();
        long hits = cacheHits.sum();
        long[] sample;
        synchronized (latenciesNanos) {
            int size = Math.min(cursor.get(), WINDOW);
            sample = Arrays.copyOf(latenciesNanos, size);
        }
        Arrays.sort(sample);
        long p95 = sample.length == 0 ? 0 : sample[(int) Math.ceil(sample.length * 0.95) - 1];
        long sum = 0;
        for (long value : sample) {
            sum += value;
        }
        long avg = sample.length == 0 ? 0 : sum / sample.length;

        double hitRate = ok == 0 ? 0 : round((double) hits / ok);
        return new MetricsView(total, ok, hits, hitRate,
                avg / 1_000_000L, p95 / 1_000_000L,
                syncPending.sum(), rateLimited.sum(), failures.sum(),
                sample.length, LocalDateTime.now(SHANGHAI).toString());
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    /**
     * 一次快照。{@code cacheHitRate} 是 0–1 的小数；{@code sampleSize} 是参与
     * P95 的最近成功请求数（最多 256）。
     */
    public record MetricsView(
            long coreRequests,
            long coreOk,
            long cacheHits,
            double cacheHitRate,
            long avgMs,
            long p95Ms,
            long syncPending,
            long rateLimited,
            long failures,
            int sampleSize,
            String checkedAt
    ) {
    }
}
