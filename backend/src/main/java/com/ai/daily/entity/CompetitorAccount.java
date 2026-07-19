package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("competitor_account")
public class CompetitorAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String platform;

    private String accountName;

    private String homepageUrl;

    private String note;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
