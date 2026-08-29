package com.ai.daily.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSnippetExtractorTest {

    @Test
    void prefersKeywordParagraphsOverPreambleAndUnrelatedSections() {
        String content = """
                # 导语

                今天市场整体平稳，指数小幅波动。

                ## AI 大模型

                OpenAI 发布 GPT-5。DeepSeek 更新推理接口。

                ## ETF

                沪深300ETF 少买，PE 分位 71%。
                """;

        String snippet = ChatSnippetExtractor.extract(content, List.of("大模型", "gpt"), 180);

        assertThat(snippet).contains("GPT-5", "DeepSeek");
        assertThat(snippet).doesNotContain("少买");
    }
}
