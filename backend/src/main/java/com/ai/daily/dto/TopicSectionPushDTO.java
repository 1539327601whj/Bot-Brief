package com.ai.daily.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class TopicSectionPushDTO {

    @NotBlank(message = "版本不能为空")
    private String edition;

    @NotNull(message = "报告日期不能为空")
    private LocalDate reportDate;

    @NotBlank(message = "主题不能为空")
    private String topic;

    @NotBlank(message = "标题不能为空")
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;

    private String summary;

    private String runId;
}
