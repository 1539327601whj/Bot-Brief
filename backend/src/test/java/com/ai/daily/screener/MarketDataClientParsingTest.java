package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 东财返回值的解析与 URL 拼装测试。
 *
 * <p>字段 ID 与含义来自一次真实的探测（已与真实公司数值交叉核对），但**没有做过端到端批量验证**，
 * 所以这一层必须能离线自测。这里最要紧的三条：
 * 缺失值必须是 null 而不是 0（0 会被当成真实数值参与排雷与打分）、
 * 市场标识必须拼得出正确的 secid、
 * 以及两个接口对「编码」的要求**正好相反**（清单的 filter 必须原样，行情的 secids 必须保留下标点）。
 */
class MarketDataClientParsingTest {

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    void mapsEveryFieldWeActuallyScore() {
        Map<String, Object> raw = item(
                "f12", "600519", "f14", "贵州茅台",
                "f2", 1520.5, "f3", -1.24, "f6", 3.5e9, "f8", 0.42,
                "f20", 1.9e12, "f21", 1.9e12, "f23", 7.8,
                "f24", -8.5, "f25", 3.2, "f26", 20010827,
                "f37", 34.2, "f41", 9.7, "f46", 11.1, "f49", 91.5,
                "f57", 16.3, "f100", "酿酒行业",
                "f113", 195.0, "f115", 21.4, "f133", 2.9);

        StockRow r = MarketDataClient.toRow(raw);
        assertThat(r).isNotNull();
        assertThat(r.getCode()).isEqualTo("600519");
        assertThat(r.getName()).isEqualTo("贵州茅台");
        assertThat(r.getMarket()).isEqualTo(1); // 6 开头 → 沪市
        assertThat(r.secid()).isEqualTo("1.600519");
        assertThat(r.getIndustry()).isEqualTo("酿酒行业");
        assertThat(r.getPeTtm()).isEqualByComparingTo("21.4");
        assertThat(r.getPb()).isEqualByComparingTo("7.8");
        assertThat(r.getRoe()).isEqualByComparingTo("34.2");
        assertThat(r.getDebtRatio()).isEqualByComparingTo("16.3");
        assertThat(r.getDividendYield()).isEqualByComparingTo("2.9");
        assertThat(r.getListDate()).isEqualTo(LocalDate.of(2001, 8, 27));
        // 这两个只作背景展示，不能进打分
        assertThat(r.getChange60d()).isEqualByComparingTo("-8.5");
        assertThat(r.getYtdChange()).isEqualByComparingTo("3.2");
        assertThat(r.isIndustryUnknown()).isFalse();
    }

    @Test
    void shenzhenCodesGetTheOtherMarketFlag() {
        assertThat(MarketDataClient.toRow(item("f12", "000001", "f14", "平安银行")).secid())
                .isEqualTo("0.000001");
        assertThat(MarketDataClient.toRow(item("f12", "300750", "f14", "宁德时代")).secid())
                .isEqualTo("0.300750");
        assertThat(MarketDataClient.toRow(item("f12", "688981", "f14", "中芯国际")).secid())
                .isEqualTo("1.688981");
    }

    @Test
    void dashPlaceholdersBecomeNullNotZero() {
        Map<String, Object> raw = item(
                "f12", "600001", "f14", "某公司",
                "f2", 12.3, "f6", 5.0e8,
                "f23", "-", "f37", "-", "f41", "-", "f46", "-",
                "f49", "-", "f57", "-", "f115", "-", "f133", "-",
                "f26", "-", "f100", "-");

        StockRow r = MarketDataClient.toRow(raw);
        assertThat(r).isNotNull();
        assertThat(r.getPb()).isNull();
        assertThat(r.getRoe()).isNull();
        assertThat(r.getPeTtm()).isNull();
        assertThat(r.getDebtRatio()).isNull();
        assertThat(r.getDividendYield()).isNull();
        assertThat(r.getListDate()).isNull();
        assertThat(r.getIndustry()).isNull();
        assertThat(r.isIndustryUnknown()).isTrue();
        // 关键：0 会被当成「资产负债率 0%，非常安全」参与打分
        assertThat(r.getDebtRatio()).isNotEqualTo(BigDecimal.ZERO);
    }

