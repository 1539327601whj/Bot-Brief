package com.ai.daily.service;

import com.ai.daily.entity.Report;
import com.ai.daily.entity.TopicSection;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
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
        LocalTime time = LocalTime.of(8, 15);
        TopicSection database = section("数据库", "## 数据库\n\nPostgreSQL 发布更新。");
        TopicSection security = section("安全", "## 安全\n\n披露新的鉴权漏洞。");
        when(reports.getByUserEditionDateAndTime(7L, Report.PERSONAL, date, time)).thenReturn(null);
        when(sections.findFor(date, ReportWindows.W06_12, List.of("安全", "数据库"))).thenReturn(List.of(security, database));
        Report saved = new Report();
        saved.setId(99L);
        when(reports.saveUserReport(eq(7L), eq(date), eq(time), any(), any(), any())).thenReturn(saved);

        Report result = service.assembleAndPersist(7L, date, time, List.of("安全", "数据库"));

        assertThat(result).isSameAs(saved);
        verify(reports).saveUserReport(
                eq(7L),
                eq(date),
                eq(time),
                eq("【08:15】我的简报 2026-08-28"),
                eq("# 🎯 我的简报 08:15 · 2026-08-28\n\n---\n\n## 安全\n\n披露新的鉴权漏洞。\n\n## 数据库\n\nPostgreSQL 发布更新。\n"),
                any());
    }

    @Test
    void returnsExistingUserReportWithoutReassembling() {
        TopicSectionService sections = mock(TopicSectionService.class);
        ReportService reports = mock(ReportService.class);
        ReportAssemblyService service = new ReportAssemblyService(sections, reports);
        LocalDate date = LocalDate.of(2026, 8, 28);
        LocalTime time = LocalTime.of(20, 15);
        Report existing = new Report();
        existing.setId(3L);
        existing.setContent("已经发出的内容");
        when(reports.getByUserEditionDateAndTime(7L, Report.PERSONAL, date, time)).thenReturn(existing);

        assertThat(service.assembleAndPersist(7L, date, time, List.of("安全"))).isSameAs(existing);
        verify(sections, never()).findFor(any(), any(), any());
        verify(reports, never()).saveUserReport(any(), any(), any(), any(), any(), any());
    }

    @Test
    void returnsNullWhenNoSectionsMatchSoUsersDoNotGetSharedReport() {
        TopicSectionService sections = mock(TopicSectionService.class);
        ReportService reports = mock(ReportService.class);
        ReportAssemblyService service = new ReportAssemblyService(sections, reports);
        LocalDate date = LocalDate.of(2026, 8, 28);
        LocalTime time = LocalTime.of(8, 15);
        when(reports.getByUserEditionDateAndTime(7L, Report.PERSONAL, date, time)).thenReturn(null);
        when(sections.findFor(date, ReportWindows.W06_12, List.of("区块链"))).thenReturn(List.of());

        assertThat(service.assembleAndPersist(7L, date, time, List.of("区块链"))).isNull();
        verify(reports, never()).saveUserReport(any(), any(), any(), any(), any(), any());
    }

    @Test
    void techDigestUsesPublicMorningReportInsteadOfTopicSection() {
        TopicSectionService sections = mock(TopicSectionService.class);
        ReportService reports = mock(ReportService.class);
        ReportAssemblyService service = new ReportAssemblyService(sections, reports);
        LocalDate date = LocalDate.of(2026, 8, 31);
        LocalTime time = LocalTime.of(8, 0);
        Report digest = new Report();
        digest.setTitle("【早间版】AI 每日简报 2026-08-31");
        digest.setContent("# 🤖 AI 每日高价值简报\n\n正文");
        when(reports.getByUserEditionDateAndTime(7L, Report.PERSONAL, date, time)).thenReturn(null);
        when(reports.getLatestByEditionForDate("morning", date)).thenReturn(digest);
        Report saved = new Report();
        saved.setId(12L);
        when(reports.saveUserReport(eq(7L), eq(date), eq(time), any(), any(), any())).thenReturn(saved);

        Report result = service.assembleAndPersist(7L, date, time, List.of("科技"));

        assertThat(result).isSameAs(saved);
        verify(reports).saveUserReport(
                eq(7L),
                eq(date),
                eq(time),
                eq("【早间版】AI 每日简报 2026-08-31"),
                eq("# 🤖 AI 每日高价值简报\n\n正文"),
                any());
        verify(sections, never()).findFor(any(), any(), any());
    }

    @Test
    void techDigestAtEveningUsesPublicEveningReport() {
        TopicSectionService sections = mock(TopicSectionService.class);
        ReportService reports = mock(ReportService.class);
        ReportAssemblyService service = new ReportAssemblyService(sections, reports);
        LocalDate date = LocalDate.of(2026, 8, 31);
        LocalTime time = LocalTime.of(20, 0);
        Report digest = new Report();
        digest.setTitle("【晚间版】AI 每日简报 2026-08-31");
        digest.setContent("晚间正文");
        when(reports.getLatestByEditionForDate("evening", date)).thenReturn(digest);

        Report result = service.assembleEphemeral(9L, date, time, List.of("科技"));

        assertThat(result.getTitle()).isEqualTo("【晚间版】AI 每日简报 2026-08-31");
        assertThat(result.getContent()).isEqualTo("晚间正文");
        verify(sections, never()).findFor(any(), any(), any());
    }

    private TopicSection section(String topic, String content) {
        TopicSection section = new TopicSection();
        section.setTopicKey(topic);
        section.setContent(content);
        return section;
    }
}
