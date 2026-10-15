package com.ai.daily.screener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * 新闻源用到的**线上形态**处理：百分号编码、JSONP 剥壳、日期字段取值。
 *
 * <p>集中在一处是因为这几条都是 {@code verify_analysis_sources.py} **实测定死**的，
 * 每一条都有一条「猜错了不报错、只是永远拿不到数据」的失败模式：
 *
 * <ul>
 *   <li>{@link #percentEncode} —— 资讯搜索的 {@code param} **只编一次**。编两次服务端会回
 *       {@code {"code":400,"msg":"非法的json格式"}}（探针实测）。这里刻意**不用
 *       {@code URLEncoder}**：它把空格编成 {@code +}，而验证过能用的形态是 {@code %20}。
 *       在「服务端要把这个参数解码两次」的接口上，{@code +} 能不能还原成空格取决于
 *       第二层解码走的哪套规则，是纯粹的白送风险。</li>
 *   <li>{@link #jsonp} —— 壳形态实测为 {@code cb(...)}。**不拿「第一个左括号到最后一个右括号」
 *       去糊**：那种写法在服务端改成回 HTML 错误页时会静默解析失败，而这里要的是
 *       把服务端**实际回了什么**写进异常。</li>
 *   <li>{@link #dayOf} —— 公告的 {@code display_time} 是 {@code 16:02:03:301}（毫秒位用冒号），
 *       不是标准格式。所以这里只认 {@code yyyy-MM-dd} 开头的前十位，认不出就返回 null
 *       让上层丢掉这条，**绝不猜**。</li>
 * </ul>
 */
final class NewsWire {

    private NewsWire() {
    }

    /**
     * 解析响应用的。字段只取我们点名的那些——服务端多塞一列不该让解析失败。
     *
     * <p>与 {@link MarketDataClient} 那份是各自独立的实例：那边 {@code private}，
     * 而把它的可见性放开只为省一个对象，会让「行情解析」和「新闻解析」从此共享一份配置，
     * 将来调一处影响两处。一个 ObjectMapper 的构造成本不值得拿这个换。
     */
    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * RFC 3986 的百分号编码：只留 unreserved（{@code A-Za-z0-9-._~}），其余按 UTF-8 逐字节编码。
     *
     * <p>十六进制用**大写**，与探针脚本（Python 的 {@code urllib.parse.quote}）逐字一致——
     * 这一条能钉住，靠的是 {@code NewsWireTest} 里拿探针实际打出去的那个 URL 做等值断言。
     */
    static String percentEncode(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() * 3);
        for (byte b : raw.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }

    /**
     * 剥 {@code callback(...)} 壳并解析。
     *
     * <p><b>壳对不上就抛，不返回 null。</b>回 HTML 错误页、回登录页、或者对方换了回调名，
     * 表现都是「解析不出东西」——返回 null 的话调用方只能报一句「没拿到数据」，
     * 而真正该看到的是服务端回的那段原文。这里把原文截一段放进异常（章程 §6：
     * 不许把「我们没解析出来」写成「源没有数据」）。
     */
    static JsonNode jsonp(String body, String callback) {
        String text = body == null ? "" : body.strip();
        String head = callback + "(";
        if (!text.startsWith(head)) {
            throw new MarketDataException("资讯源返回的不是预期的 JSONP（期望以 " + head + " 开头）："
                    + MarketDataClient.snippet(text));
        }
        // 从**最后**一个右括号往回切：JSON 里不含裸的 ')'，但字符串值里可能有，
        // 而壳的收尾一定是最后一个。切错了会得到一个语法错误的 JSON，当场就报错，不会静默。
        int end = text.lastIndexOf(')');
        if (end < head.length()) {
            throw new MarketDataException("资讯源的 JSONP 壳不完整：" + MarketDataClient.snippet(text));
        }
        String inner = text.substring(head.length(), end);
        try {
            return MAPPER.readTree(inner);
        } catch (Exception e) {
            throw new MarketDataException("资讯源的 JSONP 里不是 JSON：" + MarketDataClient.snippet(inner), e);
        }
    }

    /**
     * {@code "2026-10-15 11:07:27"} / {@code "2026-10-15T00:00:00"} → {@code 2026-10-15}。
     *
     * <p>只认前十位的 {@code yyyy-MM-dd}。认不出返回 null——上层会**丢掉这一条**
     * 并把丢掉的条数报上去，而不是给它编一个今天。章程 §8 要求每个时间都能回答「哪一日」，
     * 编一个日期比不显示更糟：它看起来和真的一样。
     *
     * <p><b>取值走 {@link #text}，不自己写 {@code String.valueOf}。</b>
     * 这里曾经写成 {@code String.valueOf(raw)}，而 {@link EastMoneyNewsClient} 传进来的是
     * {@link JsonNode}（{@link StockNoticeClient} 传的是 {@code Map} 里的原生 {@code String}）——
     * 同一个 {@code dayOf} 被两种类型的调用方共用，只有一种能正常工作。
     * 表现是**每一条资讯都被丢掉**：日期取不到 → 上层判为「字段不全」→ 整路返回空。
     * 而 {@link #text} 早就有 {@code instanceof JsonNode} 分支，重复一份取值逻辑就是这次的错。
     */
    static LocalDate dayOf(Object raw) {
        String s = text(raw);
        if (s == null || s.length() < 10 || s.charAt(4) != '-' || s.charAt(7) != '-') {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 取一个字符串字段并去空白；空串按 null 处理（东财会用 {@code ""} 表示没有）。 */
    static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = raw instanceof JsonNode node ? node.asText("") : String.valueOf(raw);
        s = s.strip();
        return s.isEmpty() ? null : s;
    }
}
