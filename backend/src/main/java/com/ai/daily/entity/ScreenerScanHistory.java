package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「低估精选」的一次筛选记录。
 *
 * <p><b>为什么要同时存摘要列和完整快照。</b>
 * 摘要列（扫描数、通过排雷数、指数合格数、入选清单）是为了列表页不用把一个几十 KB 的
 * JSON 拉下来解析；{@code result_json} 是为了「回放当时那一页」——用户要的是
 * 「上次和这次差在哪」，只给一句摘要对不上。
 *
 * <p><b>这张表是只增不改的审计台账</b>：不更新、不当缓存用，写入失败也不影响本次筛选结果
 * （见 {@code StockScreenerController} 里那段 catch）。
 */
@Data
@TableName("screener_scan_history")
public class ScreenerScanHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 本次计算时间，Asia/Shanghai。**与 {@code dataTime} 用的是同一个时钟取值。** */
    private LocalDateTime scannedAt;

    /** 触发人。定时任务不会写这张表（只有管理员点按钮才写），但列仍允许空。 */
    private Long scannedByUserId;

    private String scannedByEmail;

    private String mode;

    private String bucket;

    private Integer perBucket;

    /** 生效参数（解析默认值之后）的 JSON，回填筛选条件面板用。 */
    private String paramsJson;

    private Integer scannedCount;

    private Integer afterVetoes;

    private Integer steadyPool;

    private Integer growthPool;

    private Integer shortlistFetched;

    private Integer indexCount;

    private Integer qualifiedIndexCount;

    /** 入选清单的 JSON：指数的代码/名称/是否合格 + 两档个股的代码/名称。 */
    private String selectionJson;

    /** 完整结果快照，可整页恢复。 */
    private String resultJson;

    private LocalDateTime createdAt;
}