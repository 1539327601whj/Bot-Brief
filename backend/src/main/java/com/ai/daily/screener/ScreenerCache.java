package com.ai.daily.screener;

import com.ai.daily.entity.MarketValuationHistory;
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
 * 三层内存缓存 + 一层限流冷却。
 *
 * <ul>
 *   <li><b>全市场快照</b>：TTL {@code screener.universe-ttl-minutes}（默认 15 分钟）。</li>
 *   <li><b>日线</b>：按交易日缓存。盘中（09:30–15:00）超过 {@code screener.kline-intraday-ttl-minutes}
 *       就重取，保证现价位置不会停在一小时前；收盘后当天一直有效。</li>
 *   <li><b>估值分位</b>：TTL {@code screener.valuation-ttl-minutes}（默认 60 分钟）。</li>
 *   <li><b>限流冷却</b>：被某个行情源拒绝一次就记下，冷却期内不再对它外呼。</li>
 * </ul>
 *
 * <p><b>估值为什么单独有一层</b>：它是**库里**的数据，不是外呼来的，一天才变一次
 * （每日同步 16:30 后写入）。但也不能按「一天」缓存——用户下午三点点一次、
 * 四点半同步完再点，看到的还是旧数时，他会怀疑这个功能根本没在更新。
 * 一小时的 TTL 是「数据几乎不会变」和「用户相信它是新的」之间的折中。
 *
 * <p><b>冷却为什么按 provider 分开记</b>：以前只有一个 {@code cooldownUntil}，谁被限流都写它。
 * 兜底链上线后这就不再是个小瑕疵了——腾讯被限流会顺手锁掉东财的清单请求，
 * 于是页面上写着「东财在限流」而东财其实好好的，那句提示从此不可信。
 * 限流是**按源、按 IP** 的，冷却也必须按源记，否则兜底链一触发的第一个后果就是误伤主源。
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
    private final long valuationTtlMinutes;
    private final long cooldownMinutesDefault;
    private final Object universeLock = new Object();

    private volatile MarketDataClient.UniverseSnapshot universe;
    private volatile LocalDateTime universeAt;

    /** provider → 冷却到什么时候。见类注释：按源分开，不然兜底源被限流会误伤主源。 */
    private final Map<String, LocalDateTime> cooldowns = new ConcurrentHashMap<>();

    private final Map<String, KlineEntry> klines = new ConcurrentHashMap<>();

    /** 指数池整块的估值分位，键见 {@link #valuationKey}。整块一起过期，不逐条记。 */
    private volatile Map<String, MarketValuationHistory> valuations;
    private volatile LocalDateTime valuationsAt;

    private record KlineEntry(MarketDataClient.KlineOutcome outcome, LocalDate stamp, LocalDateTime at) {}

    public ScreenerCache(
            @Value("${screener.universe-ttl-minutes:15}") long universeTtlMinutes,
            @Value("${screener.kline-intraday-ttl-minutes:20}") long klineIntradayTtlMinutes,
            @Value("${screener.valuation-ttl-minutes:60}") long valuationTtlMinutes,
            @Value("${screener.rate-limit-cooldown-minutes:10}") long cooldownMinutesDefault) {
        this.universeTtlMinutes = universeTtlMinutes;
        this.klineIntradayTtlMinutes = klineIntradayTtlMinutes;
        this.valuationTtlMinutes = Math.max(1, valuationTtlMinutes);
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
            // 取数成功说明链路是通的，冷却没必要再挂着。只清**清单那一路**（东财）的冷却：
            // 快照成功并不证明腾讯也恢复了，顺手把它的冷却也抹掉等于替它宣布解封。
            cooldowns.remove(MarketDataClient.PROVIDER_EASTMONEY);
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

    /** 记下一次「被行情源拒绝」，冷却期内不再外呼。无参版本针对东财，现有调用点语义不变。 */
    public void enterCooldown() {
        enterCooldown(MarketDataClient.PROVIDER_EASTMONEY);
    }

    public void enterCooldown(long minutes) {
        enterCooldown(MarketDataClient.PROVIDER_EASTMONEY, minutes);
    }

    public void enterCooldown(String provider) {
        enterCooldown(provider, cooldownMinutesDefault);
    }

    public void enterCooldown(String provider, long minutes) {
        LocalDateTime until = LocalDateTime.now(SHANGHAI).plusMinutes(Math.max(1, minutes));
        String key = provider == null ? MarketDataClient.PROVIDER_EASTMONEY : provider;
        LocalDateTime current = cooldowns.get(key);
        if (current == null || until.isAfter(current)) {
            cooldowns.put(key, until);
            // 只在真的把冷却推后时打日志：同一批里每只标的都报一次会刷屏，
            // 而「最早那次」才是值得看的那条
            log.warn("{} 限流，冷却至 {}", key, until);
        }
    }

    /** 无参版本等价于 {@code inCooldown("eastmoney")}。 */
    public boolean inCooldown() {
        return inCooldown(MarketDataClient.PROVIDER_EASTMONEY);
    }

    public boolean inCooldown(String provider) {
        LocalDateTime until = cooldowns.get(provider == null ? MarketDataClient.PROVIDER_EASTMONEY : provider);
        return until != null && LocalDateTime.now(SHANGHAI).isBefore(until);
    }

    public Optional<LocalDateTime> cooldownUntil() {
        return cooldownUntil(MarketDataClient.PROVIDER_EASTMONEY);
    }

    public Optional<LocalDateTime> cooldownUntil(String provider) {
        return inCooldown(provider) ? Optional.ofNullable(providerUntil(provider)) : Optional.empty();
    }

    /** 冷却剩余整分钟数，向上取整——显示成「还剩 0 分钟」会让人以为马上就能点。 */
    public long cooldownRemainingMinutes() {
        return cooldownRemainingMinutes(MarketDataClient.PROVIDER_EASTMONEY);
    }

    public long cooldownRemainingMinutes(String provider) {
        if (!inCooldown(provider)) return 0;
        long seconds = Duration.between(LocalDateTime.now(SHANGHAI), providerUntil(provider)).getSeconds();
        return Math.max(1, (seconds + 59) / 60);
    }

    private LocalDateTime providerUntil(String provider) {
        return cooldowns.get(provider == null ? MarketDataClient.PROVIDER_EASTMONEY : provider);
    }

    /** 仅测试与诊断用。无参版本只清东财那一路。 */
    public void clearCooldown() {
        clearCooldown(MarketDataClient.PROVIDER_EASTMONEY);
    }

    public void clearCooldown(String provider) {
        cooldowns.remove(provider == null ? MarketDataClient.PROVIDER_EASTMONEY : provider);
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

    // ==================================================================
    // 估值分位
    // ==================================================================

    /**
     * 估值的键。**口径写在键里是故意的**：同一个指数的
     * {@code CSI_PE_TTM_ROLLING_10Y} 与 {@code DANJUAN_PE_TTM_PROVIDER} 不是同一个数，
     * 只按 indexCode 缓存的话，池子改了口径之后会继续命中旧口径那一行，
     * 而页面上写着新口径——一个查不出来的错误。
     */
    public static String valuationKey(String indexCode, String percentileMethod) {
        return indexCode + "|" + percentileMethod;
    }

    /** 整块的估值，过期就返回 empty（`{}` 与「没缓存」是两件事，所以用 Optional）。 */
    public Optional<Map<String, MarketValuationHistory>> valuations() {
        Map<String, MarketValuationHistory> cached = valuations;
        LocalDateTime at = valuationsAt;
        if (cached == null || at == null) return Optional.empty();
        if (LocalDateTime.now(SHANGHAI).isAfter(at.plusMinutes(valuationTtlMinutes))) return Optional.empty();
        return Optional.of(cached);
    }

    /** 存一整块估值。**空 map 也存**——「库里确实一条都没有」值得缓存，否则每次点击都去查一遍空。 */
    public void putValuations(Map<String, MarketValuationHistory> rows) {
        valuations = Map.copyOf(rows);
        valuationsAt = LocalDateTime.now(SHANGHAI);
    }

    /** 仅测试与诊断用。 */
    public void clearValuations() {
        valuations = null;
        valuationsAt = null;
    }

    /** 仅测试与诊断用。 */
    public Map<String, Boolean> snapshotState() {
        Map<String, Boolean> m = new HashMap<>();
        m.put("universeFresh", universeFresh());
        m.put("klineEntries", !klines.isEmpty());
        m.put("valuationFresh", valuations().isPresent());
        m.put("inCooldown", inCooldown());
        return m;
    }

    static boolean isTradingHours(LocalTime t) {
        return !t.isBefore(LocalTime.of(9, 30)) && !t.isAfter(LocalTime.of(15, 0));
    }
}