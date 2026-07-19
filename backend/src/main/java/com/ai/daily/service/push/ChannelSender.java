package com.ai.daily.service.push;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;

/**
 * 所有推送渠道的统一接口。
 */
public interface ChannelSender {

    /** 渠道类型：email|wechat|dingtalk|feishu */
    String type();

    /** 发送。失败请抛异常（PushDispatcher 会捕获并记 push_log） */
    void send(PushChannel channel, Report report) throws Exception;
}
