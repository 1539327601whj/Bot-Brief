package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ops_heartbeat")
public class OpsHeartbeat {

    @TableId(type = IdType.INPUT)
    private String name;

    private LocalDateTime lastSeen;

    private String detail;
}
