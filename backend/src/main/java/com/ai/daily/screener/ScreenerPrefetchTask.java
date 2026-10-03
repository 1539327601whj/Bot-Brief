package com.ai.daily.screener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 盘后预取：收盘后取一次全市场清单 + 基本面落库，让盘后点击不再外呼东财。
 *
 * <p><b>为什么在 Java 侧、而不是 Python 的 poller 里</b>：清单接口的 {@code filter} 必须原样发、
 * {@code ulist} 的 21 个字段要按同一套规则解析、限流要按同一套判据识别——放 Python 就得把这些
 * 全部重写一遍，再在 Java 侧另加一套 ingest 端点，等于同时新增两套代码。仓库里三处明文
 * 反对同一逻辑两语言各写一套（{@code MarketDataClient} 与 {@code EtfPriceSeriesSelector}
 * 的类注释、{@code index_pool_sync.py}），这里直接复用 {@link MarketDataClient#fetchUniverse()}，
 * 一行取数逻辑都不重写。
 *
 * <p><b>外呼量</b>：一天约 4 次（1 次清单 + 3 次批量行情，池子 300、每批 100）。
 * 这是本方案唯一新增的外呼，且集中在收盘后——正是为了把散在全天的点击量收拢成一次。
 *
 * <p><b>为什么收盘后 30 分钟才跑</b>：15:00 收盘价已定，但盘后接口的数据落定需要一点时间；
 * 早跑会取到没结算完的数，晚跑则把「盘后点击零外呼」的窗口往后推。30 分钟是这两者之间的
 * 折中，可用 {@code screener.prefetch.cron} 调整。**注意 15:00–15:30 这段点击仍走实时**，
 * 这是已知取舍：那段若读库只能读到昨天的数据，比实时更差。
 */
@Slf4j
@Component
public class ScreenerPrefetchTask implements ApplicationRunner {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 收盘后多久算「该跑的钟点到了」。与默认 cron 的 15:30 一致，启动补偿用它判是否已过点。 */
    static final LocalTime PREFETCH_TIME = LocalTime.of(15, 30);

    private final MarketDataClient marketDataClient;
    private final ScreenerPrefetchService prefetchService;
    private final ScreenerCache cache;
    private final boolean enabled;
    private final boolean force;

    public ScreenerPrefetchTask(
            MarketDataClient marketDataClient,
            ScreenerPrefetchService prefetchService,
            ScreenerCache cache,
            @Value("${screener.prefetch.enabled:true}") boolean enabled,
            @Value("${screener.prefetch.force:false}") boolean force) {
        this.marketDataClient = marketDataClient;
        this.prefetchService = prefetchService;
        this.cache = cache;
        this.enabled = enabled;
        this.force = force;
    }

    /** 主跑：收盘后 30 分钟。 */
    @Scheduled(cron = "${screener.prefetch.cron:0 30 15 * * MON-FRI}", zone = "Asia/Shanghai")
    public void prefetchAfterClose() {
        runOnce();
    }

    /**
     * 重试：16:00–18:30 每半小时一次。
     *
     * <p>主跑被限流或取数失败时，下一次进来会重新跑；已经成功过的那些次会被幂等闸门挡住，
     * 一次外呼都不发。放到 18:30 是因为东财的限流按 IP 计时，等一阵再试才有意义；
     * 再往后就没必要了——当天没补上也只是退回实时，不是数据错误。
     */
    @Scheduled(cron = "${screener.prefetch.retry-cron:0 0/30 16-18 * * MON-FRI}", zone = "Asia/Shanghai")
    public void retryMissed() {
        runOnce();
    }

    /**
     * 启动补偿：容器在 15:30 那一刻没开着（重启、发版）时补跑一次。
     *
     * <p>**任何异常都不许冒出来**：这是启动路径，让它把应用带崩是拿一个可选的优化
     * 换掉了整个服务的可用性。预取没跑成的代价只是当天退回实时，与崩掉不是一个量级。
     * 同样的理由见 {@code poll_loop.py} 的 {@code sync_index_pool_once}。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            if (localTimeNow().isBefore(PREFETCH_TIME)) {
                log.debug("启动补偿跳过：还没到预取时刻");
                return;
            }
            runOnce(localDateNow());
        } catch (Exception e) {
            log.warn("低估精选预取的启动补偿异常（不影响启动）", e);
        }
    }

    /**
     * 「现在几点」。与 {@link StockScreenerService#localTimeNow()} 同一个理由：让
     * 「还没过点就不补跑」这条能被测到，而不必把一个测试绑在下午三点半之后跑。
     */
    LocalTime localTimeNow() {
        return LocalTime.now(SHANGHAI);
    }

    /**
     * 「今天几号」。理由与 {@link #localTimeNow()} 完全一样，只是这里的坑更隐蔽：
     * 启动补偿若在里面直接 {@code LocalDate.now()}，测试就没法钉住日期，
     * 于是**同一条用例在工作日绿、在周末红**——而 CI 哪天跑不由我们定。
     * 周末不补跑本身是对的（见 {@link #runOnce(LocalDate)}），要有缝才测得到它。
     */
    LocalDate localDateNow() {
        return LocalDate.now(SHANGHAI);
    }

    /**
     * 跑一次。**幂等**：已经跑过的自然日直接返回，不碰任何外呼。
     *
     * <p>失败**什么都不写**——头表只在整体写成时落行，所以「今天有没有这一行」
     * 就等于「今天成功没有」，不需要状态列，也不需要清理半成品。
     *
     * <p>日期做成参数（而不是在里面 {@code LocalDate.now()}）是为了让「周末不跑」
     * 这条能被测到：否则这个方法的每条断言都得看测试当天是星期几。
     */
    void runOnce() {
        runOnce(localDateNow());
    }

    void runOnce(LocalDate today) {
        if (!enabled) {
            log.debug("低估精选预取已关闭，跳过");
            return;
        }
        try {
            // 周末一律不跑，启动补偿也不例外。周六周日永远不是交易日，东财这时给的
            // latestTradeDate 还是周五，所以补出来的那一行 trade_date 是周五——
            // 而读侧只在周五找它（见 ScreenerPrefetchPolicy），等于白打 4 次外呼。
            // 定时那两条 cron 已经限了 MON-FRI，这里的判断是给启动补跑兜底：
            // 免得「哪天把 cron 改宽了」时这条保护也跟着一起消失。
            if (!force && (today.getDayOfWeek() == DayOfWeek.SATURDAY
                    || today.getDayOfWeek() == DayOfWeek.SUNDAY)) {
                log.debug("周末不预取 date={}", today);
                return;
            }
            if (!force && prefetchService.hasPrefetchedOn(today)) {
                log.debug("低估精选预取今天已跑过，跳过 date={}", today);
                return;
            }
            // 冷却是被行情源拒过的标记，与点击路径共用。冷却期里再打一次只是把封禁推深。
            if (cache.inCooldown()) {
                log.info("行情源冷却中，本轮预取跳过，等下一次重试 date={}", today);
                return;
            }
            MarketDataClient.UniverseSnapshot snapshot = marketDataClient.fetchUniverse();
            int written = prefetchService.save(snapshot, marketDataClient.poolSize());
            log.info("低估精选盘后预取完成：交易日 {}，明细 {} 行", snapshot.tradeDate(), written);
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            cache.enterCooldown();
            log.warn("低估精选预取被行情源限流，本次不落库，等重试：{}", e.getMessage());
        } catch (Exception e) {
            // 取数失败、库不可用都走这里。都不能让定时线程带着异常结束——
            // 那样 Spring 会一直重试同一个调度点，而这里需要的是「等下个整点再来」。
            log.error("低估精选预取失败 date={}", today, e);
        }
    }
}