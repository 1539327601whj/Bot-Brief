package com.ai.daily.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthCheckServiceTest {

    @Test
    void missingEncryptionKeyMarksServiceDegraded() {
        HealthCheckService service = readyService(false, true);

        Map<String, Object> body = service.snapshot();

        assertThat(body.get("status")).isEqualTo("DEGRADED");
        assertThat(((Map<?, ?>) ((Map<?, ?>) body.get("checks")).get("pushChannelEncryption")).get("ok"))
                .isEqualTo(false);
    }

    @Test
    void configuredEncryptionKeepsServiceUp() {
        HealthCheckService service = readyService(true, true);

        Map<String, Object> body = service.snapshot();

        assertThat(body.get("status")).isEqualTo("UP");
        assertThat(((Map<?, ?>) ((Map<?, ?>) body.get("checks")).get("pushChannelEncryption")).get("ok"))
                .isEqualTo(true);
    }

    private static HealthCheckService readyService(boolean encryptionOk, boolean pollerOk) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        OpsHeartbeatService heartbeat = mock(OpsHeartbeatService.class);
        when(heartbeat.find(any())).thenReturn(null);
        when(heartbeat.isFresh(any())).thenReturn(pollerOk);
        PushChannelCrypto crypto = mock(PushChannelCrypto.class);
        when(crypto.isAvailable()).thenReturn(encryptionOk);
        HealthCheckService service = new HealthCheckService(jdbc, heartbeat, crypto);
        ReflectionTestUtils.setField(service, "jwtSecret", "x".repeat(32));
        ReflectionTestUtils.setField(service, "ingestToken", "ingest-token");
        ReflectionTestUtils.setField(service, "mailHost", "smtp.example.com");
        ReflectionTestUtils.setField(service, "mailUsername", "ops@example.com");
        return service;
    }
}
