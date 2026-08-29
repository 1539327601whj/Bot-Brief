package com.ai.daily.service;

import com.ai.daily.entity.TopicSection;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSectionRankerTest {

    @Test
    void prefersMatchingTopicOverUnrelatedRecentSection() {
        TopicSection models = section("AI大模型", "OpenAI 发布新模型，DeepSeek 更新接口。", LocalDate.of(2026, 8, 20));
        TopicSection database = section("数据库", "PostgreSQL 发布小版本。", LocalDate.of(2026, 8, 29));

        List<TopicSection> selected = ChatSectionRanker.select(
                List.of(database, models),
                List.of("大模型", "openai"),
                List.of("AI大模型"),
                3);

        assertThat(selected).extracting(TopicSection::getTopicKey).containsExactly("AI大模型");
    }

    private static TopicSection section(String topic, String content, LocalDate date) {
        TopicSection section = new TopicSection();
        section.setTopicKey(topic);
        section.setTitle("【" + topic + "】");
        section.setContent(content);
        section.setSummary(content);
        section.setSectionDate(date);
        section.setEdition("w06_12");
        return section;
    }
}
