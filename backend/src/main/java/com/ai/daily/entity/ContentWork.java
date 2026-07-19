package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("content_work")
public class ContentWork {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long accountId;

    private String platform;

    private String title;

    private String coverUrl;

    private String workUrl;

    private LocalDateTime publishTime;

    private Long playCount;

    private Long likeCount;

    private Long commentCount;

    private Long collectCount;

    private Long shareCount;

    private Long followerGain;

    private String contentType;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
