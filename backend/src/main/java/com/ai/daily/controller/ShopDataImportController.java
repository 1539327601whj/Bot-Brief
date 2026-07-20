package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.dto.ShopImportConfirmDTO;
import com.ai.daily.dto.ShopImportPreviewDTO;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.ShopDataImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/shop/import")
@RequiredArgsConstructor
public class ShopDataImportController {

    private final ShopDataImportService shopDataImportService;

    @GetMapping("/templates/{type}")
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable String type) {
        String content = shopDataImportService.template(type);
        String filename = "shop_" + type.toLowerCase() + "_template.csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        return ResponseEntity.ok().headers(headers).body(content.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ShopImportPreviewDTO> preview(@RequestParam Long storeId,
                                                @RequestParam String type,
                                                @RequestPart MultipartFile file) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok(shopDataImportService.preview(userId, storeId, type, file));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping(value = "/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ShopImportConfirmDTO> confirm(@RequestParam Long storeId,
                                                @RequestParam String type,
                                                @RequestParam String fileHash,
                                                @RequestPart MultipartFile file) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok("数据导入成功", shopDataImportService.confirm(userId, storeId, type, fileHash, file));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }
}
