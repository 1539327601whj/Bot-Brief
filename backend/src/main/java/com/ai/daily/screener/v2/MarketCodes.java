package com.ai.daily.screener.v2;

import com.ai.daily.warehouse.InstrumentCode;

import java.util.Locale;

/**
 * 把 {@link InstrumentCode} 翻译成各源要的代码形态。
 *
 * <p>这是 v2 自己的映射，**不借用 v1 的**：v1 的 {@code AltQuoteSource.tencentSymbol}
 * 刻意只认基金段（5/1 开头），它服务的是 ETF 块；v2 要覆盖个股，塞回那条映射
 * 会让 v1 的「判断不了就跳过」失去意义。
 *
 * <p>拿不准时返回 {@code null}，表示「这个源表示不了这只标的」——调用方据此**不发请求**。
 * 拼一个猜出来的代码发出去，源回空，会被读成「源说没有这只标的」，比不请求更难查。
 */
final class MarketCodes {

    private MarketCodes() {
    }

    /**
     * 东财 secid：{@code 市场.代码}。
     *
     * <ul>
     *   <li>沪市股票/ETF/指数 → {@code 1.xxxxxx}</li>
     *   <li>深市 → {@code 0.xxxxxx}</li>
     *   <li>中证独立指数（93/H3 开头）→ {@code 2.xxxxxx}（东财把中证单独放在市场 2）</li>
     *   <li>海外指数 → null：东财的市场编号因指数而异，不猜。</li>
     * </ul>
     */
    static String secid(InstrumentCode code) {
        if (code == null) return null;
        String raw = stripExchange(code.rawCode());
        if (raw == null) return null;
        if (code.isIndex()) {
            String market = code.market() == null ? "" : code.market().toUpperCase(Locale.ROOT);
            return switch (market) {
                case "SH" -> "1." + raw;
                case "SZ" -> "0." + raw;
                case "CSI" -> "2." + raw;
                default -> null;
            };
        }
        String market = code.market() == null ? "" : code.market().toUpperCase(Locale.ROOT);
        return switch (market) {
            case "SH" -> "1." + raw;
            case "SZ", "BJ" -> "0." + raw;
            default -> null;
        };
    }

    /**
     * 腾讯/新浪符号：{@code sh600519} / {@code sz300274}。
     *
     * <p>指数只有沪/深两市的有这个形态；中证独立指数与海外指数返回 null。
     */
    static String quoteSymbol(InstrumentCode code) {
        if (code == null) return null;
        String raw = stripExchange(code.rawCode());
        if (raw == null || !raw.matches("\\d{6}")) return null;
        String market = code.market() == null ? "" : code.market().toUpperCase(Locale.ROOT);
        if ("SH".equals(market)) return "sh" + raw;
        if ("SZ".equals(market) || ("BJ".equals(market) && !code.isIndex())) return "sz" + raw;
        return null;
    }

    /** 裸证券代码。指数键里的 {@code SH}/{@code SZ} 前缀要去掉；{@code CSI} 前缀同理。 */
    static String stripExchange(String rawCode) {
        if (rawCode == null) return null;
        String value = rawCode.strip().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        if (value.startsWith("SH") || value.startsWith("SZ") || value.startsWith("CSI")) {
            return value.substring(value.startsWith("CSI") ? 3 : 2);
        }
        return value;
    }

    /**
     * 东财日线/行情接口只认 6 位数字（股票、ETF）或 6 位指数代码；
     * 字母开头的指数（H30533）在行情接口里没有稳定形态。
     */
    static boolean hasQuoteableRaw(InstrumentCode code) {
        String raw = stripExchange(code == null ? null : code.rawCode());
        return raw != null && raw.matches("\\d{6}");
    }
}
