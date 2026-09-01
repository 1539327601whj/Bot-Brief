package com.ai.daily.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicIntentsTest {

    @Test
    void emptyIntentUsesPublicDigestForTechAndEtf() {
        assertThat(TopicIntents.usePublicDigest("AI科技", "")).isTrue();
        assertThat(TopicIntents.usePublicDigest("AI科技", "只要芯片和航天")).isFalse();
        assertThat(TopicIntents.usePublicDigest("数据库", "")).isFalse();
    }

    @Test
    void mergesUniqueIntentsAndRejectsOverlongOnSave() {
        assertThat(TopicIntents.merge("只要芯片", "只要芯片；只要航天")).isEqualTo("只要芯片；只要航天");
        assertThatThrownBy(() -> TopicIntents.normalize("长".repeat(121)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("120");
        assertThat(TopicIntents.clip("长".repeat(121)).codePointCount(0, TopicIntents.clip("长".repeat(121)).length()))
                .isEqualTo(120);
    }
}
