package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("topic_sections")
public class TopicSection {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String edition;

    private LocalDate sectionDate;

    private String topicKey;

    private String title;

    private String content;

    private String summary;

    private String runId;

    private LocalDateTime createdAt;
}
