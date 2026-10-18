package com.ai.daily.warehouse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨进程限流闸门的持久层封装（Java 侧）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>{@code MarketRateLimitGate} 是进程内的：Java 与 Python warehouse 同时外呼时，
 * 两边各按 150ms 排队，合起来就是 75ms——等于把限速做没。这里把「下一次允许发车」的
 * 时刻放进 MySQL 的 {@code md_source_state}，两个进程看到同一条时间线。
 *
 * <h2>失败时绝不拖垮查询</h2>
 *
 * <p>共享闸门是**增强**，不是查询的前置条件。DB 不可用、迁移没执行、SQL 超时，
 * 都只降级为进程内限速并打一条 WARN，请求照常走。降级后 30 秒内不再尝试，
 * 避免每次外呼都白等一次数据库超时——那会把「源慢」放大成「页面卡」。
 *
 * <h2>车道</h2>
 *
 * <ul>
 *   <li>{@code eastmoney:interactive}：用户查询链路，150–300ms；</li>
 *   <li>{@code eastmoney:batch}：Python 批量任务，500ms，17:00 后跑；</li>
 *   <li>其余源用 provider 原名（{@code tencent}、{@code csindex} 等）。</li>
 * </ul>
 *
 * <p>车道键必须与 Python 侧一致。Java 侧传进来的 provider 是 {@code eastmoney}，
 * 在这里统一映射成交互车道；调用方不需要知道车道的存在。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SharedRateLimitStore {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 失败后的熔断时长：这段时间内直接按「共享闸门不可用」处理。 */
    private static final long BREAKER_MILLIS = 30_000L;

    /** WARN 的最小间隔，避免一次批量任务刷出几百行同样的日志。 */
    private static final long WARN_INTERVAL_MILLIS = 60_000L;

    private final WarehouseProperties properties;
    private final WarehouseRepository repository;

    private final ConcurrentHashMap<String, CachedCooldown> cooldownCache = new ConcurrentHashMap<>();
    private final AtomicLong lastWarnAt = new AtomicLong();
    private volatile long unavailableUntilMillis;

    /**
     * 原子预约一个发车时刻。
     *
     * @return {@code empty} 表示共享闸门不可用/被关闭，调用方应只按进程内限速走；
     *         有值表示共享闸门给出了明确答复（允许或拒绝）
     */
    public Optional<Reservation> reserve(String provider, long intervalMs, long maxWaitMs) {
        if (!enabled()) {
            return Optional.empty();
        }
        String lane = lane(provider);
        try {
            WarehouseRepository.Reservation result =
                    repository.reserve(lane, Math.max(0, intervalMs), Math.max(0, maxWaitMs));
            return Optional.of(new Reservation(result.allowed(), result.waitMs(),
                    result.cooling(), result.cooldownRemainingMs(), result.reason()));
        } catch (RuntimeException e) {
            degrade("预约", e);
            return Optional.empty();
        }
    }

    /**
     * 共享冷却状态。{@code empty} 表示查不到（闸门关闭/DB 不可用），调用方应退回本地冷却。
     * 冷却状态本地缓存 {@code warehouse.shared-cooldown-cache-ms}，避免每次外呼都打 DB。
     */
    public Optional<CooldownInfo> cooldown(String provider) {
        if (!enabled()) {
            return Optional.empty();
        }
        String lane = lane(provider);
        long now = System.currentTimeMillis();
        CachedCooldown cached = cooldownCache.get(lane);
        if (cached != null && now - cached.readAtMillis() < properties.getSharedCooldownCacheMs()) {
            return Optional.of(cached.info());
        }
        try {
            Optional<WarehouseRepository.CooldownState> state = repository.cooldownState(lane);
            CooldownInfo info = state
                    .map(s -> new CooldownInfo(s.cooling(), s.remainingMs(), s.lastError()))
                    .orElseGet(() -> new CooldownInfo(false, 0, null));
            cooldownCache.put(lane, new CachedCooldown(now, info));
            return Optional.of(info);
        } catch (RuntimeException e) {
            degrade("读取冷却", e);
            return Optional.empty();
        }
    }

    /** 把「被源拒绝」写进共享状态；本地冷却仍由 {@code ScreenerCache} 负责。 */
    public void enterCooldown(String provider, long seconds, String reason) {
        if (!enabled()) {
            return;
        }
        String lane = lane(provider);
        try {
            repository.enterCooldown(lane, seconds, reason);
            // 立刻让下一次读取看到新状态，而不是等缓存过期
            cooldownCache.put(lane, new CachedCooldown(System.currentTimeMillis(),
                    new CooldownInfo(true, Math.max(1, seconds) * 1000L, reason)));
        } catch (RuntimeException e) {
            degrade("写入冷却", e);
        }
    }

    public void clearCooldown(String provider) {
        if (!enabled()) {
            return;
        }
        String lane = lane(provider);
        cooldownCache.remove(lane);
        try {
            repository.clearCooldown(lane);
        } catch (RuntimeException e) {
            degrade("清除冷却", e);
        }
    }

    /** 诊断用：当前是否处于熔断降级。 */
    public boolean degraded() {
        return System.currentTimeMillis() < unavailableUntilMillis;
    }

    /**
     * provider → 车道键。**这是 Java 与 Python 的唯一约定**，改动必须两边一起改。
     */
    public static String lane(String provider) {
        if (provider == null || provider.isBlank()) {
            return "unknown";
        }
        String key = provider.strip();
        if ("eastmoney".equals(key)) {
            return "eastmoney:interactive";
        }
        return key;
    }

    private boolean enabled() {
        return properties.isSharedRateLimitEnabled()
                && System.currentTimeMillis() >= unavailableUntilMillis;
    }

    private void degrade(String action, RuntimeException e) {
        unavailableUntilMillis = System.currentTimeMillis() + BREAKER_MILLIS;
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_INTERVAL_MILLIS && lastWarnAt.compareAndSet(last, now)) {
            log.warn("共享限流闸门不可用（{}），已降级为进程内限速 {} 秒。原因：{}",
                    action, BREAKER_MILLIS / 1000, e.getMessage());
        }
    }

    /**
     * 共享闸门的一次预约结果。
     *
     * @param allowed         是否拿到发车资格
     * @param waitMs          需要等待的毫秒数
     * @param cooling         是否处于冷却期
     * @param cooldownRemainingMs 冷却剩余毫秒
     * @param reason          拒绝原因
     */
    public record Reservation(boolean allowed, long waitMs, boolean cooling,
                              long cooldownRemainingMs, String reason) {
    }

    /**
     * 共享冷却状态。
     *
     * @param cooling     此刻是否在冷却期
     * @param remainingMs 剩余毫秒
     * @param lastError   写入冷却时记下的原因
     */
    public record CooldownInfo(boolean cooling, long remainingMs, String lastError) {

        /** 换算成本地时区下的「冷却到什么时候」，供页面文案使用。 */
        public Optional<LocalDateTime> until() {
            if (!cooling || remainingMs <= 0) {
                return Optional.empty();
            }
            return Optional.of(LocalDateTime.now(SHANGHAI).plusNanos(remainingMs * 1_000_000L));
        }
    }

    private record CachedCooldown(long readAtMillis, CooldownInfo info) {
    }
}
