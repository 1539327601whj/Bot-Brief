package com.ai.daily.service.impl;

import com.ai.daily.entity.PushLog;
import com.ai.daily.mapper.PushLogMapper;
import com.ai.daily.service.PushLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class PushLogServiceImpl extends ServiceImpl<PushLogMapper, PushLog> implements PushLogService {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final int ERROR_MAX_LENGTH = 1000;

    @Override
    public List<PushLog> recentByUser(Long userId, int limit) {
        LambdaQueryWrapper<PushLog> w = new LambdaQueryWrapper<>();
        w.eq(PushLog::getUserId, userId)
                .orderByDesc(PushLog::getPushedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 500)));
        return this.list(w);
    }

    @Override
    public void record(Long userId, Long reportId, Long channelId, String channelType,
                       boolean success, String errorMessage) {
        PushLog log = newLog(userId, reportId, channelId, channelType);
        log.setStatus(success ? "success" : "failed");
        log.setErrorMessage(sanitizeError(errorMessage));
        this.save(log);
    }

    @Override
    public Long claimScheduled(Long userId, Long reportId, Long channelId, String channelType, String dispatchKey) {
        PushLog log = newLog(userId, reportId, channelId, channelType);
        log.setStatus("sending");
        log.setDispatchKey(dispatchKey);
        try {
            return baseMapper.insert(log) == 1 ? log.getId() : null;
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    @Override
    public void markSuccess(Long logId) {
        PushLog update = new PushLog();
        update.setId(logId);
        update.setStatus("success");
        update.setErrorMessage(null);
        updateById(update);
    }

    @Override
    public void markFailed(Long logId, String errorMessage) {
        PushLog update = new PushLog();
        update.setId(logId);
        update.setStatus("failed");
        update.setErrorMessage(sanitizeError(errorMessage));
        updateById(update);
    }

    private PushLog newLog(Long userId, Long reportId, Long channelId, String channelType) {
        PushLog log = new PushLog();
        log.setUserId(userId);
        log.setReportId(reportId);
        log.setChannelId(channelId);
        log.setChannelType(channelType);
        log.setPushedAt(LocalDateTime.now(BEIJING));
        return log;
    }

    private String sanitizeError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) return null;
        String sanitized = errorMessage.replaceAll("[\\r\\n\\t]+", " ").trim();
        return sanitized.length() > ERROR_MAX_LENGTH
                ? sanitized.substring(0, ERROR_MAX_LENGTH)
                : sanitized;
    }
}
