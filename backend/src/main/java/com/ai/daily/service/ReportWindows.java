package com.ai.daily.service;

import java.time.LocalTime;
import java.util.List;

public final class ReportWindows {

    public static final String W00_06 = "w00_06";
    public static final String W06_12 = "w06_12";
    public static final String W12_18 = "w12_18";
    public static final String W18_24 = "w18_24";
    public static final String PERSONAL = "personal";

    public static final List<String> ALL = List.of(W00_06, W06_12, W12_18, W18_24);

    private ReportWindows() {}

    public static String of(LocalTime time) {
        if (time == null) return W06_12;
        int hour = time.getHour();
        if (hour < 6) return W00_06;
        if (hour < 12) return W06_12;
        if (hour < 18) return W12_18;
        return W18_24;
    }

    public static boolean isGenerationWindow(String value) {
        return ALL.contains(value);
    }

    public static LocalTime publicDisplayTime(String edition) {
        if ("morning".equals(edition)) return LocalTime.of(8, 0);
        if ("evening".equals(edition)) return LocalTime.of(20, 0);
        if (edition != null && edition.startsWith("market_watch")) return LocalTime.of(18, 0);
        return LocalTime.of(0, 0);
    }

    public static String format(LocalTime time) {
        if (time == null) return null;
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    public static String digestStyle(String window) {
        if (W12_18.equals(window) || W18_24.equals(window)) return "evening";
        return "morning";
    }

    public static LocalTime parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("推送时间不能为空");
        }
        if (!value.matches("\\d{2}:\\d{2}(:\\d{2})?")) {
            throw new IllegalArgumentException("推送时间格式无效");
        }
        return value.length() == 5 ? LocalTime.parse(value + ":00") : LocalTime.parse(value);
    }
}
