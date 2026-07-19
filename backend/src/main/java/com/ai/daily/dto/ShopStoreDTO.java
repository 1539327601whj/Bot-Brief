package com.ai.daily.dto;

import lombok.Data;

@Data
public class ShopStoreDTO {
    private Long id;
    private String platform;
    private String storeName;
    private Boolean enabled;
}
