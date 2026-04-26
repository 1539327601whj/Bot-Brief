package com.ai.daily.mapper;

import com.ai.daily.entity.Subscription;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订阅配置 Mapper
 */
@Mapper
public interface SubscriptionMapper extends BaseMapper<Subscription> {
}
