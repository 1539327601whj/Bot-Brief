package com.ai.daily.screener;

import com.ai.daily.entity.ScreenerUniverseSnapshot;
import com.ai.daily.entity.ScreenerUniverseStock;
import com.ai.daily.mapper.ScreenerUniverseSnapshotMapper;
import com.ai.daily.mapper.ScreenerUniverseStockMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 盘后预取库的读写。写的是**东财自己返回的那份数据**，只是取的时间提前到收盘后；
 * 读的时候原样还原成 {@link MarketDataClient.UniverseSnapshot}，上层规则完全无感。
 *
 * <p><b>为什么写库和读库在同一个类里</b>：它是同一个契约的两端，分开就会各自漂移——
 * 写侧加了一列、读侧忘了带，字段悄悄变 null 而没有任何报错。同在一处，
 * 改一边时另一边就在眼前。这跟 {@code EtfPriceSeriesSelector} 把「怎么选源」钉在一处是同一个理由。
 *
 * <p>放在 {@code screener} 包而不是 {@code service} 包：它直接吃 {@code StockRow} /
 * {@code UniverseSnapshot} 这类筛选内部类型，放进通用 service 层会把这层耦合扩散出去。
 * 实体与 mapper 仍在通用的 {@code entity} / {@code mapper} 包里，与其他表一致。
 */
