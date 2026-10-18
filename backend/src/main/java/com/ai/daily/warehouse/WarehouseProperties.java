package com.ai.daily.warehouse;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 代码查询 v2 与本地数据仓库的配置。
 *
 * <p>所有开关都留了环境变量入口（见 {@code application.yml}），出问题时可以只改环境变量，
 * 不必回滚代码：
 * <ul>
 *   <li>{@code enabled=false}：v2 端点整体 503，v1 完全不受影响；</li>
 *   <li>{@code on-demand-enabled=false}：只读本地库，不再外呼；</li>
 *   <li>{@code shared-rate-limit-enabled=false}：退回进程内限速（跨进程闸门失效时的回退绳）。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "warehouse")
public class WarehouseProperties {

    /** v2 功能总开关。关掉不影响 v1。 */
    private boolean enabled = true;

    /** 用户查询时是否允许 Java 同步外呼取数。关掉后只读本地库。 */
    private boolean onDemandEnabled = true;

    /** core 端点的时间预算（毫秒）。超预算就返回已有数据 + syncPending。 */
    private int coreBudgetMs = 3500;

    /** 个股财务/资金/行业多久算过期（小时）。默认 24 小时。 */
    private int stockDetailStaleHours = 24;

    /** 价格超过几个交易日视为陈旧。 */
    private int priceStaleTradingDays = 3;

    /** 全市场估值快照取总市值前多少只。 */
    private int marketSnapshotSize = 3000;

    /** 跨进程限流闸门开关。DB 不可用时自动降级，但保留这个显式回退绳。 */
    private boolean sharedRateLimitEnabled = true;

    /** 共享闸门冷却状态的本地缓存毫秒数，避免每次外呼都打一次 DB。 */
    private long sharedCooldownCacheMs = 1000;

    /** 批量任务允许启动的最早时刻（小时，0–23）。17 点前不跑批量。 */
    private int batchStartHour = 17;
}
