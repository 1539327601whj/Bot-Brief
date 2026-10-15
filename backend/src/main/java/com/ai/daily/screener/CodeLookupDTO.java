package com.ai.daily.screener;

import java.math.BigDecimal;
import java.util.List;

/**
 * 「代码查询」页的返回体。
 *
 * <p><b>缺数据一律走 {@code status}，不留裸 null、不用 0 顶缺</b>（章程 §6）：
 * 每一格要么给出数字，要么给出「为什么没有」的那句话。前端据此把那格渲染成文案，
 * 标签照留——这样用户看得到「五年」这一档存在、只是这次取不到，而不是以为这个版式没有五年。
 *
 * <p><b>来源与口径必须随数字一起给出去</b>（章程 §8：能回答「用了哪些数字、哪一日、哪一口径」）：
 * 价格、估值各自带来源名，估值还带 {@code percentileMethod}、数据日与历史长度。
 * 没有这几样，页面上的「同一只标的两个 PE」就只能靠用户自己发现。
 *
 * <p>全是不可变记录，DTO 层不做任何计算。
 */
public record CodeLookupDTO(
        /** 用户输入的原文（已去首尾空白）。 */
        String code,
        /** 实际拿去查询的代码：池内 {@code SH000300}、东财 secid、蛋卷的 {@code NDX}。 */
        String resolvedCode,
        String name,
        Kind kind,
        /** 类型的页面写法，形如「池内指数（沪深300）」。 */
        String kindLabel,
        QuoteView quote,
        /**
         * 基本面快照。**与 {@code quote} 同源、同一次外呼**——这些字段本来就随行情接口回来了
         * （{@code MarketDataClient.QUOTE_FIELDS} 里的 {@code f37/f41/f46/f49/f57/f133/f8/f24/f25}），
         * 以前只是没有端出来。所以这一块**没有增加任何外呼**。
         */
        FundamentalsView fundamentals,
        /**
         * 价格行的「今」：价格序列的**最后一点**，与 {@code priceLookbacks} 的各档基线同源。
         *
         * <p>为什么不直接让前端取 {@code quote.price}：池外指数**不取现价**（指数代码在报价源里
         * 没有可靠形态），那些标的没有 {@code quote}，可价格八档是照常算出来的。少了这个字段，
         * 页面就只能印出「昨 4.050｜周 …」而没有今天——一行以「昨」开头的回看是读不通的。
         *
         * <p>{@code quote} 存在时页面优先用它（与 ETF 日报一致：日报的「今」取的就是报价里的最新价），
         * 这个字段是它缺席时的同一根日线的最后一点。
         */
        BigDecimal currentPrice,
        String currentPriceDate,
        /** 价格八档：昨/周/月/半年/一年/三年/五年/十年。 */
        List<LookbackCell> priceLookbacks,
        PositionView position,
        ValuationView valuation,
        /** 口径摘要：本次用了哪个源、做了哪些降级。给用户看的，不是给日志看的。 */
        List<String> notes,
        /** 这份结果是什么时候取的（ISO-8601）。命中进程内缓存时是**缓存写入时刻**，不是现在。 */
        String snapshotAt,
        /** 是否取自进程内缓存（约 {@value CodeLookupService#CACHE_TTL_SECONDS} 秒）。 */
        boolean fromCache
) {

    /**
     * 判成哪一类。**类型决定取数路径**，所以必须显式给出来：
     * 同一个 {@code 000905} 既是深市股票代码也是中证500，走错路径会拿到一只完全无关的票，
     * 而页面上只看到一个名字——用户得能看出页面是按哪一类理解的。
     */
    public enum Kind {
        /** 池内指数，输入的是它的代表 ETF 代码（如 510300）。 */
        INDEX_FUND,
        /** 池内指数，输入的是裸指数代码（如 000300、SH000300、NDX）。 */
        INDEX,
        /** 池外指数：不在 42 只池子里，按需实时取，不进库。 */
        OFF_POOL_INDEX,
        /** 个股。 */
        STOCK,
        /** 基金（ETF/LOF），不在池里的。 */
        FUND
    }

    /** 一档基线，与 {@link LookbackCalculator.Cell} 逐字段对应；{@code baseline == null} ⟺ {@code status != null}。 */
    public record LookbackCell(String label, BigDecimal baseline, String baselineDate,
                               BigDecimal change, String status) {
        public boolean present() {
            return baseline != null;
        }
    }

    /**
     * 现价那一段。
     *
     * <p>{@code provider} 是这份行情**实际来自哪个源**——兜底链启用后「有值」与「值可信」
     * 不再等价（新浪那一路不给市值），页面要写得出这个数是从哪来的。
     *
     * <p>这里**没有「报价日期」这个字段**：东财 {@code ulist} 根本不返回报价所属交易日
     * （{@code f26} 是上市日，不是成交日）。硬填一个就是编的。报价所属的交易日由
     * {@link PositionView#lastTradeDate()} 给——那是日线的最后一根，是真实存在的日期。
     */
    public record QuoteView(String provider, String providerLabel, String name,
                            BigDecimal price, BigDecimal pctChange,
                            BigDecimal peTtm, BigDecimal pb, String industry,
                            BigDecimal totalMarketCap) {}

    /**
     * 基本面与动量快照，全部来自行情接口那一次外呼。
     *
     * <p><b>每一项都可能为 null，缺哪一项就写哪一项的 {@code notes}，不用 0 顶缺</b>（章程 §6）。
     * 这里不做「可用 / 不可用」以外的任何判断——阈值判定属于后续的分析段，不在 DTO 层。
     *
     * @param available       这次到底有没有拿到任何一项。false 时下面全是 null，{@code notes} 说明原因
     * @param provider        这一行实际来自哪个源。**必须给**：腾讯与新浪不提供这组字段，
     *                        页面要能说出「空是因为换源了」而不是「这家公司没有 ROE」
     * @param providerLabel   源的页面写法
     * @param roe             ROE 加权 %（{@code f37}）。高杠杆能把 ROE 抬上去
     * @param revenueGrowth   营收同比 %（{@code f41}）。**源不给所属报告期**，
     *                        所以无法确认是季报还是年报，页面必须写明（章程 §6 第 2 条）
     * @param profitGrowth    净利同比 %（{@code f46}）。基数效应会让同比失真
     * @param grossMargin     毛利率 %（{@code f49}）。不同行业不可横比
     * @param debtRatio       资产负债率 %（{@code f57}）。**对银行是存款负债比，与制造业不同口径**
     * @param dividendYield   股息率 %（{@code f133}）。过去分红不代表未来分红
     * @param turnoverRate    换手率 %（{@code f8}）
     * @param amount          当日成交额，元（{@code f6}）
     * @param floatMarketCap  流通市值，元（{@code f21}）
     * @param change60d       60 日涨跌幅 %（{@code f24}）。**只作背景，不是入选理由**（章程 §4）
     * @param ytdChange       年初至今 %（{@code f25}）。同上
     * @param bps             每股净资产（{@code f113}）。**不剔除商誉**
     * @param listDate        上市日期（{@code f26}，按 ISO 格式给页面）。不反映借壳前的历史
     * @param notes           这一块的缺口与来源说明，给用户看的
     */
    public record FundamentalsView(boolean available, String provider, String providerLabel,
                                   BigDecimal roe, BigDecimal revenueGrowth, BigDecimal profitGrowth,
                                   BigDecimal grossMargin, BigDecimal debtRatio, BigDecimal dividendYield,
                                   BigDecimal turnoverRate, BigDecimal amount, BigDecimal floatMarketCap,
                                   BigDecimal change60d, BigDecimal ytdChange, BigDecimal bps,
                                   String listDate, List<String> notes) {

        /** 这个源压根不提供这组字段（腾讯 / 新浪）。 */
        static FundamentalsView unavailableFrom(String provider, String providerLabel) {
            return new FundamentalsView(false, provider, providerLabel, null, null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    List.of("行情取自" + providerLabel + "，该源只给价格与成交额，"
                            + "不提供 ROE / 营收同比 / 毛利率 / 资产负债率 / 股息率等基本面字段"));
        }

        /** 根本没取行情（池外指数），所以这组字段也就无从谈起——**这不等于「这家公司没有」。** */
        static FundamentalsView noQuote() {
            return new FundamentalsView(false, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null,
                    List.of("本次没有取行情，基本面字段未确认"));
        }
    }

    /**
     * 价格位置。{@code available == false} 时各值为 null，{@code notes} 里写着为什么——
     * 与 {@link PricePosition} 同一语义，只是这里只端出页面要用的那几项。
     */
    public record PositionView(boolean available, BigDecimal pricePercentile,
                               BigDecimal drawdownFromHigh, BigDecimal vsMa250,
                               Integer barCount, String lastTradeDate, List<String> notes) {}

    /**
     * 估值那一段。
     *
     * @param available         这次到底有没有拿到估值。false 时下面各值都是 null，{@code notes} 说明原因
     * @param source            来源键（{@code csindex}/{@code danjuan}/{@code eastmoney-valueanalysis}）
     * @param sourceLabel       来源的页面写法（「中证官网」「蛋卷」「东财估值分析」）
     * @param percentileMethod  口径名。<b>必须显示</b>：中证自算与蛋卷口径实测能差 22%–75%
     * @param tradeDate         这个 PE 属于哪一天
     * @param historyLength     这次算分位实际用到的观测条数。**不足 10 年时它比 10 年小**，
     *                          页面必须写出来，否则「10 年分位」其实是个 6 年窗口的分位
     * @param historyFrom/To    观测区间的两端
     * @param lookbacks         分位八档
     */
    public record ValuationView(boolean available, String source, String sourceLabel,
                                String percentileMethod, BigDecimal peTtm, BigDecimal pePercentile,
                                String tradeDate, Integer historyLength, String historyFrom,
                                String historyTo, List<LookbackCell> lookbacks, List<String> notes) {}

    /**
     * 打上缓存标记。写在记录里而不是外面，是为了让「以后加字段」在编译期就报出来——
     * 在外面拼一个 new 的话，加了字段这里会静默漏掉，表现为「缓存命中的结果少一个字段」。
     */
    public CodeLookupDTO withCacheInfo(String snapshotAt, boolean fromCache) {
        return new CodeLookupDTO(code, resolvedCode, name, kind, kindLabel, quote, fundamentals,
                currentPrice, currentPriceDate, priceLookbacks, position, valuation, notes,
                snapshotAt, fromCache);
    }
}