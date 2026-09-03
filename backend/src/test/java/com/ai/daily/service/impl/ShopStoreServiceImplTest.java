package com.ai.daily.service.impl;

import com.ai.daily.entity.ShopStore;
import com.ai.daily.mapper.ShopStoreMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopStoreServiceImplTest {

    @Test
    void acceptsJingdongAliasesAsJd() {
        assertThat(ShopStoreServiceImpl.normalizePlatform("jd")).isEqualTo("jd");
        assertThat(ShopStoreServiceImpl.normalizePlatform("JD")).isEqualTo("jd");
        assertThat(ShopStoreServiceImpl.normalizePlatform("jingdong")).isEqualTo("jd");
        assertThat(ShopStoreServiceImpl.normalizePlatform("京东")).isEqualTo("jd");
    }

    @Test
    void acceptsExistingShopPlatforms() {
        assertThat(ShopStoreServiceImpl.normalizePlatform("taobao")).isEqualTo("taobao");
        assertThat(ShopStoreServiceImpl.normalizePlatform("douyin")).isEqualTo("douyin");
        assertThat(ShopStoreServiceImpl.normalizePlatform(null)).isEqualTo("manual");
        assertThat(ShopStoreServiceImpl.normalizePlatform("  ")).isEqualTo("manual");
    }

    @Test
    void rejectsUnknownPlatform() {
        assertThatThrownBy(() -> ShopStoreServiceImpl.normalizePlatform("amazon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不支持的店铺平台");
    }

    @Test
    void disableKeepsAtLeastOneStore() {
        ShopStoreServiceImpl service = serviceWith(store(1L), 1L);

        assertThatThrownBy(() -> service.disableForUser(7L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少保留一家店铺");
        verify(service.getBaseMapper(), never()).updateById(any());
    }

    @Test
    void disableRejectsUnknownStore() {
        ShopStoreServiceImpl service = serviceWith(null, 2L);

        assertThatThrownBy(() -> service.disableForUser(7L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("店铺不存在");
        verify(service.getBaseMapper(), never()).updateById(any());
    }

    @Test
    void disableTurnsStoreOffWhenAnotherRemains() {
        ShopStore store = store(3L);
        ShopStoreServiceImpl service = serviceWith(store, 2L);
        when(service.getBaseMapper().updateById(store)).thenReturn(1);

        service.disableForUser(7L, 3L);

        assertThat(store.getEnabled()).isFalse();
        verify(service.getBaseMapper()).updateById(store);
    }

    private static ShopStore store(Long id) {
        ShopStore store = new ShopStore();
        store.setId(id);
        store.setUserId(7L);
        store.setEnabled(true);
        store.setStoreName("京东旗舰店");
        return store;
    }

    private static ShopStoreServiceImpl serviceWith(ShopStore store, Long enabledCount) {
        ShopStoreMapper mapper = mock(ShopStoreMapper.class);
        ShopStoreServiceImpl service = spy(new ShopStoreServiceImpl());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        doReturn(store).when(service).getForUser(any(), any());
        doReturn(enabledCount).when(service).count(any());
        return service;
    }
}
