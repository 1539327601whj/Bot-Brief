package com.ai.daily.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
