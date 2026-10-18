package com.ai.daily.warehouse;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 仓库 SQL 的契约测试。不连数据库，只钉住**那些一旦写错就很难发现的东西**：
 * upsert 必须幂等、旧的 {@code fetched_at} 不能覆盖新数据、批量要分片、
 * 读不到要返回空而不是 0。
 */
class WarehouseRepositorySqlTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final WarehouseRepository repository = new WarehouseRepository(jdbcTemplate);

    @Test
    void emptyInputDoesNotTouchTheDatabase() {
        repository.upsertPrices(null);
        repository.upsertPrices(List.of());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void priceUpsertIsIdempotentAndProtectsNewerRows() {
        List<WarehouseRows.PriceBar> rows = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            rows.add(bar(LocalDate.of(2020, 1, 1).plusDays(i)));
        }

        repository.upsertPrices(rows);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, times(3)).batchUpdate(sqlCaptor.capture(), rowsCaptor.capture());

        assertThat(sqlCaptor.getAllValues()).allSatisfy(sql -> assertThat(sql)
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("VALUES(fetched_at) >= fetched_at")
                .contains("GREATEST(fetched_at, VALUES(fetched_at))"));
        assertThat(rowsCaptor.getAllValues())
                .extracting(List::size)
                .containsExactly(500, 500, 200);
    }

    @Test
    void valuationUpsertAlsoProtectsNewerRows() {
        repository.upsertValuations(List.of(new WarehouseRows.ValuationPoint(
                "STOCK:600519", LocalDate.of(2026, 10, 17), new BigDecimal("22.5"),
                new BigDecimal("8.1"), null, new BigDecimal("2.3"), new BigDecimal("1500"),
                "eastmoney-valueanalysis", "eastmoney-valueanalysis", LocalDateTime.now())));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).batchUpdate(sql.capture(), any(List.class));
        assertThat(sql.getValue())
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("VALUES(fetched_at) >= fetched_at");
    }

    @Test
    void latestPricesReturnsAscendingOrder() {
        WarehouseRows.PriceBar oldest = bar(LocalDate.of(2026, 10, 15));
        WarehouseRows.PriceBar middle = bar(LocalDate.of(2026, 10, 16));
        WarehouseRows.PriceBar newest = bar(LocalDate.of(2026, 10, 17));

        // SQL 取最近 N 根，天然是倒序；上层要的是升序（算均线、画图都按时间正序）。
        when(jdbcTemplate.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<WarehouseRows.PriceBar>>any(),
                any(), any()))
                .thenReturn(List.of(newest, middle, oldest));

        List<WarehouseRows.PriceBar> result = repository.latestPrices("STOCK:600519", "qfq", 250);

        assertThat(result).extracting(WarehouseRows.PriceBar::tradeDate)
                .containsExactly(oldest.tradeDate(), middle.tradeDate(), newest.tradeDate());
    }

    @Test
    void financialsAreQueriedAsOfTheAvailableDateNotTheReportDate() {
        // available_from 是防前视偏差的关键：回看某一天时，只能用那天之前已经公告的财报。
        when(jdbcTemplate.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<WarehouseRows.FinancialReport>>any(),
                any(), any()))
                .thenReturn(List.of());

        repository.financialsAvailableAsOf("600519", LocalDate.of(2026, 10, 18), 8);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(), any());
        assertThat(sql.getValue())
                .contains("COALESCE(available_from, announce_date, report_date) <=")
                .contains("ORDER BY report_date DESC")
                .contains("LIMIT 8");
    }

    @Test
    void aMissingRowIsEmptyNotZero() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString()))
                .thenReturn(List.of());
        assertThat(repository.findInstrument("STOCK:000000")).isEmpty();
    }

    @Test
    void aNullCountIsReportedAsZeroOnlyForTheHealthCounter() {
        // 这里返回 0 是安全的：它只用于健康检查/页面上的「已缓存多少根」，
        // 不参与任何打分。业务字段一律 Optional/空列表。
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString()))
                .thenReturn(null);
        assertThat(repository.countPrices("STOCK:600519")).isZero();
    }

    @Test
    void jobFailureUsesTimestampAddInsteadOfIntervalPlaceholder() {
        repository.markJobFailed("STOCK_DETAIL:600519:2026-10-18", "boom", 300);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(), any(), any());
        assertThat(sql.getValue())
                .contains("TIMESTAMPADD(SECOND, ?, NOW(3))")
                .doesNotContain("INTERVAL ? SECOND");
    }

    @Test
    void enqueueKeepsDoneJobsDoneAndRetriesFailedOnes() {
        repository.enqueueJob("STOCK_DETAIL:600519:2026-10-18", "STOCK_DETAIL", 100,
                "STOCK:600519", "{}");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(), any(), any(), any(), any());
        assertThat(sql.getValue())
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("WHEN status = 'RUNNING' THEN status")
                .contains("WHEN status = 'DONE' THEN status")
                .contains("ELSE 'PENDING'");
    }

    @Test
    void reserveIsNotCalledWhenThereIsNothingToWrite() {
        // 防御性：空列表不能落一条空 batch（MySQL 会报 “No values specified”）。
        repository.upsertValuations(List.of());
        repository.upsertFinancials(List.of());
        repository.upsertMoneyflows(List.of());
        verify(jdbcTemplate, never()).batchUpdate(anyString(), any(List.class));
    }

    private static WarehouseRows.PriceBar bar(LocalDate date) {
        return new WarehouseRows.PriceBar(
                "STOCK:600519", date, "qfq",
                new BigDecimal("1500.00"), new BigDecimal("1520.00"),
                new BigDecimal("1490.00"), new BigDecimal("1510.00"),
                new BigDecimal("100000"), new BigDecimal("151000000"),
                new BigDecimal("0.8"), new BigDecimal("1.2"),
                "eastmoney", LocalDateTime.of(2026, 10, 18, 18, 0));
    }
}
