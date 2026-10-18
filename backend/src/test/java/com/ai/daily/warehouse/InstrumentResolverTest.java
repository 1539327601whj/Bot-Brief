package com.ai.daily.warehouse;

import com.ai.daily.screener.IndexFundPool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 代码解析。重点不是「能解析」，而是**歧义代码落到哪一边**：
 * 同一个 6 位数字在不同资产类型下是不同标的，解析错了页面照样有数，很难发现。
 */
class InstrumentResolverTest {

    private final IndexFundPool pool = mock(IndexFundPool.class);
    private final InstrumentResolver resolver = new InstrumentResolver(pool);

    @Test
    void resolvesAShanghaiStock() {
        InstrumentCode code = resolver.resolve("600519");
        assertThat(code.key()).isEqualTo("STOCK:600519");
        assertThat(code.assetType()).isEqualTo(InstrumentCode.STOCK);
        assertThat(code.market()).isEqualTo("SH");
    }

    @Test
    void resolvesAShenzhenStock() {
        InstrumentCode code = resolver.resolve("300274");
        assertThat(code.key()).isEqualTo("STOCK:300274");
        assertThat(code.market()).isEqualTo("SZ");
    }

    @Test
    void resolvesAnEtfByCodeShape() {
        InstrumentCode code = resolver.resolve("510300");
        assertThat(code.key()).isEqualTo("ETF:510300");
        assertThat(code.market()).isEqualTo("SH");

        InstrumentCode shenzhen = resolver.resolve("159915");
        assertThat(shenzhen.key()).isEqualTo("ETF:159915");
        assertThat(shenzhen.market()).isEqualTo("SZ");
    }

    @Test
    void resolvesAKnownIndex() {
        InstrumentCode code = resolver.resolve("000300");
        // 与 Python warehouse 的 md_instrument 对齐：池内指数保留交易所前缀。
        assertThat(code.key()).isEqualTo("INDEX:SH000300");
        assertThat(code.market()).isEqualTo("SH");
    }

    @Test
    void resolvesACsindexCodeThroughThePool() {
        // 930740 不在 KNOWN_INDEX_CODES 里，单看形态会被当成个股；
        // 指数池里能找到它时才应该是指数。
        when(pool.byCsindexCode("930740"))
                .thenReturn(fund("SH930740", "930740", "512100"));

        InstrumentCode code = resolver.resolve("930740");
        assertThat(code.key()).isEqualTo("INDEX:SH930740");
        assertThat(code.market()).isEqualTo("SH");
    }

    @Test
    void theSameBareCodeStillDefaultsToAStock() {
        // 000001 裸着是平安银行。这是最高频的输入，不能因为上证指数也叫 000001 就改口径。
        InstrumentCode code = resolver.resolve("000001");
        assertThat(code.key()).isEqualTo("STOCK:000001");
        // 平安银行在深市；上证指数要写成 SH000001 才落到指数。
        assertThat(code.market()).isEqualTo("SZ");
    }

    @Test
    void anExplicitExchangePrefixDisambiguatesAnIndex() {
        // 用户写 SH000001 时是在明确要上证指数；写成 SH600519 仍然是股票。
        assertThat(resolver.resolve("SH000001").key()).isEqualTo("INDEX:SH000001");
        assertThat(resolver.resolve("SH600519").key()).isEqualTo("STOCK:600519");
        assertThat(resolver.resolve("SZ399006").key()).isEqualTo("INDEX:SZ399006");
        assertThat(resolver.resolve("SZ000001").key()).isEqualTo("STOCK:000001");
    }

    @Test
    void resolvesAnEtfListedInThePoolBeforeFallingBackToStock() {
        // 池内的 ETF 优先：即便某个代码形态上像个股，只要池子认它，就是 ETF。
        when(pool.byEtfCode("510300"))
                .thenReturn(fund("SH000300", "000300", "510300"));

        assertThat(resolver.resolve("510300").key()).isEqualTo("ETF:510300");
    }

    @Test
    void resolvesAnIndexPoolKeyWithExchangePrefix() {
        when(pool.byIndexCode("000905"))
                .thenReturn(fund("SH000905", "000905", "510500"));

        InstrumentCode code = resolver.resolve("000905");
        assertThat(code.key()).isEqualTo("INDEX:SH000905");
        assertThat(code.market()).isEqualTo("SH");
    }

    @Test
    void preservesALeadingLetterIndexCode() {
        assertThat(resolver.resolve("h30533").key()).isEqualTo("INDEX:H30533");
    }

    @Test
    void rejectsBlankInput() {
        assertThatThrownBy(() -> resolver.resolve("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("代码");
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static IndexFundPool.Fund fund(String indexCode, String csindexCode, String etfCode) {
        return new IndexFundPool.Fund(
                indexCode, "测试指数", "broad", "csindex",
                IndexFundPool.METHOD_CSINDEX_ROLLING_10Y, csindexCode, null,
                etfCode, etfCode.startsWith("5") ? 1 : 0, "测试ETF", null);
    }
}
