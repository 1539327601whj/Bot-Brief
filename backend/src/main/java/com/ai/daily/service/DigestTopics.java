package com.ai.daily.service;

import java.time.LocalTime;
import java.util.Locale;

/**
 * 特殊订阅主题：沿用全站已生成的原文，不走关键词短段落。
 */
public final class DigestTopics {

    public static final String AI_TECH = "AI科技";
    public static final String ETF = "纳指标普沪深300ETF";
    public static final LocalTime ETF_DISPLAY_TIME = LocalTime.of(18, 0);

    private DigestTopics() {
    }

    public static boolean isDigest(String topic) {
        return isAiTech(topic) || isEtf(topic);
    }

    public static boolean isAiTech(String topic) {
        String normalized = normalize(topic);
        return "ai科技".equals(normalized) || "科技".equals(normalized);
    }

    public static boolean isEtf(String topic) {
        String normalized = normalize(topic);
        return ETF.toLowerCase(Locale.ROOT).equals(normalized)
                || "etf".equals(normalized)
                || "市场观察".equals(normalized);
    }

    public static String publicEditionFor(String topic, LocalTime time) {
        if (isEtf(topic)) return "market_watch_evening";
        if (isAiTech(topic)) return ReportWindows.digestStyle(ReportWindows.of(time));
        return null;
    }

    /** ETF 只能订傍晚窗口；更早的时刻收到 18:00。已在傍晚的时刻原样保留。 */
    public static LocalTime clampDisplayTime(String topic, LocalTime time) {
        if (!isEtf(topic)) return time;
        if (time != null && ReportWindows.W18_24.equals(ReportWindows.of(time))) return time;
        return ETF_DISPLAY_TIME;
    }

    private static String normalize(String topic) {
        return topic == null ? "" : topic.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }
}
