package com.ai.daily.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

    /** 早间版 Web 展示时间，范围 00:00-14:59，格式 "HH:mm" 或 "HH:mm:ss" */
    private String morningTime;

    /** 是否接收晚间版 */
    private Boolean eveningEnabled;

    /** 晚间版 Web 展示时间，范围 15:00-23:59 */
    private String eveningTime;

    /** 按主题列出的订阅（每条自带时刻和星期范围） */
    private TopicSchedulesDTO topicSchedules;

    @Data
    public static class TopicSchedulesDTO {
        private List<TopicScheduleItemDTO> items;
        /** 旧版早/晚分组，仅用于读取兼容 */
        private List<TopicScheduleItemDTO> morning;
        private List<TopicScheduleItemDTO> evening;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TopicScheduleItemDTO {
        private String topic;
        private Boolean enabled;
        /** HH:mm，网页显示与渠道推送使用同一时刻 */
        private String time;
        /** 1=周一 … 7=周日，闭区间；可跨周末，如 6→1 表示周六至周一 */
        private Integer weekdayFrom;
        private Integer weekdayTo;
        private List<Long> channelIds;
        /** 用户对这个主题想看的范围；空着走系统默认 */
        private String intent;
    }
}
