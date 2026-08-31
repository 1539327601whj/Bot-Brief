package com.ai.daily.service;

import java.time.LocalTime;

/**
 * 科技是特殊订阅主题：内容沿用全站早晚报（原 prompt），不走关键词短段落。
 */
public final class DigestTopics {

    public static final String TECH = "科技";

    private DigestTopics() {
    }

    public static boolean isTech(String topic) {
        return topic != null && TECH.equalsIgnoreCase(topic.trim());
    }

    public static String publicEditionFor(LocalTime time) {
        return ReportWindows.digestStyle(ReportWindows.of(time));
    }
}
