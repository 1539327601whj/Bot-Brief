package com.ai.daily.service.push;

import java.nio.charset.StandardCharsets;

final class PushContentLimits {

    private PushContentLimits() {}

    static String truncateToBytes(String text, int maxBytes) {
        if (text == null) return "";
        if (maxBytes <= 3) return "...";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        int limit = maxBytes - 3;
        int end = 0;
        int used = 0;
        while (end < text.length()) {
            int next = text.offsetByCodePoints(end, 1);
            int size = text.substring(end, next).getBytes(StandardCharsets.UTF_8).length;
            if (used + size > limit) break;
            used += size;
            end = next;
        }
        return text.substring(0, end) + "...";
    }
}
