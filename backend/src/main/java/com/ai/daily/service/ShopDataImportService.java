package com.ai.daily.service;

import com.ai.daily.dto.ShopImportConfirmDTO;
import com.ai.daily.dto.ShopImportPreviewDTO;
import org.springframework.web.multipart.MultipartFile;

public interface ShopDataImportService {
    String template(String type);

    ShopImportPreviewDTO preview(Long userId, Long storeId, String type, MultipartFile file);

    ShopImportConfirmDTO confirm(Long userId, Long storeId, String type, String fileHash, MultipartFile file);
}
