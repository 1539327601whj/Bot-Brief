package com.ai.daily.screener.v2;

import com.ai.daily.dto.EtfPriceHistoryIngestDTO;
import com.ai.daily.screener.AltQuoteSource;
import com.ai.daily.screener.MarketDataClient;
import com.ai.daily.screener.ScreenerCache;
import com.ai.daily.service.EtfPriceHistoryService;
import com.ai.daily.warehouse.InstrumentCode;
import com.ai.daily.warehouse.WarehouseProperties;
import com.ai.daily.warehouse.WarehouseRows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「点击时才取」的 ETF 日线补齐：本地偏薄或过期时现取腾讯前复权日线，写进
 * {@code etf_price_history}，让下一次点击零外呼。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>指数池里的 ETF 由 poller 每天预取 260 根；**池外的 ETF 一根都没有**，而 v2 的按需日线
 * 只走东财，东财按 IP 限流后整条路径为空——页面上就有价格、有名字，唯独六维全是 NA。
 * 腾讯日线是通的，但 v1 只拿它算价格位置，不落库、也没有 OHLC。
 *
 * <h2>只写 {@code etf_price_history}，绝不写 {@code md_price_daily}</h2>
 *
 * <p>后者不是为多源设计的：唯一键含 {@code source}，而读侧 {@code latestPrices} 不按
 * source 过滤。东财与腾讯两套前复权的基准日不同，混进同一张表会在换源那天出现重复交易日
 * 或一个说不清的跳空。{@code etf_price_history} + {@link com.ai.daily.screener.EtfPriceSeriesSelector}
 * 本来就是「同一天可能两家都有、选一条完整序列」的形状，这里顺着它用。
 *
 * <h2>翻页与代价</h2>
 *
 * <p>腾讯单页上限 800 根（≈3.3 年），要补十年就按 {@code end = 上一页最早交易日 − 1 天}
 * 往回翻，最多 {@code ceil(lookup-kline-limit / 800)} 页。三个终止条件照
 * {@code CodeLookupService.tencentBars}：收够 / 日期数不再增长（防源忽略 end 时死循环）/
 * 页数上限。中途限流保留已拿到的页——它们是从最近往回的连续段，丢掉等于白打。
 *
 * <p>只补尾部时（历史已经够长、只是最新一根旧了）只打一页，把「每天过期一次」的代价
 * 固定为一次外呼，而不是每天重翻四页。
 */
@Slf4j
@Component
public class EtfLiveKlineService {

    /**
     * 与 Python poller 完全一致的来源串。**不要改**：唯一键含 source，
     * 改一个字，同一只 ETF 就会被选源器当成两组序列。
     */
    public static final String SOURCE_TENCENT_QFQ = "腾讯前复权日线";

    /** 腾讯一次请求的硬上限。请求超过 800 会被悄悄截成 640，2600 直接报 param error。 */
    static final int PAGE_SIZE = 800;

    /** 批量写库接口一次最多 250 行（与 poller 的分块一致）。 */
    static final int INGEST_BATCH_SIZE = 250;

    /** 同一只 ETF 失败后多久内不再外呼。只影响这一只，不是全局冷却。 */
    static final long FAILURE_TTL_MS = 10 * 60_000L;

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final AltQuoteSource altQuoteSource;
    private final EtfPriceHistoryService etfPriceHistoryService;
    private final WarehouseProperties properties;
    private final ScreenerCache screenerCache;
    private final int lookupKlineLimit;

    /** 代码 → 可以再试的时间点（epoch millis）。 */
    private final Map<String, Long> failureUntil = new ConcurrentHashMap<>();

    public EtfLiveKlineService(AltQuoteSource altQuoteSource,
                               EtfPriceHistoryService etfPriceHistoryService,
                               WarehouseProperties properties,
                               ScreenerCache screenerCache,
                               @Value("${screener.lookup-kline-limit:2600}") int lookupKlineLimit) {
        this.altQuoteSource = altQuoteSource;
        this.etfPriceHistoryService = etfPriceHistoryService;
        this.properties = properties;
        this.screenerCache = screenerCache;
        this.lookupKlineLimit = Math.max(PAGE_SIZE, lookupKlineLimit);
    }

    /**
     * 取腾讯日线并尽力落库。**不抛异常**：失败返回空列表，页面照常显示「暂不可用」。
     *
     * @param fullHistory true 时翻满 {@code lookup-kline-limit}（≈10 年）；false 只取最新一页
     * @return 本次拿到的日线（升序，同源）。落库失败时仍返回内存里的这批，供本次展示。
     */
    public List<WarehouseRows.PriceBar> fetchAndStore(InstrumentCode etf, String fundName,
                                                      boolean fullHistory) {
        if (etf == null || !etf.isEtf() || !properties.isOnDemandEnabled()) {
            return List.of();
        }
        String code = etf.rawCode();
        long now = System.currentTimeMillis();
        Long retryAt = failureUntil.get(code);
        if (retryAt != null && retryAt > now) {
            return List.of();
        }
        if (screenerCache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)) {
            return List.of();
        }

        int want = fullHistory ? lookupKlineLimit : PAGE_SIZE;
        int maxPages = Math.max(1, (want + PAGE_SIZE - 1) / PAGE_SIZE);
        // 页边界必然重叠一根（上一页最早那天），用日期作键天然去重；TreeMap 顺带定死升序。
        Map<LocalDate, MarketDataClient.KlineRow> byDate = new TreeMap<>();
        LocalDate end = null;
        boolean incomplete = false;

