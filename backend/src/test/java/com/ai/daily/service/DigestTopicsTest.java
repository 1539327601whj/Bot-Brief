package com.ai.daily.service;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class DigestTopicsTest {

    @Test
    void mapsMorningAndEveningSlotsToPublicEditions() {
        assertThat(DigestTopics.isTech("科技")).isTrue();
        assertThat(DigestTopics.isTech(" 科技 ")).isTrue();
        assertThat(DigestTopics.isTech("AI大模型")).isFalse();
        assertThat(DigestTopics.publicEditionFor(LocalTime.of(8, 0))).isEqualTo("morning");
        assertThat(DigestTopics.publicEditionFor(LocalTime.of(20, 0))).isEqualTo("evening");
    }
}
