package com.ai.daily.service.impl;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.mapper.PushChannelMapper;
import com.ai.daily.service.PushChannelService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PushChannelServiceImpl extends ServiceImpl<PushChannelMapper, PushChannel> implements PushChannelService {

    @Override
    public List<PushChannel> listByUser(Long userId) {
        LambdaQueryWrapper<PushChannel> w = new LambdaQueryWrapper<>();
        w.eq(PushChannel::getUserId, userId).orderByDesc(PushChannel::getCreatedAt);
        return this.list(w);
    }

    @Override
    public List<PushChannel> listEnabledByUser(Long userId) {
        LambdaQueryWrapper<PushChannel> w = new LambdaQueryWrapper<>();
        w.eq(PushChannel::getUserId, userId).eq(PushChannel::getEnabled, true);
        return this.list(w);
    }

    @Override
    public PushChannel getByIdForUser(Long id, Long userId) {
        LambdaQueryWrapper<PushChannel> w = new LambdaQueryWrapper<>();
        w.eq(PushChannel::getId, id).eq(PushChannel::getUserId, userId).last("LIMIT 1");
        return this.getOne(w);
    }
}
