package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按源的外呼与限流计数。
 *
 * <p>时钟用覆盖 {@code localDateNow()/localDateTimeNow()} 的匿名子类注入（同
 * {@code CodeLookupServiceTest.serviceWithClock} 的写法）：跨天归零这条**必须**能钉住日期，
 * 否则它只能在真的跨天那一刻跑，等于没有。基准一律是显式日期，不取「现在」——
 * CI 在 UTC、本机在 UTC+8，用「现在」写断言的用例会变成只有一台机器能照出问题。
 */
class MarketCallMetricsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);

    private final LocalDate[] today = {DAY};

    private MarketCallMetrics metrics() {
        return new MarketCallMetrics() {
            @Override
            LocalDate localDateNow() {
                return today[0];
            }

            @Override
            LocalDateTime localDateTimeNow() {
                return today[0].atTime(10, 0);
            }
        };
    }

    private static Map<String, Object> row(MarketCallMetrics m, String provider) {
        return m.snapshot().get(provider);
    }

    @Test
    void callsAndThrottlesAreCountedPerProvider() {
        MarketCallMetrics m = metrics();
        m.recordCall("eastmoney");
        m.recordCall("eastmoney");
        m.recordCall("eastmoney");
        m.recordThrottled("eastmoney");
        m.recordCall("tencent");

        assertThat(row(m, "eastmoney")).containsEntry("calls", 3L).containsEntry("throttled", 1L);
        // 按源分开：腾讯没挨打，它的数字就得是 0，不能被东财的连累
        assertThat(row(m, "tencent")).containsEntry("calls", 1L).containsEntry("throttled", 0L);
        assertThat(row(m, "sina")).isNull();
    }

    @Test
    void aThrottleCarriesItsMoment() {
        MarketCallMetrics m = metrics();
        m.recordCall("csindex");
        assertThat(row(m, "csindex")).containsEntry("lastThrottledAt", null);

        // csindex 与 danjuan 目前没有任何 enterCooldown 调用点，所以它们的冷却恒为空，
        // 但「被拒了几次」仍然要如实记下来——这正是只看冷却表会漏掉的那部分压力。
        m.recordThrottled("csindex");
        assertThat(row(m, "csindex")).containsEntry("lastThrottledAt", "2026-10-03T10:00");
    }

    @Test
    void theDayRollsOverAndTheNumbersStartAgain() {
        MarketCallMetrics m = metrics();
        m.recordCall("eastmoney");
        m.recordThrottled("eastmoney");
        assertThat(m.currentDay()).isEqualTo(DAY);

        today[0] = DAY.plusDays(1);

        // 惰性归零：跨天后的第一次读写就把它清掉，不需要定时任务
        assertThat(row(m, "eastmoney")).isNull();
        assertThat(m.currentDay()).isEqualTo(DAY.plusDays(1));
        // 归零后重新计数，不是在旧数字上累加
        m.recordCall("eastmoney");
        assertThat(row(m, "eastmoney")).containsEntry("calls", 1L).containsEntry("throttled", 0L);
    }

    @Test
    void aMissingProviderNameDoesNotThrowOrLeakIntoAnotherBucket() {
        // 键是空的说明这一路没人给它命名，但它仍然占一个桶——
        // 混进任何一个真实源里都会让那个源的数字变得无法解释。
        MarketCallMetrics m = metrics();
        m.recordCall(null);
        assertThat(row(m, "unknown")).containsEntry("calls", 1L);
    }
}