package com.ai.daily.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Component
public class PushChannelCrypto {

    static final String PREFIX = "enc:v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec key;

    public PushChannelCrypto(@Value("${push-channel.encryption-key:}") String configuredKey) {
        this.key = parseKey(configuredKey);
    }

    PushChannelCrypto(byte[] keyBytes) {
        this.key = keyBytes != null && keyBytes.length == 32 ? new SecretKeySpec(keyBytes, "AES") : null;
    }

    public boolean isAvailable() {
        return key != null;
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        requireAvailable();
        if (isEncrypted(plaintext)) return plaintext;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
            return PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("推送渠道加密失败");
        }
    }

    public String decrypt(String storedValue) {
        if (storedValue == null || !isEncrypted(storedValue)) return storedValue;
        requireAvailable();
        try {
            byte[] envelope = Base64.getDecoder().decode(storedValue.substring(PREFIX.length()));
            if (envelope.length <= NONCE_BYTES) throw new GeneralSecurityException("invalid envelope");
            ByteBuffer buffer = ByteBuffer.wrap(envelope);
            byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("推送渠道凭据无法解密");
        }
    }

    public void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("推送渠道加密未配置，请设置 PUSH_CHANNEL_ENCRYPTION_KEY");
        }
    }

    private SecretKeySpec parseKey(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(configuredKey.trim());
            if (decoded.length != 32) {
                log.error("PUSH_CHANNEL_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥，渠道功能已禁用");
                return null;
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException e) {
            log.error("PUSH_CHANNEL_ENCRYPTION_KEY 不是有效的 Base64，渠道功能已禁用");
            return null;
        }
    }
}
