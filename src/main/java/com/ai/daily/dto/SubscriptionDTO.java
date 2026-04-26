package com.ai.daily.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 订阅配置 DTO
 */
@Data
public class SubscriptionDTO {

    /** 接收时间：morning / evening / both */
    @NotNull(message = "接收时间不能为空")
    private String receiveTime;

    /** 偏好领域列表 */
    private List<String> preferenceFields;

    /** 是否启用 */
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;
}