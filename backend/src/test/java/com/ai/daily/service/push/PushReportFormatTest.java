package com.ai.daily.service.push;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PushReportFormatTest {

    private static final String CONTENT = """
            # 马斯克日报 · 15:10 · 2026-09-03

            ---

            ## 马斯克日报

            ### 1. SpaceX 星舰试飞

            **发生了什么：** 今天进行了新一次试飞。
            """;

    @Test
    void wecomUsesColoredTitleAndSectionHeadings() {
        String markdown = PushReportFormat.wecomMarkdown("马斯克日报 · 15:10 · 2026-09-03", CONTENT);

        assertThat(markdown).startsWith("<font color=\"warning\">**马斯克日报 · 15:10 · 2026-09-03**</font>");
        assertThat(markdown).contains("> <font color=\"warning\">**马斯克日报**</font>");
        assertThat(markdown).contains("<font color=\"info\">**1. SpaceX 星舰试飞**</font>");
        assertThat(markdown).contains("**发生了什么：** 今天进行了新一次试飞。");
        assertThat(markdown).doesNotContain("# 马斯克日报");
    }

    @Test
    void dingtalkUsesHeadingLevels() {
        String markdown = PushReportFormat.dingtalkMarkdown("马斯克日报 · 15:10 · 2026-09-03", CONTENT);

        assertThat(markdown).startsWith("# 马斯克日报 · 15:10 · 2026-09-03");
        assertThat(markdown).contains("## 马斯克日报");
        assertThat(markdown).contains("### 1. SpaceX 星舰试飞");
    }

    @Test
    void feishuTurnsHeadingsIntoBoldMarkers() {
        String markdown = PushReportFormat.feishuMarkdown("马斯克日报 · 15:10 · 2026-09-03", CONTENT);

        assertThat(markdown).contains("**▎ 马斯克日报**");
        assertThat(markdown).contains("**· 1. SpaceX 星舰试飞**");
        assertThat(markdown).doesNotContain("# 马斯克日报");
    }

    @Test
    void stripsDuplicateLeadTitleFromBody() {
        String body = PushReportFormat.bodyWithoutLeadTitle(
                "ETF 行情日报 · 2026-09-03（晚间）",
                "> **ETF 行情日报 · 2026-09-03（晚间）**\n\n## 先看结论\n\n今天先看美股。");

        assertThat(body).startsWith("## 先看结论");
        assertThat(body).doesNotContain("ETF 行情日报 · 2026-09-03（晚间）");
    }

    @Test
    void wecomColorsUpRedAndDownGreen() {
        String markdown = PushReportFormat.wecomMarkdown(
                "ETF 行情日报 · 2026-09-03（晚间）",
                """
                ## ETF变化

                ### 沪深300ETF
                - 今 4.100｜昨 4.000 ↑ +2.50%｜周 3.900 ↓ -1.20%｜月 3.800 0.00%
                """);

        assertThat(markdown).contains("<font color=\"warning\">↑ +2.50%</font>");
        assertThat(markdown).contains("<font color=\"info\">↓ -1.20%</font>");
        assertThat(markdown).contains("<font color=\"comment\">0.00%</font>");
        assertThat(markdown).doesNotContain("<font color=\"warning\">4.100");
    }

    @Test
    void wecomDropsRefreshMarkerAndResearchDisclaimer() {
        String markdown = PushReportFormat.wecomMarkdown(
                "ETF 行情日报 · 2026-09-03（晚间）",
                """
                ## 先看结论

                - 按计划买
                - 候选基于公开量价与估值机械筛选，仅作研究线索，不代表推荐或确定性预测。
                <!-- ETF_DATA_REFRESH:IOPV -->
                """);

        assertThat(markdown).contains("按计划买");
        assertThat(markdown).doesNotContain("ETF_DATA_REFRESH");
        assertThat(markdown).doesNotContain("仅作研究线索");
        assertThat(markdown).doesNotContain("候选基于公开量价");
    }

    @Test
    void feishuHeaderFollowsEditionTone() {
        assertThat(PushReportFormat.feishuHeaderTemplate("morning")).isEqualTo("orange");
        assertThat(PushReportFormat.feishuHeaderTemplate("market_watch_evening")).isEqualTo("turquoise");
        assertThat(PushReportFormat.feishuHeaderTemplate("personal")).isEqualTo("blue");
    }
}
