package com.ai.daily.dto;

import lombok.Data;

import java.util.List;

/**
 * 订阅配置 DTO
 */
@Data
public class SubscriptionDTO {

    /** 兼容字段：morning / evening / both（可留空，前端使用 morning_enabled/evening_enabled 主导） */
    private String receiveTime;

    /** 偏好领域列表 */
    private List<String> preferenceFields;

    /** 总开关 */
    private Boolean enabled;

    /** 是否接收早间版 */
    private Boolean morningEnabled;

    /** 早间版推送时间 "HH:mm" 或 "HH:mm:ss" */
    private String morningTime;

    /** 是否接收晚间版 */
    private Boolean eveningEnabled;

    /** 晚间版推送时间 */
    private String eveningTime;
}
