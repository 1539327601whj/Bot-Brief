package com.ai.daily.screener;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全市场快照的组装：清单（datacenter-web）+ 行情批量（ulist）。
 *
 * <p>这里钉的是换掉 clist 之后最容易悄悄出错的三处：
 * <ul>
 *   <li>行情没回来的标的必须**整个剔出池子**，而不是留着一堆 null 字段进去，
 *       否则它们会被记进某条排雷规则的命中数，「哪条规则拦下了多少只」这个摘要就失真了；</li>
 *   <li>批次要按配置切分，否则一次问 500 只等于把批量接口当单发用；</li>
 *   <li>一旦被限流要**立刻停手**——继续把余下批次打出去换不来数据，只会加深封禁。</li>
 * </ul>
 */
class MarketDataUniverseTest {

    private static final int KLINE_LIMIT = 250;
    private static final int POOL_SIZE = 500;
    private static final int BATCH = 50;

    // ------------------------------------------------------------------
    // 假返回
    // ------------------------------------------------------------------

    private static Map<String, Object> listRow(String code, String secucode, double cap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("SECURITY_CODE", code);
        m.put("SECUCODE", secucode);
        m.put("SECURITY_NAME_ABBR", "名称" + code);
        m.put("TOTAL_MARKET_CAP", cap);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> listBody(Map<String, Object>... rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", List.of(rows));
        result.put("count", rows.length);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("result", result);
        root.put("success", true);
        return root;
    }

    private static Map<String, Object> quoteRow(String code, double cap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("f12", code);
        m.put("f14", "名称" + code);
        m.put("f2", 10.0);
        m.put("f20", cap);
        m.put("f115", 12.0);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> quoteBody(Map<String, Object>... rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("diff", List.of(rows));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("data", data);
        return root;
    }

    private static Map<String, Object> tradeDateBody(String date) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("TRADE_DATE", date + " 00:00:00");
        return listBody(row);
    }

    // ------------------------------------------------------------------
    // 假 RestTemplate
    // ------------------------------------------------------------------

    /** URL 分类：清单要交易日 / 清单要池子 / 行情批量。 */
    private static String kindOf(URI uri) {
        String s = uri.toString();
        if (s.contains("/api/data/v1/get")) {
            return s.contains("columns=TRADE_DATE") ? "date" : "list";
        }
        return "quote";
    }

    /**
     * 把清单 / 行情两次外呼分开回答；host 用不到，统一按路径判别。
     *
     * <p>假响应必须回**字节**：生产代码故意不要 {@code Map.class}（服务端把 JSON 声明成
     * {@code text/plain}，Jackson 转换器读不了，见 {@code MarketDataClient.getJson}），
     * 而是自己拿字节按 charset 解码再解析。这里跟着真实那一步走，
     * 免得「mock 的返回类型与生产不一致」把整类问题挡在测试之外。
     */
    private static RestTemplate client(Function<URI, Map<String, Object>> handler) {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenAnswer(inv -> {
                    Map<String, Object> body = handler.apply(inv.getArgument(0));
                    try {
                        return ResponseEntity.ok(MAPPER.writeValueAsBytes(body));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                });
        return rt;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static RestTemplate clientThatThrowsOnQuote(int failFromBatch,
                                                        RuntimeException toThrow) {
        AtomicInteger quoteCalls = new AtomicInteger();
        return client(uri -> {
            switch (kindOf(uri)) {
                case "date": return tradeDateBody("2026-09-30");
                case "list": return listBody(listRow("600519", "600519.SH", 1.9e12));
                default:
                    if (quoteCalls.incrementAndGet() >= failFromBatch) throw toThrow;
                    return quoteBody(quoteRow("600519", 1.9e12));
            }
        });
    }

    private static MarketDataClient newClient(RestTemplate rt, int batch) {
        return new MarketDataClient(rt, KLINE_LIMIT, POOL_SIZE, batch);
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    @Test
    void assemblesThePoolFromTheListThenFillsItWithQuotes() {
        RestTemplate rt = client(uri -> switch (kindOf(uri)) {
            case "date" -> tradeDateBody("2026-09-30");
            case "list" -> listBody(
                    listRow("600519", "600519.SH", 1.9e12),
                    listRow("000001", "000001.SZ", 2.5e11),
                    listRow("300750", "300750.SZ", 1.1e12));
            default -> quoteBody(
                    quoteRow("600519", 1.9e12),
                    quoteRow("000001", 2.5e11),
                    quoteRow("300750", 1.1e12));
        });

        MarketDataClient.UniverseSnapshot snap = newClient(rt, BATCH).fetchUniverse();

        assertThat(snap.rows()).extracting(StockRow::getCode)
                .containsExactlyInAnyOrder("600519", "000001", "300750");
        assertThat(snap.listedCount()).isEqualTo(3);
        assertThat(snap.missing()).isZero();
        assertThat(snap.tradeDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 30));
        // 基本面必须来自行情接口，不是清单
        assertThat(snap.rows()).allSatisfy(r -> assertThat(r.getPeTtm()).isNotNull());
    }

    @Test
    void dropsCodesWhoseQuotesNeverCameBack() {
        // 关键：没行情的标的必须整个剔出去。留着它，它就会以一堆 null 字段进入排雷，
        // 然后被记成某条规则的命中数——摘要里「这条规则拦下了多少只」就假了。
        RestTemplate rt = client(uri -> switch (kindOf(uri)) {
            case "date" -> tradeDateBody("2026-09-30");
            case "list" -> listBody(
                    listRow("600519", "600519.SH", 1.9e12),
                    listRow("000001", "000001.SZ", 2.5e11),
                    listRow("300750", "300750.SZ", 1.1e12));
            default -> quoteBody(quoteRow("600519", 1.9e12), quoteRow("000001", 2.5e11));
        });

        MarketDataClient.UniverseSnapshot snap = newClient(rt, BATCH).fetchUniverse();

        assertThat(snap.rows()).extracting(StockRow::getCode)
                .containsExactlyInAnyOrder("600519", "000001");
        assertThat(snap.listedCount()).isEqualTo(3);
        assertThat(snap.missing()).isEqualTo(1);
    }

    @Test
    void excludesBeijingCodesBecauseTheCharterDoesNotCoverThem() {
        RestTemplate rt = client(uri -> switch (kindOf(uri)) {
            case "date" -> tradeDateBody("2026-09-30");
            case "list" -> listBody(
                    listRow("600519", "600519.SH", 1.9e12),
                    listRow("920014", "920014.BJ", 1.0e9));
            default -> quoteBody(quoteRow("600519", 1.9e12), quoteRow("920014", 1.0e9));
        });

        MarketDataClient.UniverseSnapshot snap = newClient(rt, BATCH).fetchUniverse();

        assertThat(snap.rows()).extracting(StockRow::getCode).containsExactly("600519");
        // 北交所是在清单那一步就丢掉的，不该被算成「行情没回来」
        assertThat(snap.listedCount()).isEqualTo(1);
        assertThat(snap.missing()).isZero();
    }

    @Test
    void capFloorIsTheSmallestMarketCapInThePool() {
        RestTemplate rt = client(uri -> switch (kindOf(uri)) {
            case "date" -> tradeDateBody("2026-09-30");
            case "list" -> listBody(
                    listRow("600519", "600519.SH", 1.9e12),
                    listRow("601216", "601216.SH", 3.83e10));
            default -> quoteBody(
                    quoteRow("600519", 1.9e12),
                    quoteRow("601216", 3.83e10));
        });

        MarketDataClient.UniverseSnapshot snap = newClient(rt, BATCH).fetchUniverse();

        assertThat(snap.capFloor()).isNotNull().isLessThan(java.math.BigDecimal.valueOf(4e10));
    }

    @Test
    void emptyListFailsLoudlyRatherThanReturningNothing() {
        RestTemplate rt = client(uri -> switch (kindOf(uri)) {
            case "date" -> tradeDateBody("2026-09-30");
            default -> listBody();
        });

        assertThatThrownBy(() -> newClient(rt, BATCH).fetchUniverse())
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("空列表");
    }

    @Test
    void listWithoutAnyQuoteFailsLoudly() {
        RestTemplate rt = client(uri -> switch (kindOf(uri)) {
            case "date" -> tradeDateBody("2026-09-30");
            case "list" -> listBody(listRow("600519", "600519.SH", 1.9e12));
            default -> quoteBody();
        });

        assertThatThrownBy(() -> newClient(rt, BATCH).fetchUniverse())
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("一只都没取到");
    }

    // ------------------------------------------------------------------
    // 分批与限流
    // ------------------------------------------------------------------

    @Test
    void quotesAreSplitIntoBatchesOfTheConfiguredSize() {
        List<String> secids = List.of("1.600519", "1.600520", "1.600521", "1.600522", "1.600523");
        AtomicInteger quoteCalls = new AtomicInteger();
        RestTemplate rt = client(uri -> {
            if (!"quote".equals(kindOf(uri))) throw new IllegalStateException("不该走到这里");
            quoteCalls.incrementAndGet();
            return quoteBody();
        });

        newClient(rt, 2).fetchQuotes(secids);

        // 5 只、每批 2 只 → 3 批，不是 5 次单发
        assertThat(quoteCalls.get()).isEqualTo(3);
    }

    @Test
    void stopsBatchingImmediatelyWhenThrottled() {
        // 被限流时继续把余下的批次打出去，换不来数据，只会把封禁推得更深。
        AtomicInteger quoteCalls = new AtomicInteger();
        RestTemplate rt = client(uri -> {
            if (!"quote".equals(kindOf(uri))) throw new IllegalStateException("不该走到这里");
            if (quoteCalls.incrementAndGet() >= 2) {
                throw new ResourceAccessException("I/O error",
                        new SocketException("Connection reset"));
            }
            return quoteBody();
        });
        List<String> secids = new ArrayList<>();
        for (int i = 0; i < 10; i++) secids.add("1.60051" + i);

        assertThatThrownBy(() -> newClient(rt, 2).fetchQuotes(secids))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class);

        // 共 5 批，第 2 批就撞上限流，剩下 3 批一次都不该发
        assertThat(quoteCalls.get()).isEqualTo(2);
    }

    @Test
    void aFailedBatchIsReportedWithBatchNumberAndOtherBatchesStillCount() {
        AtomicInteger quoteCalls = new AtomicInteger();
        RestTemplate rt = client(uri -> {
            if (!"quote".equals(kindOf(uri))) throw new IllegalStateException("不该走到这里");
            if (quoteCalls.incrementAndGet() == 1) {
                throw new ResourceAccessException("I/O error", new SocketTimeoutException("timed out"));
            }
            return quoteBody(quoteRow("600520", 1e10));
        });

        Map<String, StockRow> quotes = newClient(rt, 1)
                .fetchQuotes(List.of("1.600519", "1.600520"));

        assertThat(quotes).containsOnlyKeys("600520");
    }

    @Test
    void klineTellsTheCallerWhenItWasThrottledSoCooldownCanStart() {
        RestTemplate rt = client(uri -> {
            throw new ResourceAccessException("I/O error", new SocketException("Connection reset"));
        });

        MarketDataClient.KlineOutcome outcome = newClient(rt, BATCH).fetchKline("1.600519");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.throttled()).isTrue();
        assertThat(outcome.failureReason()).contains("限流");
    }

    @Test
    void klineFailureThatIsNotThrottlingIsNotReportedAsThrottling() {
        RestTemplate rt = client(uri -> {
            throw new ResourceAccessException("I/O error", new UnknownHostException("no dns"));
        });

        MarketDataClient.KlineOutcome outcome = newClient(rt, BATCH).fetchKline("1.600519");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.throttled()).isFalse();
        assertThat(outcome.failureReason()).contains("日线不可达");
    }

