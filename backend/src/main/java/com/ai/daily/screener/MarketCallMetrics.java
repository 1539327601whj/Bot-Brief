package com.ai.daily.screener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 按源记「今天外呼了几次、被打回来几次、最近一次是什么时候」。
 *
 * <p><b>为什么要有这个</b>：限流以前只有一条事件式日志（{@link ScreenerCache} 的
 * 「{} 限流，冷却至 {}」），没有累计。于是「最近老是被打回来」只能靠感觉，
 * 调限速参数也只能拍脑袋——而参数拍错了的代价是首屏变慢或者限流照旧，两种都要几天才看得出来。
 *
 * <p><b>为什么不复用 {@link ScreenerCache}</b>：那个类管的是缓存与冷却，都是「策略状态」。
 * 计数是「事实」，还有按天归零这种它不关心的语义，塞进去会让它的职责再扩一层，
 * 而它已经背着三层缓存 + 一层冷却了。冷却状态继续留在那边，展示时把两者拼起来即可——
 * 两边的键都来自 {@link MarketProviders}，天然对得上。
 *
 * <p><b>为什么不落库</b>：进程内计数足够回答「为什么最近老被打回、是哪个源在挨打」。
 * 为一份重启即失效的诊断数据建表 + 写迁移 + 想清理策略，收益不成比例。
 * 真要长期趋势，那是「日线落库」那条线要顺带解决的观测体系。
 */
@Slf4j
@Component
public class MarketCallMetrics {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final Map<String, ProviderStat> stats = new ConcurrentHashMap<>();

    /**
     * 当前这份数字属于哪一天（东八区），以及这份数字从什么时候开始算。
     * 跨天就整体归零，见 {@link #rolloverIfNeeded()}。
     *
     * <p><b>惰性初始化、不在字段声明处调 {@link #localDateNow()}</b>：那两个方法是可覆盖的，
     * 在构造期调它等于让子类（测试的匿名子类就是）在**自己的字段还没赋值**时被回调——
     * 测试里捕获的「假今天」会是个 null，报出来的却是与时间无关的 NPE。
     */
    private volatile LocalDate day;
    private volatile LocalDateTime since;

    // ==================================================================
    // 埋点
    // ==================================================================

    /**
     * 一次**真的发出去**的外呼。拦截器在 {@code execution.execute} 之前调用，所以
     * 「被限速闸门挡下、根本没发」的那次不会被算进来——那是没打出去，与打出去被打回是两件事。
     */
    public void recordCall(String provider) {
        rolloverIfNeeded();
        stat(provider).calls.increment();
    }

    /**
     * 一次**被服务端拒绝**的外呼。
     *
     * <p>这里记的是「原始事实」，与 {@link ScreenerCache#inCooldown(String)} 那个「策略状态」
     * 刻意分开：有些路径会吞掉限流异常继续跑（{@code MarketDataClient.fetchEtfQuotes} 为了
     * 不把整页结果丢掉，捕获后返回空 Map），那些调用**不会**进冷却，但它们确实被拒了。
     * 只看冷却表会漏掉这部分压力，而它们同样在把 IP 往深处推。
     */
    public void recordThrottled(String provider) {
        rolloverIfNeeded();
        ProviderStat s = stat(provider);
        s.throttled.increment();
        s.lastThrottledAt = localDateTimeNow();
    }

    // ==================================================================
    // 读取
    // ==================================================================

    /**
     * 快照。返回的每个源含 {@code calls} / {@code throttled} / {@code lastThrottledAt}；
     * 「现在冷却到几点」在 controller 里从 {@link ScreenerCache} 拼进来——那是策略状态，
     * 由冷却表负责，不在这里复制一份，否则两处会各说各话。
     */
    public Map<String, Map<String, Object>> snapshot() {
        rolloverIfNeeded();
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        stats.forEach((provider, s) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("calls", s.calls.sum());
            row.put("throttled", s.throttled.sum());
            row.put("lastThrottledAt", s.lastThrottledAt == null ? null : s.lastThrottledAt.toString());
            out.put(provider, row);
        });
        return out;
    }

    public LocalDate currentDay() {
        rolloverIfNeeded();
        return day;
    }

    public LocalDateTime since() {
        rolloverIfNeeded();
        return since;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private ProviderStat stat(String provider) {
        String key = provider == null || provider.isBlank() ? "unknown" : provider;
        return stats.computeIfAbsent(key, k -> new ProviderStat());
    }

    /**
     * 跨天整体归零。惰性做，没有定时任务——没人看的时候不必付这个代价，
     * 而「看的时候数字属于哪一天」由 {@link #snapshot()} 一并给出，不会含糊。
     *
     * <p>双重检查 + 同步：读路径（{@code snapshot}）是高频的，不该每次都抢锁；
     * 真正需要互斥的只有「归零」这一下。
     */
    private void rolloverIfNeeded() {
        LocalDate today = localDateNow();
        if (today.equals(day)) {
            return;
        }
        synchronized (this) {
            if (today.equals(day)) {
                return;
            }
            boolean crossing = day != null;   // 首次调用不是「跨天」，别把第一次说成归零
            stats.clear();
            day = today;
            since = localDateTimeNow();
            if (crossing) {
                log.info("行情外呼计数跨天归零，新的统计日是 {}", today);
            }
        }
    }

    /**
     * 「今天几号」与「现在几点」做成可覆盖方法而不是直接 {@code LocalDate.now()}：
     * 测试要钉得住跨天这件事，否则那条用例只能在真的跨天时跑，等于没有。
     * 同款写法见 {@link CodeLookupService#localDateNow()}。
     */
    LocalDate localDateNow() {
        return LocalDate.now(SHANGHAI);
    }

    LocalDateTime localDateTimeNow() {
        return LocalDateTime.now(SHANGHAI);
    }

    private static final class ProviderStat {
        private final LongAdder calls = new LongAdder();
        private final LongAdder throttled = new LongAdder();
        private volatile LocalDateTime lastThrottledAt;
    }
}