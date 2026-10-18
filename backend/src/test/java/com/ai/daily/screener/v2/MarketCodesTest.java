package com.ai.daily.screener.v2;

import com.ai.daily.warehouse.InstrumentCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代码形态映射。**拿不准就返回 null**——宁可少发一次请求，
 * 也不要发一个猜出来的代码让源回空，再把空响应当成「源说没有这只标的」。
 */
class MarketCodesTest {

    @Test
    void stockSecidAndQuoteSymbolFollowExchange() {
        assertThat(MarketCodes.secid(InstrumentCode.stock("600519", "SH"))).isEqualTo("1.600519");
        assertThat(MarketCodes.secid(InstrumentCode.stock("300274", "SZ"))).isEqualTo("0.300274");
        assertThat(MarketCodes.quoteSymbol(InstrumentCode.stock("600519", "SH")))
                .isEqualTo("sh600519");
        assertThat(MarketCodes.quoteSymbol(InstrumentCode.stock("300274", "SZ")))
                .isEqualTo("sz300274");
    }

    @Test
    void indexesKeepTheirPrefixOnlyInInstrumentKey() {
        assertThat(MarketCodes.secid(InstrumentCode.index("SH000300", "SH"))).isEqualTo("1.000300");
        assertThat(MarketCodes.secid(InstrumentCode.index("SZ399006", "SZ"))).isEqualTo("0.399006");
        assertThat(MarketCodes.secid(InstrumentCode.index("H30533", "CSI"))).isEqualTo("2.H30533");
        assertThat(MarketCodes.quoteSymbol(InstrumentCode.index("SH000300", "SH")))
                .isEqualTo("sh000300");
        // 中证独立指数没有 sh/sz 形态；猜一个 sz/h30533 发出去只会拿到空响应。
        assertThat(MarketCodes.quoteSymbol(InstrumentCode.index("H30533", "CSI"))).isNull();
    }

    @Test
    void offshoreAndUnknownMarketsReturnNull() {
        assertThat(MarketCodes.secid(InstrumentCode.index("NDX", "OFFSHORE"))).isNull();
        assertThat(MarketCodes.quoteSymbol(InstrumentCode.index("NDX", "OFFSHORE"))).isNull();
        assertThat(MarketCodes.secid(InstrumentCode.stock("00700", "HK"))).isNull();
    }

    @Test
    void stripExchangeHandlesPoolAndIndexPrefixes() {
        assertThat(MarketCodes.stripExchange("SH000300")).isEqualTo("000300");
        assertThat(MarketCodes.stripExchange("SZ399006")).isEqualTo("399006");
        assertThat(MarketCodes.stripExchange("CSIH30533")).isEqualTo("H30533");
        assertThat(MarketCodes.stripExchange("300274")).isEqualTo("300274");
        assertThat(MarketCodes.stripExchange(null)).isNull();
    }

    @Test
    void quoteableRawRequiresSixDigits() {
        assertThat(MarketCodes.hasQuoteableRaw(InstrumentCode.stock("600519", "SH"))).isTrue();
        assertThat(MarketCodes.hasQuoteableRaw(InstrumentCode.index("H30533", "CSI"))).isFalse();
    }
}
