package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("topic_generation_status")
public class TopicGenerationStatus {

    public static final String READY = "ready";
    public static final String SKIPPED_NO_NEWS = "skipped_no_news";
    public static final String FAILED = "failed";

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate sectionDate;

    private String windowKey;

    private String topicKey;

    private String status;

    private String message;

    private String runId;

    private LocalDateTime updatedAt;
}
