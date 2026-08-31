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
