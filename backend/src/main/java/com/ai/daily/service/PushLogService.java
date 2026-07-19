package com.ai.daily.service;

import com.ai.daily.entity.PushLog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface PushLogService extends IService<PushLog> {

    List<PushLog> recentByUser(Long userId, int limit);

    void record(Long userId, Long reportId, Long channelId, String channelType,
                boolean success, String errorMessage);
}
