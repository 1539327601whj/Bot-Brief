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
    void feishuColorsSectionAndItemHeadings() {
        String markdown = PushReportFormat.feishuMarkdown("马斯克日报 · 15:10 · 2026-09-03", CONTENT);

        assertThat(markdown).contains("<font color=\"blue\">**▎ 马斯克日报**</font>");
        assertThat(markdown).contains("<font color=\"orange\">**1. SpaceX 星舰试飞**</font>");
        assertThat(markdown).contains("<text_tag color='violet'>发生了什么</text_tag>");
        assertThat(markdown).doesNotContain("# 马斯克日报");
    }

    @Test
    void feishuColorsUpRedAndDownGreen() {
        String markdown = PushReportFormat.feishuMarkdown(
                "ETF 行情日报 · 2026-09-03（晚间）",
                """
                ## ETF变化

                ### 沪深300ETF
                - 今 4.100｜昨 4.000 ↑ +2.50%｜周 3.900 ↓ -1.20%｜月 3.800 0.00%
                """);

        assertThat(markdown).contains("<font color=\"blue\">**▎ ETF变化**</font>");
        assertThat(markdown).contains("<font color=\"orange\">**沪深300ETF**</font>");
        assertThat(markdown).contains("<font color=\"red\">↑ +2.50%</font>");
        assertThat(markdown).contains("<font color=\"green\">↓ -1.20%</font>");
        assertThat(markdown).contains("<font color=\"grey\">0.00%</font>");
        assertThat(markdown).doesNotContain("<font color=\"red\">4.100");
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
    void feishuUsesIndigoForTopLevelTitle() {
        String markdown = PushReportFormat.feishuMarkdown(
                "其他标题",
                "# 区块链日报 · 14:35 · 2026-09-07\n\n## 区块链日报\n");

        assertThat(markdown).contains("<font color=\"indigo\">**▎ 区块链日报 · 14:35 · 2026-09-07**</font>");
        assertThat(markdown).contains("<hr>");
        assertThat(markdown).contains("<font color=\"blue\">**▎ 区块链日报**</font>");
    }

    @Test
    void feishuHeaderFollowsEditionTone() {
        assertThat(PushReportFormat.feishuHeaderTemplate("morning")).isEqualTo("orange");
        assertThat(PushReportFormat.feishuHeaderTemplate("market_watch_evening")).isEqualTo("turquoise");
        assertThat(PushReportFormat.feishuHeaderTemplate("personal")).isEqualTo("blue");
    }

    @Test
    void wecomSkipsBodyLeadTitleWhenPushTitleExists() {
        String markdown = PushReportFormat.wecomMarkdown(
                "【ETF市场数据简报晚间版】沪深300ETF / 纳指100ETF / 标普500ETF 2026-09-08",
                """
                > **ETF 行情日报 · 2026-09-08（晚间版）**

                ## 先看结论

                - 沪深300ETF：按计划买｜4.656｜PE 14.20｜分位 82
                """);

        assertThat(markdown).contains("先看结论");
        assertThat(markdown).doesNotContain("ETF 行情日报 · 2026-09-08");
    }

    @Test
    void wecomKeepsLookbacksAndFitsCompactAShareInOneMessage() {
        String title = "【ETF市场数据简报晚间版】沪深300ETF / 纳指100ETF / 标普500ETF 2026-09-08";
        String light = PushReportFormat.wecomMarkdown(title, PRODUCTION_LIKE_ETF_REPORT);
        int lightBytes = light.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String markdown = PushReportFormat.wecomMarkdown(title, PRODUCTION_LIKE_ETF_REPORT, 4096);

        assertThat(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(4096);
        assertThat(markdown).doesNotEndWith("...");
        assertThat(markdown).contains("周 4.627");
        assertThat(markdown).contains("+0.63%");
        assertThat(markdown).contains("一年 4.188");
        assertThat(markdown).contains("+11.17%");
        assertThat(markdown).contains("三年 3.674");
        assertThat(markdown).contains("+26.73%");
        assertThat(markdown).contains("中信证券");
        assertThat(markdown).contains("紫金矿业");
        assertThat(markdown).contains("PE 18.5");
        assertThat(markdown).contains("趋势：");
        assertThat(markdown).doesNotContain("需观察后续量能");
        assertThat(markdown).doesNotContain("ETF 行情日报 · 2026-09-08");
        assertThat(lightBytes).isLessThanOrEqualTo(4096);
    }

    private static final String PRODUCTION_LIKE_ETF_REPORT = """
            > **ETF 行情日报 · 2026-09-08（晚间版）**

            ## 先看结论
            - 沪深300ETF：按计划买｜4.656｜PE 14.20｜分位 82
            - 纳指100ETF：少买｜1.615｜PE 34.47｜分位 86，溢价偏高
            - 标普500ETF：少买｜1.642｜PE 27.99｜分位 87，溢价偏高

            ## ETF变化
            ### 沪深300ETF
            - 今 4.656｜昨 4.623 ↑ +0.71%｜周 4.627 ↑ +0.63%｜月 4.479 ↑ +3.95%｜半年 3.946 ↑ +18.00%｜一年 4.188 ↑ +11.17%｜三年 3.674 ↑ +26.73%
            ### 纳指100ETF
            - 今 1.615｜昨 1.608 ↑ +0.44%｜周 1.622 ↓ -0.43%｜月 1.590 ↑ +1.57%｜半年 1.420 ↑ +13.73%｜一年 1.380 ↑ +17.03%｜三年 1.050 ↑ +53.81%
            ### 标普500ETF
            - 今 1.642｜昨 1.635 ↑ +0.43%｜周 1.650 ↓ -0.48%｜月 1.610 ↑ +1.99%｜半年 1.480 ↑ +10.95%｜一年 1.430 ↑ +14.83%｜三年 1.120 ↑ +46.61%

            ## PE分位变化
            ### 沪深300ETF
            - 今 82｜昨 80 ↑ +2.00%｜周 78 ↑ +4.00%｜月 70 ↑ +12.00%｜半年 55 ↑ +27.00%｜一年 48 ↑ +34.00%｜三年 30 ↑ +52.00%
            ### 纳指100ETF
            - 今 86｜昨 85 ↑ +1.00%｜周 84 ↑ +2.00%｜月 80 ↑ +6.00%｜半年 72 ↑ +14.00%｜一年 65 ↑ +21.00%｜三年 40 ↑ +46.00%
            ### 标普500ETF
            - 今 87｜昨 86 ↑ +1.00%｜周 85 ↑ +2.00%｜月 81 ↑ +6.00%｜半年 74 ↑ +13.00%｜一年 68 ↑ +19.00%｜三年 42 ↑ +45.00%

            ## 溢价变化
            ### 纳指100ETF
            - 今 0.85%｜昨 0.90% ↓ -0.05%｜周 0.70% ↑ +0.15%｜月 0.60% ↑ +0.25%｜半年 0.40% ↑ +0.45%｜一年 0.30% ↑ +0.55%｜三年 0.10% ↑ +0.75%
            ### 标普500ETF
            - 今 0.72%｜昨 0.80% ↓ -0.08%｜周 0.65% ↑ +0.07%｜月 0.50% ↑ +0.22%｜半年 0.35% ↑ +0.37%｜一年 0.25% ↑ +0.47%｜三年 0.08% ↑ +0.64%

            ## A股观察候选
            - **中信证券（600030）**：成交额 33.4 亿元；动态PE 13.2、PB 1.2。趋势：短期更可能维持震荡；需观察后续量能和当日高低点突破方向。风险：机械筛选未覆盖基本面、公告和行业事件。
            - **紫金矿业（601899）**：成交额 84.6 亿元；动态PE 18.5、PB 3.1；主力净流入 12000 万元。趋势：量价与中期方向偏强；若后续成交额维持且不跌破当日低点，强势可能延续。风险：机械筛选未覆盖基本面、公告和行业事件。
            """;
}
