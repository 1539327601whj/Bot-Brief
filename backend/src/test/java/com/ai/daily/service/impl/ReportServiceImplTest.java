package com.ai.daily.service.impl;

import com.ai.daily.entity.Report;
import com.ai.daily.mapper.ReportMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

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

        assertThat(service.saveReport(
                LocalDate.of(2026, 8, 18),
                "morning", "测试早报", "## 要点\n\n这是有效正文。", "摘要", "run-1"
        )).isFalse();

        verify(mapper, never()).insert(any());
    }

    @Test
    void skipsDuplicateBusinessReportBeforeInsert() {
        ReportMapper mapper = mock(ReportMapper.class);
        LocalDate reportDate = LocalDate.of(2026, 8, 18);
        when(mapper.findIdByEditionAndReportDate("morning", reportDate)).thenReturn(42L);
        ReportServiceImpl service = new ReportServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        assertThat(service.saveReport(
                reportDate,
                "morning", "测试早报", "## 要点\n\n这是有效正文。", "摘要", "run-2"
        )).isFalse();

        verify(mapper, never()).insert(any());
    }

    @Test
    void savesReportDateAndBothIdempotencyKeys() {
        ReportMapper mapper = mock(ReportMapper.class);
        ReportServiceImpl service = new ReportServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.findIdByIngestKey("morning:run-2")).thenReturn(null);
        when(mapper.findIdByEditionAndReportDate("morning", LocalDate.of(2026, 8, 18))).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);
        LocalDate reportDate = LocalDate.of(2026, 8, 18);

        assertThat(service.saveReport(
                reportDate,
                "morning", "测试早报", "## 要点\n\n这是有效正文。", "摘要", "run-2"
        )).isTrue();

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getReportDate()).isEqualTo(reportDate);
        assertThat(captor.getValue().getIngestKey()).isEqualTo("morning:run-2");
    }

    @Test
    void treatsConcurrentBusinessDuplicateAsSuccess() {
        ReportMapper mapper = mock(ReportMapper.class);
        LocalDate reportDate = LocalDate.of(2026, 8, 18);
        when(mapper.findIdByEditionAndReportDate("morning", reportDate))
                .thenReturn(null, 42L);
        ReportServiceImpl service = new ReportServiceImpl() {
            @Override
            public boolean save(Report report) {
                throw new DuplicateKeyException("uk_reports_edition_report_date");
            }
        };
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        assertThat(service.saveReport(
                reportDate,
                "morning", "测试早报", "## 要点\n\n这是有效正文。", "摘要", "run-2"
        )).isFalse();
    }

    @Test
    void rethrowsUnknownDuplicateKey() {
        ReportMapper mapper = mock(ReportMapper.class);
        LocalDate reportDate = LocalDate.of(2026, 8, 18);
        ReportServiceImpl service = new ReportServiceImpl() {
            @Override
            public boolean save(Report report) {
                throw new DuplicateKeyException("unknown constraint");
            }
        };
        when(mapper.findIdByIngestKey("morning:run-2")).thenReturn(null);
        when(mapper.findIdByEditionAndReportDate("morning", reportDate)).thenReturn(null);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        assertThatThrownBy(() -> service.saveReport(
                reportDate,
                "morning", "测试早报", "## 要点\n\n这是有效正文。", "摘要", "run-2"
        )).isInstanceOf(DuplicateKeyException.class);
    }
}
