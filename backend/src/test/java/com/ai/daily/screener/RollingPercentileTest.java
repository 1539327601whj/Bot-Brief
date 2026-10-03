package com.ai.daily.screener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RollingPercentile} 的测试。
 *
 * <p>核心是 {@link #matchesThePythonGeneratedFixture()}：它读的是
 * {@code automation/tests/fixtures/pe_percentile_parity.json}，那份数据由
 * {@code automation/scripts/gen_pe_percentile_fixture.py} 从 **Python 的生产函数**
 * {@code etf_report.rolling_percentiles} 生成，不是手写的期望值。
 *
 * <p>为什么非要这么绕：两个实现「差 0.3 个百分点」不会有任何页面报错，
 * 用户只会觉得「好像和上次不一样」。手写期望值挡不住这件事——写期望值的人
 * 心里想的是其中一边。所以期望值由另一边**跑出来**，任何一边改了算法，
 * 这个测试就红。Python 那边有 {@code test_rolling_percentile.py} 守着同一份文件。
 *
 * <p>没有网络、没有 Spring 上下文，纯计算。
 */
class RollingPercentileTest {

    /** fixture 在仓库里的相对位置。Surefire 的工作目录是 {@code backend/}，IDE 里跑可能是仓库根。 */
    private static final List<Path> FIXTURE_CANDIDATES = List.of(
            Path.of("..", "automation", "tests", "fixtures", "pe_percentile_parity.json"),
            Path.of("automation", "tests", "fixtures", "pe_percentile_parity.json"));

    // ---------------------------------------------------------------- 跨语言对照

    @Test
    void matchesThePythonGeneratedFixture() throws IOException {
        JsonNode fixture = readFixture();
        List<RollingPercentile.Point> points = new ArrayList<>();
        for (JsonNode p : fixture.get("points")) {
            points.add(new RollingPercentile.Point(
                    LocalDate.parse(p.get("date").asText()),
                    new BigDecimal(p.get("value").asText())));
        }
        JsonNode expected = fixture.get("percentiles");

        List<BigDecimal> actual = RollingPercentile.rolling(points, fixture.get("windowYears").asInt());

        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            // fixture 存的是 Python 的原始浮点，本类按生产契约收到 4 位（库里 pe_percentile
            // 是 DECIMAL(8,4)）。所以期望值也收一次到 4 位再比**精确相等**——
            // 用容差会把真正的算法分歧一起盖掉。
            //
            // 两个坑都在这里踩过：
            // 1. fixture 曾经预先 round 到 6 位，于是 25.93984962406015 → 25.93985 → 25.9399，
            //    而直接收 4 位是 25.9398。取整取了两遍，报出来的「差 0.0001」是假的。
            // 2. 收尾必须用 HALF_UP，与 Python 侧显式的 ROUND_HALF_UP 对齐；
            //    Python 内建 round() 是四舍六入五成双，在 .00005 这类点位上会和这边分道扬镳。
            BigDecimal want = BigDecimal.valueOf(expected.get(i).asDouble())
                    .setScale(4, RoundingMode.HALF_UP);
            assertThat(actual.get(i))
                    .as("第 %d 个点（%s）与 Python 生成的对照值不一致——两边算法分叉了",
                            i, points.get(i).date())
                    .isEqualByComparingTo(want);
        }
    }

    @Test
    void theFixtureIsLongEnoughToActuallyExerciseWindowEviction() throws IOException {
        // 跨度不足一个窗口时，「按日期淘汰旧点」那段代码一次都不会执行——
        // 那正是两端最容易写错的地方。这条守住 fixture 本身还有意义。
        JsonNode fixture = readFixture();
        assertThat(fixture.get("spanYears").asDouble())
                .as("fixture 跨度不足一个窗口，窗口淘汰分支不会被执行")
                .isGreaterThan(fixture.get("windowYears").asDouble());
    }

    private static JsonNode readFixture() throws IOException {
        for (Path candidate : FIXTURE_CANDIDATES) {
            if (Files.isRegularFile(candidate)) {
                return new ObjectMapper().readTree(candidate.toFile());
            }
        }
        throw new AssertionError("找不到跨语言对照数据 pe_percentile_parity.json。试过这些位置："
                + FIXTURE_CANDIDATES.stream()
                        .map(p -> p.toAbsolutePath().normalize().toString())
                        .collect(Collectors.joining("，"))
                + "（当前工作目录 " + Path.of("").toAbsolutePath() + "）"
                + "——缺少它等于跨语言一致性完全没测");
    }

    // ---------------------------------------------------------------- 性质（手工期望值）

    @Test
    void percentileUsesLessThanOrEqualNotStrictlyLess() {
        // <= 与 < 的分界。用严格小于时末点的分位是 25 而不是 50，
        // 而「窗口里有一个和我一样大的值」在日频 PE 里很常见（估值没变的日子）。
        List<RollingPercentile.Point> points = List.of(
                p("2020-01-01", "10"), p("2020-01-02", "20"),
                p("2020-01-03", "20"), p("2020-01-04", "15"));
        assertThat(RollingPercentile.rolling(points))
                .containsExactly(dec("100.0000"), dec("100.0000"), dec("100.0000"), dec("50.0000"));
    }

    @Test
    void duplicateValuesAllShareTheSamePercentile() {
        List<RollingPercentile.Point> points = List.of(
                p("2020-01-01", "20"), p("2020-01-02", "20"), p("2020-01-03", "20"));
        assertThat(RollingPercentile.rolling(points))
                .containsExactly(dec("100.0000"), dec("100.0000"), dec("100.0000"));
    }

    @Test
    void windowIsMeasuredInDatesNotInRowCount() {
        // 相隔 11 年：旧点必须出窗。不出窗的话末点分位是 50 而不是 100。
        List<RollingPercentile.Point> points = List.of(p("2010-01-01", "30"), p("2021-01-01", "20"));
        assertThat(RollingPercentile.rolling(points)).containsExactly(dec("100.0000"), dec("100.0000"));
    }

    @Test
    void aPointExactlyTenYearsBackIsStillInsideTheWindow() {
        // 窗口左端是「日期 >= 当日 - 10 年」，边界那一日是**含**在里面的。
        // 写成不含，末点会从 50 变成 100——注意这与 Python 的 `date < minimum 才淘汰` 一致。
        List<RollingPercentile.Point> points = List.of(p("2011-01-01", "30"), p("2021-01-01", "20"));
        assertThat(RollingPercentile.rolling(points)).containsExactly(dec("100.0000"), dec("50.0000"));
    }

    @Test
    void theFirstPointIsAlwaysOneHundred() {
        // 窗口里只有它自己：既是最大也是最小
        assertThat(RollingPercentile.rolling(List.of(p("2020-01-01", "10"))))
                .containsExactly(dec("100.0000"));
    }

    @Test
    void positiveValuesNeverProduceAZeroPercentile() {
        // 0 在页面上会被读成「极度低估」，而它真正的含义是「没有数据」。
        // 正常算出来的最小值是 100/窗口大小 > 0。
        List<RollingPercentile.Point> points = List.of(
                p("2020-01-01", "10"), p("2020-01-02", "30"), p("2020-01-03", "1"));
        assertThat(RollingPercentile.rolling(points))
                .allSatisfy(v -> assertThat(v).isGreaterThan(BigDecimal.ZERO));
    }

    @Test
    void resultsCarryFourDecimalsBecauseTheColumnIsDecimal8x4() {
        // 库里 pe_percentile 是 DECIMAL(8,4)。算法先收到 4 位，
        // 页面显示的数与库里的数才不会有「存进去又变了」的差别。
        List<RollingPercentile.Point> points = List.of(
                p("2020-01-01", "10"), p("2020-01-02", "20"), p("2020-01-03", "15"));
        assertThat(RollingPercentile.rolling(points)).allSatisfy(v -> assertThat(v.scale()).isEqualTo(4));
    }

    // ---------------------------------------------------------------- latest

    @Test
    void latestReturnsTheLastPointsPercentile() {
        List<RollingPercentile.Point> points = List.of(
                p("2020-01-01", "30"), p("2020-01-02", "20"), p("2020-01-03", "25"));
        assertThat(RollingPercentile.latest(points)).contains(dec("66.6667"));
    }

    @Test
    void latestIgnoresTrailingGarbageRowsThatRollingDrops() {
        // 末行日期解析失败在真实数据里会出现。丢掉它之后，「最新」应该退到最后一个好点，
        // 而不是返回 empty 让整个页面显示「无数据」。
        List<RollingPercentile.Point> withNull = new ArrayList<>();
        withNull.add(p("2020-01-01", "30"));
        withNull.add(p("2020-01-02", "20"));
        withNull.add(new RollingPercentile.Point(null, new BigDecimal("999")));
        // 剩下的两个点里，20 在 [20, 30] 中是较小者 → 1/2 = 50。若那个坏行被当成
        // 最新点，这里会给 100——所以 50 这个值本身就证明了它被丢掉了。
        assertThat(RollingPercentile.latest(withNull)).contains(dec("50.0000"));
    }

    @Test
    void emptyOrAllGarbageYieldsEmptyInsteadOfZero() {
        assertThat(RollingPercentile.rolling(List.of())).isEmpty();
        assertThat(RollingPercentile.latest(List.of())).isEmpty();
        assertThat(RollingPercentile.latest(null)).isEmpty();
        // 非正 PE 是坏数据：算进去会把整条曲线拉低
        assertThat(RollingPercentile.rolling(List.of(
                p("2020-01-01", "0"), p("2020-01-02", "-5")))).isEmpty();
    }

    @Test
    void nonPositiveValuesAreDroppedRatherThanCountedAsTheLowest() {
        // 一段正常序列里混进一个 0：它必须被丢掉，而不是变成「历史最低」。
        // 末点 15 是刻意挑的中间值——保留那个 0 的话它会算成 3/4 = 75，
        // 丢掉才是 2/3 = 66.6667。换成让它当端点（最大或最小）就看不出来了。
        List<RollingPercentile.Point> points = List.of(
                p("2020-01-01", "10"), p("2020-01-02", "0"),
                p("2020-01-03", "20"), p("2020-01-04", "15"));
        assertThat(RollingPercentile.rolling(points))
                .containsExactly(dec("100.0000"), dec("100.0000"), dec("66.6667"));
    }

    @Test
    void windowYearsDefaultsToTheCharterConstant() {
        assertThat(RollingPercentile.WINDOW_YEARS).isEqualTo(10);
        List<RollingPercentile.Point> points = List.of(p("2010-01-01", "30"), p("2021-01-01", "20"));
        assertThat(RollingPercentile.rolling(points))
                .isEqualTo(RollingPercentile.rolling(points, RollingPercentile.WINDOW_YEARS));
    }

    // ---------------------------------------------------------------- helpers

    private static RollingPercentile.Point p(String date, String value) {
        return new RollingPercentile.Point(LocalDate.parse(date), new BigDecimal(value));
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }
}