package com.ai.daily.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class ShopAiReportDTO {
    private Long id;
    private LocalDate reportDate;
    private String title;
    private String summary;
    private String content;
    private String riskLevel;
    private String generatedBy;
    private LocalDateTime createdAt;
}
