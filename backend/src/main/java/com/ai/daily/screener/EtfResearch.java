package com.ai.daily.screener;

import com.ai.daily.screener.v2.FredRates;
import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseRepository.ConstituentEarnings;
import com.ai.daily.warehouse.WarehouseRows.PriceBar;
import com.ai.daily.warehouse.WarehouseSchemaGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EtfResearch {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal SUPPORT_SHARE = BigDecimal.valueOf(60);
    private static final BigDecimal MIN_INDEX_WEIGHT = BigDecimal.TEN;

    private final WarehouseRepository repository;
    private final WarehouseSchemaGuard schemaGuard;

    public record Earnings(String state, LocalDate holdingsDate, LocalDate reportDate,
                           int companies, BigDecimal sampleWeight, BigDecimal knownWeight,
                           BigDecimal revenueYoy, BigDecimal profitYoy,
                           BigDecimal resilientWeightShare, String note, Growth growth) {
        public boolean supported() {
            return "SUPPORTED".equals(state);
        }

        public boolean sustainedContraction() {
            return growth != null && growth.revenueCagr().signum() < 0 && growth.profitCagr().signum() < 0;
        }
    }

    public record Growth(LocalDate from, LocalDate to, int companies, BigDecimal sampleWeight,
                         BigDecimal revenueCagr, BigDecimal profitCagr) {}

    public record Volume(LocalDate date, String source, BigDecimal volumeVsPrevious20,
                         BigDecimal closeChange, BigDecimal amount, BigDecimal turnoverRate) {}

    public record Benchmark(String code, String label, LocalDate from, LocalDate to,
                            BigDecimal etfReturnPct, BigDecimal benchmarkReturnPct,
                            BigDecimal relativePctPoints) {}

    public record Gold(Boolean lowPrice, FredRates.GoldComparison macro) {}

    public record View(Boolean lowPrice, boolean candidate, Earnings earnings, Volume volume,
                       FredRates.Comparison usRates, Benchmark benchmark, Gold gold) {
        public static View of(Boolean lowPrice, Earnings earnings, Volume volume,
                              FredRates.Comparison usRates, Benchmark benchmark) {
            return new View(lowPrice, Boolean.TRUE.equals(lowPrice) && earnings.supported()
                    && !earnings.sustainedContraction(), earnings, volume, usRates, benchmark, null);
        }
    }

    public Map<String, Earnings> earnings(List<String> codes, LocalDate asOf) {
        List<String> indexCodes = codes.stream().filter(code -> code != null && !code.isBlank())
                .distinct().toList();
        if (indexCodes.isEmpty()) return Map.of();
        try {
            if (!schemaGuard.isReady()) return unavailable(indexCodes, "成分股数据仓库暂不可用");
            Map<String, List<ConstituentEarnings>> byIndex = repository.constituentEarnings(indexCodes, asOf)
                    .stream().collect(Collectors.groupingBy(ConstituentEarnings::indexCode));
            Map<String, Earnings> results = new HashMap<>();
            for (String code : indexCodes) {
                results.put(code, summarize(byIndex.getOrDefault(code, List.of()), asOf));
            }
            return results;
        } catch (DataAccessException ex) {
            log.warn("读取 ETF 成分股财报失败，指数数目={}", indexCodes.size(), ex);
            return unavailable(indexCodes, "本次读取成分股财报失败");
        }
    }

    private static Map<String, Earnings> unavailable(List<String> indexCodes, String note) {
        return indexCodes.stream().collect(Collectors.toMap(Function.identity(), code -> missing(note)));
    }

    public static Earnings missing(String note) {
        return new Earnings("UNCONFIRMED", null, null, 0, null, null, null, null, null, note, null);
    }

    static Earnings summarize(List<ConstituentEarnings> rows, LocalDate asOf) {
        if (rows.isEmpty()) return missing("暂无带权重的指数成分股数据");
        LocalDate holdingsDate = rows.get(0).holdingsDate();
        if (holdingsDate.isBefore(asOf.minusMonths(6))) {
            return new Earnings("UNCONFIRMED", holdingsDate, null, 0, null, null,
                    null, null, null, "成分权重距评估日超过六个月", null);
        }

        Map<String, BigDecimal> weights = new HashMap<>();
        Map<LocalDate, Map<String, ConstituentEarnings>> byPeriod = new TreeMap<>(Comparator.reverseOrder());
        for (ConstituentEarnings row : rows) {
            weights.putIfAbsent(row.stockCode(), row.weight());
            if (row.reportDate() != null) {
                byPeriod.computeIfAbsent(row.reportDate(), date -> new LinkedHashMap<>())
                        .putIfAbsent(row.stockCode(), row);
            }
        }
        BigDecimal knownWeight = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (knownWeight.compareTo(HUNDRED) > 0) {
            return missing("成分权重口径不一致，合计超过 100%");
        }
        Earnings insufficient = new Earnings("UNCONFIRMED", holdingsDate, null, 0, null, knownWeight,
                null, null, null, "缺少同时披露营收和归母净利润同比的成分股财报", null);
        boolean recordedCoverage = false;
        for (var period : byPeriod.entrySet()) {
            LocalDate reportDate = period.getKey();
            if (reportDate.isBefore(asOf.minusMonths(9))) break;
            var sample = period.getValue().values().stream()
                    .filter(row -> row.revenueYoy() != null && row.profitYoy() != null && row.profit() != null)
                    .toList();
            BigDecimal weight = sample.stream().map(ConstituentEarnings::weight)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!enoughCoverage(sample.size(), weight, knownWeight)) {
                if (!recordedCoverage) {
                    insufficient = new Earnings("UNCONFIRMED", holdingsDate, period.getKey(),
                            sample.size(), weight, knownWeight, null, null, null,
                            "同一期财报的可用成分股数量或权重不足", null);
                    recordedCoverage = true;
                }
                continue;
            }
            BigDecimal revenue = BigDecimal.ZERO;
            BigDecimal profit = BigDecimal.ZERO;
            BigDecimal resilient = BigDecimal.ZERO;
            for (ConstituentEarnings row : sample) {
                revenue = revenue.add(row.revenueYoy().multiply(row.weight()));
                profit = profit.add(row.profitYoy().multiply(row.weight()));
                if (row.profit().signum() > 0 && row.profitYoy().signum() >= 0) {
                    resilient = resilient.add(row.weight());
                }
            }
            boolean supported = revenue.signum() >= 0 && profit.signum() >= 0
                    && resilient.multiply(HUNDRED).compareTo(weight.multiply(SUPPORT_SHARE)) >= 0;
            return new Earnings(supported ? "SUPPORTED" : "WEAK_OR_MIXED",
                    holdingsDate, reportDate, sample.size(), weight, knownWeight,
                    divide(revenue, weight), divide(profit, weight),
                    divide(resilient.multiply(HUNDRED), weight),
                    "按当前指数权重计算成分样本同比增速，财报期与指数权重日期各自列出",
                    growth(sample, reportDate, byPeriod.get(reportDate.minusYears(3)), knownWeight));
        }
        return insufficient;
    }

    private static boolean enoughCoverage(int companies, BigDecimal weight, BigDecimal knownWeight) {
        return companies >= 3 && weight.compareTo(MIN_INDEX_WEIGHT) >= 0
                && weight.multiply(HUNDRED).compareTo(knownWeight.multiply(SUPPORT_SHARE)) >= 0;
    }

    private static Growth growth(List<ConstituentEarnings> current, LocalDate period,
                                 Map<String, ConstituentEarnings> baseline, BigDecimal knownWeight) {
        if (baseline == null) return null;
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal profit = BigDecimal.ZERO;
        int count = 0;
        for (ConstituentEarnings row : current) {
            ConstituentEarnings earlier = baseline.get(row.stockCode());
            if (earlier == null || !positive(earlier.revenue()) || !positive(row.revenue())
                    || !positive(earlier.profit()) || !positive(row.profit())) continue;
            count++;
            weight = weight.add(row.weight());
            revenue = revenue.add(cagr(row.revenue(), earlier.revenue()).multiply(row.weight()));
            profit = profit.add(cagr(row.profit(), earlier.profit()).multiply(row.weight()));
        }
        return enoughCoverage(count, weight, knownWeight)
                ? new Growth(period.minusYears(3), period, count, weight,
                        divide(revenue, weight), divide(profit, weight)) : null;
    }

    private static BigDecimal cagr(BigDecimal value, BigDecimal baseline) {
        return BigDecimal.valueOf((Math.cbrt(value.doubleValue() / baseline.doubleValue()) - 1) * 100);
    }

    public static Boolean lowPrice(PricePosition position, LocalDate asOf) {
        if (position == null || !position.isAvailable() || position.getBarCount() < 200
                || position.getLastTradeDate().isAfter(asOf)
                || position.getLastTradeDate().isBefore(asOf.minusDays(7))) return null;
        return position.getPricePercentile().compareTo(BigDecimal.valueOf(30)) <= 0
                && position.getDrawdownFromHigh().compareTo(BigDecimal.TEN) >= 0;
    }

    public static LocalDate completedDay(LocalDateTime shanghaiTime) {
        LocalDate day = shanghaiTime.toLocalDate();
        return shanghaiTime.toLocalTime().isBefore(LocalTime.of(15, 0)) ? day.minusDays(1) : day;
    }

    public static Volume volume(List<PriceBar> input, LocalDate asOf) {
        if (input == null) return null;
        List<PriceBar> bars = datedBars(input, asOf);
        if (bars.isEmpty()) return null;
        PriceBar last = bars.get(bars.size() - 1);
        if (last.tradeDate().isBefore(asOf.minusDays(7))) return null;
        BigDecimal ratio = null;
        BigDecimal change = null;
        if (bars.size() >= 2) {
            PriceBar previous = bars.get(bars.size() - 2);
            change = divide(last.closePrice().subtract(previous.closePrice()).multiply(HUNDRED),
                    previous.closePrice());
        }
        if (bars.size() >= 21 && positive(last.volume()) && last.source() != null) {
            List<PriceBar> window = bars.subList(bars.size() - 21, bars.size());
            if (window.stream().allMatch(b -> last.source().equals(b.source()) && positive(b.volume()))) {
                BigDecimal sum = window.subList(0, 20).stream().map(PriceBar::volume)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                ratio = divide(last.volume().multiply(BigDecimal.valueOf(20)), sum);
            }
        }
        return new Volume(last.tradeDate(), last.source(), ratio, change, last.amount(), last.turnoverRate());
    }

    public static FredRates.Comparison compareUsRates(FredRates fredRates, List<PriceBar> prices,
                                                     LocalDate asOf) {
        var window = recentWindow(prices, asOf);
        if (window.isEmpty()) return null;
        return fredRates.compare(asOf, window.get(0).tradeDate(), window.get(1).tradeDate(),
                priceReturn(window.get(0), window.get(1)));
    }

    public static View gold(FredRates fredRates, List<PriceBar> prices, List<PriceBar> volumePrices,
                            LocalDate completedDay, LocalDate today) {
        List<PriceBar> bars = datedBars(prices, completedDay);
        Boolean lowPrice = lowPrice(PricePositionCalculator.compute(bars.stream()
                .map(bar -> new PricePositionCalculator.Bar(bar.tradeDate(), bar.closePrice())).toList()), today);
        var window = recentWindow(bars, completedDay);
        var macro = window.isEmpty() ? null : fredRates.compareGold(completedDay,
                window.get(0).tradeDate(), window.get(1).tradeDate(), priceReturn(window.get(0), window.get(1)));
        return new View(lowPrice, false, missing("黄金不适用成分股盈利分析"),
                volume(volumePrices, completedDay), null, null, new Gold(lowPrice, macro));
    }

    public static Benchmark compareBenchmark(String code, String label, List<PriceBar> prices,
                                             List<PriceBar> benchmark, LocalDate asOf) {
        var window = recentWindow(prices, asOf);
        if (window.isEmpty()) return null;
        LocalDate from = window.get(0).tradeDate();
        LocalDate to = window.get(1).tradeDate();
        var endpoints = datedBars(benchmark, asOf).stream()
                .filter(bar -> bar.tradeDate().equals(from) || bar.tradeDate().equals(to)).toList();
        if (endpoints.size() != 2 || endpoints.get(0).source() == null
                || !endpoints.get(0).source().equals(endpoints.get(1).source())) return null;
        BigDecimal etfReturn = priceReturn(window.get(0), window.get(1));
        BigDecimal benchmarkReturn = priceReturn(endpoints.get(0), endpoints.get(1));
        return new Benchmark(code, label, from, to, etfReturn, benchmarkReturn,
                etfReturn.subtract(benchmarkReturn));
    }

    private static List<PriceBar> recentWindow(List<PriceBar> prices, LocalDate asOf) {
        List<PriceBar> bars = datedBars(prices, asOf);
        if (bars.size() < 21) return List.of();
        PriceBar last = bars.get(bars.size() - 1);
        PriceBar first = bars.get(bars.size() - 21);
        if (last.tradeDate().isBefore(asOf.minusDays(7))
                || first.tradeDate().isBefore(last.tradeDate().minusDays(45))
                || first.source() == null || !first.source().equals(last.source())) return List.of();
        return List.of(first, last);
    }

    private static BigDecimal priceReturn(PriceBar first, PriceBar last) {
        return divide(last.closePrice().subtract(first.closePrice()).multiply(HUNDRED), first.closePrice());
    }

    private static List<PriceBar> datedBars(List<PriceBar> input, LocalDate asOf) {
        Map<LocalDate, PriceBar> byDate = new TreeMap<>();
        for (PriceBar bar : input) {
            if (bar.tradeDate() != null && !bar.tradeDate().isAfter(asOf) && positive(bar.closePrice())) {
                byDate.putIfAbsent(bar.tradeDate(), bar);
            }
        }
        return List.copyOf(byDate.values());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }
}
