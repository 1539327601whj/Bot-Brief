package com.ai.daily.service;

import com.ai.daily.entity.PushChannel;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface PushChannelService extends IService<PushChannel> {

    List<PushChannel> listByUser(Long userId);

    List<PushChannel> listEnabledByUser(Long userId);

    PushChannel getByIdForUser(Long id, Long userId);
}
