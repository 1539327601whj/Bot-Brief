package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LookbackCalculator} 的测试。纯计算，无网络无 Spring。
 *
 * <p>重点在三件容易「看着对、其实错」的事上：
 * <ol>
 *   <li><b>价格的涨跌幅是相对值，分位的是百分点差</b>——分位本身已经是百分数，
 *       再算一次相对涨幅会把「51 比 50.48 高 0.52 个百分点」说成「高 1.03%」，
 *       而页面上两个都带 % 号，看不出来。见 {@link #pointsStyleIsAPointDifferenceNotARelativeChange()}。</li>
 *   <li><b>基线是相对数据自身的日期算的，不是相对服务器今天</b>——停更的数据上，
 *       用今天会得到「昨」= 一个三个月前的价，而页面上写着昨天。</li>
 *   <li><b>缺档只给 status，不给 0、不给破折号</b>，而且三种原因分开写。</li>
 * </ol>
 */
class LookbackCalculatorTest {

    /** 报价日。用 2026-09-30 是为了让「三年」落在 2023-09-30，与真实用例同形。 */
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    // ---------------------------------------------------------------- 版式

    @Test
    void theEightLabelsAreExactlyTheDocumentedOrder() {
        // 顺序即版式，改它会一起改掉页面上那一行的顺序——所以钉死在这里
        assertThat(LookbackCalculator.LABELS)
                .containsExactly("昨", "周", "月", "半年", "一年", "三年", "五年", "十年");
    }

    @Test
    void everyResultCarriesAllEightCellsInOrderEvenWhenNothingIsAvailable() {
        LookbackCalculator.Result r =
                LookbackCalculator.compute(List.of(), LookbackCalculator.ChangeStyle.PRICE, null);
        assertThat(r.cells()).hasSize(8);
        assertThat(r.cells()).extracting(LookbackCalculator.Cell::label)
                .containsExactlyElementsOf(LookbackCalculator.LABELS);
    }

    // ---------------------------------------------------------------- 两种变化的算法

    @Test
    void priceChangeIsRelativePercent() {
        assertThat(LookbackCalculator.change(LookbackCalculator.ChangeStyle.PRICE,
                new BigDecimal("110"), new BigDecimal("100"))).isEqualByComparingTo("10.00");
        assertThat(LookbackCalculator.change(LookbackCalculator.ChangeStyle.PRICE,
                new BigDecimal("90"), new BigDecimal("100"))).isEqualByComparingTo("-10.00");
    }

    @Test
    void pointsStyleIsAPointDifferenceNotARelativeChange() {
        // 这一条是整个类里最要紧的一条。用相对涨幅的话，分位从 50 涨到 51 会被说成
        // 「+2.00%」，而实际是「+1.00 个百分点」。页面上两个都带 % 号，
        // 用户看不出来，所以只能靠这里钉住。
        assertThat(LookbackCalculator.change(LookbackCalculator.ChangeStyle.POINTS,
                new BigDecimal("51"), new BigDecimal("50"))).isEqualByComparingTo("1.00");
        // 同样两个数走价格口径是 2.00——两者必须不同
        assertThat(LookbackCalculator.change(LookbackCalculator.ChangeStyle.PRICE,
                new BigDecimal("51"), new BigDecimal("50"))).isEqualByComparingTo("2.00");
    }

    @Test
    void pointsStyleReproducesTheLineTheUserAlreadyReadsInTheEtfDailyReport() {
        // 用户看惯了的那一行：今 51｜昨 50 ↑ +0.52%｜…｜三年 90 ↓ -39.48%
        // 「昨 50」是显示时收成整数，真实基线 50.48；变化是 51 - 50.48 = +0.52。
        List<LookbackCalculator.Point> series = List.of(
                p("2023-09-29", "90.48"),
                p("2026-09-29", "50.48"),
                p("2026-09-30", "51.00"));

        LookbackCalculator.Result r =
                LookbackCalculator.compute(series, LookbackCalculator.ChangeStyle.POINTS, null);

        assertThat(r.current()).isEqualByComparingTo("51.00");
        assertThat(r.currentDate()).isEqualTo(AS_OF);
        assertThat(cell(r, "昨").baseline()).isEqualByComparingTo("50.48");
        assertThat(cell(r, "昨").change()).isEqualByComparingTo("0.52");
        assertThat(cell(r, "三年").baseline()).isEqualByComparingTo("90.48");
        assertThat(cell(r, "三年").change()).isEqualByComparingTo("-39.48");
    }

    @Test
    void changeRefusesANonPositiveBaselineForPriceStyleInsteadOfInventingAPercent() {
        // 除以 0 会炸，除以负数会给一个正负号颠倒的「涨幅」。
        // 分位口径不受影响：0 是个合法的分位值（虽然正常算法取不到 0）。
        assertThat(LookbackCalculator.change(LookbackCalculator.ChangeStyle.PRICE,
                new BigDecimal("100"), BigDecimal.ZERO)).isNull();
        assertThat(LookbackCalculator.change(LookbackCalculator.ChangeStyle.PRICE,
                new BigDecimal("100"), new BigDecimal("-5"))).isNull();
        assertThat(LookbackCalculator.change(LookbackCalculator.ChangeStyle.POINTS,
                new BigDecimal("50"), BigDecimal.ZERO)).isEqualByComparingTo("50.00");
    }

    // ---------------------------------------------------------------- 目标日

    @Test
    void dayAndWeekCountCalendarDays() {
        assertThat(LookbackCalculator.targetDate(AS_OF, "昨")).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(LookbackCalculator.targetDate(AS_OF, "周")).isEqualTo(LocalDate.of(2026, 9, 23));
    }

    @Test
    void monthRollbackClampsToTheEndOfAShorterMonth() {
        // 3/31 回退一月没有 2/31，必须夹到 2/28——写成「减 30 天」会得到 3/1，
        // 那是个未来日期，基线会取到当天自己，「月」这一档就等于恒为 0%
        assertThat(LookbackCalculator.targetDate(LocalDate.of(2021, 3, 31), "月"))
                .isEqualTo(LocalDate.of(2021, 2, 28));
        assertThat(LookbackCalculator.targetDate(LocalDate.of(2021, 8, 31), "半年"))
                .isEqualTo(LocalDate.of(2021, 2, 28));
        assertThat(LookbackCalculator.targetDate(LocalDate.of(2021, 5, 31), "月"))
                .isEqualTo(LocalDate.of(2021, 4, 30));
    }

    @Test
    void yearRollbackHandlesLeapDay() {
        assertThat(LookbackCalculator.targetDate(LocalDate.of(2024, 2, 29), "一年"))
                .isEqualTo(LocalDate.of(2023, 2, 28));
        assertThat(LookbackCalculator.targetDate(LocalDate.of(2024, 2, 29), "三年"))
                .isEqualTo(LocalDate.of(2021, 2, 28));
    }

    @Test
    void theLongLookbacksAreCalendarYearRollbacks() {
        assertThat(LookbackCalculator.targetDate(AS_OF, "五年")).isEqualTo(LocalDate.of(2021, 9, 30));
        assertThat(LookbackCalculator.targetDate(AS_OF, "十年")).isEqualTo(LocalDate.of(2016, 9, 30));
    }

    @Test
    void anUnknownLabelFailsLoudly() {
        assertThatThrownBy(() -> LookbackCalculator.targetDate(AS_OF, "两年"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 基线怎么取

    @Test
    void aPointExactlyFifteenDaysStaleIsAccepted() {
        // 15 天是停牌与长假的容忍宽度，与 etf_report.PE_LOOKBACK_DAYS 是同一个数。
        // 边界值必须**含**，否则长假后的第一档会莫名消失。
        assertThat(LookbackCalculator.STALE_DAYS).isEqualTo(15);
        LookbackCalculator.Result r = LookbackCalculator.compute(
                List.of(p("2020-06-08", "50"), p("2020-06-30", "100")),
                LookbackCalculator.ChangeStyle.PRICE, null);

        LookbackCalculator.Cell week = cell(r, "周");
        assertThat(week.present()).isTrue();
        assertThat(week.baselineDate()).isEqualTo(LocalDate.of(2020, 6, 8));
        assertThat(week.change()).isEqualByComparingTo("100.00");
    }

    @Test
    void aPointSixteenDaysStaleIsRejectedRatherThanQuietlyUsed() {
        LookbackCalculator.Result r = LookbackCalculator.compute(
                List.of(p("2020-06-07", "50"), p("2020-06-30", "100")),
                LookbackCalculator.ChangeStyle.PRICE, null);

        LookbackCalculator.Cell week = cell(r, "周");
        assertThat(week.present()).isFalse();
        assertThat(week.status()).isNotNull();
    }

    @Test
    void theBaselineIsTheLatestObservationOnOrBeforeTheTargetNotTheNearest() {
        // 目标日当天没数据（休市）时取**之前**最近的一次，绝不取之后的一次——
        // 取之后的那就是用未来数据算「过去」。
        List<LookbackCalculator.Point> sorted = List.of(
                p("2020-06-29", "50"), p("2020-06-30", "100"));
        assertThat(LookbackCalculator.latestOnOrBefore(sorted, LocalDate.of(2020, 6, 29), 15).date())
                .isEqualTo(LocalDate.of(2020, 6, 29));
        assertThat(LookbackCalculator.latestOnOrBefore(sorted, LocalDate.of(2020, 6, 28), 15))
                .isNull();
    }

    @Test
    void anOverlyStaleObservationIsNotReplacedByScanningFurtherBack() {
        // 往前翻只会更旧。这里最新的合格点已经 151 天，返回 null 才对；
        // 若「继续往前找」，会把一个半年前的价当成「昨」。
        List<LookbackCalculator.Point> sorted = List.of(
                p("2019-01-01", "10"), p("2020-01-01", "50"), p("2020-06-01", "100"));
        assertThat(LookbackCalculator.latestOnOrBefore(sorted, LocalDate.of(2020, 5, 31), 15)).isNull();
    }

    // ---------------------------------------------------------------- 基线相对谁

    @Test
    void baselinesAreRelativeToTheDataDateNotToServerToday() {
        // 这是类注释第 2 条。序列停在 2020-06-15，而「今天」是 2026 年。
        // 若基线相对今天算，「昨」会指向 2026 年、什么都取不到，
        // 整个页面变成一片「无数据」——而数据其实是好的，只是不新鲜。
        List<LookbackCalculator.Point> series = List.of(p("2020-06-01", "50"), p("2020-06-15", "100"));
        LookbackCalculator.Result r =
                LookbackCalculator.compute(series, LookbackCalculator.ChangeStyle.PRICE, null);

        assertThat(r.currentDate()).isEqualTo(LocalDate.of(2020, 6, 15));
        assertThat(r.current()).isEqualByComparingTo("100");
        LookbackCalculator.Cell yesterday = cell(r, "昨");
        assertThat(yesterday.baselineDate()).isEqualTo(LocalDate.of(2020, 6, 1));
        assertThat(yesterday.change()).isEqualByComparingTo("100.00");
    }

    @Test
    void theCurrentValueIsTheLastPointByDateEvenIfTheInputIsShuffled() {
        List<LookbackCalculator.Point> shuffled = new ArrayList<>(List.of(
                p("2026-09-29", "50.48"), p("2023-09-29", "90.48"), p("2026-09-30", "51.00")));
        Collections.shuffle(shuffled, new java.util.Random(7));

        LookbackCalculator.Result r =
                LookbackCalculator.compute(shuffled, LookbackCalculator.ChangeStyle.POINTS, null);
        assertThat(r.currentDate()).isEqualTo(AS_OF);
        assertThat(r.current()).isEqualByComparingTo("51.00");
    }

    // ---------------------------------------------------------------- 缺档怎么表达

    @Test
    void anEmptySeriesMarksEveryLabelMissingAndSaysWhy() {
        LookbackCalculator.Result r =
                LookbackCalculator.compute(List.of(), LookbackCalculator.ChangeStyle.PRICE, null);

        assertThat(r.current()).isNull();
        assertThat(r.currentDate()).isNull();
        assertThat(r.cells()).allSatisfy(c -> {
            assertThat(c.present()).isFalse();
            assertThat(c.status()).isNotBlank();
        });
    }

    @Test
    void theShortHistoryNoteIsUsedOnlyWhenTheHistoryActuallyStartsTooLate() {
        // 只有一点，比所有目标日都晚 → 「历史不足」，用调用方给的来源说明
        String note = "该指数口径源不提供 5/10 年历史分位";
        LookbackCalculator.Result r = LookbackCalculator.compute(
                List.of(p("2020-01-01", "100")), LookbackCalculator.ChangeStyle.POINTS, note);

        assertThat(cell(r, "昨").status()).isEqualTo(note);
        assertThat(cell(r, "三年").status()).isEqualTo(note);
    }

    @Test
    void theThreeMissingReasonsStayDistinct() {
        // 「来源不提供」「历史不足」「当日前后没有观测」下一步该做的事完全不同：
        // 换源 / 等历史 / 查停牌。套用来源那句话会把后两种指错方向。
        String note = "该指数口径源不提供 5/10 年历史分位";

        // 情形一：历史起点晚于目标日 → 用来源说明
        LookbackCalculator.Result tooShort = LookbackCalculator.compute(
                List.of(p("2020-01-01", "100")), LookbackCalculator.ChangeStyle.POINTS, note);
        assertThat(cell(tooShort, "昨").status()).isEqualTo(note);

        // 情形二：起点够早，但那一天附近就是没有观测 → 说明里要指出这一点和容忍宽度，
        // 不能借用来源那句话
        LookbackCalculator.Result gap = LookbackCalculator.compute(
                List.of(p("2020-01-01", "50"), p("2020-06-30", "100")),
                LookbackCalculator.ChangeStyle.POINTS, note);
        String gapStatus = cell(gap, "昨").status();
        assertThat(gapStatus).isNotEqualTo(note);
        assertThat(gapStatus).contains("没有观测").contains("15");
    }

    @Test
    void withoutAHistoryNoteTheTooShortReasonStillNamesTheDateAndTheSpan() {
        // 通用文案也不能只说「无数据」——得让人知道是从哪天起、有多长
        LookbackCalculator.Result r = LookbackCalculator.compute(
                List.of(p("2020-01-01", "100")), LookbackCalculator.ChangeStyle.POINTS, null);
        assertThat(cell(r, "十年").status()).contains("2020-01-01").contains("历史不足");
    }

    @Test
    void aMissingCellNeverComesBackAsZeroOrABareDash() {
        // 章程 §6：缺了就是缺了，标出来。0 会被读成「极度低估」，
        // 破折号会让人以为后端没接上——两者都比「说清楚」更糟。
        LookbackCalculator.Result r = LookbackCalculator.compute(
                List.of(p("2020-01-01", "50"), p("2020-06-30", "100")),
                LookbackCalculator.ChangeStyle.PRICE, null);

        assertThat(r.cells()).allSatisfy(c -> {
            if (!c.present()) {
                assertThat(c.baseline()).isNull();
                assertThat(c.change()).isNull();
                assertThat(c.status()).isNotBlank();
            }
        });
    }

    @Test
    void baselineAndStatusAreNeverBothPresentOrBothAbsent() {
        // 这条不变量是 DTO 契约的另一半：前端只看 status 是否为空来决定
        // 渲染数值还是渲染原因。两边同时有、或同时无，前端就会渲染出空白。
        List<List<LookbackCalculator.Point>> scenarios = List.of(
                List.of(),
                List.of(p("2020-01-01", "100")),
                List.of(p("2020-01-01", "50"), p("2020-06-30", "100")),
                List.of(p("2023-09-29", "90.48"), p("2026-09-29", "50.48"), p("2026-09-30", "51.00")));

        for (List<LookbackCalculator.Point> series : scenarios) {
            for (LookbackCalculator.ChangeStyle style : LookbackCalculator.ChangeStyle.values()) {
                LookbackCalculator.Result r = LookbackCalculator.compute(series, style, "note");
                assertThat(r.cells()).allSatisfy(c ->
                        assertThat(c.present()).as("label=%s", c.label()).isEqualTo(c.status() == null));
            }
        }
    }

    // ---------------------------------------------------------------- 坏数据

    @Test
    void priceStyleDropsNonPositiveClosesInsteadOfTreatingThemAsPrices() {
        // 0 与负的收盘价是坏数据（停牌占位、取数失败）。当成真实价格会算出
        // 「跌了 100%」这种结论。丢掉之后「昨」就该是缺档，而不是拿它当基线。
        List<LookbackCalculator.Point> series = List.of(
                p("2020-06-25", "0"), p("2020-06-29", "-5"), p("2020-06-30", "100"));
        LookbackCalculator.Result r =
                LookbackCalculator.compute(series, LookbackCalculator.ChangeStyle.PRICE, null);

        assertThat(r.current()).isEqualByComparingTo("100");
        assertThat(cell(r, "昨").present()).isFalse();
    }

    @Test
    void pointsStyleKeepsZeroBecauseZeroIsAMeaningfulPercentileOnTheWire() {
        // 与上一条对照：分位口径不筛 0。正常算法取不到 0（最小值是 100/窗口大小），
        // 但 0 是接口能合法传回来的值，静默丢掉会让「去年今天分位是 0」
        // 变成「去年今天没有数据」。
        List<LookbackCalculator.Point> series = List.of(p("2026-09-29", "0"), p("2026-09-30", "50"));
        LookbackCalculator.Result r =
                LookbackCalculator.compute(series, LookbackCalculator.ChangeStyle.POINTS, null);

        LookbackCalculator.Cell yesterday = cell(r, "昨");
        assertThat(yesterday.present()).isTrue();
        assertThat(yesterday.baseline()).isEqualByComparingTo("0");
        assertThat(yesterday.change()).isEqualByComparingTo("50.00");
    }

    @Test
    void nullPointsAndNullFieldsAreDroppedNotCrashedOn() {
        List<LookbackCalculator.Point> messy = new ArrayList<>();
        messy.add(null);
        messy.add(p("2026-09-29", "50.48"));
        messy.add(new LookbackCalculator.Point(null, new BigDecimal("1")));
        messy.add(new LookbackCalculator.Point(AS_OF, null));
        messy.add(p("2026-09-30", "51"));

        LookbackCalculator.Result r =
                LookbackCalculator.compute(messy, LookbackCalculator.ChangeStyle.POINTS, null);
        assertThat(r.currentDate()).isEqualTo(AS_OF);
        assertThat(r.current()).isEqualByComparingTo("51");
    }

    // ---------------------------------------------------------------- helpers

    private static LookbackCalculator.Point p(String date, String value) {
        return new LookbackCalculator.Point(LocalDate.parse(date), new BigDecimal(value));
    }

    private static LookbackCalculator.Cell cell(LookbackCalculator.Result result, String label) {
        return result.cells().stream()
                .filter(c -> c.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果里没有「" + label + "」这一档"));
    }
}