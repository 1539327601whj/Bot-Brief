package com.ai.daily.service;

import com.ai.daily.entity.OpsHeartbeat;
import com.ai.daily.mapper.OpsHeartbeatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpsHeartbeatService {

    public static final String POLLER = "poller";
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final Duration STALE_AFTER = Duration.ofMinutes(20);

    private final OpsHeartbeatMapper heartbeatMapper;

    public void beat(String name, String detail) {
        if (name == null || name.isBlank()) return;
        try {
            LocalDateTime now = LocalDateTime.now(BEIJING);
            OpsHeartbeat row = heartbeatMapper.selectById(name);
            if (row == null) {
                row = new OpsHeartbeat();
                row.setName(name.trim());
                row.setLastSeen(now);
                row.setDetail(trim(detail));
                heartbeatMapper.insert(row);
                return;
            }
            row.setLastSeen(now);
            row.setDetail(trim(detail));
            heartbeatMapper.updateById(row);
        } catch (Exception e) {
            log.warn("心跳写入失败: {}", e.getMessage());
        }
    }

    public OpsHeartbeat find(String name) {
        try {
            return name == null ? null : heartbeatMapper.selectById(name);
        } catch (Exception e) {
            log.warn("读取心跳失败: {}", e.getMessage());
            return null;
        }
    }

    public boolean isFresh(OpsHeartbeat row) {
        if (row == null || row.getLastSeen() == null) return false;
        return !row.getLastSeen().isBefore(LocalDateTime.now(BEIJING).minus(STALE_AFTER));
    }

    private static String trim(String detail) {
        if (detail == null) return null;
        String value = detail.trim();
        return value.length() > 255 ? value.substring(0, 255) : value;
    }
}
