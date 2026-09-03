package com.ai.daily.service.impl;

import com.ai.daily.entity.ShopStore;
import com.ai.daily.mapper.ShopStoreMapper;
import com.ai.daily.service.ShopStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ShopStoreServiceImpl extends ServiceImpl<ShopStoreMapper, ShopStore> implements ShopStoreService {

    private static final Set<String> PLATFORMS = Set.of(
            "manual", "taobao", "jd", "douyin", "wechat_shop", "pdd", "kuaishou");

    @Override
    public List<ShopStore> listForUser(Long userId) {
        return this.list(new LambdaQueryWrapper<ShopStore>()
                .eq(ShopStore::getUserId, userId)
                .eq(ShopStore::getEnabled, true)
                .orderByDesc(ShopStore::getUpdatedAt));
    }

    @Override
    public ShopStore createForUser(Long userId, String platform, String storeName) {
        ShopStore store = new ShopStore();
        store.setUserId(userId);
        store.setPlatform(normalizePlatform(platform));
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
                .eq(ShopStore::getEnabled, true)
                .last("LIMIT 1"));
    }

    @Override
    public ShopStore getOrCreateDefault(Long userId) {
        ShopStore store = this.getOne(new LambdaQueryWrapper<ShopStore>()
                .eq(ShopStore::getUserId, userId)
                .eq(ShopStore::getEnabled, true)
                .orderByAsc(ShopStore::getId)
                .last("LIMIT 1"));
        if (store != null) return store;
        return createForUser(userId, "manual", "我的店铺");
    }

    @Override
    public void disableForUser(Long userId, Long storeId) {
        ShopStore store = getForUser(userId, storeId);
        if (store == null) throw new IllegalArgumentException("店铺不存在");
        long enabled = this.count(new LambdaQueryWrapper<ShopStore>()
                .eq(ShopStore::getUserId, userId)
                .eq(ShopStore::getEnabled, true));
        if (enabled <= 1) throw new IllegalArgumentException("至少保留一家店铺");
        store.setEnabled(false);
        this.updateById(store);
    }

    static String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) return "manual";
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        if ("jingdong".equals(normalized) || "京东".equals(platform.trim())) return "jd";
        if (!PLATFORMS.contains(normalized)) {
            throw new IllegalArgumentException("不支持的店铺平台");
        }
        return normalized;
    }
}
