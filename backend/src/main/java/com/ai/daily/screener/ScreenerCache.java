package com.ai.daily.screener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 两层内存缓存 + 一层限流冷却。
 *
 * <ul>
 *   <li><b>全市场快照</b>：TTL {@code screener.universe-ttl-minutes}（默认 15 分钟）。</li>
 *   <li><b>日线</b>：按交易日缓存。盘中（09:30–15:00）超过 {@code screener.kline-intraday-ttl-minutes}
 *       就重取，保证现价位置不会停在一小时前；收盘后当天一直有效。</li>
 *   <li><b>限流冷却</b>：被东财拒绝一次就记下，冷却期内不再外呼。</li>
 * </ul>
 *
 * <p>全市场快照的刷新**加锁单飞**：同一时刻只有一个请求去外呼，其余等待并复用同一份结果，
 * 否则首屏一被连点就会变成对东财的小型压测。
 * 日线不走单飞——它是被上层并发线程池拉取的，加锁会把并发退化成串行。
 *
 * <p><b>冷却期为什么还要留着旧快照</b>：被限流时把「15 分钟前的行情」带着明确时间戳
 * 端出来，比甩一个错误框有用；用户看得到数据时间，也就知道该不该信。
 * 真正不可用（一次都没取到过）才报错——那时确实没有东西可给。
 */
@Slf4j
@Component
public class ScreenerCache {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final long universeTtlMinutes;
    private final long klineIntradayTtlMinutes;
    private final long cooldownMinutesDefault;
    private final Object universeLock = new Object();

    private volatile MarketDataClient.UniverseSnapshot universe;
    private volatile LocalDateTime universeAt;
    private volatile LocalDateTime cooldownUntil;

    private final Map<String, KlineEntry> klines = new ConcurrentHashMap<>();

    private record KlineEntry(MarketDataClient.KlineOutcome outcome, LocalDate stamp, LocalDateTime at) {}

    public ScreenerCache(
            @Value("${screener.universe-ttl-minutes:15}") long universeTtlMinutes,
            @Value("${screener.kline-intraday-ttl-minutes:20}") long klineIntradayTtlMinutes,
            @Value("${screener.rate-limit-cooldown-minutes:10}") long cooldownMinutesDefault) {
        this.universeTtlMinutes = universeTtlMinutes;
        this.klineIntradayTtlMinutes = klineIntradayTtlMinutes;
        this.cooldownMinutesDefault = Math.max(1, cooldownMinutesDefault);
    }

    // ==================================================================
    // 全市场快照
    // ==================================================================

    /** 取全市场快照；过期则用 loader 刷新。loader 抛的异常原样冒出去。 */
    public MarketDataClient.UniverseSnapshot universe(
            Supplier<MarketDataClient.UniverseSnapshot> loader) {
        MarketDataClient.UniverseSnapshot cached = universe;
        if (cached != null && universeAt != null && isFresh(universeAt)) {
            return cached;
        }
        synchronized (universeLock) {
            // 双重检查：等锁期间别人可能已经刷好了
            cached = universe;
            if (cached != null && universeAt != null && isFresh(universeAt)) {
                return cached;
            }
            MarketDataClient.UniverseSnapshot fresh = loader.get();
            universe = fresh;
            universeAt = LocalDateTime.now(SHANGHAI);
            // 取数成功说明链路是通的，冷却没必要再挂着
            cooldownUntil = null;
            log.info("全市场快照已刷新：{} 条（清单 {} 条，剔除 {} 条）",
                    fresh == null ? 0 : fresh.rows().size(),
                    fresh == null ? 0 : fresh.listedCount(),
                    fresh == null ? 0 : fresh.missing());
            return fresh;
        }
    }

    public boolean universeFresh() {
        return universe != null && universeAt != null && isFresh(universeAt);
    }

    /** 手上这份快照，不管新不新。被限流时拿它兜底。 */
    public Optional<MarketDataClient.UniverseSnapshot> universeOrStale() {
        return Optional.ofNullable(universe);
    }

    public Optional<LocalDateTime> universeTakenAt() {
        return Optional.ofNullable(universeAt);
    }

    private boolean isFresh(LocalDateTime at) {
        return !LocalDateTime.now(SHANGHAI).isAfter(at.plusMinutes(universeTtlMinutes));
    }

    // ==================================================================
    // 限流冷却
    // ==================================================================

    /** 记下一次「被行情源拒绝」，冷却期内不再外呼。 */
    public void enterCooldown() {
        enterCooldown(cooldownMinutesDefault);
    }

    public void enterCooldown(long minutes) {
        LocalDateTime until = LocalDateTime.now(SHANGHAI).plusMinutes(Math.max(1, minutes));
        if (cooldownUntil == null || until.isAfter(cooldownUntil)) {
            cooldownUntil = until;
            log.warn("行情源限流，冷却至 {}", until);
        }
    }

    public boolean inCooldown() {
        return cooldownUntil != null && LocalDateTime.now(SHANGHAI).isBefore(cooldownUntil);
    }

    public Optional<LocalDateTime> cooldownUntil() {
        return inCooldown() ? Optional.ofNullable(cooldownUntil) : Optional.empty();
    }

    /** 冷却剩余整分钟数，向上取整——显示成「还剩 0 分钟」会让人以为马上就能点。 */
    public long cooldownRemainingMinutes() {
        if (!inCooldown()) return 0;
        long seconds = Duration.between(LocalDateTime.now(SHANGHAI), cooldownUntil).getSeconds();
        return Math.max(1, (seconds + 59) / 60);
    }

    /** 仅测试与诊断用。 */
    public void clearCooldown() {
        cooldownUntil = null;
    }

    // ==================================================================
    // 日线
    // ==================================================================

    public Optional<MarketDataClient.KlineOutcome> kline(String secid) {
        KlineEntry e = klines.get(secid);
        if (e == null) return Optional.empty();
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        if (!e.stamp().equals(LocalDate.now(SHANGHAI))) return Optional.empty();
        if (isTradingHours(now.toLocalTime())
                && now.isAfter(e.at().plusMinutes(klineIntradayTtlMinutes))) {
            return Optional.empty();
        }
        return Optional.of(e.outcome());
    }

    public void putKlines(Map<String, MarketDataClient.KlineOutcome> fetched) {
        LocalDate today = LocalDate.now(SHANGHAI);
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        fetched.forEach((secid, outcome) -> {
            // 失败的也缓存，避免同一批里反复重试同一个死掉的代码
            klines.put(secid, new KlineEntry(outcome, today, now));
        });
    }

    /** 仅测试与诊断用。 */
    public Map<String, Boolean> snapshotState() {
        Map<String, Boolean> m = new HashMap<>();
        m.put("universeFresh", universeFresh());
        m.put("klineEntries", !klines.isEmpty());
        m.put("inCooldown", inCooldown());
        return m;
    }

    static boolean isTradingHours(LocalTime t) {
        return !t.isBefore(LocalTime.of(9, 30)) && !t.isAfter(LocalTime.of(15, 0));
    }
}