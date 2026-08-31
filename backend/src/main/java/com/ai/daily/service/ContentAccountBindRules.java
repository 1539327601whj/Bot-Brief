package com.ai.daily.service;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 手工登记账号时的主页链接规则。空链接允许；填写了就必须是对应平台的 http(s) 地址。 */
public final class ContentAccountBindRules {

    private static final Map<String, List<String>> PLATFORM_HOSTS = Map.of(
            "douyin", List.of("douyin.com", "iesdouyin.com"),
            "xiaohongshu", List.of("xiaohongshu.com", "xhslink.com"),
            "kuaishou", List.of("kuaishou.com"),
            "bilibili", List.of("bilibili.com", "b23.tv")
    );

    private ContentAccountBindRules() {}

    public static String validateHomepage(String platform, String homepageUrl) {
        if (homepageUrl == null || homepageUrl.isBlank()) {
            return null;
        }
        String trimmed = homepageUrl.trim();
        if (trimmed.length() > 1000) {
            return "链接不能超过 1000 个字符";
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            return "主页链接无效";
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return "主页链接需要以 http:// 或 https:// 开头";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "主页链接无效";
        }
        String key = platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
        List<String> allowed = PLATFORM_HOSTS.get(key);
        if (allowed != null && allowed.stream().noneMatch(suffix -> hostMatches(host, suffix))) {
            return "主页链接要对应所选平台";
        }
        return null;
    }

    private static boolean hostMatches(String host, String suffix) {
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals(suffix) || value.endsWith("." + suffix);
    }
}
