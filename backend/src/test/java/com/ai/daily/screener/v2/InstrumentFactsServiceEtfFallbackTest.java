package com.ai.daily.screener.v2;

import com.ai.daily.screener.IndexFundPool;
import com.ai.daily.screener.ScreenerCache;
import com.ai.daily.service.EtfPriceHistoryService;
import com.ai.daily.service.MarketValuationHistoryService;
import com.ai.daily.warehouse.InstrumentCode;
import com.ai.daily.warehouse.InstrumentResolver;
import com.ai.daily.warehouse.WarehouseProperties;
import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 池外 ETF 的点击补数：本地两路日线都空时，腾讯现取的行必须进入快照，
 * 且图表用的序列与打分用的是同一条。
 */
class InstrumentFactsServiceEtfFallbackTest {

    private static final String CODE = "513180";
    private static final InstrumentCode ETF = new InstrumentCode("ETF", CODE, "SH");

    private InstrumentResolver resolver;
    private OnDemandSyncService syncService;
    private WarehouseRepository repository;
    private IndexFundPool indexFundPool;
    private EtfLiveKlineService liveKlineService;
    private FundProfileClient fundProfileClient;
    private ScreenerCache cache;
    private WarehouseProperties properties;
    private InstrumentFactsService service;

    @BeforeEach
    void setUp() {
        resolver = mock(InstrumentResolver.class);
        syncService = mock(OnDemandSyncService.class);
        repository = mock(WarehouseRepository.class);
        indexFundPool = mock(IndexFundPool.class);
        liveKlineService = mock(EtfLiveKlineService.class);
        fundProfileClient = mock(FundProfileClient.class);
        cache = mock(ScreenerCache.class);
        properties = new WarehouseProperties();

        when(resolver.resolve(anyString())).thenReturn(ETF);
        when(indexFundPool.byEtfCode(anyString())).thenReturn(null);
        service = new InstrumentFactsService(resolver, syncService, repository,
                mock(MarketValuationHistoryService.class), indexFundPool,
                mock(EtfPriceHistoryService.class), mock(com.ai.daily.screener.OffPoolIndexClient.class),
                liveKlineService, fundProfileClient, cache, properties, mock(com.ai.daily.screener.EtfResearch.class), mock(FredRates.class));
    }

    @Test
    void liveTencentBarsBecomeThePriceDomain() {
        when(syncService.sync(any(), any())).thenReturn(report(false));
        when(liveKlineService.fetchAndStore(any(), any(), anyBoolean()))
                .thenReturn(tencentBars(800));
        when(fundProfileClient.fetch(CODE)).thenReturn(FundProfileClient.Result.missing("未收录"));

        FactsSnapshot facts = service.snapshot(CODE);

        assertThat(facts.prices().status()).isEqualTo(DataStatus.OK);
        assertThat(facts.prices().data()).hasSize(800);
        assertThat(facts.prices().note()).contains("腾讯");
        assertThat(facts.fromCache()).isFalse();
        assertThat(facts.syncPending()).isFalse();
        // 池外 ETF 没有跟踪指数，档案页也不是「不适用」，而是 EMPTY。
        assertThat(facts.fundProfile().status()).isEqualTo(DataStatus.EMPTY);
        assertThat(facts.fundProfile().data()).isNull();
        verify(liveKlineService).fetchAndStore(ETF, "恒生科技ETF华夏", true);
    }

    @Test
    void throttleLeavesTheDomainFailedButDoesNotThrow() {
        when(syncService.sync(any(), any())).thenReturn(report(true));
        when(liveKlineService.fetchAndStore(any(), any(), anyBoolean()))
                .thenReturn(List.of());
        when(fundProfileClient.fetch(CODE)).thenReturn(FundProfileClient.Result.missing("未收录"));

        FactsSnapshot facts = service.snapshot(CODE);

        assertThat(facts.prices().status()).isEqualTo(DataStatus.FAILED);
        assertThat(facts.prices().data()).isEmpty();
    }

    @Test
    void stockNeverCallsTheEtfOnlySources() {
        InstrumentCode stock = InstrumentCode.stock("600519", "SH");
        when(resolver.resolve(anyString())).thenReturn(stock);
        when(syncService.sync(any(), any())).thenReturn(report(false));

        FactsSnapshot facts = service.snapshot("600519");

        assertThat(facts.fundProfile().status()).isEqualTo(DataStatus.NOT_APPLICABLE);
        verify(fundProfileClient, never()).fetch(anyString());
        verify(liveKlineService, never()).fetchAndStore(any(), any(), anyBoolean());
    }

    private OnDemandSyncService.SyncReport report(boolean failed) {
        FactsSnapshot.Quote quote = new FactsSnapshot.Quote("tencent", "腾讯",
                new BigDecimal("0.800"), new BigDecimal("1.2"), new BigDecimal("500000000"),
                new BigDecimal("3"), null, null, null, null, LocalDate.now().minusDays(1));
        return new OnDemandSyncService.SyncReport(quote, DataStatus.OK, null,
                "恒生科技ETF华夏", null, LocalDate.of(2021, 5, 25),
                failed ? DataStatus.FAILED : DataStatus.OK,
                failed ? "东财限流" : null,
                DataStatus.NOT_APPLICABLE, null,
                !failed, 0, true, List.of());
    }

    private static List<WarehouseRows.PriceBar> tencentBars(int count) {
        LocalDate base = LocalDate.now().minusDays(1);
        List<WarehouseRows.PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LocalDate date = base.minusDays(count - 1L - i);
            BigDecimal close = new BigDecimal("0.800");
            bars.add(new WarehouseRows.PriceBar(ETF.key(), date, EtfPriceHistoryService.QFQ,
                    close, new BigDecimal("0.81"), new BigDecimal("0.79"), close,
                    new BigDecimal("1000"), null, null, null,
                    EtfLiveKlineService.SOURCE_TENCENT_QFQ, LocalDateTime.now()));
        }
        return bars;
    }
}
