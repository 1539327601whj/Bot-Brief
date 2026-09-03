package com.ai.daily.service.impl;

import com.ai.daily.dto.ShopOverviewDTO;
import com.ai.daily.entity.ShopSalesDaily;
import com.ai.daily.entity.ShopStore;
import com.ai.daily.mapper.ShopAiReportMapper;
import com.ai.daily.mapper.ShopCustomerSummaryMapper;
import com.ai.daily.mapper.ShopProductMapper;
import com.ai.daily.mapper.ShopProductSalesDailyMapper;
import com.ai.daily.mapper.ShopSalesDailyMapper;
import com.ai.daily.service.ShopStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopAnalyticsServiceImplTest {

    private ShopStoreService stores;
    private ShopSalesDailyMapper sales;
    private ShopProductMapper products;
    private ShopProductSalesDailyMapper productSales;
    private ShopAiReportMapper reports;
    private ShopAnalyticsServiceImpl service;

    @BeforeEach
    void setUp() {
        stores = mock(ShopStoreService.class);
        sales = mock(ShopSalesDailyMapper.class);
        products = mock(ShopProductMapper.class);
        productSales = mock(ShopProductSalesDailyMapper.class);
        reports = mock(ShopAiReportMapper.class);
        service = new ShopAnalyticsServiceImpl(
                stores,
                products,
                sales,
                productSales,
                mock(ShopCustomerSummaryMapper.class),
                reports,
                new ObjectMapper());
        ShopStore store = new ShopStore();
        store.setId(3L);
        store.setUserId(7L);
        when(stores.getForUser(7L, 3L)).thenReturn(store);
        when(products.selectList(any())).thenReturn(List.of());
        when(productSales.selectList(any())).thenReturn(List.of());
    }

    @Test
    void overviewUsesLatestSalesDayInsteadOfPretendingItIsToday() {
        LocalDate latestDay = LocalDate.now().minusDays(2);
        ShopSalesDaily row = new ShopSalesDaily();
        row.setStatDate(latestDay);
        row.setSalesAmount(new BigDecimal("10748.00"));
        row.setOrderCount(86);
        row.setBuyerCount(80);
        when(sales.selectOne(any())).thenReturn(row);
        when(sales.selectList(any())).thenReturn(List.of(row));

        ShopOverviewDTO overview = service.getOverview(7L, 3L, 7);

        assertThat(overview.getAnalysisDate()).isEqualTo(latestDay);
        assertThat(overview.getAnalysisDate()).isNotEqualTo(LocalDate.now());
        assertThat(overview.getToday().getSalesAmount()).isEqualByComparingTo("10748.00");
        assertThat(overview.getRequestedRange()).isEqualTo(7);
    }

    @Test
    void overviewWithoutSalesDoesNotInventABusyDay() {
        when(sales.selectOne(any())).thenReturn(null);
        when(sales.selectList(any())).thenReturn(List.of());

        ShopOverviewDTO overview = service.getOverview(7L, 3L, 7);

        assertThat(overview.getEffectiveDays()).isZero();
        assertThat(overview.getToday().getOrderCount()).isZero();
        assertThat(overview.getToday().getSalesAmount()).isEqualByComparingTo("0");
    }

    @Test
    void generateDemoDataRefusesToOverwriteExistingSales() {
        when(sales.selectCount(any())).thenReturn(4L);

        assertThatThrownBy(() -> service.generateDemoData(7L, 3L, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已有经营数据");
        verify(products, never()).insert(any());
        verify(sales, never()).insert(any());
    }

    @Test
    void generateAiReportWithoutSalesDoesNotWriteAFakeDaily() {
        when(sales.selectOne(any())).thenReturn(null);
        when(sales.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.generateAiReport(7L, 3L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可分析的销售日");
        verify(reports, never()).insert(any());
        verify(reports, never()).updateById(any());
    }
}