        for (int page = 0; page < maxPages; page++) {
            if (screenerCache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)) {
                incomplete = true;
                break;
            }
            MarketDataClient.KlineRowsOutcome outcome =
                    altQuoteSource.fetchTencentKlineRows(code, end, PAGE_SIZE);
            if (outcome == null) {
                // 生产不会返回 null；mock 未 stub 时是 null，不判空就是 NPE。
                log.warn("ETF 日线补齐：腾讯返回了 null code={}", code);
                incomplete = true;
                break;
            }
            if (!outcome.ok()) {
                if (outcome.throttled()) {
                    screenerCache.enterCooldown(AltQuoteSource.PROVIDER_TENCENT);
                }
                log.info("ETF 日线补齐未完成 code={} page={}：{}",
                        code, page, outcome.failureReason());
                incomplete = true;
                break;
            }

            int before = byDate.size();
            LocalDate earliest = null;
            for (MarketDataClient.KlineRow row : outcome.rows()) {
                if (!usable(row)) {
                    continue;
                }
                byDate.put(row.tradeDate(), row);
                if (earliest == null || row.tradeDate().isBefore(earliest)) {
                    earliest = row.tradeDate();
                }
            }
            // 没有比手上更早的日期：要么已到头，要么源忽略了 end。这是唯一的防死循环闸门。
            if (earliest == null || byDate.size() == before) {
                break;
            }
            if (byDate.size() >= want) {
                break;
            }
            // 不足一整页 = 已经翻到该基金上市之前，再往回问只会拿到空响应。
            if (outcome.rows().size() < PAGE_SIZE) {
                break;
            }
            end = earliest.minusDays(1);
        }

        if (byDate.isEmpty()) {
            if (incomplete) {
                failureUntil.put(code, now + FAILURE_TTL_MS);
            }
            return List.of();
        }
        if (incomplete) {
            // 拿到了连续的一段，但后面没翻完：保留数据，同时压住十分钟内的重复外呼。
            failureUntil.put(code, now + FAILURE_TTL_MS);
        }

        LocalDateTime fetchedAt = LocalDateTime.now(SHANGHAI);
        List<EtfPriceHistoryIngestDTO> dtos = toIngest(etf, fundName, byDate, fetchedAt);
        persist(code, dtos);
        return toPriceBars(etf, byDate, fetchedAt);
    }

    /**
     * 逐行校验。批量写库接口是整批校验，一根坏行会让 250 根都写不进去，所以坏行在这里丢弃。
     *
     * <p>另外挡掉未来日期：数据库侧的 {@code @PastOrPresent} 用的是服务器时钟，
     * 而交易日期必须以上海时区为准。
     */
    private static boolean usable(MarketDataClient.KlineRow row) {
        if (row == null || row.tradeDate() == null
                || row.tradeDate().isAfter(LocalDate.now(SHANGHAI))) {
            return false;
        }
        BigDecimal open = row.openPrice();
        BigDecimal high = row.highPrice();
        BigDecimal low = row.lowPrice();
        BigDecimal close = row.closePrice();
        if (!positive(open) || !positive(high) || !positive(low) || !positive(close)) {
            return false;
        }
        return high.compareTo(open.max(close)) >= 0 && low.compareTo(open.min(close)) <= 0;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static List<EtfPriceHistoryIngestDTO> toIngest(InstrumentCode etf, String fundName,
                                                           Map<LocalDate, MarketDataClient.KlineRow> rows,
                                                           LocalDateTime fetchedAt) {
        String name = fundName == null || fundName.isBlank() ? etf.rawCode() : fundName;
        List<EtfPriceHistoryIngestDTO> out = new ArrayList<>(rows.size());
        for (MarketDataClient.KlineRow row : rows.values()) {
            EtfPriceHistoryIngestDTO dto = new EtfPriceHistoryIngestDTO();
            dto.setFundCode(etf.rawCode());
            dto.setFundName(name);
            dto.setTradeDate(row.tradeDate());
            dto.setOpen(row.openPrice());
            dto.setHigh(row.highPrice());
            dto.setLow(row.lowPrice());
            dto.setClose(row.closePrice());
            dto.setAdjustmentType(EtfPriceHistoryService.QFQ);
            dto.setSource(SOURCE_TENCENT_QFQ);
            dto.setFetchedAt(fetchedAt);
            out.add(dto);
        }
        return out;
    }

    private void persist(String code, List<EtfPriceHistoryIngestDTO> dtos) {
        for (int i = 0; i < dtos.size(); i += INGEST_BATCH_SIZE) {
            int to = Math.min(i + INGEST_BATCH_SIZE, dtos.size());
            try {
                etfPriceHistoryService.upsertBatch(dtos.subList(i, to));
            } catch (RuntimeException e) {
                // 已经拿到的日线数据是对的，只是没写进库；当次仍可展示，下次会再试。
                // 这里**不**记失败时间戳，否则会把一次写库问题变成十分钟的数据不可用。
                log.warn("ETF 日线写库失败 code={} 第 {} 批：{}", code, i / INGEST_BATCH_SIZE + 1,
                        e.toString());
                return;
            }
        }
    }

    private static List<WarehouseRows.PriceBar> toPriceBars(
            InstrumentCode etf, Map<LocalDate, MarketDataClient.KlineRow> rows,
            LocalDateTime fetchedAt) {
        List<WarehouseRows.PriceBar> out = new ArrayList<>(rows.size());
        for (MarketDataClient.KlineRow row : rows.values()) {
            // 腾讯日线只有成交量，没有成交额与换手率；留 null，不拿 volume×close 编。
            out.add(new WarehouseRows.PriceBar(etf.key(), row.tradeDate(),
                    EtfPriceHistoryService.QFQ, row.openPrice(), row.highPrice(),
                    row.lowPrice(), row.closePrice(), row.volume(), null, null, null,
                    SOURCE_TENCENT_QFQ, fetchedAt));
        }
        return out;
    }
}
