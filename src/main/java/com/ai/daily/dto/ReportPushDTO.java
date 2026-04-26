package com.ai.daily.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 简报接收 DTO（供 GitHub Actions 推送使用）
 */
@Data
public class ReportPushDTO {

    @NotBlank(message = "版本不能为空")
    private String edition;

    @NotBlank(message = "标题不能为空")
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;

    /** 摘要，可选 */
    private String summary;

    /** GitHub Actions run ID，可选 */
    private String runId;
}
