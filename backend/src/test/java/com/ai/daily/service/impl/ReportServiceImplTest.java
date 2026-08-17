package com.ai.daily.service.impl;

import com.ai.daily.mapper.ReportMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void skipsDuplicateRunBeforeInsert() {
        ReportMapper mapper = mock(ReportMapper.class);
        when(mapper.findIdByIngestKey("morning:run-1")).thenReturn(42L);
        ReportServiceImpl service = new ReportServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        service.saveReport(
                "morning", "测试早报", "## 要点\n\n这是有效正文。", "摘要", "run-1"
        );

        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }
}
