package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("content_growth_analysis")
public class ContentGrowthAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long accountId;

    private String analysisType;

    private String inputText;

    private String resultText;

    private LocalDateTime createdAt;
}