    // ------------------------------------------------------------------
    // 失败分类
    // ------------------------------------------------------------------

    @Test
    void distinguishesBeingThrottledFromTheNetworkBeingDown() {
        // 「连接建立起来又被掐」= 服务端在拒绝我们 → 必须停手等冷却
        assertThat(MarketDataClient.isThrottled(
                new ResourceAccessException("x", new SocketException("Connection reset")))).isTrue();
        assertThat(MarketDataClient.isThrottled(
                new ResourceAccessException("x",
                        new SocketException("Software caused connection abort")))).isTrue();
        assertThat(MarketDataClient.isThrottled(
                new ResourceAccessException("x", new EOFException()))).isTrue();

        // 「连都连不上」= 网络问题 → 与限流区分开，因为处置完全不同
        assertThat(MarketDataClient.isThrottled(
                new ResourceAccessException("x", new ConnectException("Connection refused")))).isFalse();
        assertThat(MarketDataClient.isThrottled(
                new ResourceAccessException("x", new UnknownHostException("no dns")))).isFalse();
        assertThat(MarketDataClient.isThrottled(new RuntimeException("其它"))).isFalse();

        assertThat(MarketDataClient.isConnectLevel(
                new ResourceAccessException("x", new ConnectException("Connection refused")))).isTrue();
        assertThat(MarketDataClient.isConnectLevel(
                new ResourceAccessException("x", new SocketException("Connection reset")))).isFalse();
    }

    @Test
    void shortReasonDigsToTheRootCauseInsteadOfDumpingTheUrl() {
        RuntimeException wrapped = new ResourceAccessException(
                "I/O error on GET request for \"https://push2.eastmoney.com/api/qt/ulist.np/get?...\"",
                new ConnectException("Connection refused: connect"));
        assertThat(MarketDataClient.shortReason(wrapped))
                .isEqualTo("ConnectException：Connection refused: connect");
    }
}