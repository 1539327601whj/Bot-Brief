package com.ai.daily.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class OpsDeliveryDTO {

    private Long reportId;
    private String edition;
    private LocalDate reportDate;
    private String channelType;
    private Boolean success;
    private String errorMessage;
}
