package com.ai.daily.service;

import java.util.List;

public interface AiClientService {

    /**
     * 既有调用点用的默认读超时。**刻意留成 {@code int} 常量而不是改那条方法**：
     * 对话与内容生成原来跑在「Apache 默认 = 无限超时」上，把它们的超时一并收紧要单独评估，
     * 不该搭着「代码查询要个超时」这趟车一起改。
     */
    int DEFAULT_TIMEOUT_MS = 120_000;

    String chat(String prompt);

    /**
     * 带读超时的调用。**代码查询的深度解读走这条**：那个端点前面还有四路新闻外呼，
     * 大模型这一段无超时的话，一次卡住会把整页拖到浏览器先超时——
     * 而页面上连「是哪一段慢」都看不出来。
     *
     * @param timeoutMs 连接与读取的超时上限，毫秒
     */
    String chat(List<AiMessage> messages, double temperature, int maxTokens, int timeoutMs);

    /** 不带超时的重载走 {@link #DEFAULT_TIMEOUT_MS}，既有行为不变。 */
    default String chat(List<AiMessage> messages, double temperature, int maxTokens) {
        return chat(messages, temperature, maxTokens, DEFAULT_TIMEOUT_MS);
    }

    record AiMessage(String role, String content) {
    }
}
