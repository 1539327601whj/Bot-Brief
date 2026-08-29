package com.ai.daily.service;

import java.time.LocalDate;

/**
 * 订阅星期范围：1=周一 … 7=周日。未写明的旧配置按每天处理。
 */
public final class SubscriptionWeekdays {

    public static final int MONDAY = 1;
    public static final int SUNDAY = 7;
    public static final int DEFAULT_FROM = 1;
    public static final int DEFAULT_TO = 5;

    private SubscriptionWeekdays() {}

    public static int[] rangeOf(Integer from, Integer to) {
        if (from == null && to == null) {
            return new int[]{MONDAY, SUNDAY};
        }
        return new int[]{clamp(from, DEFAULT_FROM), clamp(to, DEFAULT_TO)};
    }

    public static int clamp(Integer value, int fallback) {
        if (value == null || value < MONDAY || value > SUNDAY) return fallback;
        return value;
    }

    public static boolean covers(Integer from, Integer to, LocalDate date) {
        if (date == null) return true;
        int[] range = rangeOf(from, to);
        return contains(range[0], range[1], date.getDayOfWeek().getValue());
    }

    public static boolean contains(int from, int to, int isoDay) {
        if (from <= to) return isoDay >= from && isoDay <= to;
        return isoDay >= from || isoDay <= to;
    }

    public static LocalDate nextOnOrAfter(LocalDate start, Integer from, Integer to) {
        LocalDate cursor = start == null ? LocalDate.now() : start;
        for (int offset = 0; offset < 7; offset++) {
            LocalDate candidate = cursor.plusDays(offset);
            if (covers(from, to, candidate)) return candidate;
        }
        return cursor;
    }
}
