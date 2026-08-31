package com.ai.daily.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestTokensTest {

    @Test
    void rejectsMissingConfiguredOrWrongToken() {
        assertThat(IngestTokens.invalid("secret", null)).isTrue();
        assertThat(IngestTokens.invalid("secret", "")).isTrue();
        assertThat(IngestTokens.invalid("secret", "wrong")).isTrue();
        assertThat(IngestTokens.invalid(null, "secret")).isTrue();
        assertThat(IngestTokens.invalid("", "secret")).isTrue();
    }

    @Test
    void acceptsExactConfiguredToken() {
        assertThat(IngestTokens.invalid("secret", "secret")).isFalse();
    }
}
