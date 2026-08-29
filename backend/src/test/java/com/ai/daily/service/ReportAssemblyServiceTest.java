package com.ai.daily.service;

import com.ai.daily.entity.Report;
import com.ai.daily.entity.TopicSection;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportAssemblyServiceTest {

    @Test
    void persistsAssembledReportFromMatchingSectionsInTopicOrder() {
        TopicSectionService sections = mock(TopicSectionService.class);
        ReportService reports = mock(ReportService.class);
        ReportAssemblyService service = new ReportAssemblyService(sections, reports);
        LocalDate date = LocalDate.of(2026, 8, 28);
        TopicSection database = section("数据库", "## 数据库\n\nPostgreSQL 发布更新。");
        TopicSection security = section("安全", "## 安全\n\n披露新的鉴权漏洞。");
        when(reports.getByUserEditionDate(7L, "morning", date)).thenReturn(null);
        when(sections.findFor(date, "morning", List.of("安全", "数据库"))).thenReturn(List.of(security, database));
        Report saved = new Report();
        saved.setId(99L);
        when(reports.saveUserReport(eq(7L), eq(date), eq("morning"), any(), any(), any())).thenReturn(saved);

        Report result = service.assembleAndPersist(7L, "morning", date, List.of("安全", "数据库"));

        assertThat(result).isSameAs(saved);
        verify(reports).saveUserReport(
                eq(7L),
                eq(date),
                eq("morning"),
                eq("【早间版】我的简报 2026-08-28"),
                eq("# 🎯 我的早间版 · 2026-08-28\n\n---\n\n## 安全\n\n披露新的鉴权漏洞。\n\n## 数据库\n\nPostgreSQL 发布更新。\n"),
                any());
    }

    @Test
    void returnsExistingUserReportWithoutReassembling() {
        TopicSectionService sections = mock(TopicSectionService.class);
        ReportService reports = mock(ReportService.class);
        ReportAssemblyService service = new ReportAssemblyService(sections, reports);
        LocalDate date = LocalDate.of(2026, 8, 28);
        Report existing = new Report();
        existing.setId(3L);
        existing.setContent("已经发出的内容");
        when(reports.getByUserEditionDate(7L, "evening", date)).thenReturn(existing);

        assertThat(service.assembleAndPersist(7L, "evening", date, List.of("安全"))).isSameAs(existing);
        verify(sections, never()).findFor(any(), any(), any());
        verify(reports, never()).saveUserReport(any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotAssembleForWebUntilPublicReportMarksGenerationComplete() {
        TopicSectionService sections = mock(TopicSectionService.class);
        ReportService reports = mock(ReportService.class);
        ReportAssemblyService service = new ReportAssemblyService(sections, reports);
        LocalDate date = LocalDate.of(2026, 8, 28);
        when(reports.getByUserEditionDate(7L, "morning", date)).thenReturn(null);
        when(reports.publicReportExists("morning", date)).thenReturn(false);

        assertThat(service.assembleForWebIfReady(7L, "morning", date, List.of("安全"))).isNull();
        verify(sections, never()).findFor(any(), any(), any());
    }

    @Test
    void returnsNullWhenNoSectionsMatchSoUsersDoNotGetSharedReport() {
        TopicSectionService sections = mock(TopicSectionService.class);
        ReportService reports = mock(ReportService.class);
        ReportAssemblyService service = new ReportAssemblyService(sections, reports);
        LocalDate date = LocalDate.of(2026, 8, 28);
        when(reports.getByUserEditionDate(7L, "morning", date)).thenReturn(null);
        when(sections.findFor(date, "morning", List.of("区块链"))).thenReturn(List.of());

        assertThat(service.assembleAndPersist(7L, "morning", date, List.of("区块链"))).isNull();
        verify(reports, never()).saveUserReport(any(), any(), any(), any(), any(), any());
    }

    private TopicSection section(String topic, String content) {
        TopicSection section = new TopicSection();
        section.setTopicKey(topic);
        section.setContent(content);
        return section;
    }
}
