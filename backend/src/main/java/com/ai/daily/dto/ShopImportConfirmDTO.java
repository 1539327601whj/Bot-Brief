package com.ai.daily.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ShopImportConfirmDTO {
    private String type;
    private Integer importedRows;
}
