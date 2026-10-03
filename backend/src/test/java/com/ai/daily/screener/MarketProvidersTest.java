package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冷却、计数、限速共用的那个 provider 键。它错一次，三张表就一起错，
 * 而且是**静默**错——页面上写着「腾讯被限流」，计数却落在另一个桶里。
 */
class MarketProvidersTest {

    @Test
    void theThreeEastmoneyHostsShareOneKey() {
        // 这条是本类存在的全部理由：三个域名、一份 IP 配额。
        // 按域名分桶的话，一次筛选扫描会同时在三个桶里各自「还没超限」，
        // 而实际打出去的是同一个 IP 上的同一个计数——限速等于没做。
        assertThat(MarketProviders.of(URI.create("https://datacenter-web.eastmoney.com/api/data/v1/get")))
                .isEqualTo(MarketDataClient.PROVIDER_EASTMONEY);
        assertThat(MarketProviders.of(URI.create("https://push2.eastmoney.com/api/qt/ulist.np/get")))
                .isEqualTo(MarketDataClient.PROVIDER_EASTMONEY);
        assertThat(MarketProviders.of(URI.create("https://push2his.eastmoney.com/api/qt/stock/kline/get")))
                .isEqualTo(MarketDataClient.PROVIDER_EASTMONEY);
    }

    @Test
    void tencentAndSinaAreTheirOwnKeys() {
        // AltQuoteSource 会并发打这两个源，它们各有各的额度，不能混。
        assertThat(MarketProviders.of(URI.create("https://qt.gtimg.cn/q=sh510300")))
                .isEqualTo(AltQuoteSource.PROVIDER_TENCENT);
        assertThat(MarketProviders.of(URI.create("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get")))
                .isEqualTo(AltQuoteSource.PROVIDER_TENCENT);
        assertThat(MarketProviders.of(URI.create("https://hq.sinajs.cn/list=sh510300")))
                .isEqualTo(AltQuoteSource.PROVIDER_SINA);
    }

    @Test
    void csindexAndDanjuanUseTheSameNamesTheValuationSideAlreadyUses() {
        // 复用 IndexFundPool 那两个常量，不另起名字：估值分位那边已经按这两个值
        // 记口径，两处叫法不一致时「这个源的数来自哪」就对不上了。
        assertThat(MarketProviders.of(URI.create("https://www.csindex.com.cn/csindex-home/perf/indexCsiDsPe")))
                .isEqualTo(IndexFundPool.SOURCE_CSINDEX);
        assertThat(MarketProviders.of(URI.create("https://danjuanfunds.com/djapi/index_eva/dj")))
                .isEqualTo(IndexFundPool.SOURCE_DANJUAN);
    }

    @Test
    void anUnknownHostBecomesItsOwnBucket() {
        // **不是一个共用的「其它」桶**：未知源之间没有理由分享额度，
        // 混在一起会让新接入的源替老源背限流。
        assertThat(MarketProviders.of(URI.create("https://example.com/x"))).isEqualTo("example.com");
        assertThat(MarketProviders.of(URI.create("https://other.example.com/x")))
                .isEqualTo("other.example.com");
    }

    @Test
    void anAddressWithNoHostDoesNotBlowUp() {
        assertThat(MarketProviders.of(URI.create("/relative/path"))).isEqualTo("unknown");
        assertThat(MarketProviders.of(null)).isEqualTo("unknown");
    }
}