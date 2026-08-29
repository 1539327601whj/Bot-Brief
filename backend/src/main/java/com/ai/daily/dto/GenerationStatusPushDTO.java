package com.ai.daily.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class GenerationStatusPushDTO {

    @NotBlank(message = "版本不能为空")
    private String edition;

    @NotNull(message = "报告日期不能为空")
    private LocalDate reportDate;

    @NotBlank(message = "主题不能为空")
    private String topic;

    @NotBlank(message = "状态不能为空")
    private String status;

    private String message;

    private String runId;
}
