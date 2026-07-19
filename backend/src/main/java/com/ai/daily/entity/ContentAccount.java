package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("content_account")
public class ContentAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String platform;

    private String accountName;

    private String homepageUrl;

    private String avatarUrl;

    private Long followerCount;

    private String accountPositioning;

    private String bindStatus;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
