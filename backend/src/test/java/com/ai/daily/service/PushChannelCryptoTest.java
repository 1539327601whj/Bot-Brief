package com.ai.daily.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushChannelCryptoTest {

    @Test
    void encryptsAndDecryptsWithoutExposingPlaintext() {
        byte[] key = Arrays.copyOf("test-key-material-for-push-channel".getBytes(StandardCharsets.UTF_8), 32);
        PushChannelCrypto crypto = new PushChannelCrypto(key);

        String encrypted = crypto.encrypt("https://example.invalid/secret");

        assertThat(encrypted).startsWith(PushChannelCrypto.PREFIX);
        assertThat(encrypted).doesNotContain("example.invalid");
        assertThat(crypto.decrypt(encrypted)).isEqualTo("https://example.invalid/secret");
    }

    @Test
    void rejectsMissingOrMalformedKeysWithoutBreakingConstruction() {
        PushChannelCrypto missing = new PushChannelCrypto("");
        PushChannelCrypto malformed = new PushChannelCrypto(Base64.getEncoder().encodeToString(new byte[16]));

        assertThat(missing.isAvailable()).isFalse();
        assertThat(malformed.isAvailable()).isFalse();
        assertThatThrownBy(() -> missing.encrypt("secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUSH_CHANNEL_ENCRYPTION_KEY");
    }

    @Test
    void rejectsCiphertextEncryptedWithAnotherKey() {
        PushChannelCrypto first = new PushChannelCrypto(new byte[32]);
        byte[] otherKey = new byte[32];
        Arrays.fill(otherKey, (byte) 1);
        PushChannelCrypto second = new PushChannelCrypto(otherKey);

        assertThatThrownBy(() -> second.decrypt(first.encrypt("secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("推送渠道凭据无法解密");
    }
}
