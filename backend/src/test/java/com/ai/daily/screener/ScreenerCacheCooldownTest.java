package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冷却**按源分开记**。
 *
 * <p>以前只有一个 {@code cooldownUntil}，谁被限流都写它。兜底链上线后这就成了真问题：
 * 腾讯被限流会顺手锁掉东财的清单请求，于是页面写着「东财在限流」而东财其实好好的。
 * 限流本来就是按源、按 IP 的，冷却不分开记，兜底链一触发的第一个后果就是误伤主源。
 */
class ScreenerCacheCooldownTest {

    private static final String EASTMONEY = MarketDataClient.PROVIDER_EASTMONEY;

    private ScreenerCache cache() {
        return new ScreenerCache(15, 20, 60, 10);
    }

    @Test
    void tencentBeingThrottledDoesNotLockOutEastmoney() {
        ScreenerCache cache = cache();

        cache.enterCooldown(AltQuoteSource.PROVIDER_TENCENT);

        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)).isTrue();
        // 这条是整个改动的理由：腾讯自己挨了限流，不该把东财的清单请求一起挡掉
        assertThat(cache.inCooldown(EASTMONEY)).isFalse();
        assertThat(cache.cooldownRemainingMinutes(EASTMONEY)).isZero();
        assertThat(cache.cooldownUntil(EASTMONEY)).isEmpty();
    }

    @Test
    void eastmoneyBeingThrottledDoesNotLockOutTheFallbacks() {
        ScreenerCache cache = cache();

        cache.enterCooldown(EASTMONEY);

        assertThat(cache.inCooldown(EASTMONEY)).isTrue();
        // 主源被封恰恰是**最需要**兜底源的时候，此时把兜底也锁上等于自己把路堵死
        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)).isFalse();
        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_SINA)).isFalse();
    }

    @Test
    void theNoArgFormsStillMeanEastmoney() {
        ScreenerCache cache = cache();

        cache.enterCooldown();

        // 无参版本是既有调用点的语义，改了它就等于把原有行为改了
        assertThat(cache.inCooldown()).isTrue();
        assertThat(cache.inCooldown(EASTMONEY)).isTrue();
        assertThat(cache.cooldownRemainingMinutes()).isEqualTo(cache.cooldownRemainingMinutes(EASTMONEY));
        assertThat(cache.cooldownUntil()).isPresent();
        assertThat(cache.cooldownUntil()).isEqualTo(cache.cooldownUntil(EASTMONEY));
        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)).isFalse();

        cache.clearCooldown();

        assertThat(cache.inCooldown()).isFalse();
        assertThat(cache.inCooldown(EASTMONEY)).isFalse();
    }

    @Test
    void clearingOneProviderLeavesTheOtherAlone() {
        ScreenerCache cache = cache();
        cache.enterCooldown(EASTMONEY);
        cache.enterCooldown(AltQuoteSource.PROVIDER_TENCENT);

        cache.clearCooldown(AltQuoteSource.PROVIDER_TENCENT);

        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)).isFalse();
        assertThat(cache.inCooldown(EASTMONEY)).isTrue();
    }

    @Test
    void aSuccessfulSnapshotOnlyClearsEastmoneysCooldown() {
        ScreenerCache cache = cache();
        cache.enterCooldown(EASTMONEY);
        cache.enterCooldown(AltQuoteSource.PROVIDER_TENCENT);

        cache.universe(() -> new MarketDataClient.UniverseSnapshot(
                List.of(), 0, 0, LocalDate.of(2026, 9, 30), null));

        // 清单取数成功证明的是**东财那条线**通了。顺手把腾讯的冷却也抹掉，
        // 等于替腾讯宣布解封——它并没有被请求过。
        assertThat(cache.inCooldown(EASTMONEY)).isFalse();
        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)).isTrue();
    }

    @Test
    void remainingMinutesRoundsUpSoItNeverSaysZeroWhileStillCoolingDown() {
        ScreenerCache cache = cache();

        cache.enterCooldown(AltQuoteSource.PROVIDER_TENCENT);

        // 「还剩 0 分钟」会让人以为马上就能点
        assertThat(cache.cooldownRemainingMinutes(AltQuoteSource.PROVIDER_TENCENT)).isPositive();
        assertThat(cache.cooldownRemainingMinutes(EASTMONEY)).isZero();
    }

    @Test
    void aShorterCooldownCannotShortenTheOneAlreadyRunning() {
        ScreenerCache cache = cache();

        cache.enterCooldown(AltQuoteSource.PROVIDER_TENCENT, 30);
        cache.enterCooldown(AltQuoteSource.PROVIDER_TENCENT, 1);

        // 一批标的一次扫描会连着报好几次限流，短的那次不能把长的冷却改短
        assertThat(cache.cooldownRemainingMinutes(AltQuoteSource.PROVIDER_TENCENT)).isEqualTo(30);
    }

    @Test
    void snapshotStateStillReportsAnOverallCooldown() {
        ScreenerCache cache = cache();
        assertThat(cache.snapshotState()).containsEntry("inCooldown", false);

        cache.enterCooldown(EASTMONEY);

        assertThat(cache.snapshotState()).containsEntry("inCooldown", true);
    }
}