package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String email;

    private String passwordHash;

    private String displayName;

    /** ADMIN | USER */
    private String role;

    private Boolean enabled;

    private String inviteCodeUsed;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
