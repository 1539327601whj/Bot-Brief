package com.ai.daily.service.impl;

import com.ai.daily.dto.ShopImportConfirmDTO;
import com.ai.daily.dto.ShopImportPreviewDTO;
import com.ai.daily.entity.ShopProduct;
import com.ai.daily.entity.ShopSalesDaily;
import com.ai.daily.entity.ShopStore;
import com.ai.daily.mapper.ShopProductMapper;
import com.ai.daily.mapper.ShopProductSalesDailyMapper;
import com.ai.daily.mapper.ShopSalesDailyMapper;
import com.ai.daily.service.ShopStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopDataImportServiceImplTest {

    private ShopStoreService stores;
    private ShopProductMapper products;
    private ShopSalesDailyMapper sales;
    private ShopDataImportServiceImpl service;

    @BeforeEach
    void setUp() {
        stores = mock(ShopStoreService.class);
        products = mock(ShopProductMapper.class);
        sales = mock(ShopSalesDailyMapper.class);
        service = new ShopDataImportServiceImpl(
                stores, products, sales, mock(ShopProductSalesDailyMapper.class));
        ShopStore store = new ShopStore();
        store.setId(3L);
        store.setUserId(7L);
        store.setPlatform("jd");
        when(stores.getForUser(7L, 3L)).thenReturn(store);
    }

    @Test
    void previewAcceptsValidProductCsv() {
        ShopImportPreviewDTO preview = service.preview(7L, 3L, "PRODUCT", csv(
                "external_product_id,product_name,category,price,stock\nSKU-001,京东示例,女装,99.00,100\n"));
        assertThat(preview.getValidRows()).isEqualTo(1);
        assertThat(preview.getErrors()).isEmpty();
        assertThat(preview.getFileHash()).isNotBlank();
    }

    @Test
    void previewRejectsWrongHeader() {
        ShopImportPreviewDTO preview = service.preview(7L, 3L, "PRODUCT", csv("name,price\n鞋,10\n"));
        assertThat(preview.getValidRows()).isZero();
        assertThat(preview.getErrors()).extracting("message")
                .anyMatch(message -> message.toString().contains("表头必须为"));
    }

    @Test
    void confirmRejectsHashMismatch() {
        assertThatThrownBy(() -> service.confirm(7L, 3L, "PRODUCT", "deadbeef", csv(
                "external_product_id,product_name,category,price,stock\nSKU-001,京东示例,女装,99.00,100\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件与预览时不一致");
    }

    @Test
    void productDailyRequiresExistingSku() {
        when(products.selectOne(any())).thenReturn(null);
        ShopImportPreviewDTO preview = service.preview(7L, 3L, "PRODUCT_DAILY", csv(
                "external_product_id,stat_date,sales_amount,order_count,quantity_sold,stock\n"
                        + "SKU-404,2026-08-30,1980.00,18,20,80\n"));
        assertThat(preview.getErrors()).extracting("message")
                .contains("未找到该商品，请先导入商品基础数据");
    }

    @Test
    void previewRejectsInvalidUtf8() {
        byte[] gbk = ("external_product_id,product_name,category,price,stock\n"
                + "SKU-001,\u6d4b\u8bd5,女装,99.00,100\n").getBytes(Charset.forName("GBK"));
        MockMultipartFile file = new MockMultipartFile("file", "shop.csv", "text/csv", gbk);
        assertThatThrownBy(() -> service.preview(7L, 3L, "PRODUCT", file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void previewRejectsFutureDate() {
        String tomorrow = LocalDate.now().plusDays(1).toString();
        ShopImportPreviewDTO preview = service.preview(7L, 3L, "STORE_DAILY", csv(
                "stat_date,sales_amount,order_count,buyer_count,refund_amount\n"
                        + tomorrow + ",10748.00,86,80,320.00\n"));
        assertThat(preview.getValidRows()).isZero();
        assertThat(preview.getErrors()).extracting("message").contains("不能导入未来日期");
    }

    @Test
    void confirmWritesProductWhenPreviewHashMatches() {
        when(products.selectOne(any())).thenReturn(null);
        MockMultipartFile file = csv(
                "external_product_id,product_name,category,price,stock\nSKU-001,京东示例,女装,99.00,100\n");
        ShopImportPreviewDTO preview = service.preview(7L, 3L, "PRODUCT", file);

        ShopImportConfirmDTO confirmed = service.confirm(7L, 3L, "PRODUCT", preview.getFileHash(), file);

        assertThat(confirmed.getType()).isEqualTo("PRODUCT");
        assertThat(confirmed.getImportedRows()).isEqualTo(1);
        verify(products).insert(any(ShopProduct.class));
    }

    @Test
    void confirmWritesStoreDailyForPastDate() {
        when(sales.selectOne(any())).thenReturn(null);
        MockMultipartFile file = csv(
                "stat_date,sales_amount,order_count,buyer_count,refund_amount\n"
                        + LocalDate.now().minusDays(1) + ",10748.00,86,80,320.00\n");
        ShopImportPreviewDTO preview = service.preview(7L, 3L, "STORE_DAILY", file);

        ShopImportConfirmDTO confirmed = service.confirm(7L, 3L, "STORE_DAILY", preview.getFileHash(), file);

        assertThat(confirmed.getImportedRows()).isEqualTo(1);
        verify(sales).insert(any(ShopSalesDaily.class));
    }

    @Test
    void confirmRejectsUnknownStore() {
        when(stores.getForUser(7L, 99L)).thenReturn(null);
        MockMultipartFile file = csv(
                "external_product_id,product_name,category,price,stock\nSKU-001,京东示例,女装,99.00,100\n");
        assertThatThrownBy(() -> service.confirm(7L, 99L, "PRODUCT", "any", file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("店铺不存在");
        verify(products, never()).insert(any());
    }

    @Test
    void productDailyAcceptsKnownSku() {
        ShopProduct product = new ShopProduct();
        product.setId(11L);
        product.setExternalProductId("SKU-001");
        when(products.selectOne(any())).thenReturn(product);
        ShopImportPreviewDTO preview = service.preview(7L, 3L, "PRODUCT_DAILY", csv(
                "external_product_id,stat_date,sales_amount,order_count,quantity_sold,stock\n"
                        + "SKU-001,2026-08-30,1980.00,18,20,80\n"));
        assertThat(preview.getValidRows()).isEqualTo(1);
        assertThat(preview.getErrors()).isEmpty();
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile(
                "file", "shop.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
