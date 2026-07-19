package com.ai.daily.service.impl;

import com.ai.daily.entity.ShopStore;
import com.ai.daily.mapper.ShopStoreMapper;
import com.ai.daily.service.ShopStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ShopStoreServiceImpl extends ServiceImpl<ShopStoreMapper, ShopStore> implements ShopStoreService {

    @Override
    public List<ShopStore> listForUser(Long userId) {
        return this.list(new LambdaQueryWrapper<ShopStore>()
                .eq(ShopStore::getUserId, userId)
                .orderByDesc(ShopStore::getUpdatedAt));
    }

    @Override
    public ShopStore createForUser(Long userId, String platform, String storeName) {
        ShopStore store = new ShopStore();
        store.setUserId(userId);
        store.setPlatform(platform == null || platform.isBlank() ? "manual" : platform);
        store.setStoreName(storeName == null || storeName.isBlank() ? "我的店铺" : storeName);
        store.setEnabled(true);
        this.save(store);
        return store;
    }

    @Override
    public ShopStore getForUser(Long userId, Long storeId) {
        if (storeId == null) return null;
        return this.getOne(new LambdaQueryWrapper<ShopStore>()
                .eq(ShopStore::getUserId, userId)
                .eq(ShopStore::getId, storeId)
                .last("LIMIT 1"));
    }

    @Override
    public ShopStore getOrCreateDefault(Long userId) {
        ShopStore store = this.getOne(new LambdaQueryWrapper<ShopStore>()
                .eq(ShopStore::getUserId, userId)
                .orderByAsc(ShopStore::getId)
                .last("LIMIT 1"));
        if (store != null) return store;
        return createForUser(userId, "manual", "我的店铺");
    }
}
