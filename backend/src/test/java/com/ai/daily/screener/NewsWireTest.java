package com.ai.daily.screener;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link NewsWire} 的三件事，每一件都对应探针实测钉死的一条形态。
 *
 * <p>这些断言的价值全在**「猜错了不报错」**上：编码方式错了服务端回一个空壳，
 * JSONP 壳对不上只会解析不出东西，日期格式错了会静默丢掉整条。
 * 三种表现都是「页面那一栏空着」，而空着最容易读成「最近确实没有」。
 */
class NewsWireTest {

    // ------------------------------------------------------------------
    // 百分号编码
    // ------------------------------------------------------------------

    /**
     * 这条 URL 是 {@code verify_analysis_sources.py} **实际打出去并拿到 200 的那一条**
     * （2026-10-15 探针第二节）。拿它做等值断言，而不是拿「我以为对」的编码做断言——
     * 差别在于后者只能证明我的编码器自洽，前者能证明它和那个接口的约定一致。
     */
    private static final String PROBE_URL =
            "https://search-api-web.eastmoney.com/search/jsonp?cb=cb&param="
                    + "%7B%22uid%22%3A%22%22%2C%22keyword%22%3A%22%E9%98%B3%E5%85%89%E7%94%B5%E6%BA%90%22"
                    + "%2C%22type%22%3A%5B%22cmsArticleWebOld%22%5D%2C%22client%22%3A%22web%22"
                    + "%2C%22clientType%22%3A%22web%22%2C%22clientVersion%22%3A%22curr%22"
                    + "%2C%22param%22%3A%7B%22cmsArticleWebOld%22%3A%7B%22searchScope%22%3A%22default%22"
                    + "%2C%22sort%22%3A%22default%22%2C%22pageIndex%22%3A1%2C%22pageSize%22%3A20"
                    + "%2C%22preTag%22%3A%22%3Cem%3E%22%2C%22postTag%22%3A%22%3C%2Fem%3E%22%7D%7D%7D";

    @Test
    void theSearchUrlIsByteForByteTheOneTheProbeGotA200From() {
        assertThat(EastMoneyNewsClient.searchUrl("阳光电源")).isEqualTo(PROBE_URL);
    }

    @Test
    void unreservedCharactersSurviveAndEverythingElseIsUppercaseHex() {
        // 空格必须是 %20 而不是 +。URLEncoder 会给 +，而 + 在「服务端要解码两次」的
        // 接口上能不能还原成空格，取决于第二层解码走的哪套规则——纯白送的风险。
        assertThat(NewsWire.percentEncode("a b")).isEqualTo("a%20b");
        assertThat(NewsWire.percentEncode("A-z_0.9~")).isEqualTo("A-z_0.9~");
        // 大写十六进制，与 Python 的 urllib.parse.quote 逐字一致
        assertThat(NewsWire.percentEncode("中")).isEqualTo("%E4%B8%AD");
        // 斜杠、冒号、问号这些在 query 里有保留含义的一律编掉
        assertThat(NewsWire.percentEncode("a/b?c:d")).isEqualTo("a%2Fb%3Fc%3Ad");
    }

    @Test
    void theCallbackNameWeSendIsTheOneWeStripFor() {
        // 壳名是我们指定的常量而不是随机数——不然「响应以 cb( 开头」这个判据写不出来，
        // 只能退化成「找第一个左括号」，而那种写法在服务端回 HTML 错误页时静默解析失败。
        assertThat(EastMoneyNewsClient.CALLBACK).isEqualTo("cb");
    }

    // ------------------------------------------------------------------
    // JSONP 剥壳
    // ------------------------------------------------------------------

    @Test
    void aWellFormedShellIsStrippedAndParsed() {
        JsonNode node = NewsWire.jsonp("cb({\"code\":0,\"hitsTotal\":7})", "cb");
        assertThat(node.path("hitsTotal").asInt()).isEqualTo(7);
    }

    @Test
    void surroundingWhitespaceAndATrailingSemicolonAreTolerated() {
        // 有些 CDN 会在壳后面补一个分号，有些会前置换行。这两种都不是错误。
        JsonNode node = NewsWire.jsonp("\n  cb({\"a\":1});\n", "cb");
        assertThat(node.path("a").asInt()).isEqualTo(1);
    }

