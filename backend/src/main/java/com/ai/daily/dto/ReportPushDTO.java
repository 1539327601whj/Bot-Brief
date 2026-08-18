package com.ai.daily.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 简报接收 DTO（供 GitHub Actions 推送使用）
 */
@Data
public class ReportPushDTO {

    @NotBlank(message = "版本不能为空")
    private String edition;

    @NotNull(message = "报告日期不能为空")
    private LocalDate reportDate;

    @NotBlank(message = "标题不能为空")
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;

    /** 摘要，可选 */
    private String summary;

    /** GitHub Actions run ID，可选 */
    private String runId;
}
