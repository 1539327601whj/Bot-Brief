package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link BriefCache}：键是什么、什么时候过期、满了怎么办。 */
class BriefCacheTest {

    private static final Instant T0 = Instant.parse("2026-10-15T06:00:00Z");
    private static final String SNAP = "2026-10-15T10:00:00";

    private static AnalysisBrief brief(String text) {
        return AnalysisBrief.ok(text);
    }

    @Test
    void aHitOnTheSameCodeAndSnapshotComesBack() {
        BriefCache cache = new BriefCache(Duration.ofMinutes(30));
        cache.put("300274", SNAP, brief("第一份"), T0);

        assertThat(cache.get("300274", SNAP, T0.plusSeconds(60)).text()).isEqualTo("第一份");
    }

    @Test
    void aDifferentSnapshotMissesEvenForTheSameCode() {
        // **这是这个缓存最要紧的一条。** 只按代码做键的话，隔天点开同一只票会拿到
        // 昨天的解读，而解读里每个数字都来自昨天——两个日期对不上的页面比没有页面更糟
        // （章程 §8）。带上快照时刻之后，这件事是**构造上不可能**的。
        BriefCache cache = new BriefCache(Duration.ofMinutes(30));
        cache.put("300274", SNAP, brief("昨日的"), T0);

        assertThat(cache.get("300274", "2026-10-16T10:00:00", T0)).isNull();
    }

    @Test
    void aDifferentCodeMissesEvenWithTheSameSnapshot() {
        BriefCache cache = new BriefCache(Duration.ofMinutes(30));
        cache.put("300274", SNAP, brief("阳光电源的"), T0);

        assertThat(cache.get("600000", SNAP, T0)).isNull();
    }

    @Test
    void anExpiredEntryIsNotReturned() {
        BriefCache cache = new BriefCache(Duration.ofMinutes(30));
        cache.put("300274", SNAP, brief("x"), T0);

        assertThat(cache.get("300274", SNAP, T0.plusSeconds(30 * 60 - 1))).isNotNull();
        // 正好到点算过期：边界取「>=」而不是「>」，是为了让「TTL 30 分钟」这句
        // 在页面上说的和实际一致——卡在第 1800 秒上的那一份不该还算有效。
        assertThat(cache.get("300274", SNAP, T0.plusSeconds(30 * 60))).isNull();
    }

    @Test
    void anExpiredEntryIsDroppedRatherThanLeftToAccumulate() {
        BriefCache cache = new BriefCache(Duration.ofSeconds(1));
        cache.put("300274", SNAP, brief("x"), T0);

        cache.get("300274", SNAP, T0.plusSeconds(2));

        assertThat(cache.size()).isZero();
    }

    @Test
    void failuresAreCachedToo() {
        // 未配置 key、模型超时这些都不是「过一秒就好」的状态。不缓存的话，
        // 用户连点五次按钮就打五次接口换回同一句话。
        BriefCache cache = new BriefCache(Duration.ofMinutes(30));
        cache.put("300274", SNAP, AnalysisBrief.unavailable("AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。"), T0);

        AnalysisBrief hit = cache.get("300274", SNAP, T0.plusSeconds(10));

        assertThat(hit).isNotNull();
        assertThat(hit.available()).isFalse();
    }

    @Test
    void aZeroOrNegativeTtlFallsBackToTheDefaultInsteadOfCachingForever() {
        // 配成 0 的本意多半是「关掉缓存」，而 0 秒 TTL 的字面意思也是「立刻过期」，
        // 两种理解都不会导致「永不过期」。**绝不能把它当成无限**——
        // 那会让一次模型超时被永远记住。这里退到 30 分钟，并让测试钉住这个选择。
        BriefCache zero = new BriefCache(Duration.ZERO);
        zero.put("300274", SNAP, brief("x"), T0);
        assertThat(zero.get("300274", SNAP, T0.plusSeconds(29 * 60))).isNotNull();
        assertThat(zero.get("300274", SNAP, T0.plusSeconds(31 * 60))).isNull();
    }

    @Test
    void aNullTtlAlsoFallsBack() {
        BriefCache cache = new BriefCache(null);
        cache.put("300274", SNAP, brief("x"), T0);

        assertThat(cache.get("300274", SNAP, T0.plusSeconds(60))).isNotNull();
    }

    @Test
    void overflowingTheCapClearsRatherThanGrowingWithoutBound() {
        // 这是个按需点击的页面，正常一天几十条，撞不到上限。
        // 但**撞到时必须有个动作**——不然一个长期运行的进程会把它堆成泄漏。
        // 选择整体清空而不是 LRU：多出来的收益是零，多出来的代码要人读懂。
        BriefCache cache = new BriefCache(Duration.ofMinutes(30));
        for (int i = 0; i < BriefCache.MAX_ENTRIES; i++) {
            cache.put("code" + i, SNAP, brief("x"), T0);
        }
        assertThat(cache.size()).isEqualTo(BriefCache.MAX_ENTRIES);

        cache.put("one-more", SNAP, brief("x"), T0);

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("one-more", SNAP, T0)).isNotNull();
    }

    @Test
    void clearEmptiesIt() {
        BriefCache cache = new BriefCache(Duration.ofMinutes(30));
        cache.put("300274", SNAP, brief("x"), T0);

        cache.clear();

        assertThat(cache.size()).isZero();
        assertThat(cache.get("300274", SNAP, T0)).isNull();
    }
}
