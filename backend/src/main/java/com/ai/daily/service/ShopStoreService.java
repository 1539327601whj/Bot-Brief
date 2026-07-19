package com.ai.daily.service;

import com.ai.daily.entity.ShopStore;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface ShopStoreService extends IService<ShopStore> {
    List<ShopStore> listForUser(Long userId);

    ShopStore createForUser(Long userId, String platform, String storeName);

    ShopStore getForUser(Long userId, Long storeId);

    ShopStore getOrCreateDefault(Long userId);
}
