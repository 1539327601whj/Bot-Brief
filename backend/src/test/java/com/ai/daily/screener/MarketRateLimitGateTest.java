package com.ai.daily.screener;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事前限速闸门。**全部用例都不真睡**：假时钟 + 记录型 sleeper。
 *
 * <p>真睡的话，这些用例会既慢又抖（150ms 的量级正是 CI 上最容易抖的量级），
 * 而且会开始依赖机器的调度——一条「间隔生效了」的断言不该由调度器来判定。
 */
class MarketRateLimitGateTest {

    private static final long MS = 1_000_000L;

    /** 不前进的假时钟：等待量才能被精确断言，而不是「大约 150ms」。 */
    private static final class FakeClock implements MarketRateLimitGate.NanoClock {
        private long nanos;

        @Override
        public long nanos() {
            return nanos;
        }

        void advance(long millis) {
            nanos += millis * MS;
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

    @Test
    void intervalZeroMeansTheGateIsOff() {
        // 置 0 是回退绳：出现异常时改环境变量重启即可回到「不限速」，不用回滚代码。
        MarketRateLimitGate gate = new MarketRateLimitGate(0, 8000, clock, sleeper);
        gate.acquire("eastmoney");
        gate.acquire("eastmoney");
        gate.acquire("eastmoney");
        assertThat(sleeper.sleptNanos).isEmpty();
    }

    @Test
    void theSameProviderIsSpacedByTheInterval() {
        // 三次连发被摊成 0 / 150 / 300 的发车时刻。这就是「抹匀突发」的全部含义：
        // 不是拦住谁，而是让它们别同时到。时钟故意不前进——等待量累加得越清楚，
        // 「间隔真的按次数线性生效」这件事才越不会被调度掩盖。
        MarketRateLimitGate gate = new MarketRateLimitGate(150, 8000, clock, sleeper);
        gate.acquire("eastmoney");
        gate.acquire("eastmoney");
        gate.acquire("eastmoney");
        // 第一次不用等（所以 sleeper 不会被调用），后两次各等 150/300
        assertThat(sleeper.sleptMillis()).containsExactly(150L, 300L);
    }

    @Test
    void differentProvidersDoNotQueueBehindEachOther() {
        // 额度是按源分的，锁也必须按源分。否则东财排队会拖住腾讯，
        // 一次扫描里「主源慢了连兜底源也跟着慢」，而它们本来是互不相干的两条线。
        MarketRateLimitGate gate = new MarketRateLimitGate(150, 8000, clock, sleeper);
        gate.acquire("eastmoney");  // 各自的第一个槽，都不用等
        gate.acquire("tencent");
        gate.acquire("tencent");    // 腾讯排自己的第二个槽：等 150
        gate.acquire("eastmoney");  // 东财的槽没被腾讯推后，也只等 150（而不是 300）
        assertThat(sleeper.sleptMillis()).containsExactly(150L, 150L);
    }

    @Test
    void aWaitLongerThanTheCapFailsHonestlyAndDoesNotPushTheQueueBack() {
        // 间隔 5s、最多等 100ms：第二次必然超时。
        MarketRateLimitGate gate = new MarketRateLimitGate(5000, 100, clock, sleeper);
        gate.acquire("eastmoney");

        assertThatThrownBy(() -> gate.acquire("eastmoney"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("限速排队超时");

        // **超时的那次不许占槽**：它连请求都没发出去，没资格把后面所有人的发车时刻往后推。
        // 时钟推进到第一个槽之后，下一次应当立刻走。
        clock.advance(5000);
        gate.acquire("eastmoney");
        assertThat(sleeper.sleptNanos).isEmpty();
    }

    @Test
    void anInterruptedWaitFailsInsteadOfFiringTheRequest() throws Exception {
        // 被中断时**不能**照常发请求：那一下会绕开刚排好的间隔，
        // 而中断多半正说明这一次扫描已经被放弃了。
        MarketRateLimitGate gate = new MarketRateLimitGate(150, 8000, clock,
                nanos -> {
                    throw new InterruptedException("测试打断");
                });
        gate.acquire("eastmoney");

        assertThatThrownBy(() -> gate.acquire("eastmoney"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("被中断");

        // 中断位必须还回去（下面这行同时把它清掉，免得污染后续用例）
        assertThat(Thread.interrupted()).isTrue();
    }

    /**
     * 两个构造器（生产的 + 上面那个测试用的）时，Spring 必须能认出该用哪个。
     *
     * <p>不标注的话它会退回去找**无参构造器**，找不到就抛
     * {@code NoDefaultConstructorFoundException}——**启动时**才报，本地全量测试照样绿。
     * 这类「编译期与单测期都看不出来、炸在重启」的失败在这个仓库是有前科的模式
     * （{@code MarketHttpClientConfigTest} 的 bean 名反射检查就是为同一类问题设的），
     * 所以这里也留一条闸门。
     */
    @Test
    void springPicksTheProductionConstructorNotTheTestOne() {
        java.lang.reflect.Constructor<?>[] ctors = MarketRateLimitGate.class.getDeclaredConstructors();
        java.lang.reflect.Constructor<?> production = java.util.Arrays.stream(ctors)
                .filter(c -> java.util.Arrays.equals(c.getParameterTypes(),
                        new Class<?>[]{long.class, long.class}))
                .findFirst()
                .orElseThrow(() -> new AssertionError("生产的 (long, long) 构造器不见了"));

        assertThat(production.isAnnotationPresent(Autowired.class))
                .as("生产构造器必须带 @Autowired，否则 Spring 找不到无参构造器就抛 "
                        + "NoDefaultConstructorFoundException——只有启动才报")
                .isTrue();

        // 数量也必须是 1：标到测试那个构造器上同样会让启动炸（它要注入假时钟和 sleeper），
        // 而「正好一个」是能同时挡住这两种写错的唯一断言。
        assertThat(java.util.Arrays.stream(ctors)
                .filter(c -> c.isAnnotationPresent(Autowired.class))
                .count())
                .as("只能有一个构造器带 @Autowired")
                .isEqualTo(1);
    }

    @Test
    void anUnknownProviderKeyStillGetsSpaced() {
        // 认不出的域名归到它自己，但仍然要排队——否则新接入的源就是一条绕过限速的后门。
        MarketRateLimitGate gate = new MarketRateLimitGate(150, 8000, clock, sleeper);
        gate.acquire("example.com");
        gate.acquire("example.com");
        assertThat(sleeper.sleptMillis()).containsExactly(150L);
    }
}