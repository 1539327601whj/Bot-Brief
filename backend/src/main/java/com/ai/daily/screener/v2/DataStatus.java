package com.ai.daily.screener.v2;

import java.util.List;

/**
 * 一个数据域在本轮查询里的状态。
 *
 * <p><b>为什么不用布尔值</b>：页面要能区分「没有数据」与「源这次没给到」，两者的下一页
 * 文案完全不同——前者写「免费源不提供」，后者写「同步中 / 请稍后重试」。
 * {@code FAILED} 也不等于 0 分：0 是一个有效数值，而失败必须让结论端降覆盖率。
 *
 * <p>{@link #SYNCING} 只出现在 core 响应里，表示已经排了后台任务但本轮预算内没等到结果。
 */
public enum DataStatus {
    /** 有数据，且新鲜。 */
    OK,
    /** 有数据，但只覆盖了一部分（例如只拿到一条序列的尾部，或分位历史不足 10 年）。 */
    PARTIAL,
    /** 源明确回答「没有这个标的 / 没有这段历史」，不是错误。 */
    EMPTY,
    /** 取数失败：网络、限流、结构异常。 */
    FAILED,
    /** 这类标的本来就不适用（指数没有财务三表）。 */
    NOT_APPLICABLE,
    /** 已排后台同步任务，本轮先返回已有数据。 */
    SYNCING;

    public boolean usable() {
        return this == OK || this == PARTIAL;
    }

    /**
     * 多个子项合成一个域状态。顺序：FAILED &gt; SYNCING &gt; PARTIAL &gt; OK &gt; EMPTY &gt; NOT_APPLICABLE。
     *
     * <p>{@code FAILED} 优先于 {@code PARTIAL}：一个域里有一项彻底失败时，页面上应该先提示
     * 「这一段没拿到」，而不是让部分成功把它盖过去。
     */
    public static DataStatus combine(List<DataStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return NOT_APPLICABLE;
        }
        DataStatus result = NOT_APPLICABLE;
        for (DataStatus status : statuses) {
            if (status == null) {
                continue;
            }
            if (status == FAILED) {
                return FAILED;
            }
            if (status == SYNCING) {
                result = SYNCING;
            } else if (status == PARTIAL && result != SYNCING) {
                result = PARTIAL;
            } else if (status == OK && (result == NOT_APPLICABLE || result == EMPTY)) {
                result = OK;
            } else if (status == EMPTY && result == NOT_APPLICABLE) {
                result = EMPTY;
            }
        }
        return result;
    }
}
