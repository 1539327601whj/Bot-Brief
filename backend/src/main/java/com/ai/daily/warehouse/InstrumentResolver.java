package com.ai.daily.warehouse;

import com.ai.daily.screener.IndexFundPool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 把用户输入的裸代码解析成 {@link InstrumentCode}。
 *
 * <p><b>顺序很重要</b>：
 * <ol>
 *   <li>先查指数池的代表 ETF：{@code 510300} 这类代码进了池子就一定是 ETF；</li>
 *   <li>再查指数池的中证入参/池内键：{@code 000300}、{@code H30533}；</li>
 *   <li>再按显式前缀（{@code SH}/{@code SZ}）与代码形态判断；</li>
 *   <li>都没有才落到个股。</li>
 * </ol>
 *
 * <p><b>{@code 000001} 默认按个股（平安银行）解析</b>，不按上证指数。理由很直接：
 * 这个页面是「输入一只股票代码」的入口，用户打 000001 时几乎总是查平安银行；
 * 要查上证指数应写成 {@code SH000001}。把歧义代码默认成指数，会让最高频的输入
 * 落到一条完全不同的分析链上，而且页面看起来「有数」，很难发现查错了。
 */
@Component
@RequiredArgsConstructor
public class InstrumentResolver {

    /**
     * 常见指数代码。只列「裸代码在个股里不存在」的那批，避免把 000001 这类
     * 双关代码误判成指数。
     */
    private static final Set<String> KNOWN_INDEX_CODES = Set.of(
            "000300", "000905", "000852", "000016", "000688", "000906", "000985",
            "399001", "399005", "399006", "399300", "399905", "399852",
            "930740", "931643", "931079", "931151", "932000", "H30533"
    );

    private final IndexFundPool indexFundPool;

    public InstrumentCode resolve(String raw) {
        String code = normalize(raw);
        if (code == null) {
            throw new IllegalArgumentException("请输入代码");
        }

        // 用户显式写了交易所前缀：SH000300 / SZ399006。前缀只保留在 market，
        // rawCode 仍是裸代码，保证同一个标的在不同写法下落到同一个键。
        String explicitMarket = null;
        if (code.length() > 2 && (code.startsWith("SH") || code.startsWith("SZ"))) {
            explicitMarket = code.substring(0, 2);
            code = code.substring(2);
        }

        IndexFundPool.Fund etf = indexFundPool.byEtfCode(code);
        if (etf != null) {
            return InstrumentCode.etf(code, marketOfEtf(code));
        }

        IndexFundPool.Fund csindex = indexFundPool.byCsindexCode(code);
        if (csindex != null) {
            // 键必须与 Python warehouse 写入的 md_instrument 完全一致：那边用的是池子里的
            // indexCode 原样（SH000300 / CSIH30533 / NDX），**不剥交易所前缀**。
            // 这里剥掉的话，同一个指数在 Java 与 Python 眼里会变成两个标的，
            // 盘后同步的数据 Java 永远读不到。
            return InstrumentCode.index(csindex.indexCode(), marketOfIndex(csindex.indexCode()));
        }
        IndexFundPool.Fund poolIndex = indexFundPool.byIndexCode(code);
        if (poolIndex != null) {
            return InstrumentCode.index(poolIndex.indexCode(), marketOfIndex(poolIndex.indexCode()));
        }

        if (explicitMarket != null && isExchangeIndexShaped(code, explicitMarket)) {
            // SH000300 与裸 000300 必须落到同一个键；池内键带前缀，这里也带上。
            return InstrumentCode.index(explicitMarket + code, explicitMarket);
        }
        if (KNOWN_INDEX_CODES.contains(code) || code.startsWith("399")) {
            // 与 Python 的 index_market 对齐：399xxx 是深证，93/H3 是中证，其余按沪市。
            String rawIndex;
            if (code.startsWith("399")) {
                rawIndex = "SZ" + code;
            } else if (code.startsWith("93") || code.startsWith("H3")) {
                rawIndex = code;
            } else {
                rawIndex = "SH" + code;
            }
            return InstrumentCode.index(rawIndex, marketOfIndex(rawIndex));
        }
        if (isEtfShaped(code)) {
            return InstrumentCode.etf(code, marketOfEtf(code));
        }
        return InstrumentCode.stock(code, InstrumentCode.guessMarket(code));
    }

    /** 只做去空白、大写、去前缀；不校验长度（指数代码可能是 H30533）。 */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        return value;
    }

    /**
     * ETF 形态：5 开头（沪市 ETF）或 1 开头（深市 ETF/LOF）。
     * 这条只在指数池没匹配上时使用；池内的 ETF 已经在前面一步命中。
     */
    static boolean isEtfShaped(String code) {
        if (code == null || code.length() != 6) {
            return false;
        }
        return code.startsWith("5") || code.startsWith("1");
    }

    /**
     * 带交易所前缀时怎么判断「这是指数」。
     *
     * <p>不能沿用裸代码的 {@code isIndexShaped}：{@code 000001} 裸着是平安银行，
     * 而 {@code SH000001} 在上证是上证指数。用户写下前缀，本身就是在消歧。
     * 但也不能把「带前缀」直接当指数——{@code SH600519} 仍然是贵州茅台。
     *
     * <p>按交易所的代码段判：沪市指数是 {@code 000xxx}，深市指数是 {@code 399xxx}。
     */
    static boolean isExchangeIndexShaped(String code, String market) {
        if (code == null || market == null) {
            return false;
        }
        if ("SH".equals(market)) {
            return code.length() == 6 && code.startsWith("000");
        }
        if ("SZ".equals(market)) {
            return code.startsWith("399");
        }
        return false;
    }

    static String marketOfEtf(String code) {
        return code.startsWith("5") ? "SH" : "SZ";
    }

    static String marketOfIndex(String code) {
        if (code == null) {
            return null;
        }
        String value = code.toUpperCase(Locale.ROOT);
        if (value.startsWith("SZ") || value.startsWith("399")) {
            return "SZ";
        }
        if (value.startsWith("CSI") || value.startsWith("93") || value.startsWith("H3")) {
            return "CSI";
        }
        if (value.startsWith("HK") || value.startsWith("NDX") || value.startsWith("SP500")
                || value.startsWith("GDAXI") || value.startsWith("HSFML")) {
            return "OFFSHORE";
        }
        return "SH";
    }

    static String stripExchange(String indexCode) {
        if (indexCode == null) {
            return null;
        }
        String value = indexCode.strip().toUpperCase(Locale.ROOT);
        if (value.startsWith("SH") || value.startsWith("SZ")) {
            return value.substring(2);
        }
        return value;
    }
}
