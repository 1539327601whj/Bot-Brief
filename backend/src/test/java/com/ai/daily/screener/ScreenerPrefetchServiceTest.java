package com.ai.daily.screener;

import com.ai.daily.entity.ScreenerUniverseSnapshot;
import com.ai.daily.entity.ScreenerUniverseStock;
import com.ai.daily.mapper.ScreenerUniverseSnapshotMapper;
import com.ai.daily.mapper.ScreenerUniverseStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 预取库的读写。
 *
 * <p>这个类最大的风险是**两端悄悄不一致**（写侧加了一列、读侧没带上，字段变 null 而没人报错），
 * 类注释里说「写读放同一个类就不会漂移」——那只是让漂移看得见，不等于它不会发生。
 * 所以这里最重要的一条是往返一致：造一份每个字段都填满的 {@code StockRow}，写下去、
 * 再从写侧真正提交给 mapper 的那些实体里读回来，逐字段比对。
 */
class ScreenerPrefetchServiceTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 30);

    private ScreenerUniverseSnapshotMapper snapshotMapper;
    private ScreenerUniverseStockMapper stockMapper;
    private ScreenerPrefetchService service;

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(ScreenerUniverseSnapshotMapper.class);
        stockMapper = mock(ScreenerUniverseStockMapper.class);
        service = new ScreenerPrefetchService(snapshotMapper, stockMapper);
        when(stockMapper.upsertBatch(any())).thenAnswer(i -> ((List<?>) i.getArgument(0)).size());
    }

    // ================= 写入 =================

    @Test
    void aSavedSnapshotComesBackFieldForField() {
        List<StockRow> rows = List.of(fullRow(1), fullRow(2));
        service.save(snapshot(rows, 7, 3), 300);

        // 读回来的东西**取自写侧真的交给 mapper 的那批实体**，不是另造一份——
        // 否则测的是「我造的夹具能否还原自己」，与 toEntity 有没有漏字段无关
        List<ScreenerUniverseStock> written = capturedDetails();
        ScreenerUniverseSnapshot header = capturedHeader();
        when(stockMapper.findByTradeDate(TRADE_DATE)).thenReturn(written);
        when(snapshotMapper.findByTradeDate(TRADE_DATE)).thenReturn(header);

        Optional<MarketDataClient.UniverseSnapshot> back = service.snapshotFor(TRADE_DATE);

        assertThat(back).isPresent();
        assertThat(back.get().rows()).containsExactlyElementsOf(rows);
        assertThat(back.get().listedCount()).isEqualTo(7);
        assertThat(back.get().missing()).isEqualTo(3);
        assertThat(back.get().capFloor()).isEqualByComparingTo("38300000000");
    }

    @Test
    void theHeaderCarriesTheScalarsThatBelongToOneScanNotToOneStock() {
        service.save(snapshot(List.of(fullRow(1)), 12, 4), 300);

        ScreenerUniverseSnapshot header = capturedHeader();

        assertThat(header.getTradeDate()).isEqualTo(TRADE_DATE);
        assertThat(header.getPoolSize()).isEqualTo(300);
        assertThat(header.getListedCount()).isEqualTo(12);
        assertThat(header.getMissingCount()).isEqualTo(4);
        assertThat(header.getCapFloor()).isEqualByComparingTo("38300000000");
        assertThat(header.getSource()).isEqualTo(MarketDataClient.PROVIDER_EASTMONEY);
        // 写入侧闸门读的是**自然日**，与交易日是两件事：休市日重跑时二者不相等，
        // 而闸门必须按自然日判否，否则每次重试都会重新外呼一遍同样的数据
        assertThat(header.getPrefetchDate()).isNotNull();
        assertThat(header.getFetchedAt()).isNotNull();
    }

    @Test
    void theHeaderIsWrittenLastSoAFailureInBetweenLeavesNoGateRow() {
        service.save(snapshot(List.of(fullRow(1)), 1, 0), 300);

        InOrder order = inOrder(stockMapper, snapshotMapper);
        // 头表就是幂等闸门：先写头、再写明细的话，崩在中间会让第二天读到「有头没明细」，
        // 于是以为今天预取过，实际一个标的都没有
        order.verify(stockMapper).upsertBatch(any());
        order.verify(snapshotMapper).upsert(any());
    }

    @Test
    void theWholeSaveIsOneTransaction() throws Exception {
        // 单测里 service 是裸对象，@Transactional 不会真的生效，所以只能钉住它还在。
        // 这个注解一旦被删掉，头与明细就会各写各的，而测试全绿——正是最该防的那种回归。
        assertThat(ScreenerPrefetchService.class
                .getMethod("save", MarketDataClient.UniverseSnapshot.class, int.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void aBatchLargerThanTheChunkSizeIsSplitButEveryRowIsWritten() {
        List<StockRow> rows = new ArrayList<>();
        for (int i = 0; i < 300; i++) rows.add(fullRow(i));

        int written = service.save(snapshot(rows, 300, 0), 300);

        assertThat(written).isEqualTo(300);
        ArgumentCaptor<List<ScreenerUniverseStock>> captor = ArgumentCaptor.forClass(List.class);
        // 250 + 50：上限存在是为了「池子将来长大」时不要撞上占位符上限，而不是因为现在会撞
        verify(stockMapper, org.mockito.Mockito.times(2)).upsertBatch(captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(250, 50);
    }

    // ================= 一条不合法就一条都不写 =================

    @Test
    void oneBadRowStopsTheWholeBatchBeforeAnythingIsWritten() {
        StockRow bad = fullRow(1);
        bad.setCode("  ");

        assertThatThrownBy(() -> service.save(snapshot(List.of(fullRow(2), bad), 2, 0), 300))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stockCode");

        // 半批数据比没有数据更难查：要么整天都在，要么一天都不在
        verifyNoInteractions(stockMapper);
        verifyNoInteractions(snapshotMapper);
    }

    @Test
    void aRowWithAnOutOfRangeMarketIsRejected() {
        StockRow bad = fullRow(1);
        bad.setMarket(7);

        assertThatThrownBy(() -> service.save(snapshot(List.of(bad), 1, 0), 300))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("market");
        verifyNoInteractions(stockMapper);
    }

    @Test
    void anEmptyUniverseIsRejectedInsteadOfStoringAnEmptyPool() {
        assertThatThrownBy(() -> service.save(snapshot(List.of(), 0, 0), 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(null, 300))
                .isInstanceOf(IllegalArgumentException.class);
        // 请求的池子大小落进头表的 pool_size 列，是排除「池子为什么这么小」时唯一的依据，
        // 0 会让它看起来像「一个标的都没请求过」
        assertThatThrownBy(() -> service.save(snapshot(List.of(fullRow(1)), 1, 0), 0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(stockMapper, snapshotMapper);
    }

    // ================= 读取 =================

    @Test
    void noHeaderMeansNoSnapshot() {
        assertThat(service.snapshotFor(TRADE_DATE)).isEmpty();
        verify(stockMapper, never()).findByTradeDate(any());
    }

    @Test
    void aHeaderWithoutDetailsIsTreatedAsNoSnapshotAtAll() {
        when(snapshotMapper.findByTradeDate(TRADE_DATE)).thenReturn(new ScreenerUniverseSnapshot());
        when(stockMapper.findByTradeDate(TRADE_DATE)).thenReturn(List.of());

        // 返回一个空 rows 的快照会让上层抛「行情源返回了空的股票列表」，把一次本可正常降级的
        // 点击变成错误框。宁可当作「没有预取过」，退回实时。
        assertThat(service.snapshotFor(TRADE_DATE)).isEmpty();
    }

    @Test
    void nullTradeDatesAreAnsweredWithoutTouchingTheDatabase() {
        assertThat(service.snapshotFor(null)).isEmpty();
        assertThat(service.hasSnapshotFor(null)).isFalse();
        assertThat(service.hasPrefetchedOn(null)).isFalse();
        verifyNoInteractions(snapshotMapper);
    }

    @Test
    void theTwoGatesAskTwoDifferentQuestions() {
        when(snapshotMapper.countByTradeDate(TRADE_DATE)).thenReturn(1);
        when(snapshotMapper.countByPrefetchDate(TRADE_DATE)).thenReturn(0);

        // 交易日有数据、但今天没跑过预取——休市日就是这个形状：
        // 读侧该说「有」（能读），写侧该说「没有」（还得跑一次）
        assertThat(service.hasSnapshotFor(TRADE_DATE)).isTrue();
        assertThat(service.hasPrefetchedOn(TRADE_DATE)).isFalse();
    }

    // ================= 夹具 =================

    /**
     * 把 {@code StockRow} 的**每一个字段**都填上互不相同的值。
     *
     * <p>用反射遍历而不是手写 23 个 setter：这样有人往 {@code StockRow} 加字段、
     * 却忘了在 {@code toEntity}/{@code toRow} 里带上时，往返比对会直接红。
     * 手写的夹具做不到这一点——它只会用到自己写过的那几个字段。
     */
    private static StockRow fullRow(int seed) {
        StockRow row = new StockRow();
        int i = 0;
        for (Field f : StockRow.class.getDeclaredFields()) {
            f.setAccessible(true);
            Object value = switch (f.getType().getName()) {
                case "java.lang.String" -> f.getName() + "-" + seed;
                case "int" -> 1;                       // market 只接受 0/1
                case "boolean" -> true;
                case "java.math.BigDecimal" -> new BigDecimal(seed + "." + (i + 10));
                case "java.time.LocalDate" -> LocalDate.of(2010, 1, 1).plusDays(seed);
                default -> throw new IllegalStateException(
                        "StockRow 多了个字段，这个夹具得跟着改：" + f.getName() + " " + f.getType());
            };
            try {
                f.set(row, value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            i++;
        }
        return row;
    }

    private static MarketDataClient.UniverseSnapshot snapshot(List<StockRow> rows, int listed, int missing) {
        return new MarketDataClient.UniverseSnapshot(rows, listed, missing, TRADE_DATE,
                new BigDecimal("38300000000"));
    }

    @SuppressWarnings("unchecked")
    private List<ScreenerUniverseStock> capturedDetails() {
        ArgumentCaptor<List<ScreenerUniverseStock>> captor = ArgumentCaptor.forClass(List.class);
        verify(stockMapper).upsertBatch(captor.capture());
        // 超过一批的用例自己看 getAllValues；这条路径只有一批
        return captor.getAllValues().get(0);
    }

    private ScreenerUniverseSnapshot capturedHeader() {
        ArgumentCaptor<ScreenerUniverseSnapshot> captor =
                ArgumentCaptor.forClass(ScreenerUniverseSnapshot.class);
        verify(snapshotMapper).upsert(captor.capture());
        return captor.getValue();
    }
}