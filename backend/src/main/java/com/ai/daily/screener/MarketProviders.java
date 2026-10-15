package com.ai.daily.screener;

import java.net.URI;

/**
 * 「这条请求在打谁」：把 URI 归到一个 provider 键上。
 *
 * <p>冷却、计数、限速三件事共用这一个键，所以映射**必须只有一份实现**。分散成两三处之后，
 * 同一个源在不同表里会变成两个名字——页面写着「腾讯被限流」而计数落进另一个桶，
 * 两边都不报错。这正是 {@link ScreenerCache} 当初把冷却从「只有一个 cooldownUntil」
 * 改成按源分键时要消灭的那类问题，别再制造一次。
 *
 * <p><b>东财三个域名必须归成同一个键</b>：datacenter-web / push2 / push2his 是三个域名、
 * 一份 IP 配额。按域名分桶的话，一次筛选扫描会同时在三个桶里各自「还没超限」，
 * 而实际打出去的是同一个 IP 上的同一个计数——限速等于没做。
 *
 * <p><b>按域名判定，而不是让调用方传 header</b>：域名是「在打谁」的事实，header 是调用方的声明。
 * 声明漏传一次就静默落进未知桶、拿不到限速。{@code AltQuoteSource} 绕过
 * {@link MarketDataClient#fetchJson} 自己实现传输这件事，说明「靠调用方自觉」的漏点是真实存在的，
 * 不是假想。
 */
final class MarketProviders {

    private MarketProviders() {
    }

    /**
     * 认不出的域名**归到它自己**，不是一个共用的「其它」桶：未知源之间没有任何理由分享
     * 同一份额度，混在一起会让新接入的源替老源背限流。
     */
    static String of(URI uri) {
        String host = uri == null ? null : uri.getHost();
        if (host == null) {
            return "unknown";
        }
        return switch (host) {
            case "datacenter-web.eastmoney.com",
                 "push2.eastmoney.com",
                 "push2his.eastmoney.com" -> MarketDataClient.PROVIDER_EASTMONEY;
            // 下面两个也是东财的域名，但**刻意不并入上面那一键**（章程 §9.9）。
            // 归一键管的是「同一封禁面内的配额分摊」——那说的是扫描这种批量高频行为，
            // 三个行情域共用一份 IP 配额，按域名分桶等于没做限速。
            // 这两条管的是**不同封禁面之间的隔离**：合成的代价很具体——一次新闻限流
            // 会把整条价格主链锁死 10 分钟，是纯粹的降级放大。生产证据
            // （CodeLookupService 顶部注释）已记录过 push2 被封而 datacenter-web 仍 200，
            // 说明封禁面并不总是同一片。
            case "np-anotice-stock.eastmoney.com" -> StockNoticeClient.ORIGIN;
            case "search-api-web.eastmoney.com" -> EastMoneyNewsClient.ORIGIN;
            case "news.google.com" -> PolicyNewsClient.ORIGIN;
            case "qt.gtimg.cn", "web.ifzq.gtimg.cn" -> AltQuoteSource.PROVIDER_TENCENT;
            case "hq.sinajs.cn" -> AltQuoteSource.PROVIDER_SINA;
            case "www.csindex.com.cn", "csindex.com.cn" -> IndexFundPool.SOURCE_CSINDEX;
            case "danjuanfunds.com", "www.danjuanfunds.com" -> IndexFundPool.SOURCE_DANJUAN;
            default -> host;
        };
    }
}