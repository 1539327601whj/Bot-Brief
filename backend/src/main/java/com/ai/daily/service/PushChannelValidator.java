package com.ai.daily.service;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class PushChannelValidator {

    private static final Set<String> TYPES = Set.of("email", "wechat", "dingtalk", "feishu");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final int EMAIL_MAX_LENGTH = 254;
    private static final int WEBHOOK_MAX_LENGTH = 1500;
    private static final int SECRET_MAX_LENGTH = 500;

    public void validate(String type, String target) {
        if (type == null || !TYPES.contains(type)) {
            throw new IllegalArgumentException("渠道类型必须是 email/wechat/dingtalk/feishu");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("推送目标不能为空");
        }
        if ("email".equals(type)) {
            if (target.length() > EMAIL_MAX_LENGTH) throw new IllegalArgumentException("邮箱地址格式无效");
            validateEmail(target);
        } else {
            if (target.length() > WEBHOOK_MAX_LENGTH) throw new IllegalArgumentException("Webhook 地址过长");
            validateWebhook(type, target);
        }
    }

    public void validateSecret(String secret) {
        if (secret != null && secret.trim().length() > SECRET_MAX_LENGTH) {
            throw new IllegalArgumentException("签名密钥过长");
        }
    }

    public void validateForSend(com.ai.daily.entity.PushChannel channel) {
        if (channel == null) throw new IllegalArgumentException("推送渠道不存在");
        validate(channel.getChannelType(), channel.getTarget());
    }

    public String normalizeType(String type) {
        return type == null ? null : type.trim().toLowerCase(Locale.ROOT);
    }

    private void validateEmail(String target) {
        if (!target.equals(target.trim()) || target.indexOf(',') >= 0 || target.indexOf(';') >= 0
                || target.indexOf('\r') >= 0 || target.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("邮箱地址格式无效");
        }
        try {
            InternetAddress address = new InternetAddress(target, true);
            address.validate();
            if (!target.equals(address.getAddress()) || address.getPersonal() != null || !target.contains("@")) {
                throw new IllegalArgumentException("邮箱地址格式无效");
            }
        } catch (AddressException e) {
            throw new IllegalArgumentException("邮箱地址格式无效");
        }
    }

    private void validateWebhook(String type, String target) {
        final URI uri;
        try {
            uri = new URI(target);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Webhook 地址格式无效");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() != -1
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Webhook 必须使用官方 HTTPS 地址");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath();
        String query = uri.getRawQuery();
        boolean valid = switch (type) {
            case "wechat" -> host.equals("qyapi.weixin.qq.com")
                    && path.equals("/cgi-bin/webhook/send")
                    && singleQueryToken(query, "key");
            case "feishu" -> (host.equals("open.feishu.cn") || host.equals("open.larksuite.com"))
                    && query == null
                    && path != null
                    && path.startsWith("/open-apis/bot/v2/hook/")
                    && TOKEN.matcher(path.substring("/open-apis/bot/v2/hook/".length())).matches();
            case "dingtalk" -> host.equals("oapi.dingtalk.com")
                    && path.equals("/robot/send")
                    && singleQueryToken(query, "access_token");
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Webhook 地址不是受支持的官方机器人地址");
    }

    private boolean singleQueryToken(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.indexOf('&') >= 0 || rawQuery.indexOf(';') >= 0) return false;
        String prefix = name + "=";
        return rawQuery.startsWith(prefix)
                && rawQuery.length() > prefix.length()
                && TOKEN.matcher(rawQuery.substring(prefix.length())).matches();
    }
}
