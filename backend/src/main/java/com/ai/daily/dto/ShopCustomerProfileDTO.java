package com.ai.daily.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ShopCustomerProfileDTO {
    private Integer newCustomerCount;
    private Integer repeatCustomerCount;
    private Integer highValueCustomerCount;
    private BigDecimal avgCustomerValue;
    private BigDecimal femaleRatio;
    private BigDecimal maleRatio;
    private List<NameValueDTO> topRegions;
    private List<NameValueDTO> ageDistribution;
}
