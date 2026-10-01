package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 从**混源**的本地日线里挑一条同源序列。
 *
 * <p>这是 阶段 5 唯一一个「错了不会报错」的地方：{@code etf_price_history} 的唯一键里含
 * {@code source}，同一天可以同时有东财行和腾讯行。要是按日期直接铺开，曲线会在中间换一次
 * 复权基准，之后算出的价格分位、回撤、年化波动**每一个数都是错的，而每一根日线看上去都合法**。
 * 所以这些用例不测「能不能选出数据」，测的是「选出来的那根曲线有没有混源」——
 * 下面几条都用「两个源的同一天给**不同的收盘价**」来让混源**可观测**。
 */
class EtfBarFromDbTest {

    private static final String EM = "eastmoney";
    private static final String TX = "tencent";

    /** 2026-09-01 是周二，09-04/09-05 是周五、周六，09-07 是周一——用来验证跨周末的连续段。 */
    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 9, 3);
    private static final LocalDate D4 = LocalDate.of(2026, 9, 4);
    private static final LocalDate D5 = LocalDate.of(2026, 9, 5);
    private static final LocalDate D6 = LocalDate.of(2026, 9, 6);
    private static final LocalDate D7 = LocalDate.of(2026, 9, 7);

    /** 收盘价按源分开：东财那批 10.x，腾讯那批 20.x。这样「这一根来自哪个源」一眼可验。 */
    private static EtfPriceSeriesSelector.Point em(LocalDate date, double close) {
        return EtfPriceSeriesSelector.Point.of(date, BigDecimal.valueOf(close), EM);
    }

    private static EtfPriceSeriesSelector.Point tx(LocalDate date, double close) {
        return EtfPriceSeriesSelector.Point.of(date, BigDecimal.valueOf(close), TX);
    }

    private static List<BigDecimal> closes(List<PricePositionCalculator.Bar> bars) {
        return bars.stream().map(PricePositionCalculator.Bar::close).toList();
    }

    @Test
    void theChosenSeriesNeverMixesSources() {
        // 东财只有前 5 根，腾讯到 D7。腾讯「最后一根更新」，所以整条曲线都该是腾讯的——
        // 东财独有的 D1、D2 **不能**出现在结果里。
        List<EtfPriceSeriesSelector.Point> rows = new ArrayList<>();
        rows.add(em(D1, 10.1));
        rows.add(em(D2, 10.2));
        rows.add(em(D3, 10.3));
        rows.add(em(D4, 10.4));
        rows.add(em(D5, 10.5));
        rows.add(tx(D3, 20.3));
        rows.add(tx(D4, 20.4));
        rows.add(tx(D5, 20.5));
        rows.add(tx(D6, 20.6));
        rows.add(tx(D7, 20.7));

        List<PricePositionCalculator.Bar> bars = EtfPriceSeriesSelector.select(rows, 250);

        assertThat(bars).hasSize(5);
        assertThat(bars.get(0).date()).isEqualTo(D3);
        // 每一根都必须来自腾讯：只要出现一个 10.x，就说明东财那截被拼进了同一条曲线
        assertThat(closes(bars)).allMatch(c -> c.compareTo(BigDecimal.valueOf(20)) >= 0);
        assertThat(bars).noneMatch(b -> b.date().equals(D1) || b.date().equals(D2));
    }

    @Test
    void theSourceWithTheFreshestLastBarWinsEvenWithFarFewerRows() {
        // 旧源有 300 根但停在 10 天前——它是「断更」的那个。
        // 新源只有 3 根但到今天：价格位置要的是「现在在哪」，所以新源赢。
        List<EtfPriceSeriesSelector.Point> rows = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            rows.add(em(D1.minusDays(310 - i), 10));
        }
        rows.add(tx(D5, 20.5));
        rows.add(tx(D6, 20.6));
        rows.add(tx(D7, 20.7));

        List<PricePositionCalculator.Bar> bars = EtfPriceSeriesSelector.select(rows, 250);

        assertThat(bars).hasSize(3);
        assertThat(bars.get(2).date()).isEqualTo(D7);
        assertThat(closes(bars)).allMatch(c -> c.compareTo(BigDecimal.valueOf(20)) >= 0);
    }

    @Test
    void aBrokenTailLosesToAContinuousOneWithTheSameLastDate() {
        // 同一根收尾日期，只能比「尾部连续段」：
        // 东财那截中间断了 30 天（run=1），腾讯是连着来的（run=3，且跨了周末 D5→D7）
        List<EtfPriceSeriesSelector.Point> rows = List.of(
                em(D7.minusDays(30), 10.1),
                em(D7, 10.2),
                tx(D5, 20.5),
                tx(D6, 20.6),
                tx(D7, 20.7));

        List<PricePositionCalculator.Bar> bars = EtfPriceSeriesSelector.select(rows, 250);

        assertThat(bars).hasSize(3);
        assertThat(closes(bars)).allMatch(c -> c.compareTo(BigDecimal.valueOf(20)) >= 0);
    }

    @Test
    void aWeekendDoesNotBreakTheContinuityRun() {
        // 周五(09-04) → 周一(09-07)隔 3 个自然日。若判据写成「必须隔 1 天」，
        // 每个周末都会把连续段砍断，于是所有源都被判成「断更」，
        // 选择退化成「谁先出现谁赢」——静默且随机。
        assertThat(EtfPriceSeriesSelector.trailingRun(List.of(tx(D4, 20.4), tx(D7, 20.7))))
                .isEqualTo(2);
    }

    @Test
    void aFourDayGapIsStillContinuousButFiveIsNot() {
        // 4 天是「周末 + 一天假期」的上限，正是判据里那个数；5 天已经算断更。
        assertThat(EtfPriceSeriesSelector.trailingRun(List.of(tx(D3, 20.3), tx(D7, 20.7))))
                .isEqualTo(2);
        assertThat(EtfPriceSeriesSelector.trailingRun(List.of(tx(D2, 20.2), tx(D7, 20.7))))
                .isEqualTo(1);
    }

    @Test
    void theShorterSourceWinsOnlyWhenItsRunIsLonger() {
        // 反证上一条：同样收于 D7，A 是「断开的两根」（run=1），B 是「连着三根」（run=3）。
        // 若判据错写成「总根数多的赢」，这里就会选错。
        List<EtfPriceSeriesSelector.Point> rows = List.of(
                em(D1, 10.1), em(D7, 10.2),
                tx(D5, 20.5), tx(D6, 20.6), tx(D7, 20.7));

        assertThat(closes(EtfPriceSeriesSelector.select(rows, 250)))
                .allMatch(c -> c.compareTo(BigDecimal.valueOf(20)) >= 0);
    }

    @Test
    void whenTwoSourcesTieCompletelyTheFirstOneSeenWins() {
        // 完全打平时必须有确定结果，否则同一份数据两次调用可能给出不同曲线。
        // 平手取「先出现的」——与 Python 侧 max() 的语义一致。
        List<EtfPriceSeriesSelector.Point> emFirst = List.of(
                em(D6, 10.6), em(D7, 10.7), tx(D6, 20.6), tx(D7, 20.7));

        List<PricePositionCalculator.Bar> bars = EtfPriceSeriesSelector.select(emFirst, 250);

        // 东财那批是 10.x、腾讯是 20.x：结果必须全是 10.x（先出现的东财赢）
        assertThat(closes(bars)).allMatch(c -> c.compareTo(BigDecimal.valueOf(11)) < 0);
        assertThat(bars.get(0).close()).isEqualByComparingTo("10.6");
    }

    @Test
    void badRowsAreDroppedInsteadOfBecomingCheapPrices() {
        List<EtfPriceSeriesSelector.Point> rows = new ArrayList<>(List.of(
                em(D1, 10.1),
                em(D2, 10.2),
                tx(D6, 20.6),
                tx(D7, 20.7)));
        rows.add(em(D3, 0));                       // 非正收盘价：坏行，不是「便宜」
        rows.add(em(D4, -1));
        rows.add(new EtfPriceSeriesSelector.Point(D5, new BigDecimal("10.5"), "  ", "QFQ")); // 来源空白
        rows.add(new EtfPriceSeriesSelector.Point(D5, null, EM, "QFQ"));                      // 没收盘价
        rows.add(new EtfPriceSeriesSelector.Point(null, new BigDecimal("10.5"), EM, "QFQ"));   // 没日期

        List<PricePositionCalculator.Bar> bars = EtfPriceSeriesSelector.select(rows, 250);

        assertThat(bars).hasSize(2);
        assertThat(closes(bars)).allMatch(c -> c.compareTo(BigDecimal.valueOf(20)) >= 0);
    }

    @Test
    void aRowThatIsNotForwardAdjustedIsIgnoredEvenIfTheSqlForgotToFilterIt() {
        // SQL 已经筛过 adjustment_type，这里再挡一次是故意的：混口径的后果是静默算错，
        // 而不是报错。宁可多一行防御，不要一个「看起来正常」的错数。
        List<EtfPriceSeriesSelector.Point> rows = new ArrayList<>(List.of(tx(D6, 20.6), tx(D7, 20.7)));
        rows.add(new EtfPriceSeriesSelector.Point(D5, new BigDecimal("99.9"), EM, "HFQ"));

        List<PricePositionCalculator.Bar> bars = EtfPriceSeriesSelector.select(rows, 250);

        assertThat(bars).hasSize(2);
        assertThat(closes(bars)).noneMatch(c -> c.compareTo(BigDecimal.valueOf(99)) == 0);
    }

    @Test
    void onlyTheLastLimitBarsAreKeptInAscendingOrder() {
        List<EtfPriceSeriesSelector.Point> rows = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            rows.add(tx(D1.minusDays(400 - i), 20));
        }

        List<PricePositionCalculator.Bar> bars = EtfPriceSeriesSelector.select(rows, 250);

        assertThat(bars).hasSize(250);
        for (int i = 1; i < bars.size(); i++) {
            assertThat(bars.get(i).date()).isAfter(bars.get(i - 1).date());
        }
        // 保留的是**最后** 250 根，不是最前 250 根
        assertThat(bars.get(249).date()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    void noRowsMeansNoBarsRatherThanADefault() {
        assertThat(EtfPriceSeriesSelector.select(null, 250)).isEmpty();
        assertThat(EtfPriceSeriesSelector.select(List.of(), 250)).isEmpty();
        assertThat(EtfPriceSeriesSelector.select(List.of(em(D1, 0)), 250)).isEmpty();
    }

    @Test
    void theLimitIsClampedSoAnEmptySeriesIsNeverProduced() {
        // limit=0 或负数：绝不能变成「切片长度为 0」然后返回空——那会把「有 3 根日线」
        // 说成「没有日线」，页面上就是一片空白
        List<EtfPriceSeriesSelector.Point> rows = List.of(tx(D5, 20.5), tx(D6, 20.6), tx(D7, 20.7));

        assertThat(EtfPriceSeriesSelector.select(rows, 0)).hasSize(1);
        assertThat(EtfPriceSeriesSelector.select(rows, -5)).hasSize(1);
    }
}