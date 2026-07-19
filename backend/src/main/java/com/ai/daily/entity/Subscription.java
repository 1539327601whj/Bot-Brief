package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 订阅配置实体（一个用户一条）
 */
@Data
@TableName("subscription")
public class Subscription {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户 */
    private Long userId;

    /** 保留字段（morning/evening/both）用于向后兼容，前端不再用它作为主判断 */
    private String receiveTime;

    /** 偏好领域（JSON 数组格式） */
    private String preferenceFields;

    /** 早/晚间版按主题配置的推送时间（JSON） */
    private String topicSchedules;

    /** 总开关：1启用 0暂停 */
    private Boolean enabled;

    /** 是否接收早间版 */
    private Boolean morningEnabled;

    /** 早间版推送时间（本地时间） */
    private LocalTime morningTime;

    /** 是否接收晚间版 */
    private Boolean eveningEnabled;

    /** 晚间版推送时间 */
    private LocalTime eveningTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
