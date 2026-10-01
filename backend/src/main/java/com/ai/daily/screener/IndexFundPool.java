package com.ai.daily.screener;

import java.util.List;

/**
 * 指数基金池：**只放宽基**。
 *
 * <p>用户要「破产、下市风险低一点」，宽基贴合；行业/主题 ETF 本质是行业押注，
 * 风险提示得另写一套，留作后续。
 *
 * <p>估值分位只接项目自己的 {@code market_valuation_history}。目前库里有沪深300 / 纳指100 / 标普500
 * 三套链路，本池只有沪深300 对得上，其余一律标「估值分位未接入」——**不编数，也不跨指数比 PE。**
 */
public final class IndexFundPool {

    /** 一只宽基 ETF。{@code valuationIndexCode} 为空 = 没有 PE 分位数据源。 */
    public record Fund(
            String code,
            int market,
            String name,
            String tracking,
            String valuationIndexCode,
            String percentileMethod
    ) {
        public String secid() {
            return market + "." + code;
        }
    }

    public static final List<Fund> FUNDS = List.of(
            new Fund("510300", 1, "沪深300ETF", "沪深300", "SH000300", "CSI_PE_TTM_ROLLING_10Y"),
            new Fund("510500", 1, "中证500ETF", "中证500", null, null),
            new Fund("510050", 1, "上证50ETF", "上证50", null, null),
            new Fund("159915", 0, "创业板ETF", "创业板指", null, null),
            new Fund("588000", 1, "科创50ETF", "科创50", null, null),
            new Fund("512100", 1, "中证1000ETF", "中证1000", null, null),
            new Fund("510880", 1, "红利ETF", "上证红利", null, null));

    /** 没有 PE 分位数据源时页面上要出现的原话。 */
    public static final String PERCENTILE_NOT_WIRED = "估值分位未接入";

    private IndexFundPool() {}

    public static Fund byCode(String code) {
        for (Fund f : FUNDS) {
            if (f.code().equals(code)) return f;
        }
        return null;
    }
}