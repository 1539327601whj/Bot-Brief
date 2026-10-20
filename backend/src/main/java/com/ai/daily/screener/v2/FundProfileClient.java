package com.ai.daily.screener.v2;

import com.ai.daily.screener.v2.FactsSnapshot.FundProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 天天基金的「基金档案」：规模（AUM）、费率、跟踪标的、年化跟踪误差。
 *
 * <h2>为什么用这两个页面</h2>
 *
 * <p>行情源只给价与总市值，给不了「基金净资产」——后者是季报里的数，与「现价 × 份额」
 * 不是同一个口径。{@code jbgk}（基本概况）有规模与费率，{@code tsdata}（跟踪误差）
 * 有年化跟踪误差和同类平均。两个页面都是 UTF-8、200、不跳转。
 *
 * <h2>解析为什么不用 jsoup</h2>
 *
 * <p>只为两个固定页面加一个 HTML 解析依赖，收益不值；而这两个页面的形状恰好很稳定：
 * {@code <th>标签</th><td>值</td>}。真正要小心的只有两件事，下面都单独处理了：
 * <ul>
 *   <li>页面里有的 {@code <td>} 省略了闭合标签——所以用「下一个 th/td 开始」隐式闭合并
 *       用 lookahead 取值，而不是等 {@code </td>}；</li>
 *   <li>页面里有多张 {@code fxtb} 表——所以按「表头命中多少已知标签」挑表，
 *       而不是默认取第一张。</li>
 * </ul>
 *
 * <h2>失败语义</h2>
 *
 * <p>{@link #fetch} **永不抛异常、永不返回 null**。页面 200 但没有可识别字段是
 * {@code missing}（这只基金没有档案页，或页面改版了），网络/HTTP 失败才是 {@code failed}。
 * 两者在页面上的含义不同：前者是「这只没有」，后者是「这次没取到」，不能混成一句。
 */
@Slf4j
@Component
public class FundProfileClient {

    static final String ORIGIN = "eastmoney-fund";
    static final String SOURCE_LABEL = "天天基金（基金档案）";

    private static final String INFO_URL = "https://fundf10.eastmoney.com/jbgk_%s.html";
    private static final String TRACKING_URL = "https://fundf10.eastmoney.com/tsdata_%s.html";
    private static final String REFERER = "https://fund.eastmoney.com/";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final long OK_TTL_MS = 12 * 60 * 60_000L;
    private static final long MISSING_TTL_MS = 30 * 60_000L;
    private static final long FAILED_TTL_MS = 10 * 60_000L;

    /** 用来判断「找到的是不是基本概况那张表」。命中 3 个以上才认。 */
    private static final List<String> KNOWN_LABELS = List.of(
            "基金全称", "基金简称", "基金类型", "成立日期", "资产规模",
            "基金管理人", "基金托管人", "业绩比较基准", "跟踪标的", "管理费率", "托管费率");

    private static final Pattern AMOUNT = Pattern.compile(
            "([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(亿|万)?\\s*元");
    private static final Pattern PERCENT = Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?)\\s*%");
    private static final Pattern DATE_DASH = Pattern.compile(
            "(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");
    private static final Pattern DATE_CN = Pattern.compile(
            "(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");

    private final RestTemplate restTemplate;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public FundProfileClient(@Qualifier("marketRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 一次取数的结果。{@code note} 只在非 OK 时有意义。 */
    public record Result(FundProfile profile, String note, boolean failed) {
        static Result ok(FundProfile profile) {
            return new Result(profile, null, false);
        }

        static Result partial(FundProfile profile, String note) {
            return new Result(profile, note, false);
        }

        static Result missing(String note) {
            return new Result(null, note, false);
        }

        static Result failed(String note) {
            return new Result(null, note, true);
        }
    }

    /** 年化跟踪误差与同类平均。{@code asOf} 是页面上「截止至」的日期。 */
    record Tracking(BigDecimal errorPct, BigDecimal peerPct, LocalDate asOf) {
    }

    private record Cached(Result result, long expiresAt) {
    }

    public Result fetch(String etfCode) {
        String code = etfCode == null ? null : etfCode.strip();
        if (code == null || !code.matches("\\d{6}")) {
            return Result.missing("基金代码不是 6 位数字，未取基金档案");
        }
        long now = System.currentTimeMillis();
        Cached cached = cache.get(code);
        if (cached != null && cached.expiresAt() > now) {
            return cached.result();
        }

        String infoFailure = null;
        String trackingFailure = null;
        Map<String, String> info = Map.of();
        Tracking tracking = null;
        try {
            info = parseInfoTable(decode(get(String.format(INFO_URL, code))));
        } catch (RuntimeException e) {
            infoFailure = shortReason(e);
        }
        try {
            tracking = parseTracking(decode(get(String.format(TRACKING_URL, code))));
        } catch (RuntimeException e) {
            trackingFailure = shortReason(e);
        }

        Result result = assemble(code, info, tracking, infoFailure, trackingFailure);
        long ttl = result.failed() ? FAILED_TTL_MS
                : result.profile() == null ? MISSING_TTL_MS : OK_TTL_MS;
        cache.put(code, new Cached(result, now + ttl));
        return result;
    }

    private Result assemble(String code, Map<String, String> info, Tracking tracking,
                            String infoFailure, String trackingFailure) {
        boolean hasInfo = !info.isEmpty();
        boolean hasTracking = tracking != null
                && (tracking.errorPct() != null || tracking.peerPct() != null);
        if (!hasInfo && !hasTracking) {
            if (infoFailure != null && trackingFailure != null) {
                return Result.failed("基金档案暂不可用（东财基金页未取到：" + infoFailure + "）");
            }
            return Result.missing("基金档案页没有可识别的字段（可能尚未收录或页面已改版）");
        }

        String fullName = value(info, "基金全称", "基金名称");
        String shortName = value(info, "基金简称");
        BigDecimal aumAmount = parseAmountYuan(value(info, "资产规模", "净资产规模", "最新规模"));
        LocalDate aumAsOf = parseDate(value(info, "资产规模", "净资产规模", "最新规模"));
        if (aumAsOf == null) {
            aumAsOf = parseDate(value(info, "成立日期"));
        }

        // jbgk 的标签是「成立日期/规模」，值形如「2013年04月25日 / 2.676亿份」。
        // 日期给 inceptionDate，斜杠后面那段才是成立规模。
        String inceptionRaw = value(info, "成立日期");
        String inceptionScale = null;
        if (inceptionRaw != null) {
            int slash = inceptionRaw.indexOf('/');
            if (slash >= 0) {
                inceptionScale = inceptionRaw.substring(slash + 1).trim();
            }
        }

        FundProfile profile = new FundProfile(
                code,
                shortName,
                fullName,
                value(info, "基金类型"),
                value(info, "基金管理人", "管理人"),
                value(info, "基金托管人", "托管人"),
                value(info, "基金经理", "基金经理人"),
                parseDate(inceptionRaw),
                inceptionScale,
                aumAmount,
                aumAsOf,
                parsePercent(value(info, "管理费率")),
                parsePercent(value(info, "托管费率")),
                value(info, "业绩比较基准"),
                value(info, "跟踪标的"),
                tracking == null ? null : tracking.errorPct(),
                tracking == null ? null : tracking.peerPct(),
                tracking == null ? null : tracking.asOf(),
                SOURCE_LABEL);

        if (!hasInfo || !hasTracking) {
            String note = !hasInfo
                    ? "基金档案只取到跟踪误差（基本概况页不可用）"
                    : "基金档案缺少年化跟踪误差（跟踪误差页不可用）";
            if (trackingFailure != null && hasInfo) {
                note = "基金档案缺少年化跟踪误差（" + trackingFailure + "）";
            }
            return Result.partial(profile, note);
        }
        return Result.ok(profile);
    }

    // ------------------------------------------------------------------
    // 传输与解码
    // ------------------------------------------------------------------

    private byte[] get(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", REFERER);
        headers.set("User-Agent", UA);
        ResponseEntity<byte[]> response = restTemplate.exchange(URI.create(url), HttpMethod.GET,
                new HttpEntity<>(headers), byte[].class);
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new IllegalStateException("响应为空");
        }
        return body;
    }

    /**
     * 页面声明 UTF-8，但为了不把「声明写错」变成一个页面上看不出来的乱码问题，
     * 先按 UTF-8 解；出现替换字符时再试 GB18030，取替换字符更少的那个。
     */
    static String decode(byte[] raw) {
        if (raw == null || raw.length == 0) return "";
        String utf8 = new String(raw, StandardCharsets.UTF_8);
        if (utf8.indexOf('�') < 0) {
            return utf8;
        }
        String gb = new String(raw, Charset.forName("GB18030"));
        return countReplacement(gb) < countReplacement(utf8) ? gb : utf8;
    }

    private static int countReplacement(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '�') n++;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // jbgk：<th>标签</th><td>值</td>
    // ------------------------------------------------------------------

    static Map<String, String> parseInfoTable(String html) {
        if (html == null || html.isBlank()) return Map.of();
        Map<String, String> best = Map.of();
        int bestScore = 0;
        for (String table : tables(html)) {
            Map<String, String> pairs = pairs(table);
            int score = 0;
            for (String label : KNOWN_LABELS) {
                if (pairs.containsKey(label)) score++;
            }
            if (score > bestScore) {
                best = pairs;
                bestScore = score;
            }
        }
        return bestScore >= 3 ? best : Map.of();
    }

    /** 一张表 → 标签到值的映射。省略的 {@code </td>} 由「下一个单元格开始」隐式闭合。 */
    private static Map<String, String> pairs(String table) {
        List<Cell> cells = cells(table);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            if (!"th".equals(cell.tag())) continue;
            if (i + 1 >= cells.size()) break;
            Cell next = cells.get(i + 1);
            if (!"td".equals(next.tag())) continue;
            String key = cell.text();
            if (!key.isEmpty() && !next.text().isEmpty()) {
                out.putIfAbsent(key, next.text());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // tsdata：table.fxtb 的跟踪误差行
    // ------------------------------------------------------------------

    static Tracking parseTracking(String html) {
        if (html == null || html.isBlank()) return null;
        for (String table : tables(html)) {
            Tracking tracking = trackingFromTable(table);
            if (tracking != null) {
                LocalDate asOf = asOfAfter(html, table);
                return new Tracking(tracking.errorPct(), tracking.peerPct(), asOf);
            }
        }
        return null;
    }

    private static Tracking trackingFromTable(String table) {
        // 同一个页面还有业绩、规模等 fxtb 表，它们的第二列同样是百分比。
        // 不先按「这张表提到跟踪误差」过滤，就会把某个收益率当成跟踪误差。
        if (!table.contains("跟踪误差")) return null;
        List<List<Cell>> rows = rows(table);
        if (rows.size() < 2) return null;
        List<Cell> header = rows.get(0);
        int errorIndex = indexOfHeader(header, "年化跟踪误差", "跟踪误差");
        int peerIndex = indexOfHeader(header, "同类平均");
        if (errorIndex < 0) {
            // 有的页面把表头放在第一行数据前一行但用 td 渲染；退化为「按列位置」。
            errorIndex = 1;
        }
        for (int r = 1; r < rows.size(); r++) {
            List<Cell> row = rows.get(r);
            if (row.size() <= errorIndex) continue;
            BigDecimal error = parsePercent(row.get(errorIndex).text());
            if (error == null) continue;
            BigDecimal peer = peerIndex >= 0 && row.size() > peerIndex
                    ? parsePercent(row.get(peerIndex).text()) : null;
            return new Tracking(clampPercent(error), clampPercent(peer), null);
        }
        return null;
    }

    private static int indexOfHeader(List<Cell> header, String... needles) {
        for (int i = 0; i < header.size(); i++) {
            String text = header.get(i).text();
            for (String needle : needles) {
                if (text.contains(needle)) return i;
            }
        }
        return -1;
    }

    /** 表后 2000 个字符内的「截止至：YYYY-MM-DD」。 */
    private static LocalDate asOfAfter(String html, String table) {
        int end = html.indexOf(table) + table.length();
        String tail = html.substring(end, Math.min(html.length(), end + 2000));
        int marker = Math.max(tail.indexOf("截止至"), tail.indexOf("截止"));
        String region = marker >= 0 ? tail.substring(marker) : tail;
        return parseDate(region);
    }

    // ------------------------------------------------------------------
    // 极简 HTML 扫描
    // ------------------------------------------------------------------

    private record Cell(String tag, String text) {
    }

    /** 抽出所有 table 块。没有闭合标签时取到文末。 */
    private static List<String> tables(String html) {
        List<String> out = new ArrayList<>();
        String lower = html.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int start = lower.indexOf("<table", from);
            if (start < 0) break;
            int end = lower.indexOf("</table>", start);
            if (end < 0) {
                out.add(html.substring(start));
                break;
            }
            out.add(html.substring(start, end + "</table>".length()));
            from = end + "</table>".length();
        }
        return out;
    }

    private static List<List<Cell>> rows(String table) {
        List<List<Cell>> rows = new ArrayList<>();
        List<Cell> current = new ArrayList<>();
        for (Cell cell : cells(table)) {
            if ("tr".equals(cell.tag())) {
                if (!current.isEmpty()) {
                    rows.add(current);
                    current = new ArrayList<>();
                }
                continue;
            }
            current.add(cell);
        }
        if (!current.isEmpty()) rows.add(current);
        return rows;
    }

    /**
     * 逐个单元格扫描。遇到 {@code th/td} 开始就把上一个单元格收掉——这正好覆盖
     * jbgk 页省略 {@code </td>} 的坏标签。
     */
    private static List<Cell> cells(String html) {
        List<Cell> out = new ArrayList<>();
        int i = 0;
        int n = html.length();
        String openTag = null;
        int openAt = 0;
        while (i < n) {
            int lt = html.indexOf('<', i);
            if (lt < 0) break;
            if (html.startsWith("<!--", lt)) {
                int close = html.indexOf("-->", lt + 4);
                i = close < 0 ? n : close + 3;
                continue;
            }
            int gt = findTagEnd(html, lt);
            if (gt < 0) break;
            String raw = html.substring(lt + 1, gt).trim();
            String lower = raw.toLowerCase(Locale.ROOT);
            boolean closing = lower.startsWith("/");
            String name = tagName(lower);
            if (openTag != null && (("th".equals(name) || "td".equals(name))
                    || ("tr".equals(name)))) {
                String text = clean(html.substring(openAt, lt));
                if (!text.isEmpty()) {
                    out.add(new Cell(openTag, text));
                }
                openTag = null;
            }
            if ("tr".equals(name)) {
                out.add(new Cell("tr", ""));
            } else if (!closing && ("th".equals(name) || "td".equals(name))) {
                openTag = name;
                openAt = gt + 1;
            } else if (closing && openTag != null && openTag.equals(name)) {
                String text = clean(html.substring(openAt, lt));
                if (!text.isEmpty()) {
                    out.add(new Cell(openTag, text));
                }
                openTag = null;
            }
            i = gt + 1;
        }
        if (openTag != null) {
            String text = clean(html.substring(openAt));
            if (!text.isEmpty()) out.add(new Cell(openTag, text));
        }
        return out;
    }

    /** 引号内的 {@code >} 不算标签结束。 */
    private static int findTagEnd(String html, int lt) {
        char quote = 0;
        for (int i = lt + 1; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static String tagName(String lower) {
        int i = lower.startsWith("/") ? 1 : 0;
        int start = i;
        while (i < lower.length()) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                i++;
            } else {
                break;
            }
        }
        return lower.substring(start, i);
    }

    private static String clean(String fragment) {
        String noTags = fragment.replaceAll("(?s)<[^>]*>", " ");
        return decodeEntities(noTags).replace(' ', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    private static String decodeEntities(String text) {
        return text.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&yen;", "¥");
    }

    // ------------------------------------------------------------------
    // 值解析
    // ------------------------------------------------------------------

    private static String value(Map<String, String> info, String... labels) {
        for (String label : labels) {
            String exact = info.get(label);
            if (exact != null && !exact.isBlank()) return exact.trim();
        }
        for (String label : labels) {
            for (Map.Entry<String, String> entry : info.entrySet()) {
                if (entry.getKey().contains(label) && !entry.getValue().isBlank()) {
                    return entry.getValue().trim();
                }
            }
        }
        return null;
    }

    /** 「194.68亿元」「5000万元」「123,456,789 元」→ 元。解析不出返回 null，不补 0。 */
    static BigDecimal parseAmountYuan(String text) {
        if (text == null) return null;
        Matcher m = AMOUNT.matcher(text.replace(",", ""));
        if (!m.find()) return null;
        BigDecimal value = new BigDecimal(m.group(1));
        BigDecimal unit = switch (m.group(2) == null ? "" : m.group(2)) {
            case "亿" -> BigDecimal.valueOf(100_000_000L);
            case "万" -> BigDecimal.valueOf(10_000L);
            default -> BigDecimal.ONE;
        };
        BigDecimal amount = value.multiply(unit);
        return amount.signum() > 0 ? amount : null;
    }

    static BigDecimal parsePercent(String text) {
        if (text == null) return null;
        Matcher m = PERCENT.matcher(text);
        return m.find() ? new BigDecimal(m.group(1)) : null;
    }

    private static BigDecimal clampPercent(BigDecimal value) {
        if (value == null || value.signum() < 0) return null;
        return value.compareTo(BigDecimal.valueOf(100)) > 0 ? null : value;
    }

    static LocalDate parseDate(String text) {
        if (text == null) return null;
        Matcher dash = DATE_DASH.matcher(text);
        if (dash.find()) {
            return safeDate(dash.group(1), dash.group(2), dash.group(3));
        }
        Matcher cn = DATE_CN.matcher(text);
        if (cn.find()) {
            return safeDate(cn.group(1), cn.group(2), cn.group(3));
        }
        return null;
    }

    private static LocalDate safeDate(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month),
                    Integer.parseInt(day));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String shortReason(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 80 ? message.substring(0, 80) : message;
    }
}
