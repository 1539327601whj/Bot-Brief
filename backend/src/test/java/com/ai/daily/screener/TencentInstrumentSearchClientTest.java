package com.ai.daily.screener;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * smartbox 响应解析：这层只做纯文本处理，不碰网络。
 * 解析错一个字段，用户会搜到名字对、代码错的标的，比搜不到更糟。
 */
class TencentInstrumentSearchClientTest {

    private static TencentInstrumentSearchClient client() {
        return new TencentInstrumentSearchClient(mock(RestTemplate.class));
    }

    @Test
    void parsesTypedHintsAndUnescapesChineseNames() {
        String body = "v_hint=\"sh~600519~\\u8d35\\u5dde\\u8305\\u53f0~gzmt~GP-A"
                + "^sz~159915~\\u521b\\u4e1a\\u677fETF~cybetf~ETF"
                + "^sh~000001~\\u4e0a\\u8bc1\\u6307\\u6570~szzs~ZS\"";

        List<TencentInstrumentSearchClient.Hint> hints = TencentInstrumentSearchClient.parse(body);

        assertThat(hints).hasSize(3);
        assertThat(hints.get(0).market()).isEqualTo("SH");
        assertThat(hints.get(0).code()).isEqualTo("600519");
        assertThat(hints.get(0).name()).isEqualTo("贵州茅台");
        assertThat(hints.get(0).pinyin()).isEqualTo("gzmt");
        assertThat(hints.get(0).type()).isEqualTo("GP-A");
        assertThat(hints.get(1).name()).isEqualTo("创业板ETF");
        assertThat(hints.get(2).type()).isEqualTo("ZS");
    }

    @Test
    void keepsOnlyResolvableTypesAndDropsBrokenRows() {
        String body = "v_hint=\"sh~600519~\\u8305\\u53f0~gzmt~GP-A"
                + "^hk~00700~\\u817e\\u8baf~tx~GP-HK"
                + "^sh~501018~\\u539f\\u6cb9LOF~yylof~LOF"
                + "^sz~~\\u65e0\\u4ee3\\u7801~wudm~GP-A"
                + "^sz~300750~\\u5b81\\u5fb7~nd~GP-A-CYB\"";

        List<TencentInstrumentSearchClient.Hint> supported =
                TencentInstrumentSearchClient.supported(TencentInstrumentSearchClient.parse(body));

        assertThat(supported).extracting(TencentInstrumentSearchClient.Hint::code)
                .containsExactly("600519", "300750");
    }

    @Test
    void emptyHintsAndLimitMarkerYieldNothing() {
        assertThat(TencentInstrumentSearchClient.parse(null)).isEmpty();
        assertThat(TencentInstrumentSearchClient.parse("")).isEmpty();
        assertThat(TencentInstrumentSearchClient.parse("v_hint=\"N\"")).isEmpty();
        assertThat(TencentInstrumentSearchClient.parse("not a hint payload")).isEmpty();
    }

    @Test
    void submitCodePrefixesIndicesButNotStocksOrEtfs() {
        TencentInstrumentSearchClient.Hint index = new TencentInstrumentSearchClient.Hint(
                "sh", "000001", "上证指数", "szzs", "ZS");
        TencentInstrumentSearchClient.Hint stock = new TencentInstrumentSearchClient.Hint(
                "sh", "600519", "贵州茅台", "gzmt", "GP-A");
        TencentInstrumentSearchClient.Hint etf = new TencentInstrumentSearchClient.Hint(
                "sz", "159915", "创业板ETF", "cybetf", "ETF");

        assertThat(TencentInstrumentSearchClient.submitCode(index)).isEqualTo("SH000001");
        assertThat(TencentInstrumentSearchClient.submitCode(stock)).isEqualTo("600519");
        assertThat(TencentInstrumentSearchClient.submitCode(etf)).isEqualTo("159915");
    }

    @Test
    void keywordIsUrlEncodedWithPercentTwentyForSpaces() {
        assertThat(TencentInstrumentSearchClient.encode("贵州 茅台"))
                .isEqualTo("%E8%B4%B5%E5%B7%9E%20%E8%8C%85%E5%8F%B0");
    }

    @Test
    void blankKeywordNeverReachesTheNetwork() {
        assertThat(client().search(null, 8)).isEmpty();
        assertThat(client().search("   ", 8)).isEmpty();
    }
}
