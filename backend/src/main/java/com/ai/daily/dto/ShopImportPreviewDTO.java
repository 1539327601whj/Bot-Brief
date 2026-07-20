package com.ai.daily.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ShopImportPreviewDTO {
    private String type;
    private String fileHash;
    private Integer totalRows;
    private Integer validRows;
    private List<ShopImportErrorDTO> errors;
    private List<Map<String, String>> previewRows;
}
