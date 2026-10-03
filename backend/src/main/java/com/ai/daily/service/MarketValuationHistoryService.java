package com.ai.daily.service;

import com.ai.daily.dto.MarketValuationIngestDTO;
import com.ai.daily.entity.MarketValuationHistory;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface MarketValuationHistoryService extends IService<MarketValuationHistory> {

    void upsert(MarketValuationIngestDTO dto);

    /** 指数池同步走这条：一次提交几十到 250 条估值。 */
    void upsertBatch(List<MarketValuationIngestDTO> valuations);

    List<MarketValuationHistory> latest(String indexCode, String percentileMethod, int limit);

    /**
     * 一个指数配一个口径。<b>口径写在键里是故意的</b>：同一指数的
     * {@code CSI_PE_TTM_ROLLING_10Y} 与 {@code DANJUAN_PE_TTM_PROVIDER} 不是同一个数
     * （实测中证500 差 22%、科创50 差 75%），按 {@code indexCode} 单键取会让
     * 「换了口径」这件事在结果里消失。
     */
    record Key(String indexCode, String percentileMethod) {}

    /**
     * 批量取每个指数**各自口径下**的最新一条估值分位。指数池那 60–200 条走这条，
     * 逐条 {@link #latest} 会是 200 次查询。
     *
     * <p>只返回**有分位**的行：{@code pe_percentile IS NULL} 的记录不算「这个指数有分位」。
     * （{@code peTtm} 与分位因此是绑在一起的——把「有 PE 但没分位」也端出来，
     * 需要卡片能把两种缺失分开说，那是 阶段 6 的改动。）
     *
     * @param keys 空列表返回空 map，**不查库**
     * @return {@code indexCode → 最新一行}；某个指数没有记录时**map 里就没有这个键**，
     *         调用方据此写「库里暂无」，而不是拿到一条空记录
     */
    Map<String, MarketValuationHistory> latestForIndices(List<Key> keys);

    /**
     * 某个指数在**一个口径下**的历史分位序列，按交易日升序。
     *
     * <p>给「代码查询」页算 5 年 / 10 年基线用——{@link #latest} 只给最后一行，
     * 回答不了「三年前那天分位是多少」。
     *
     * <p>口径必须显式传进来，**不给默认值**：同一指数的
     * {@code CSI_PE_TTM_ROLLING_10Y} 与 {@code DANJUAN_PE_TTM_PROVIDER} 实测能差 75%，
     * 让调用方「忘了传就用一个默认的」会把跨口径这件事藏起来（章程 §5.3）。
     *
     * <p>只返回**有分位**的行——{@code pe_percentile IS NULL} 的记录不是一次
     * 「分位是 0」的观测。
     *
     * @param from 含；{@code null} 不限
     * @param to   含；{@code null} 不限
     * @return 升序列表；没有数据时是**空列表**，调用方据此写「历史不足」而不是拿它当 0
     */
    List<MarketValuationHistory> historyBetween(String indexCode, String percentileMethod,
                                                LocalDate from, LocalDate to);
}