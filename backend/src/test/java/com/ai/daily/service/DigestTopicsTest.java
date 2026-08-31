package com.ai.daily.service;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class DigestTopicsTest {

    @Test
    void mapsAiTechAndEtfToPublicEditions() {
        assertThat(DigestTopics.isAiTech("AI科技")).isTrue();
        assertThat(DigestTopics.isAiTech("科技")).isTrue();
        assertThat(DigestTopics.isAiTech("AI大模型")).isFalse();
        assertThat(DigestTopics.isEtf("纳指标普沪深300ETF")).isTrue();
        assertThat(DigestTopics.isEtf("ETF")).isTrue();
        assertThat(DigestTopics.publicEditionFor("AI科技", LocalTime.of(8, 0))).isEqualTo("morning");
        assertThat(DigestTopics.publicEditionFor("AI科技", LocalTime.of(20, 0))).isEqualTo("evening");
        assertThat(DigestTopics.publicEditionFor("纳指标普沪深300ETF", LocalTime.of(18, 0)))
                .isEqualTo("market_watch_evening");
    }
}
