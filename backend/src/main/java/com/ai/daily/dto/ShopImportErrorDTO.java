package com.ai.daily.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ShopImportErrorDTO {
    private Long row;
    private String field;
    private String message;
}
