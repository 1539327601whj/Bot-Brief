package com.ai.daily.warehouse;

import java.util.Locale;

/**
 * 带资产类型的稳定代码键。
 *
 * <p><b>为什么不能直接用 6 位代码当主键</b>：{@code 000001} 同时是平安银行（深市个股）
 * 和上证指数；{@code 510300} 是 ETF；{@code 000300} 是沪深300指数。
 * 裸代码跨类型撞车，行情、估值、财务会串到一只完全不同的标的身上。
 * 所以所有 {@code md_*} 表统一用 {@code TYPE:rawCode}：
 * {@code STOCK:300274}、{@code ETF:510300}、{@code INDEX:000300}。
 *
 * <p>这个记录只负责「是什么」，不负责「去哪儿查」——东财 secid、腾讯 sh/sz 前缀、
 * 中证官网入参都由各 client 自己拼，别把取数细节塞进键里。
 */
public record InstrumentCode(String assetType, String rawCode, String market) {

    public static final String STOCK = "STOCK";
    public static final String ETF = "ETF";
    public static final String INDEX = "INDEX";

    public InstrumentCode {
        if (assetType == null || assetType.isBlank()) {
            throw new IllegalArgumentException("assetType 不能为空");
        }
        if (rawCode == null || rawCode.isBlank()) {
            throw new IllegalArgumentException("rawCode 不能为空");
        }
        assetType = assetType.strip().toUpperCase(Locale.ROOT);
        rawCode = rawCode.strip().toUpperCase(Locale.ROOT);
    }

    /** 数据库统一键。 */
    public String key() {
        return assetType + ":" + rawCode;
    }

    public static InstrumentCode of(String assetType, String rawCode, String market) {
        return new InstrumentCode(assetType, rawCode, market);
    }

    public static InstrumentCode stock(String rawCode, String market) {
        return new InstrumentCode(STOCK, rawCode, market);
    }

    public static InstrumentCode etf(String rawCode, String market) {
        return new InstrumentCode(ETF, rawCode, market);
    }

    public static InstrumentCode index(String rawCode, String market) {
        return new InstrumentCode(INDEX, rawCode, market);
    }

    public boolean isStock() {
        return STOCK.equals(assetType);
    }

    public boolean isEtf() {
        return ETF.equals(assetType);
    }

    public boolean isIndex() {
        return INDEX.equals(assetType);
    }

    /** 解析数据库键；格式不对直接抛，别把一个坏键当成「查不到」静默返回。 */
    public static InstrumentCode parse(String key) {
        if (key == null) {
            throw new IllegalArgumentException("instrumentCode 不能为空");
        }
        String value = key.strip();
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw new IllegalArgumentException("instrumentCode 格式应为 TYPE:CODE，实际=" + key);
        }
        return new InstrumentCode(value.substring(0, colon), value.substring(colon + 1), null);
    }

    /**
     * 按裸代码推断交易所前缀。只用于展示与兜底取数，**不用于判定资产类型**——
     * 资产类型由 {@link InstrumentResolver} 结合指数池判。
     */
    public static String guessMarket(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return null;
        }
        String code = rawCode.strip().toUpperCase(Locale.ROOT);
        if (code.startsWith("SH") || code.startsWith("SZ")) {
            return code.substring(0, 2);
        }
        if (code.startsWith("399")) {
            return "SZ";
        }
        if (code.startsWith("93") || code.startsWith("H3")) {
            return "CSI";
        }
        char first = code.charAt(0);
        return switch (first) {
            case '6', '5', '9' -> "SH";
            case '0', '1', '2', '3', '4', '8' -> "SZ";
            default -> null;
        };
    }

    @Override
    public String toString() {
        return key();
    }
}
