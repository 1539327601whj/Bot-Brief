package com.ai.daily.service;

import com.ai.daily.entity.OpsHeartbeat;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HealthCheckService {

    private final JdbcTemplate jdbcTemplate;
    private final OpsHeartbeatService heartbeatService;
    private final PushChannelCrypto pushChannelCrypto;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${report.ingest-token:}")
    private String ingestToken;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${spring.mail.host:}")
    private String mailHost;

    public Map<String, Object> snapshot() {
        Map<String, Object> checks = new LinkedHashMap<>();
        boolean database = pingDatabase();
        boolean jwt = jwtSecret != null && jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32;
        boolean ingest = ingestToken != null && !ingestToken.isBlank();
        boolean mail = mailHost != null && !mailHost.isBlank() && mailUsername != null && !mailUsername.isBlank();
        boolean encryption = pushChannelCrypto != null && pushChannelCrypto.isAvailable();

        checks.put("database", Map.of("ok", database));
        checks.put("jwt", Map.of("ok", jwt));
        checks.put("ingestToken", Map.of("configured", ingest));
        checks.put("mail", Map.of("configured", mail));
        checks.put("pushChannelEncryption", Map.of("ok", encryption));
        checks.put("poller", pollerCheck());

        String status = "UP";
        int http = 200;
        if (!database) {
            status = "DOWN";
            http = 503;
        } else if (!jwt || !ingest || !encryption
                || !heartbeatService.isFresh(heartbeatService.find(OpsHeartbeatService.POLLER))) {
            status = "DEGRADED";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("httpStatus", http);
        body.put("service", "ai-daily-backend");
        body.put("checks", checks);
        return body;
    }

    private boolean pingDatabase() {
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> pollerCheck() {
        OpsHeartbeat beat = heartbeatService.find(OpsHeartbeatService.POLLER);
        Map<String, Object> poller = new LinkedHashMap<>();
        boolean ok = heartbeatService.isFresh(beat);
        poller.put("ok", ok);
        poller.put("lastSeen", beat != null && beat.getLastSeen() != null ? beat.getLastSeen().toString() : null);
        poller.put("detail", beat != null ? beat.getDetail() : null);
        return poller;
    }
}
