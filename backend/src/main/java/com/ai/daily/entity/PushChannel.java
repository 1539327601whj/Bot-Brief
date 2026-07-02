package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("push_channel")
public class PushChannel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** email | wechat | dingtalk | feishu */
    private String channelType;

    private String displayName;

    /** 邮箱地址 或 webhook URL */
    private String target;

    /** 钉钉/飞书签名密钥 */
    private String secret;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
