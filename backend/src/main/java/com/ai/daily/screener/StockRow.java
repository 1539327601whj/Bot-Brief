package com.ai.daily.screener;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 归一化后的单个标的（个股或 ETF）。
 *
 * <p>这个类被两种来源共用，所以字段的来源要分清：
 * 池子清单（{@code datacenter-web}）只填代码、名称、市场、总市值；
 * 其余基本面一律来自行情接口（{@code ulist.np/get}），与
 * {@code automation/agents/stock_screening_rules.md} 第 2.1 节逐条对应。
 * 清单要是开始多返回 PE/PB，那是第二套口径，不允许拿它打分。
 *
 * <p><b>负值必须保留</b>：亏损公司的 PE(TTM) 可能为负，负 PE 看起来「很小很便宜」，
 * 是典型陷阱。这里不把负值抹成 null，让 {@link ScreeningRules} 的 V5 能明确判掉它。
 */
@Data
public class StockRow {

    /** 交易代码，如 600519。 */
    private String code;

    /** 证券简称。 */
    private String name;

    /** 东财市场标识：1 = 沪市，0 = 深市。用于拼 kline 的 secid。 */
    private int market;

    /** 东财行业分类（f100）。金融地产会被 V11 整体排除。 */
    private String industry;

    /** 最新价（f2） */
    private BigDecimal price;
    /** 当日涨跌幅 %（f3），仅背景展示，不进打分。 */
    private BigDecimal pctChange;
    /** 当日成交额，元（f6） */
    private BigDecimal amount;
    /** 换手率 %（f8） */
    private BigDecimal turnoverRate;
    /** 总市值，元（f20） */
    private BigDecimal totalMarketCap;
    /** 流通市值，元（f21） */
    private BigDecimal floatMarketCap;
    /** 市净率（f23） */
    private BigDecimal pb;
    /** PE(TTM)，亏损时可能为负或缺失（f115） */
    private BigDecimal peTtm;
    /** ROE 加权 %（f37） */
    private BigDecimal roe;
    /** 营收同比 %（f41） */
    private BigDecimal revenueGrowth;
    /** 净利同比 %（f46） */
    private BigDecimal profitGrowth;
    /** 毛利率 %（f49） */
    private BigDecimal grossMargin;
    /** 资产负债率 %（f57） */
    private BigDecimal debtRatio;
    /** 股息率 %（f133） */
    private BigDecimal dividendYield;
    /** 每股净资产（f113） */
    private BigDecimal bps;
    /** 上市日期（f26，YYYYMMDD） */
    private LocalDate listDate;
    /** 60 日涨跌幅 %（f24），仅背景展示。 */
    private BigDecimal change60d;
    /** 年初至今涨跌幅 %（f25），仅背景展示。 */
    private BigDecimal ytdChange;

    /** 是否 ETF（来自指数基金池，而非全市场个股快照）。 */
    private boolean etf;

    /**
     * 行业是否不可确认（缺 f100）。**派生而非存储**，否则任何一个没有走
     * {@code MarketDataClient} 构造的地方都会漏标这个降级。
     */
    public boolean isIndustryUnknown() {
        return industry == null || industry.isBlank();
    }

    /** kline 的 secid，如 {@code 1.600519}。 */
    public String secid() {
        return market + "." + code;
    }

    /** 是否属于金融 / 地产——口径与制造业不可比，整体不纳入。 */
    public boolean isFinancialOrRealEstate() {
        if (industry == null || industry.isBlank()) return false;
        String v = industry.replace(" ", "");
        return v.contains("银行") || v.contains("保险") || v.contains("证券")
                || v.contains("多元金融") || v.contains("信托") || v.contains("房地产")
                || v.contains("房地产开发");
    }

    /** 上市年数；未知返回 -1。 */
    public long listedYears(LocalDate asOf) {
        if (listDate == null) return -1;
        return java.time.temporal.ChronoUnit.YEARS.between(listDate, asOf);
    }

    /** 市值优先用总市值，缺失回退流通市值。两者都缺返回 null。 */
    public BigDecimal effectiveMarketCap() {
        return totalMarketCap != null ? totalMarketCap : floatMarketCap;
    }

    public boolean equalsCode(Object other) {
        if (!(other instanceof StockRow o)) return false;
        return Objects.equals(code, o.code);
    }
}