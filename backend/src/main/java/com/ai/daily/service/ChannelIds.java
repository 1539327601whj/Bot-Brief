package com.ai.daily.service;

import com.ai.daily.entity.PushChannel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ChannelIds {

    private ChannelIds() {
    }

    public static Long coerce(Object value) {
        if (value instanceof Long number) return number;
        if (value instanceof Integer number) return number.longValue();
        if (value instanceof Short number) return number.longValue();
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) return null;
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static List<Long> coerceAll(Collection<?> values) {
        if (values == null || values.isEmpty()) return List.of();
        Set<Long> unique = new LinkedHashSet<>();
        for (Object value : values) {
            Long id = coerce(value);
            if (id != null && id > 0) unique.add(id);
        }
        return new ArrayList<>(unique);
    }

    public static boolean same(Long left, Long right) {
        return left != null && right != null && left.longValue() == right.longValue();
    }

    public static PushChannel find(Collection<PushChannel> channels, Long channelId) {
        if (channels == null || channelId == null) return null;
        for (PushChannel channel : channels) {
            if (channel != null && same(channel.getId(), channelId)) return channel;
        }
        return null;
    }
}
