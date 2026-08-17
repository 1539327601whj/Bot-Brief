package com.ai.daily.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportServiceImplTest {

    @Test
    void rejectsEmptyAndDecorativeMarkdownShells() {
        assertThat(ReportServiceImpl.hasSubstantiveContent("")).isFalse();
        assertThat(ReportServiceImpl.hasSubstantiveContent("   ")).isFalse();
        assertThat(ReportServiceImpl.hasSubstantiveContent("""
                # AI 每日高价值简报 · 2026-08-17（早间版）

                ---
                """)).isFalse();
        assertThat(ReportServiceImpl.hasSubstantiveContent("""
                > **ETF 行情日报 · 2026-08-17（晚间版）**

                ---
                """)).isFalse();
    }

    @Test
    void acceptsReportWithSubstantiveBody() {
        assertThat(ReportServiceImpl.hasSubstantiveContent("""
                # AI 每日高价值简报

                ---

                ## 1. 模型更新

                新版本增加了更稳定的工具调用能力。
                """)).isTrue();
    }
}
