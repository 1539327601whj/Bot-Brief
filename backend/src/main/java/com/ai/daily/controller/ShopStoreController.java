package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.dto.ShopStoreDTO;
import com.ai.daily.entity.ShopStore;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.ShopStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/shop/stores")
@RequiredArgsConstructor
public class ShopStoreController {

    private final ShopStoreService shopStoreService;

    @GetMapping
    public Result<List<ShopStoreDTO>> listStores() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        return Result.ok(shopStoreService.listForUser(userId).stream().map(this::toDTO).toList());
    }

    @PostMapping
    public Result<ShopStoreDTO> createStore(@RequestBody ShopStoreDTO dto) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        ShopStore store = shopStoreService.createForUser(userId, dto.getPlatform(), dto.getStoreName());
        return Result.ok("店铺已创建", toDTO(store));
    }

    private ShopStoreDTO toDTO(ShopStore store) {
        ShopStoreDTO dto = new ShopStoreDTO();
        dto.setId(store.getId());
        dto.setPlatform(store.getPlatform());
        dto.setStoreName(store.getStoreName());
        dto.setEnabled(store.getEnabled());
        return dto;
    }
}