    @Test
    void anHtmlErrorPageSaysWhatTheServerActuallyReturned() {
        // **这是本类最重要的一条。** 服务端被拦或改版时回的是一段 HTML，状态码仍是 200。
        // 如果这里返回 null，上层只能说「没拿到数据」——而真正该看到的是那段原文。
        // 章程 §6 明令不许把「我们没解析出来」写成「源没有数据」。
        assertThatThrownBy(() -> NewsWire.jsonp("<html><body>403 Forbidden</body></html>", "cb"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("JSONP")
                .hasMessageContaining("403 Forbidden");
    }

    @Test
    void aDifferentCallbackNameIsAFailureNotASilentEmptyResult() {
        // 服务端换了壳名（例如按请求回显了别的名字）时必须报出来，
        // 否则表现是「这一类新闻一条都没有」。
        assertThatThrownBy(() -> NewsWire.jsonp("jQuery123({\"a\":1})", "cb"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("jQuery123");
    }

    @Test
    void aTruncatedShellIsAFailure() {
        assertThatThrownBy(() -> NewsWire.jsonp("cb(", "cb"))
                .isInstanceOf(MarketDataException.class);
    }

    @Test
    void aShellWhoseBodyIsNotJsonIsAFailureCarryingTheBody() {
        assertThatThrownBy(() -> NewsWire.jsonp("cb(not json at all)", "cb"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("not json at all");
    }

    // ------------------------------------------------------------------
    // 日期
    // ------------------------------------------------------------------

    @Test
    void theFirstTenCharactersAreTheDay() {
        assertThat(NewsWire.dayOf("2026-10-15 11:07:27")).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(NewsWire.dayOf("2026-10-15T00:00:00")).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(NewsWire.dayOf("2026-10-15")).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void anUnrecognisedDateFormatYieldsNullRatherThanToday() {
        // 公告的 display_time 长这样：2026-10-15 16:02:03:301（毫秒位用**冒号**）。
        // 这里返回 null 让上层丢掉那条并计数。**绝不能退回「今天」**——
        // 编出来的日期看起来和真的一模一样，而章程 §8 要求每个时间都能回答「哪一日」。
        assertThat(NewsWire.dayOf("2026-10-15 16:02:03:301")).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(NewsWire.dayOf("15/10/2026")).isNull();
        assertThat(NewsWire.dayOf("2026年10月15日")).isNull();
        assertThat(NewsWire.dayOf("2026-13-45")).isNull();
        assertThat(NewsWire.dayOf(null)).isNull();
        assertThat(NewsWire.dayOf("")).isNull();
    }

    @Test
    void aDateCarriedByAJsonNodeIsReadTooNotOnlyByAString() throws Exception {
        // **这条是补一个真出现过的 bug。** dayOf 曾经写成 String.valueOf(raw)，
        // 而两个调用方传进来的类型不一样：StockNoticeClient 走的是 Map 里的原生 String，
        // EastMoneyNewsClient 走的是 JsonNode。于是后者**每一条都取不到日期**，
        // 表现是整路资讯返回空——页面上就是「资讯那一栏没有内容」，
        // 而没有任何一处会报错。
        //
        // 两种入参各断言一次，是因为修法有两条路（改 dayOf，或改调用方），
        // 这条测试只认「两种都能用」这个结果。
        JsonNode node = NewsWire.MAPPER.readTree(
                "{\"date\":\"2026-10-15 11:07:27\",\"blank\":\"\",\"n\":7}");

        assertThat(NewsWire.dayOf(node.get("date"))).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(NewsWire.dayOf("2026-10-15 11:07:27")).isEqualTo(LocalDate.of(2026, 10, 15));
        // JsonNode 的 NullNode / 空串 / 数字节点都不能变成日期
        assertThat(NewsWire.dayOf(node.get("blank"))).isNull();
        assertThat(NewsWire.dayOf(node.get("n"))).isNull();
        assertThat(NewsWire.dayOf(node.get("missing"))).isNull();
    }

    @Test
    void blankStringsCountAsMissing() {
        // 东财用 "" 表示「没有」，不是 null。按 null 处理才不会把空标题当成有标题。
        assertThat(NewsWire.text("")).isNull();
        assertThat(NewsWire.text("   ")).isNull();
        assertThat(NewsWire.text(null)).isNull();
        assertThat(NewsWire.text(" x ")).isEqualTo("x");
    }
}
