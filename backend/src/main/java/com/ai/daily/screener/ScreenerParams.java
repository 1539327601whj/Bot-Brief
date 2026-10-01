package com.ai.daily.screener;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 「低估精选」的筛选条件。所有字段可为 null，null 表示「用户没填，走默认」。
 *
 * <p>对应页面的 6 个主控 + 「高级」折叠区，见
 * {@code automation/agents/stock_screening_rules.md}。
 *
 * <p>{@link #normalized()} 把 null 解析成实际生效值并做范围校验，
 * 之后所有规则只读解析后的对象，不再各自处理 null。
 */
@Data
public class ScreenerParams {

    /** index_first（默认）/ index_only / stock_only */
    private String mode;
    /** both（默认）/ steady / growth */
    private String bucket;
    /** 每档输出只数，默认 3 */
    private Integer perBucket;

    /** PE(TTM) 上限，留空按档位默认（稳健 30 / 成长 45） */
    private BigDecimal peMax;
    /** 价格分位上限 %（0-100），留空 = 不限 */
    private BigDecimal pricePercentileMax;
    /** 市值下限（亿元），留空按档位默认（稳健 200 / 成长 50） */
    private BigDecimal marketCapMinYi;

    /** 高级：PB 上限，留空按档位默认（稳健 8 / 成长 12） */
    private BigDecimal pbMax;
    /** 高级：股息率下限 % */
    private BigDecimal dividendYieldMin;
    /** 高级：ROE 下限 % */
    private BigDecimal roeMin;
    /** 高级：资产负债率上限 %，留空按档位默认（稳健 65 / 成长 75） */
    private BigDecimal debtRatioMax;
    /** 高级：排除的行业（f100 精确匹配，去空格） */
    private List<String> excludedIndustries;
    /** 高级：营收同比下限 % */
    private BigDecimal revenueGrowthMin;
    /** 高级：净利同比下限 % */
    private BigDecimal profitGrowthMin;

    public static ScreenerParams defaults() {
        return new ScreenerParams().normalized();
    }

    public static final String MODE_INDEX_FIRST = "index_first";
    public static final String MODE_INDEX_ONLY = "index_only";
    public static final String MODE_STOCK_ONLY = "stock_only";
    public static final String BUCKET_BOTH = "both";
    public static final String BUCKET_STEADY = "steady";
    public static final String BUCKET_GROWTH = "growth";

    private static final List<String> MODES = List.of(MODE_INDEX_FIRST, MODE_INDEX_ONLY, MODE_STOCK_ONLY);
    private static final List<String> BUCKETS = List.of(BUCKET_BOTH, BUCKET_STEADY, BUCKET_GROWTH);
    private static final List<Integer> PER_BUCKET_CHOICES = List.of(1, 2, 3, 5);

    /** 解析默认值并校验。非法值抛 {@link IllegalArgumentException}（控制器转 400）。 */
    public ScreenerParams normalized() {
        ScreenerParams p = new ScreenerParams();
        p.mode = blankToNull(mode) == null ? MODE_INDEX_FIRST : mode.trim();
        p.bucket = blankToNull(bucket) == null ? BUCKET_BOTH : bucket.trim();
        p.perBucket = perBucket == null ? 3 : perBucket;

        if (!MODES.contains(p.mode)) {
            throw new IllegalArgumentException("类别取值无效：" + mode);
        }
        if (!BUCKETS.contains(p.bucket)) {
            throw new IllegalArgumentException("档位取值无效：" + bucket);
        }
        if (!PER_BUCKET_CHOICES.contains(p.perBucket)) {
            throw new IllegalArgumentException("每档只数只能是 1 / 2 / 3 / 5");
        }

        p.peMax = positiveOrNull(peMax, "PE 上限");
        p.pricePercentileMax = percentOrNull(pricePercentileMax, "价格分位上限");
        p.marketCapMinYi = positiveOrNull(marketCapMinYi, "市值下限");
        p.pbMax = positiveOrNull(pbMax, "PB 上限");
        p.dividendYieldMin = anyOrNull(dividendYieldMin, "股息率下限");
        p.roeMin = anyOrNull(roeMin, "ROE 下限");
        p.debtRatioMax = percentOrNull(debtRatioMax, "资产负债率上限");
        p.revenueGrowthMin = anyOrNull(revenueGrowthMin, "营收同比下限");
        p.profitGrowthMin = anyOrNull(profitGrowthMin, "净利同比下限");

        p.excludedIndustries = new ArrayList<>();
        if (excludedIndustries != null) {
            for (String s : excludedIndustries) {
                String v = blankToNull(s);
                if (v != null && !p.excludedIndustries.contains(v)) p.excludedIndustries.add(v);
            }
        }
        return p;
    }

    public boolean wantsIndexFunds() {
        return !MODE_STOCK_ONLY.equals(mode);
    }

    public boolean wantsStocks() {
        return !MODE_INDEX_ONLY.equals(mode);
    }

    public boolean wantsSteady() {
        return !BUCKET_GROWTH.equals(bucket);
    }

    public boolean wantsGrowth() {
        return !BUCKET_STEADY.equals(bucket);
    }

    public boolean isIndustryExcluded(String industry) {
        if (industry == null || excludedIndustries == null || excludedIndustries.isEmpty()) return false;
        String v = industry.replace(" ", "");
        for (String e : excludedIndustries) {
            if (v.equals(e.replace(" ", "")) || v.contains(e.replace(" ", ""))) return true;
        }
        return false;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static BigDecimal positiveOrNull(BigDecimal v, String label) {
        if (v == null) return null;
        if (v.signum() <= 0) throw new IllegalArgumentException(label + "必须大于 0");
        if (v.compareTo(new BigDecimal("100000")) > 0) throw new IllegalArgumentException(label + "超出合理范围");
        return v;
    }

    private static BigDecimal percentOrNull(BigDecimal v, String label) {
        if (v == null) return null;
        if (v.signum() < 0 || v.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException(label + "必须在 0-100 之间");
        }
        return v;
    }

    private static BigDecimal anyOrNull(BigDecimal v, String label) {
        if (v == null) return null;
        if (v.compareTo(new BigDecimal("-1000")) < 0 || v.compareTo(new BigDecimal("1000")) > 0) {
            throw new IllegalArgumentException(label + "超出合理范围");
        }
        return v;
    }
}