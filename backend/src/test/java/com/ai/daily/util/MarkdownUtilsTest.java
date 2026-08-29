package com.ai.daily.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownUtilsTest {

    @Test
    void convertsCommonMarkdownToSimpleHtml() {
        String html = MarkdownUtils.toSimpleHtml("## 区块链\n\n**要点：** 今天有新协议发布。\n\n- 第一条\n- 第二条");
        assertThat(html).contains("<h2>区块链</h2>");
        assertThat(html).contains("<strong>要点：</strong>");
        assertThat(html).contains("<li>第一条</li>");
        assertThat(html).doesNotContain("<pre>");
    }
}