@Slf4j
@Service
public class ScreenerPrefetchService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /**
     * 一次 {@code upsertBatch} 最多多少行。与 {@code EtfPriceHistoryServiceImpl.MAX_BATCH}
     * 同一个数、同一个理由：不该由「池子会不会长大」来决定这条语句还能不能用，
     * 超了就分批，而不是撞上占位符上限再回来查。
     */
    static final int MAX_BATCH = 250;

    private final ScreenerUniverseSnapshotMapper snapshotMapper;
    private final ScreenerUniverseStockMapper stockMapper;

    public ScreenerPrefetchService(ScreenerUniverseSnapshotMapper snapshotMapper,
                                   ScreenerUniverseStockMapper stockMapper) {
        this.snapshotMapper = snapshotMapper;
        this.stockMapper = stockMapper;
    }

    // ==================================================================
    // 写
    // ==================================================================

    /**
     * 把一次取回的快照整体落库。**头与明细在同一个事务里**——否则崩在两步之间会留下
     * 「有头没明细」的行，而头表就是幂等闸门，第二天读侧会以为今天预取过、读到空池子。
     *
     * <p>整批先校验、再写：一条不合规就一条都不写，镜像
     * {@code EtfPriceHistoryServiceImpl.upsertBatch} 的做法。半批数据比没有数据更难查。
     *
     * @return 写入的明细行数
     */
    @Transactional
    public int save(MarketDataClient.UniverseSnapshot snapshot, int poolSize) {
        if (snapshot == null || snapshot.tradeDate() == null) {
            throw new IllegalArgumentException("预取快照不能为空，且必须带交易日");
        }
        List<StockRow> rows = snapshot.rows();
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("预取快照没有任何标的，不落库");
        }
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize 必须为正数");
        }

        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        List<ScreenerUniverseStock> details = new ArrayList<>(rows.size());
        for (StockRow row : rows) {
            details.add(toEntity(row, snapshot.tradeDate(), now));
        }
        // 校验放在转换之后、写库之前：校验的是**即将入库的东西**，而不是入参
        details.forEach(ScreenerPrefetchService::validate);

        int written = 0;
        for (int i = 0; i < details.size(); i += MAX_BATCH) {
            written += stockMapper.upsertBatch(
                    details.subList(i, Math.min(details.size(), i + MAX_BATCH)));
        }

        ScreenerUniverseSnapshot header = new ScreenerUniverseSnapshot();
        header.setTradeDate(snapshot.tradeDate());
        header.setPrefetchDate(LocalDate.now(SHANGHAI));
        header.setPoolSize(poolSize);
        header.setListedCount(snapshot.listedCount());
        header.setMissingCount(snapshot.missing());
        header.setCapFloor(snapshot.capFloor());
        header.setSource(MarketDataClient.PROVIDER_EASTMONEY);
        header.setFetchedAt(now);
        header.setCreatedAt(now);
        header.setUpdatedAt(now);
        snapshotMapper.upsert(header);

        log.info("低估精选盘后预取入库：交易日 {} 明细 {} 行（清单 {} 条，剔除 {} 条，池子 N={}）",
                snapshot.tradeDate(), written, snapshot.listedCount(), snapshot.missing(), poolSize);
        return written;
    }

    private static ScreenerUniverseStock toEntity(StockRow row, LocalDate tradeDate, LocalDateTime now) {
        ScreenerUniverseStock e = new ScreenerUniverseStock();
        e.setSnapshotTradeDate(tradeDate);
        e.setStockCode(row.getCode());
        e.setStockName(row.getName());
        e.setMarket(row.getMarket());
        e.setEtf(row.isEtf());
        e.setIndustry(row.getIndustry());
        e.setPrice(row.getPrice());
        e.setPctChange(row.getPctChange());
        e.setAmount(row.getAmount());
        e.setTurnoverRate(row.getTurnoverRate());
        e.setTotalMarketCap(row.getTotalMarketCap());
        e.setFloatMarketCap(row.getFloatMarketCap());
        e.setPb(row.getPb());
        e.setPeTtm(row.getPeTtm());
        e.setRoe(row.getRoe());
        e.setRevenueGrowth(row.getRevenueGrowth());
        e.setProfitGrowth(row.getProfitGrowth());
        e.setGrossMargin(row.getGrossMargin());
        e.setDebtRatio(row.getDebtRatio());
        e.setDividendYield(row.getDividendYield());
        e.setBps(row.getBps());
        e.setListDate(row.getListDate());
        e.setChange60d(row.getChange60d());
        e.setYtdChange(row.getYtdChange());
        e.setSource(MarketDataClient.PROVIDER_EASTMONEY);
        e.setFetchedAt(now);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    private static void validate(ScreenerUniverseStock e) {
        if (e.getStockCode() == null || e.getStockCode().isBlank()) {
            throw new IllegalArgumentException("预取明细缺 stockCode");
        }
        if (e.getMarket() == null || (e.getMarket() != 0 && e.getMarket() != 1)) {
            throw new IllegalArgumentException(
                    "预取明细 " + e.getStockCode() + " 的 market 不是 0/1：" + e.getMarket());
        }
        if (e.getSnapshotTradeDate() == null) {
            throw new IllegalArgumentException("预取明细 " + e.getStockCode() + " 缺 snapshotTradeDate");
        }
    }

    // ==================================================================
    // 读
    // ==================================================================

    /** 某个交易日的数据是否已经在库里。头表只在整体成功时写，所以存在即成功。 */
    public boolean hasSnapshotFor(LocalDate tradeDate) {
        if (tradeDate == null) return false;
        return snapshotMapper.countByTradeDate(tradeDate) > 0;
    }

    /**
     * 某个自然日是否已经跑过预取。写侧的幂等闸门。
     *
     * <p>与 {@link #hasSnapshotFor} 是两个问题，别合并：那个问「有没有**这一天交易日**的数据」，
     * 这个问「**今天**跑过没有」。休市日两者会给出相反答案。
     */
    public boolean hasPrefetchedOn(LocalDate prefetchDate) {
        if (prefetchDate == null) return false;
        return snapshotMapper.countByPrefetchDate(prefetchDate) > 0;
    }

    /**
     * 还原某个交易日的快照。拿不到就给 empty，由调用方回退到实时路径。
     *
     * <p>**有头没明细也返回 empty**：那种状态说明写入被截断过（正常路径下不可能，
     * 头与明细同事务）。此时返回一个空 rows 的快照会让上级抛「行情源返回了空的股票列表」，
     * 把一次本可以正常降级的点击变成一个错误框。宁可当作「没有预取过」。
     */
    public Optional<MarketDataClient.UniverseSnapshot> snapshotFor(LocalDate tradeDate) {
        if (tradeDate == null) return Optional.empty();
        ScreenerUniverseSnapshot header = snapshotMapper.findByTradeDate(tradeDate);
        if (header == null) return Optional.empty();

        List<ScreenerUniverseStock> details = stockMapper.findByTradeDate(tradeDate);
        if (details == null || details.isEmpty()) {
            log.warn("预取头存在但没有明细，按「没有预取」处理 tradeDate={}", tradeDate);
            return Optional.empty();
        }

        List<StockRow> rows = new ArrayList<>(details.size());
        for (ScreenerUniverseStock d : details) {
            rows.add(toRow(d));
        }
        return Optional.of(new MarketDataClient.UniverseSnapshot(
                rows,
                header.getListedCount() == null ? rows.size() : header.getListedCount(),
                header.getMissingCount() == null ? 0 : header.getMissingCount(),
                header.getTradeDate(),
                header.getCapFloor()));
    }

    private static StockRow toRow(ScreenerUniverseStock d) {
        StockRow row = new StockRow();
        row.setCode(d.getStockCode());
        row.setName(d.getStockName());
        row.setMarket(d.getMarket() == null ? 0 : d.getMarket());
        row.setEtf(d.isEtf());
        row.setIndustry(d.getIndustry());
        row.setPrice(d.getPrice());
        row.setPctChange(d.getPctChange());
        row.setAmount(d.getAmount());
        row.setTurnoverRate(d.getTurnoverRate());
        row.setTotalMarketCap(d.getTotalMarketCap());
        row.setFloatMarketCap(d.getFloatMarketCap());
        row.setPb(d.getPb());
        row.setPeTtm(d.getPeTtm());
        row.setRoe(d.getRoe());
        row.setRevenueGrowth(d.getRevenueGrowth());
        row.setProfitGrowth(d.getProfitGrowth());
        row.setGrossMargin(d.getGrossMargin());
        row.setDebtRatio(d.getDebtRatio());
        row.setDividendYield(d.getDividendYield());
        row.setBps(d.getBps());
        row.setListDate(d.getListDate());
        row.setChange60d(d.getChange60d());
        row.setYtdChange(d.getYtdChange());
        return row;
    }
}