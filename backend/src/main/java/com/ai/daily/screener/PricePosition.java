package com.ai.daily.screener;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 一只标的的价格位置。全部由前复权日线算出，**不含任何估值口径**。
 *
 * <p>「价格分位」和「PE 分位」是两件不同的事：前者说「现价在近一年高低区间里的位置」，
 * 后者说「估值在历史上贵不贵」。东财不给单只个股的 PE 历史分位，
 * 所以个股只有价格分位。别在页面上把两者混为一谈。
 */
@Data
public class PricePosition {

    /** 一年价格分位 0-100：0 = 贴着近一年最低，100 = 贴着近一年最高。 */
    private BigDecimal pricePercentile;
    /** 距近一年最高点的回撤 %，0 = 正在最高点。 */
    private BigDecimal drawdownFromHigh;
    /** 近一年最大回撤 %（正数）。 */
    private BigDecimal maxDrawdownInYear;
    /** 年化波动率 %（对数收益标准差 × √250）。 */
    private BigDecimal annualizedVolatility;
    /** 收盘价对 MA20 的偏离 %，正 = 在均线上方。 */
    private BigDecimal vsMa20;
    private BigDecimal vsMa60;
    private BigDecimal vsMa250;
    /** 参与计算的日线根数。 */
    private int barCount;
    /** 日线最后一根的日期。 */
    private LocalDate lastTradeDate;
    /** 计算过程中被降级的项，如「日线不足 250 根，MA250 未确认」。 */
    private List<String> degradations = new ArrayList<>();

    /** 是否拿到了可用的价格位置（日线为空时为 false）。 */
    private boolean available;

    public boolean has(String field) {
        return switch (field) {
            case "pricePercentile" -> pricePercentile != null;
            case "drawdownFromHigh" -> drawdownFromHigh != null;
            case "annualizedVolatility" -> annualizedVolatility != null;
            default -> false;
        };
    }

    public static PricePosition unavailable(String reason) {
        PricePosition p = new PricePosition();
        p.setAvailable(false);
        p.getDegradations().add(reason);
        return p;
    }
}