    @Test
    void negativePeIsPreservedSoTheLossVetoCanFireOnIt() {
        StockRow r = MarketDataClient.toRow(item("f12", "600002", "f14", "亏损股", "f115", -7.4));
        assertThat(r.getPeTtm()).isEqualByComparingTo("-7.4");
        assertThat(ScreeningRules.vetoes(r, ScreeningRules.Bucket.STEADY,
                ScreenerParams.defaults(), LocalDate.of(2026, 10, 1)))
                .anyMatch(v -> v.rule().equals("V5"));
    }

    @Test
    void rowsWithoutACodeAreDroppedInsteadOfBecomingGarbage() {
        assertThat(MarketDataClient.toRow(item("f14", "无代码"))).isNull();
        assertThat(MarketDataClient.toRow(item("f12", "", "f14", "空代码"))).isNull();
        assertThat(MarketDataClient.toRow(item("f12", "600003"))).isNotNull();
    }

    @Test
    void malformedNumbersAndDatesAreTreatedAsMissingNotAsGarbage() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("f12", "600004");
        raw.put("f14", "某公司");
        raw.put("f2", "not-a-number");
        raw.put("f26", "20");

        StockRow r = MarketDataClient.toRow(raw);
        assertThat(r).isNotNull();
        assertThat(r.getPrice()).isNull();
        assertThat(r.getListDate()).isNull();
    }

    @Test
    void quoteFieldListMatchesTheScreeningCharter() {
        assertThat(MarketDataClient.QUOTE_FIELDS)
                .contains("f100", "f115", "f133", "f57", "f37", "f41", "f46", "f26", "f6", "f8");
        // 没有净利润绝对值字段：V5 只能靠 PE(TTM) 与净利同比确认盈利，这一点必须显式
        assertThat(MarketDataClient.QUOTE_FIELDS).doesNotContain("f45");
    }

    // ------------------------------------------------------------------
    // 清单记录 → 标的
    // ------------------------------------------------------------------

    @Test
    void listRowKeepsOnlyShanghaiAndShenzhen() {
        // 北交所流动性与口径都另说，章程里明确不纳入
        assertThat(MarketDataClient.fromListRow(item(
                "SECURITY_CODE", "920014", "SECUCODE", "920014.BJ",
                "SECURITY_NAME_ABBR", "特瑞斯", "TOTAL_MARKET_CAP", 1.04e9))).isNull();
    }

    @Test
    void listRowWithoutAMarketSuffixIsDropped() {
        assertThat(MarketDataClient.fromListRow(item(
                "SECURITY_CODE", "600519", "SECURITY_NAME_ABBR", "贵州茅台"))).isNull();
        assertThat(MarketDataClient.fromListRow(item(
                "SECUCODE", "600519.SH", "SECURITY_NAME_ABBR", "贵州茅台"))).isNull();
    }

    @Test
    void listRowCarriesCodeNameMarketAndMarketCap() {
        StockRow sh = MarketDataClient.fromListRow(item(
                "SECURITY_CODE", "600519", "SECUCODE", "600519.SH",
                "SECURITY_NAME_ABBR", "贵州茅台", "TOTAL_MARKET_CAP", 1.9e12));
        assertThat(sh).isNotNull();
        assertThat(sh.getCode()).isEqualTo("600519");
        assertThat(sh.getName()).isEqualTo("贵州茅台");
        assertThat(sh.getMarket()).isEqualTo(1);
        assertThat(sh.secid()).isEqualTo("1.600519");
        assertThat(sh.getTotalMarketCap()).isEqualByComparingTo("1900000000000");

        StockRow sz = MarketDataClient.fromListRow(item(
                "SECURITY_CODE", "000001", "SECUCODE", "000001.SZ",
                "SECURITY_NAME_ABBR", "平安银行", "TOTAL_MARKET_CAP", 2.5e11));
        assertThat(sz.getMarket()).isEqualTo(0);
        assertThat(sz.secid()).isEqualTo("0.000001");

        // 清单只负责「谁在池子里」；基本面一律以行情接口为准，所以这里不该有 PE/PB/行业
        assertThat(sh.getPeTtm()).isNull();
        assertThat(sh.getIndustry()).isNull();
    }

    @Test
    void listRowToleratesALowercaseMarketSuffix() {
        assertThat(MarketDataClient.fromListRow(item(
                "SECURITY_CODE", "600519", "SECUCODE", "600519.sh")))
                .isNotNull().extracting(StockRow::getMarket).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // URL 拼装：两个接口的编码要求正好相反
    // ------------------------------------------------------------------

    @Test
    void listUrlKeepsTheFilterRawBecauseTheServerDoesNotUrlDecodeIt() {
        // 实测：把 filter 百分号编码，服务端回 `参数预处理错误: NoViableAltException`。
        // 它用 ANTLR 解析这个参数，且不做 URL 解码——与 clist 的 fs（必须编码）正好相反。
        String url = MarketDataClient.listUrl(MarketDataClient.LIST_COLUMNS, 500,
                "TOTAL_MARKET_CAP", -1, LocalDate.of(2026, 9, 30));

        assertThat(url).contains("&filter=(TRADE_DATE='2026-09-30')");
        assertThat(url).doesNotContain("%28").doesNotContain("%29")
                .doesNotContain("%27").doesNotContain("%3D");
        // 必须能被 URI 直接解析：括号、引号、等号都是 RFC 3986 的 sub-delims，无需转义
        assertThat(java.net.URI.create(url).getQuery()).contains("(TRADE_DATE='2026-09-30')");
    }

    @Test
    void listUrlAsksForThePoolSizeAndSortsByMarketCapDescending() {
        String url = MarketDataClient.listUrl(MarketDataClient.LIST_COLUMNS, 500,
                "TOTAL_MARKET_CAP", -1, LocalDate.of(2026, 9, 30));
        assertThat(url).contains("reportName=" + MarketDataClient.LIST_REPORT);
        assertThat(url).contains("pageSize=500");
        assertThat(url).contains("sortColumns=TOTAL_MARKET_CAP");
        assertThat(url).contains("sortTypes=-1");
        // columns 里的逗号保持原样
        assertThat(url).contains("columns=" + MarketDataClient.LIST_COLUMNS);
        assertThat(url).doesNotContain("columns=" + MarketDataClient.LIST_COLUMNS.replace(",", "%2C"));
    }

    @Test
    void listUrlOmitsTheFilterWhenThereIsNoTradeDate() {
        // 取「最新交易日」那一次不能带 filter，否则就套住了某一天
        String url = MarketDataClient.listUrl("TRADE_DATE", 1, "TRADE_DATE", -1, null);
        assertThat(url).doesNotContain("filter=");
        assertThat(url).contains("sortColumns=TRADE_DATE");
    }

    @Test
    void ulistUrlKeepsSecidDotsAndCommasRaw() {
        String url = MarketDataClient.ulistUri(List.of("1.510300", "0.159915")).toString();
        // 点被转义后东财就认不出 secid 了；逗号是分隔符，也不能变成 %2C 之外的形态
        assertThat(url).contains("secids=1.510300,0.159915");
        assertThat(url).doesNotContain("secids=1%2E510300");
        assertThat(url).contains("fields=" + MarketDataClient.QUOTE_FIELDS);
    }

    @Test
    void listAndQuoteAndKlineLiveOnThreeDifferentHosts() {
        // 清单域名与行情域名分开是有意的：行情域名被限流时清单域名通常还活着，
        // 这正好提供了「是被限流」而不是「网络断了」的判断依据。
        assertThat(MarketDataClient.LIST_HOST).isNotEqualTo(MarketDataClient.QUOTE_HOST);
        assertThat(MarketDataClient.KLINE_HOST).isNotEqualTo(MarketDataClient.QUOTE_HOST);
        assertThat(MarketDataClient.LIST_HOST).doesNotContain("push2");
    }
}