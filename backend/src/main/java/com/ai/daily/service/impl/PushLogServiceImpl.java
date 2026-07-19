package com.ai.daily.service.impl;

import com.ai.daily.entity.PushLog;
import com.ai.daily.mapper.PushLogMapper;
import com.ai.daily.service.PushLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PushLogServiceImpl extends ServiceImpl<PushLogMapper, PushLog> implements PushLogService {

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
        PushLog log = new PushLog();
        log.setUserId(userId);
        log.setReportId(reportId);
        log.setChannelId(channelId);
        log.setChannelType(channelType);
        log.setStatus(success ? "success" : "failed");
        if (errorMessage != null && errorMessage.length() > 1000) {
            errorMessage = errorMessage.substring(0, 1000);
        }
        log.setErrorMessage(errorMessage);
        this.save(log);
    }
}
