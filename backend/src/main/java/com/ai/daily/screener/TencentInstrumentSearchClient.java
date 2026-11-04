package com.ai.daily.screener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 腾讯 smartbox 标的搜索：名称、拼音、代码都能匹配，覆盖沪深 A 股、ETF、指数。
 *
 * <p>为什么不用东财 suggest：实测同一 IP 连续请求会很快返回「未查询到数据」，
 * 而且它和行情接口属于同一封禁面。搜索是用户每敲几个字就可能触发一次的场景，
 * 不能把一个会按 IP 限流的源放在输入框后面。腾讯这个接口在本机实测稳定，
 * 也支持拼音与简称。
 *
 * <p><b>仍然按「免费源只碰一次」设计</b>：
 * <ul>
 *   <li>结果按关键词缓存 10 分钟（空结果 60 秒），同一输入不重复外呼；</li>
 *   <li>两次外呼之间至少间隔 1.2 秒，间隔内的请求只返回缓存；</li>
 *   <li>一旦被限流，冷却 120 秒，期间直接返回空，<b>不重试</b>；</li>
 *   <li>任何失败都吞掉并返回空列表——本地库结果仍然可用。</li>
 * </ul>
 *
 * <p>只返回能被现有 {@code InstrumentResolver} 正确解析的类型：A 股、ETF、指数。
 * 港股、LOF、场外基金先不返回，避免选中后落到另一条分析链上。
 */
@Slf4j
@Component
public class TencentInstrumentSearchClient {

    static final String HOST = "https://smartbox.gtimg.cn";
    static final String REFERER = "https://stockapp.finance.qq.com/";
    static final String PATH = "/s3/";

    static final long MIN_INTERVAL_MS = 1_200;
    static final long CACHE_TTL_MS = 10 * 60_000L;
    static final long EMPTY_TTL_MS = 60_000L;
    static final long FAILURE_TTL_MS = 30_000L;
    static final long COOLDOWN_MS = 120_000L;
    static final int CACHE_MAX = 256;

    private final RestTemplate restTemplate;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicLong lastCallAt = new AtomicLong(0);
    private volatile long cooldownUntil = 0;

    public TencentInstrumentSearchClient(
            @Qualifier("marketRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public record Hint(String market, String code, String name, String pinyin, String type) {
    }

    private record Cached(long expiresAt, List<Hint> hints) {
    }

    /** 失败、限流、被间隔挡下都返回空；调用方永远不需要处理异常。 */
    public List<Hint> search(String keyword, int limit) {
        String q = keyword == null ? "" : keyword.strip();
        if (q.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<Hint> cached = cached(q, now);
        if (cached != null) {
            return trim(cached, limit);
        }
        if (now < cooldownUntil) {
            return List.of();
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            cached = cached(q, now);
            if (cached != null) {
                return trim(cached, limit);
            }
            if (now < cooldownUntil || now - lastCallAt.get() < MIN_INTERVAL_MS) {
                return List.of();
            }
            lastCallAt.set(now);
            try {
                List<Hint> hints = parse(fetch(q));
                remember(q, hints, now + (hints.isEmpty() ? EMPTY_TTL_MS : CACHE_TTL_MS));
                return trim(hints, limit);
            } catch (MarketDataException.MarketDataRateLimitedException e) {
                cooldownUntil = now + COOLDOWN_MS;
                log.warn("标的搜索被限流，{} 秒内不再外呼 keyword={}", COOLDOWN_MS / 1000, q);
                return List.of();
            } catch (RuntimeException e) {
                log.warn("标的搜索不可达 keyword={}：{}", q, MarketDataClient.shortReason(e));
                remember(q, List.of(), now + FAILURE_TTL_MS);
                return List.of();
            }
        }
    }

    private String fetch(String keyword) {
        String url = HOST + PATH + "?v=2&t=all&q=" + encode(keyword);
        return MarketDataClient.fetchBody(restTemplate, URI.create(url), REFERER);
    }

    static String encode(String keyword) {
        return URLEncoder.encode(keyword, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private List<Hint> cached(String q, long now) {
        Cached hit = cache.get(q);
        if (hit == null) {
            return null;
        }
        if (hit.expiresAt() <= now) {
            cache.remove(q, hit);
            return null;
        }
        return hit.hints();
    }

    private void remember(String q, List<Hint> hints, long expiresAt) {
        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        cache.put(q, new Cached(expiresAt, List.copyOf(hints)));
    }

    private static List<Hint> trim(List<Hint> hints, int limit) {
        int safe = Math.max(1, Math.min(limit, 20));
        return hints.size() <= safe ? hints : hints.subList(0, safe);
    }

    /**
     * 响应形如 {@code v_hint="sh~600519~贵州茅台~gzmt~GP-A^..."}。
     * 空结果或提示为 {@code N} 时返回空列表。
     */
    static List<Hint> parse(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start) {
            return List.of();
        }
        String payload = body.substring(start + 1, end).strip();
        if (payload.isEmpty() || "N".equalsIgnoreCase(payload)) {
            return List.of();
        }
        Map<String, Hint> unique = new LinkedHashMap<>();
        for (String item : payload.split("\\^")) {
            String[] fields = item.split("~", -1);
            if (fields.length < 5) {
                continue;
            }
            String market = fields[0].strip().toUpperCase(Locale.ROOT);
            String code = fields[1].strip();
            String name = unescape(fields[2]).strip();
            String pinyin = fields[3].strip();
            String type = fields[4].strip().toUpperCase(Locale.ROOT);
            if (code.isEmpty() || name.isEmpty()) {
                continue;
            }
            Hint hint = new Hint(market, code, name, pinyin, type);
            unique.putIfAbsent(market + ":" + code + ":" + type, hint);
        }
        return List.copyOf(unique.values());
    }

    /** 只处理 Unicode 转义（反斜杠 + u + 四位十六进制）；该接口把中文按这个形式返回。 */
    static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 5 < value.length() && value.charAt(i + 1) == 'u') {
                try {
                    int codePoint = Integer.parseInt(value.substring(i + 2, i + 6), 16);
                    out.append((char) codePoint);
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) {
                    // 不是合法的 Unicode 转义，按原字符输出。
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** A 股、ETF、指数之外的类型（港股、LOF、场外基金、REIT 等）暂不接收。 */
    static boolean supported(String type) {
        // 科创板是 GP-A-KCB；创业板目前仍标 GP-A。用前缀兼容以后新增的板块后缀。
        return type != null && (type.startsWith("GP-A") || "ETF".equals(type) || "ZS".equals(type));
    }

    /** 指数要带交易所前缀提交，才能和个股消歧（000001 既是上证指数也是平安银行）。 */
    public static String submitCode(Hint hint) {
        if ("ZS".equals(hint.type())) {
            return hint.market().toUpperCase(Locale.ROOT) + hint.code();
        }
        return hint.code();
    }

    public static List<Hint> supported(List<Hint> hints) {
        List<Hint> out = new ArrayList<>();
        for (Hint hint : hints) {
            if (supported(hint.type()) && !hint.market().isBlank()) {
                out.add(hint);
            }
        }
        return out;
    }
}
