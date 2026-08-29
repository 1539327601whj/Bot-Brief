package com.ai.daily.service.push;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PushContentLimitsTest {

    @Test
    void truncatesByUtf8BytesNotChars() {
        String text = "简报".repeat(20);
        String cut = PushContentLimits.truncateToBytes(text, 20);
        assertThat(cut.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(20);
        assertThat(cut).endsWith("...");
    }
}
