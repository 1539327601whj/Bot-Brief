package com.ai.daily.service;

import com.ai.daily.entity.PushChannel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelIdsTest {

    @Test
    void coercesIntegerAndStringIds() {
        assertThat(ChannelIds.coerceAll(Arrays.asList(11, 12L, "13", 0, -2, "x")))
                .containsExactly(11L, 12L, 13L);
        assertThat(ChannelIds.coerceAll(Arrays.asList(11, null, "13")))
                .containsExactly(11L, 13L);
        assertThat(ChannelIds.same(11L, ChannelIds.coerce(11))).isTrue();
        assertThat(ChannelIds.same(11L, 12L)).isFalse();
    }

    @Test
    void findsChannelByNumericEquality() {
        PushChannel channel = new PushChannel();
        channel.setId(11L);
        assertThat(ChannelIds.find(List.of(channel), 11L)).isSameAs(channel);
        assertThat(ChannelIds.find(List.of(channel), 12L)).isNull();
    }
}
