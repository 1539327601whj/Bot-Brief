package com.ai.daily.service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 订阅主题上的「我想看」。空着表示走系统默认检索和模板。
 */
public final class TopicIntents {

    public static final int MAX_CODE_POINTS = 120;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SPLIT = Pattern.compile("[；;]+");

    private TopicIntents() {
    }

    public static String normalize(String intent) {
        String clipped = clip(intent);
        if (intent != null && !intent.isBlank() && codePoints(intent.trim()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("每个主题的想法不能超过 " + MAX_CODE_POINTS + " 个字符");
        }
        return clipped;
    }

    public static String clip(String intent) {
        if (intent == null) return "";
        String normalized = WHITESPACE.matcher(intent.trim()).replaceAll(" ");
        if (normalized.isEmpty()) return "";
        int[] points = normalized.codePoints().toArray();
        if (points.length <= MAX_CODE_POINTS) return normalized;
        return new String(points, 0, MAX_CODE_POINTS);
    }

    public static boolean isBlank(String intent) {
        return clip(intent).isEmpty();
    }

    public static String merge(String left, String right) {
        Set<String> unique = new LinkedHashSet<>();
        addParts(unique, left);
        addParts(unique, right);
        return String.join("；", unique);
    }

    public static boolean usePublicDigest(String topic, String intent) {
        return DigestTopics.isDigest(topic) && isBlank(intent);
    }

    public static String planKey(String window, String topic, String intent) {
        String name = topic == null ? "" : topic.trim().toLowerCase(Locale.ROOT);
        String kind = usePublicDigest(topic, intent) ? "digest" : "section";
        return window + "|" + name + "|" + kind;
    }

    private static void addParts(Set<String> unique, String raw) {
        String clipped = clip(raw);
        if (clipped.isEmpty()) return;
        for (String part : SPLIT.split(clipped)) {
            String item = clip(part);
            if (!item.isEmpty()) unique.add(item);
        }
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }
}
