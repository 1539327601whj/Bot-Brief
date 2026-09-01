package com.ai.daily.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SubscriptionTodayStatusDTO {

    private String date;
    private int leadMinutes;
    private int onTimeLeadMinutes;
    private String earliestOnTime;
    private PollerStatusDTO poller;
    private List<ItemStatusDTO> items = new ArrayList<>();

    @Data
    public static class PollerStatusDTO {
        private boolean healthy;
        private String lastSeen;
        private String detail;
    }

    @Data
    public static class ItemStatusDTO {
        private String topic;
        private String time;
        private String window;
        private String status;
        private String label;
        private String message;
    }
}
