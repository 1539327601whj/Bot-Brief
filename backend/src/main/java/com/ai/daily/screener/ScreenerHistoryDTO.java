package com.ai.daily.screener;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 筛选历史的两层结构：**列表项**（轻，不带动辄几十 KB 的结果快照）
 * 与**详情**（列表项 + 可整页恢复的结果 JSON）。
 *
 * <p>为什么详情给 {@link JsonNode} 而不是反序列化回 {@link ScreenerResultDTO}：
 * 快照里嵌着 {@link StockRow}，那是 Lombok 生成的带派生方法的类，序列化出来的形状
 * 反序列化回去要靠宽松的未知属性容忍，脆。而「原样把 JSON 交回前端」既稳，
 * 又保证回放的就是当初那一页——前端本来就在消费这份形状。
 */
public final class ScreenerHistoryDTO {

    private ScreenerHistoryDTO() {}

    /** 入选清单里的一条：代码 + 名称，指数额外带「本次是否合格」。 */
    public record Ref(String code, String name, boolean qualified) {}

    /** 落库时 {@code selection_json} 的形状，读回来时按它解析。 */
    public record Selection(List<Ref> indices, List<Ref> steadyStocks, List<Ref> growthStocks) {}

    /** 列表页一行。字段全部来自摘要列，**不含 resultJson**。 */
    public record Item(
            long id,
            /** 本次计算时间，Asia/Shanghai，格式 yyyy-MM-dd HH:mm:ss。 */
            String scannedAt,
            String scannedByEmail,
            String mode,
            String bucket,
            int perBucket,
            int scanned,
            int afterVetoes,
            int steadyPool,
            int growthPool,
            int shortlistFetched,
            int indexCount,
            int qualifiedIndexCount,
            List<Ref> indices,
            List<Ref> steadyStocks,
            List<Ref> growthStocks,
            /** 当时生效的条件（解析默认值之后），用于回填条件面板。 */
            ScreenerParams appliedParams
    ) {}

    /** 列表项 + 完整结果快照。`result` 直接就是当初那次 `scan` 的返回体。 */
    public record Detail(Item item, JsonNode result) {}
}