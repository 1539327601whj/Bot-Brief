package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 简报实体
 */
@Data
@TableName("report")
public class Report {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本：morning / evening */
    private String edition;

    /** 简报标题，如"【早间版】AI 每日简报 2026-04-25" */
    private String title;

    /** 简报正文（Markdown 格式） */
    private String content;

    /** 内容摘要（用于列表展示） */
    private String summary;

    /** GitHub Actions 运行 ID（可关联查询） */
    private String runId;

    /** 创建时间 */
    private LocalDateTime createdAt;
}