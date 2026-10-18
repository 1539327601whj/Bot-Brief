package com.ai.daily.screener.v2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标窗口的边界：空窗口不除零、P95 取「至少 95% 的请求不超过它」、
 * 缓存命中率只按成功首屏算。
 */
class CodeLookupMetricsTest {

    private static final long MS = 1_000_000L;

    @Test
    void emptyWindowIsAllZeroNotNaN() {
        CodeLookupMetrics.MetricsView view = new CodeLookupMetrics().snapshot();

        assertThat(view.coreRequests()).isZero();
        assertThat(view.cacheHitRate()).isZero();
        assertThat(view.avgMs()).isZero();
        assertThat(view.p95Ms()).isZero();
        assertThat(view.sampleSize()).isZero();
    }

    @Test
    void p95CoversAtLeastNinetyFivePercentOfSamples() {
        CodeLookupMetrics metrics = new CodeLookupMetrics();
        // 1..100ms 各一次：P95 应为 95ms。
        for (int ms = 1; ms <= 100; ms++) {
            metrics.recordCoreRequest();
            metrics.recordCore(ms * MS, false, false);
        }

        CodeLookupMetrics.MetricsView view = metrics.snapshot();

        assertThat(view.coreRequests()).isEqualTo(100);
        assertThat(view.p95Ms()).isEqualTo(95);
        assertThat(view.avgMs()).isEqualTo(50);
        assertThat(view.cacheHitRate()).isZero();
    }

    @Test
    void cacheHitRateIsComputedOverSuccessfulCoresOnly() {
        CodeLookupMetrics metrics = new CodeLookupMetrics();
        metrics.recordCoreRequest();
        metrics.recordCore(10 * MS, true, false);
        metrics.recordCoreRequest();
        metrics.recordCore(20 * MS, false, true);
        metrics.recordRateLimited();
        metrics.recordFailure();

        CodeLookupMetrics.MetricsView view = metrics.snapshot();

        assertThat(view.coreRequests()).isEqualTo(2);
        assertThat(view.coreOk()).isEqualTo(2);
        assertThat(view.cacheHits()).isEqualTo(1);
        assertThat(view.cacheHitRate()).isEqualTo(0.5);
        assertThat(view.syncPending()).isEqualTo(1);
        assertThat(view.rateLimited()).isEqualTo(1);
        assertThat(view.failures()).isEqualTo(1);
    }

    @Test
    void windowKeepsOnlyTheLatestSamples() {
        CodeLookupMetrics metrics = new CodeLookupMetrics();
        for (int i = 0; i < 300; i++) {
            metrics.recordCoreRequest();
            metrics.recordCore(MS, false, false);
        }

        CodeLookupMetrics.MetricsView view = metrics.snapshot();

        assertThat(view.coreRequests()).isEqualTo(300);
        assertThat(view.sampleSize()).isEqualTo(256);
    }
}
