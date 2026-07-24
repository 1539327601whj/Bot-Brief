package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("push_log")
public class PushLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long reportId;

    private Long channelId;

    private String channelType;

    /** success | failed */
    private String status;

    private String errorMessage;

    private String dispatchKey;

    private LocalDateTime pushedAt;
}
