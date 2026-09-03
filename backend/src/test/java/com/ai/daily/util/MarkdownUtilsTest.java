package com.ai.daily.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownUtilsTest {

    @Test
    void convertsCommonMarkdownToSimpleHtml() {
        String html = MarkdownUtils.toSimpleHtml("## 区块链\n\n**要点：** 今天有新协议发布。\n\n- 第一条\n- 第二条");
        assertThat(html).contains("<h2").contains("区块链");
        assertThat(html).contains("<strong").contains("要点：");
        assertThat(html).contains("<li").contains("第一条");
        assertThat(html).doesNotContain("<pre>");
    }

    @Test
    void convertsMarkdownLinksToClickableHtml() {
        String html = MarkdownUtils.toSimpleHtml("### 今日来源\n\n1. [机器之心 · 大模型发布](https://www.jiqizhixin.com/a)");
        assertThat(html).contains("<a href=\"https://www.jiqizhixin.com/a\" target=\"_blank\" rel=\"noopener noreferrer\">机器之心 · 大模型发布</a>");
        assertThat(MarkdownUtils.stripToPlainText("[标题](https://example.com/a)", 0)).isEqualTo("标题");
    }
}
