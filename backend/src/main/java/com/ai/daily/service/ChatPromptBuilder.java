package com.ai.daily.service;

import java.util.List;

public final class ChatPromptBuilder {

    static final String SYSTEM = """
            你是 BriefMind 简报助手，根据检索到的简报和主题段落回答问题。
            规则：
            1. 只依据材料作答，可综合多份来源，按时间从新到旧归纳。
            2. 科技、大模型、开源、安全问题只用 AI 早报、晚报、个人简报和主题段；不要把 ETF 或市场观察当成科技资讯。
            3. 行情、估值、仓位、ETF 问题只用市场观察简报。
            4. 材料不足时直接说明简报里没有，不要编造日期、产品名或数字。
            5. 先给一两句结论，再列 3 到 6 条要点；能对应到来源编号时写 [1]、[2]。
            6. 使用简体中文，简洁、可核对。
            """;

    private ChatPromptBuilder() {
    }

    public static String systemPrompt() {
        return SYSTEM;
    }

    public static String userMessage(String question, List<String> materials) {
        StringBuilder builder = new StringBuilder();
        builder.append("【检索材料】\n");
        if (materials == null || materials.isEmpty()) {
            builder.append("（没有检索到相关材料）\n");
        } else {
            for (String material : materials) {
                if (material == null || material.isBlank()) continue;
                builder.append(material.strip()).append("\n\n");
            }
        }
        builder.append("【用户问题】\n").append(question == null ? "" : question.strip());
        return builder.toString();
    }

    public static String material(int index, String heading, String body) {
        return "[" + index + "] " + (heading == null ? "" : heading) + "\n" + (body == null ? "" : body);
    }
}
