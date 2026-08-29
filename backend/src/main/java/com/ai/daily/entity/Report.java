package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 简报实体
 */
@Data
@TableName("reports")
public class Report {

    public static final long PUBLIC_OWNER_ID = 0L;
    public static final String PERSONAL = "personal";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 0=公共简报（Demo/行情），其他=用户拼装简报 */
    private Long userId;

    /** 版本：public morning/evening、personal、market_watch_* */
    private String edition;

    /** 报告业务日期（北京时间） */
    private LocalDate reportDate;

    /** 用户选定的展示/推送时刻；公共报为固定展示时刻 */
    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime displayTime;

    /** 简报标题，如"【早间版】AI 每日简报 2026-04-25" */
    private String title;

    /** 简报正文（Markdown 格式） */
    private String content;

    /** 内容摘要（用于列表展示） */
    private String summary;

    /** GitHub Actions 运行 ID（可关联查询） */
    private String runId;

    /** 报告入库幂等键 */
    private String ingestKey;

    /** 创建时间 */
    private LocalDateTime createdAt;

    public static boolean isPublicOwner(Long ownerId) {
        return ownerId == null || ownerId == PUBLIC_OWNER_ID;
    }

    public static boolean isPersonalizedEdition(String edition) {
        return PERSONAL.equals(edition);
    }

    public static boolean isSharedPublicEdition(String edition) {
        return edition != null && edition.startsWith("market_watch");
    }

    public static boolean isPublicDigest(String edition) {
        return "morning".equals(edition) || "evening".equals(edition);
    }
}