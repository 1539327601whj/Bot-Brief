package com.ai.daily.screener.v2;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/** Daily US rate observations shared across ETF queries. */
@Component
public class FredRates {
    private static final String POLICY = "DFEDTARU";
    private static final String TREASURY = "DGS10";
    private static final String REAL_TREASURY = "DFII10";
    private static final String DOLLAR = "DTWEXBGS";
    private static final String USD_CNY = "DEXCHUS";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private Cache cache;
    private GoldCache goldCache;

    public record RateChange(LocalDate from, LocalDate to, BigDecimal basisPoints) {}
    public record Comparison(LocalDate etfFrom, LocalDate etfTo, BigDecimal etfReturnPct,
                             RateChange policy, RateChange treasury) {}
    public record FxChange(LocalDate from, LocalDate to, BigDecimal returnPct) {}
    public record GoldComparison(LocalDate etfFrom, LocalDate etfTo, BigDecimal etfReturnPct,
                                 RateChange realTreasury, FxChange dollar, FxChange usdCny) {}

    private record Cache(LocalDate date, Instant expiresAt, NavigableMap<LocalDate, BigDecimal> policy,
                         NavigableMap<LocalDate, BigDecimal> treasury) {}
    private record GoldCache(LocalDate date, Instant expiresAt, NavigableMap<LocalDate, BigDecimal> real,
                             NavigableMap<LocalDate, BigDecimal> dollar,
                             NavigableMap<LocalDate, BigDecimal> usdCny) {}

    public Comparison compare(LocalDate asOf, LocalDate from, LocalDate to, BigDecimal etfReturnPct) {
        Cache rates = load(asOf);
        // US observations dated the same day can arrive after the Shanghai close.
        return new Comparison(from, to, etfReturnPct,
                change(rates.policy(), from.minusDays(1), to.minusDays(1)),
                change(rates.treasury(), from.minusDays(1), to.minusDays(1)));
    }

    public GoldComparison compareGold(LocalDate asOf, LocalDate from, LocalDate to, BigDecimal etfReturnPct) {
        GoldCache macro = loadGold(asOf);
        LocalDate first = from.minusDays(1);
        LocalDate last = to.minusDays(1);
        return new GoldComparison(from, to, etfReturnPct, change(macro.real(), first, last),
                fx(macro.dollar(), first, last), fx(macro.usdCny(), first, last));
    }

    private synchronized GoldCache loadGold(LocalDate today) {
        Instant now = Instant.now();
        if (goldCache != null && today.equals(goldCache.date()) && now.isBefore(goldCache.expiresAt())) {
            return goldCache;
        }
        var realRequest = fetch(REAL_TREASURY, today);
        var dollarRequest = fetch(DOLLAR, today);
        var yuanRequest = fetch(USD_CNY, today);
        var real = realRequest.join();
        var dollar = dollarRequest.join();
        var yuan = yuanRequest.join();
        boolean complete = !real.isEmpty() && !dollar.isEmpty() && !yuan.isEmpty();
        goldCache = new GoldCache(today, now.plus(complete ? Duration.ofHours(6) : Duration.ofMinutes(15)),
                real, dollar, yuan);
        return goldCache;
    }

    private synchronized Cache load(LocalDate today) {
        Instant now = Instant.now();
        if (cache != null && today.equals(cache.date()) && now.isBefore(cache.expiresAt())) return cache;
        var policyRequest = fetch(POLICY, today);
        var treasuryRequest = fetch(TREASURY, today);
        var policy = policyRequest.join();
        var treasury = treasuryRequest.join();
        boolean complete = !policy.isEmpty() && !treasury.isEmpty();
        cache = new Cache(today, now.plus(complete ? Duration.ofHours(6) : Duration.ofMinutes(15)),
                policy, treasury);
        return cache;
    }

    private CompletableFuture<NavigableMap<LocalDate, BigDecimal>> fetch(String series, LocalDate today) {
        URI uri = URI.create("https://fred.stlouisfed.org/graph/fredgraph.csv?id=" + series
                + "&cosd=" + today.minusDays(100) + "&coed=" + today.minusDays(1));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> response.statusCode() == 200
                        ? parse(response.body(), series) : new TreeMap<LocalDate, BigDecimal>())
                .exceptionally(error -> new TreeMap<>());
    }

    static NavigableMap<LocalDate, BigDecimal> parse(String csv, String series) {
        NavigableMap<LocalDate, BigDecimal> rows = new TreeMap<>();
        String[] lines = csv.split("\\R");
        if (lines.length == 0 || !lines[0].strip().equalsIgnoreCase("observation_date," + series)) return rows;
        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].split(",", -1);
            if (fields.length != 2) continue;
            try {
                rows.put(LocalDate.parse(fields[0].strip()), new BigDecimal(fields[1].strip()));
            } catch (DateTimeParseException | NumberFormatException ignored) {
                // US non-trading days can have an empty observation.
            }
        }
        return rows;
    }

    static RateChange change(NavigableMap<LocalDate, BigDecimal> rates, LocalDate from, LocalDate to) {
        var first = rates.floorEntry(from);
        var last = rates.floorEntry(to);
        if (first == null || last == null || !last.getKey().isAfter(first.getKey())
                || first.getKey().isBefore(from.minusDays(7)) || last.getKey().isBefore(to.minusDays(7))) {
            return null;
        }
        return new RateChange(first.getKey(), last.getKey(),
                last.getValue().subtract(first.getValue()).movePointRight(2));
    }

    private static FxChange fx(NavigableMap<LocalDate, BigDecimal> rates, LocalDate from, LocalDate to) {
        var first = rates.floorEntry(from);
        var last = rates.floorEntry(to);
        if (first == null || last == null || !last.getKey().isAfter(first.getKey())
                || first.getKey().isBefore(from.minusDays(10)) || last.getKey().isBefore(to.minusDays(10))
                || first.getValue().signum() <= 0 || last.getValue().signum() <= 0) {
            return null;
        }
        BigDecimal change = last.getValue().subtract(first.getValue()).movePointRight(2)
                .divide(first.getValue(), 3, java.math.RoundingMode.HALF_UP);
        return new FxChange(first.getKey(), last.getKey(), change);
    }
}
