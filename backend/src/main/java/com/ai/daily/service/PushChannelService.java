package com.ai.daily.service;

import com.ai.daily.dto.PushChannelCreateRequest;
import com.ai.daily.dto.PushChannelResponse;
import com.ai.daily.dto.PushChannelUpdateRequest;
import com.ai.daily.entity.PushChannel;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface PushChannelService extends IService<PushChannel> {

    List<PushChannelResponse> listResponsesByUser(Long userId);

    PushChannelResponse createForUser(Long userId, PushChannelCreateRequest request);

    PushChannelResponse updateForUser(Long id, Long userId, PushChannelUpdateRequest request);

    boolean removeForUser(Long id, Long userId);

    List<PushChannel> listEnabledByUser(Long userId);

    PushChannel getByIdForUser(Long id, Long userId);
}
