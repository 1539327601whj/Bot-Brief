package com.ai.daily.screener.v2;

import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.screener.IndexFundPool;
import com.ai.daily.service.MarketValuationHistoryService;
import com.ai.daily.warehouse.InstrumentCode;
import com.ai.daily.warehouse.InstrumentResolver;
import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseRows;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 把本地仓库里的数据装配成一只标的的 {@link FactsSnapshot}。
 *
 * <p>这个类是 P3 与 P4 的接缝：**打分只读这里给的快照**，不再自己查库或外呼。
 * 所以每个域都必须把「值、状态、口径、数据日」四件事一起交出去，
 * 而不是只给一个 List 让上层猜「空列表到底是没数据还是失败了」。
 *
 * <p>个股与 ETF/指数共用一套事实结构，但数据来源不同：
 * 个股估值来自 {@code md_valuation_daily}（东财估值分析），
 * 指数/ETF 的 PE 分位来自 {@code market_valuation_history}（中证/蛋卷，按口径分开）。
 * **两条路绝不互相顶替**——混源分位正是旧页面上最难发现的一类错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentFactsService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** P4 改打分规则时递增；快照 ID 随之变化，缓存自然失效。 */
    static final String RULES_VERSION = "code-lookup-v2-p3";

    private static final int PRICE_BARS = 2600;
    private static final int FINANCIAL_REPORTS = 8;
    private static final int MONEYFLOW_ROWS = 30;
    private static final int EVENT_ROWS = 50;
    private static final int MIN_VALUATION_POINTS = 500;

    private final InstrumentResolver resolver;
    private final OnDemandSyncService onDemandSyncService;
    private final WarehouseRepository repository;
    private final MarketValuationHistoryService valuationHistoryService;
    private final IndexFundPool indexFundPool;

    public FactsSnapshot snapshot(String rawCode) {
        InstrumentCode instrument = resolver.resolve(rawCode);
        OnDemandSyncService.SyncReport report = onDemandSyncService.sync(instrument, null);
        LocalDate today = LocalDate.now(SHANGHAI);

        WareHousIdentity identity = identity(instrument, report);

        // ------------------------------------------------------------------
        // 价格
        // ------------------------------------------------------------------
        List<WarehouseRows.PriceBar> prices =
                repository.latestPrices(instrument.key(), OnDemandSyncService.ADJ_QFQ, PRICE_BARS);
        DataStatus priceStatus = report.priceStatus();
        String priceNote = report.priceNote();
        if (prices.isEmpty()) {
            if (priceStatus.usable()) {
                // 报告说拿到了，读库却是空的：只能以库为准，不能把「有数据」写进快照。
                priceStatus = DataStatus.EMPTY;
                priceNote = "本地日线为空";
            }
        } else if (priceStatus == DataStatus.FAILED) {
            priceStatus = DataStatus.PARTIAL;
        }
        String priceAsOf = prices.isEmpty() ? null
                : prices.get(prices.size() - 1).tradeDate().toString();
        FactsSnapshot.Domain<List<WarehouseRows.PriceBar>> priceDomain =
                FactsSnapshot.Domain.of(prices, priceStatus, priceNote, priceAsOf);

        // ------------------------------------------------------------------
        // 个股估值序列
        // ------------------------------------------------------------------
        FactsSnapshot.Domain<List<WarehouseRows.ValuationPoint>> valuationDomain;
        if (instrument.isStock()) {
            List<WarehouseRows.ValuationPoint> points = repository.valuationSeries(instrument.key(),
                    OnDemandSyncService.VALUATION_SOURCE, today.minusYears(11));
            DataStatus status = report.valuationStatus();
            String note = report.valuationNote();
            if (points.isEmpty()) {
                if (status.usable()) {
                    status = DataStatus.EMPTY;
                    note = "本地估值历史为空";
                }
            } else if (points.size() < MIN_VALUATION_POINTS && !status.usable()) {
                status = DataStatus.PARTIAL;
                note = "本地估值历史只有 " + points.size() + " 条，分位仅供参考";
            } else if (points.size() < MIN_VALUATION_POINTS) {
                status = DataStatus.PARTIAL;
                note = "估值历史只有 " + points.size() + " 条（窗口自 "
                        + points.get(0).tradeDate() + " 起），不足 500 条";
            }
            String asOf = points.isEmpty() ? null
                    : points.get(points.size() - 1).tradeDate().toString();
            valuationDomain = FactsSnapshot.Domain.of(points, status, note, asOf);
        } else {
            valuationDomain = FactsSnapshot.Domain.notApplicable(
                    "指数/ETF 的估值分位来自指数估值源，不在个股估值表里");
        }

        // ------------------------------------------------------------------
        // 指数/ETF 估值分位
        // ------------------------------------------------------------------
        String indexCode = identity.indexCode();
        FactsSnapshot.Domain<FactsSnapshot.IndexValuation> indexValuation =
                indexValuation(instrument, indexCode, today);

        // ------------------------------------------------------------------
        // 财务 / 资金 / 指数画像 / 事件
        // ------------------------------------------------------------------
        FactsSnapshot.Domain<List<WarehouseRows.FinancialReport>> financials;
        FactsSnapshot.Domain<List<WarehouseRows.MoneyFlow>> moneyflow;
        FactsSnapshot.Domain<WarehouseRows.IndexProfile> profile;
        FactsSnapshot.Domain<List<WarehouseRows.IndustryWeight>> industry;
        if (instrument.isStock()) {
            List<WarehouseRows.FinancialReport> rows =
                    repository.financialsAvailableAsOf(instrument.rawCode(), today, FINANCIAL_REPORTS);
            financials = rows.isEmpty()
                    ? FactsSnapshot.Domain.of(rows, report.syncPending() ? DataStatus.SYNCING : DataStatus.EMPTY,
                    "财务数据" + (report.syncPending() ? "同步中，通常 10–30 秒" : "本地没有"), null)
                    : FactsSnapshot.Domain.ok(rows, rows.get(0).reportDate().toString());
            moneyflow = stocksMoneyflow(instrument, report);
            profile = FactsSnapshot.Domain.notApplicable("个股没有指数画像");
            industry = FactsSnapshot.Domain.notApplicable("个股没有指数行业权重");
        } else {
            financials = FactsSnapshot.Domain.notApplicable(
                    instrument.isEtf() ? "ETF 没有公司财务报表" : "指数没有公司财务报表");
            moneyflow = FactsSnapshot.Domain.notApplicable(
                    instrument.isEtf() ? "ETF 不提供个股资金流" : "指数不提供个股资金流");
            profile = indexProfile(indexCode, report);
            industry = indexIndustry(indexCode, report);
        }

        List<WarehouseRows.Event> events = repository.events(instrument.key(), EVENT_ROWS);
        FactsSnapshot.Domain<List<WarehouseRows.Event>> eventDomain = events.isEmpty()
                ? FactsSnapshot.Domain.of(events,
                report.syncPending() ? DataStatus.SYNCING : DataStatus.EMPTY,
                report.syncPending() ? "新闻/公告同步中" : "本地暂无新闻或公告", null)
                : FactsSnapshot.Domain.ok(events, events.get(0).publishedAt() == null
                ? null : events.get(0).publishedAt().toLocalDate().toString());

        String snapshotId = snapshotId(instrument, priceAsOf, valuationDomain.asOf(),
                financials.asOf(), indexValuation.asOf());
        String snapshotAt = LocalDateTime.now(SHANGHAI).toString();

        return new FactsSnapshot(rawCode.trim().toUpperCase(java.util.Locale.ROOT), instrument,
                identity.identity(),
                quoteDomain(report),
                priceDomain, valuationDomain, indexValuation, financials, moneyflow,
                profile, industry, eventDomain,
                report.syncPending(), report.retryAfterMs(), !report.fetchedFromSource(),
                snapshotId, snapshotAt);
    }

    // ------------------------------------------------------------------
    // 身份
    // ------------------------------------------------------------------

    private record WareHousIdentity(FactsSnapshot.Identity identity, String indexCode) {
    }

    private WareHousIdentity identity(InstrumentCode instrument,
                                      OnDemandSyncService.SyncReport report) {
        Optional<WarehouseRows.Instrument> stored = repository.findInstrument(instrument.key());
        WarehouseRows.Instrument row = stored.orElse(null);

        String name = firstNonBlank(row == null ? null : row.name(), report.name());
        String industry = firstNonBlank(row == null ? null : row.industry(), report.industry());
        String industryCode = row == null ? null : row.industryCode();
        String trackingIndex = row == null ? null : row.trackingIndexCode();
        if (trackingIndex == null && instrument.isEtf()) {
            IndexFundPool.Fund fund = indexFundPool.byEtfCode(instrument.rawCode());
            trackingIndex = fund == null ? null : fund.indexCode();
        }
        LocalDate listDate = row != null && row.listDate() != null ? row.listDate() : report.listDate();
        String status = row == null || row.status() == null ? "ACTIVE" : row.status();

        if (stored.isEmpty() || name != null && (row.name() == null || row.name().isBlank())) {
            // 只补身份，不覆盖已有值：COALESCE 在 SQL 里守着，这里给全量快照即可。
            repository.upsertInstrument(new WarehouseRows.Instrument(instrument.key(),
                    instrument.assetType(), instrument.market(), instrument.rawCode(), name,
                    row == null ? null : row.aliases(), industry, industryCode, trackingIndex,
                    listDate, status, "java-on-demand", LocalDateTime.now(SHANGHAI)));
        }

        String indexCode = instrument.isIndex() ? instrument.rawCode() : trackingIndex;
        FactsSnapshot.Identity identity = new FactsSnapshot.Identity(
                instrument.rawCode(), instrument.assetType(), instrument.market(), name,
                industry, industryCode, trackingIndex, listDate, status, sectorProfile(industry));
        return new WareHousIdentity(identity, indexCode);
    }

    /**
     * 行业分组的粗口径，供 P4 的金融/地产特例使用。
     *
     * <p>这里**只按东财行业名做关键词判断**，不是严格分类；判断不出返回 null，
     * P4 会走通用模板。猜错分组比不分组更糟——银行按通用模板算资产负债率会被判成「高风险」。
     */
    static String sectorProfile(String industry) {
        if (industry == null || industry.isBlank()) {
            return null;
        }
        String name = industry.trim();
        if (name.contains("银行") || name.contains("保险") || name.contains("证券")
                || name.contains("多元金融")) {
            return "FINANCIAL";
        }
        if (name.contains("房地产") || name.contains("地产")) {
            return "REAL_ESTATE";
        }
        if (name.contains("煤炭") || name.contains("钢铁") || name.contains("有色")
                || name.contains("化工") || name.contains("石油")) {
            return "CYCLICAL";
        }
        return "GENERAL";
    }

    private static FactsSnapshot.Domain<FactsSnapshot.Quote> quoteDomain(
            OnDemandSyncService.SyncReport report) {
        FactsSnapshot.Quote quote = report.quote();
        if (quote == null) {
            return FactsSnapshot.Domain.of(null, report.quoteStatus(), report.quoteNote(), null);
        }
        return FactsSnapshot.Domain.of(quote, report.quoteStatus(), report.quoteNote(),
                quote.asOf() == null ? null : quote.asOf().toString());
    }

    private FactsSnapshot.Domain<List<WarehouseRows.MoneyFlow>> stocksMoneyflow(
            InstrumentCode instrument, OnDemandSyncService.SyncReport report) {
        List<WarehouseRows.MoneyFlow> rows = repository.moneyflow(instrument.rawCode(), MONEYFLOW_ROWS);
        if (rows.isEmpty()) {
            return FactsSnapshot.Domain.of(rows,
                    report.syncPending() ? DataStatus.SYNCING : DataStatus.EMPTY,
                    report.syncPending() ? "资金流同步中，通常 10–30 秒" : "本地没有资金流数据", null);
        }
        return FactsSnapshot.Domain.ok(rows, rows.get(0).tradeDate().toString());
    }

    // ------------------------------------------------------------------
    // 指数
    // ------------------------------------------------------------------

    private FactsSnapshot.Domain<FactsSnapshot.IndexValuation> indexValuation(
            InstrumentCode instrument, String indexCode, LocalDate today) {
        if (indexCode == null) {
            return FactsSnapshot.Domain.notApplicable(
                    instrument.isEtf() ? "这只 ETF 没有匹配到跟踪指数" : "该指数没有配置估值来源");
        }
        IndexFundPool.Fund fund = indexFundPool.byIndexCode(indexCode);
        if (fund == null || fund.percentileMethod() == null) {
            return FactsSnapshot.Domain.of(null, DataStatus.EMPTY,
                    "指数池没有为 " + indexCode + " 配置 PE 分位口径（免费源未覆盖）", null);
        }
        List<MarketValuationHistory> history = valuationHistoryService.historyBetween(
                indexCode, fund.percentileMethod(), today.minusYears(11), today);
        if (history.isEmpty()) {
            return FactsSnapshot.Domain.of(null, DataStatus.EMPTY,
                    "估值库里还没有 " + indexCode + " 的分位历史", null);
        }
        MarketValuationHistory latest = history.get(history.size() - 1);
        DataStatus status = latest.getPeTtm() != null && latest.getPePercentile() != null
                ? DataStatus.OK : DataStatus.PARTIAL;
        String note = status == DataStatus.OK ? null : "这一行缺 PE 或分位，无法给出完整估值判断";
        List<BigDecimal> peHistory = new java.util.ArrayList<>(history.size());
        List<com.ai.daily.screener.RollingPercentile.Point> pbHistory =
                new java.util.ArrayList<>(history.size());
        for (MarketValuationHistory row : history) {
            if (row.getPeTtm() != null) {
                peHistory.add(row.getPeTtm());
            }
            if (row.getPb() != null && row.getTradeDate() != null) {
                pbHistory.add(new com.ai.daily.screener.RollingPercentile.Point(
                        row.getTradeDate(), row.getPb()));
            }
        }
        BigDecimal pbPercentile = com.ai.daily.screener.RollingPercentile.latest(pbHistory)
                .orElse(null);
        FactsSnapshot.IndexValuation view = new FactsSnapshot.IndexValuation(
                latest.getSource(), sourceLabel(fund), fund.percentileMethod(),
                latest.getPeTtm(), latest.getPePercentile(),
                Quantiles.quantile(peHistory, 0.25),
                Quantiles.quantile(peHistory, 0.40),
                Quantiles.quantile(peHistory, 0.50),
                pbPercentile,
                latest.getTradeDate(),
                history.size(),
                history.get(0).getTradeDate(), latest.getTradeDate());
        return FactsSnapshot.Domain.of(view, status, note,
                latest.getTradeDate() == null ? null : latest.getTradeDate().toString());
    }

    private FactsSnapshot.Domain<WarehouseRows.IndexProfile> indexProfile(
            String indexCode, OnDemandSyncService.SyncReport report) {
        if (indexCode == null) {
            return FactsSnapshot.Domain.notApplicable("没有匹配到指数代码");
        }
        Optional<WarehouseRows.IndexProfile> row = repository.latestIndexProfile(indexCode);
        if (row.isPresent()) {
            return FactsSnapshot.Domain.ok(row.get(), row.get().asOf() == null
                    ? null : row.get().asOf().toString());
        }
        return FactsSnapshot.Domain.of(null,
                report.syncPending() ? DataStatus.SYNCING : DataStatus.EMPTY,
                report.syncPending() ? "指数画像同步中" : "本地没有指数画像", null);
    }

    private FactsSnapshot.Domain<List<WarehouseRows.IndustryWeight>> indexIndustry(
            String indexCode, OnDemandSyncService.SyncReport report) {
        if (indexCode == null) {
            return FactsSnapshot.Domain.notApplicable("没有匹配到指数代码");
        }
        List<WarehouseRows.IndustryWeight> rows = repository.latestIndustryWeights(indexCode);
        if (!rows.isEmpty()) {
            String asOf = rows.get(0).asOf() == null ? null : rows.get(0).asOf().toString();
            return FactsSnapshot.Domain.ok(rows, asOf);
        }
        return FactsSnapshot.Domain.of(rows,
                report.syncPending() ? DataStatus.SYNCING : DataStatus.EMPTY,
                report.syncPending() ? "行业权重同步中" : "本地没有行业权重", null);
    }

    private static String sourceLabel(IndexFundPool.Fund fund) {
        if (IndexFundPool.SOURCE_CSINDEX.equals(fund.valuationSource())) {
            return "中证官网（中证口径）";
        }
        if (IndexFundPool.SOURCE_DANJUAN.equals(fund.valuationSource())) {
            return "蛋卷（蛋卷口径）";
        }
        return fund.valuationSource() == null ? "未配置" : fund.valuationSource();
    }

    // ------------------------------------------------------------------
    // 快照 ID
    // ------------------------------------------------------------------

    /**
     * 价格 / 估值 / 财务 / 指数估值四个数据日 + 规则版本的哈希。
     *
     * <p>core 与 detail 共用它来发现「两份响应不是同一份事实」：
     * 中间价格更新了，detail 就带 {@code snapshotChanged=true}，前端重拉 core，
     * 而不是把旧分数和新财务拼在一页。
     */
    static String snapshotId(InstrumentCode instrument, String priceAsOf, String valuationAsOf,
                             String financialAsOf, String indexValuationAsOf) {
        String raw = String.join("|", instrument.key(),
                nullToEmpty(priceAsOf), nullToEmpty(valuationAsOf),
                nullToEmpty(financialAsOf), nullToEmpty(indexValuationAsOf), RULES_VERSION);
        return sha256(raw).substring(0, 16);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 没有 SHA-256", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return null;
    }
}
