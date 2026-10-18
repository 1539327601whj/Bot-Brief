package com.ai.daily.screener;

import com.ai.daily.warehouse.SharedRateLimitStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 进程内闸门与跨进程闸门叠加后的行为。
 *
 * <p>全部不真睡：假时钟 + 记录型 sleeper。共享闸门的返回值由 mock 控制，
 * 要验证的是「本地等多久、共享等多久、两边都拒绝时说了什么」。
 */
class MarketRateLimitGateSharedStateTest {

    private static final long MS = 1_000_000L;

    private static final class FakeClock implements MarketRateLimitGate.NanoClock {
        private long nanos;

        @Override
        public long nanos() {
            return nanos;
        }
    }

    private static final class RecordingSleeper implements MarketRateLimitGate.Sleeper {
        private final List<Long> sleptNanos = new ArrayList<>();

        @Override
        public void sleepNanos(long nanos) {
            sleptNanos.add(nanos);
        }

        List<Long> sleptMillis() {
            return sleptNanos.stream().map(n -> n / MS).toList();
        }
    }

    private final FakeClock clock = new FakeClock();
    private final RecordingSleeper sleeper = new RecordingSleeper();
    private final SharedRateLimitStore shared = mock(SharedRateLimitStore.class);

    @Test
    void sharedWaitIsAddedAfterTheLocalSlot() {
        when(shared.reserve(anyString(), anyLong(), anyLong()))
                .thenReturn(Optional.of(new SharedRateLimitStore.Reservation(
                        true, 40, false, 0, null)));

        MarketRateLimitGate gate = new MarketRateLimitGate(150, 8000, clock, sleeper, shared);
        gate.acquire("eastmoney");

        // 本地第一个槽不用等；共享闸门要求等 40ms
        assertThat(sleeper.sleptMillis()).containsExactly(40L);
    }

    @Test
    void theLocalWaitIsDeductedFromTheSharedBudget() {
        when(shared.reserve(anyString(), anyLong(), anyLong()))
                .thenReturn(Optional.of(new SharedRateLimitStore.Reservation(
                        true, 0, false, 0, null)));

        MarketRateLimitGate gate = new MarketRateLimitGate(150, 8000, clock, sleeper, shared);
        gate.acquire("eastmoney");   // 本地等 0
        gate.acquire("eastmoney");   // 本地等 150

        ArgumentCaptor<Long> maxWait = ArgumentCaptor.forClass(Long.class);
        verify(shared, org.mockito.Mockito.times(2))
                .reserve(anyString(), anyLong(), maxWait.capture());
        assertThat(maxWait.getAllValues()).containsExactly(8000L, 7850L);
    }

    @Test
    void aSharedCoolingRejectionDoesNotFireTheRequest() {
        when(shared.reserve(anyString(), anyLong(), anyLong()))
                .thenReturn(Optional.of(new SharedRateLimitStore.Reservation(
                        false, 0, true, 60_000, "源在冷却期内")));

        MarketRateLimitGate gate = new MarketRateLimitGate(150, 8000, clock, sleeper, shared);

        assertThatThrownBy(() -> gate.acquire("eastmoney"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("共享闸门冷却中")
                .hasMessageContaining("本次未外呼");
    }

    @Test
    void aSharedQueueTimeoutAlsoFailsHonestly() {
        when(shared.reserve(anyString(), anyLong(), anyLong()))
                .thenReturn(Optional.of(new SharedRateLimitStore.Reservation(
                        false, 9000, false, 0, "共享限速排队超时")));

        MarketRateLimitGate gate = new MarketRateLimitGate(150, 8000, clock, sleeper, shared);

        assertThatThrownBy(() -> gate.acquire("eastmoney"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("共享限速排队超时");
    }

    @Test
    void aDegradedSharedGateLeavesTheLocalGateWorking() {
        when(shared.reserve(anyString(), anyLong(), anyLong())).thenReturn(Optional.empty());

        MarketRateLimitGate gate = new MarketRateLimitGate(150, 8000, clock, sleeper, shared);
        gate.acquire("eastmoney");
        gate.acquire("eastmoney");

        // 共享层不可用时，本地间隔照常生效：150ms 一个槽
        assertThat(sleeper.sleptMillis()).containsExactly(150L);
    }

    @Test
    void laneKeysMatchThePythonSideContract() {
        // 这两个键是 Java 与 Python 的唯一约定，改动必须两边一起改。
        assertThat(SharedRateLimitStore.lane("eastmoney")).isEqualTo("eastmoney:interactive");
        assertThat(SharedRateLimitStore.lane("tencent")).isEqualTo("tencent");
        assertThat(SharedRateLimitStore.lane(null)).isEqualTo("unknown");
        assertThat(SharedRateLimitStore.lane("  ")).isEqualTo("unknown");
    }
}
