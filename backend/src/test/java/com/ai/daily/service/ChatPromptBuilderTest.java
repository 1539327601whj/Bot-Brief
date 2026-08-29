package com.ai.daily.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPromptBuilderTest {

    @Test
    void systemPromptKeepsTechAndMarketSeparated() {
        assertThat(ChatPromptBuilder.systemPrompt())
                .contains("不要把 ETF")
                .contains("市场观察");
    }

    @Test
    void userMessageIncludesNumberedMaterialsAndQuestion() {
        String prompt = ChatPromptBuilder.userMessage(
                "最近有哪些 AI 大模型更新？",
                List.of(ChatPromptBuilder.material(1, "2026-08-29 · AI大模型", "DeepSeek 更新接口。")));

        assertThat(prompt)
                .contains("[1] 2026-08-29 · AI大模型")
                .contains("DeepSeek 更新接口。")
                .contains("【用户问题】")
                .contains("最近有哪些 AI 大模型更新？");
    }
}
