package com.ai.daily.service;

import com.ai.daily.entity.Report;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ReportPersonalizationServiceTest {

    private final ReportPersonalizationService service = new ReportPersonalizationService();

    @Test
    void selectsMatchingSectionsWithoutMutatingCanonicalReport() {
        Report canonical = report();
        String original = canonical.getContent();

        Report personalized = service.personalize(canonical, List.of("数据库", "Claude"));

        assertThat(personalized).isNotSameAs(canonical);
        assertThat(personalized.getId()).isEqualTo(canonical.getId());
        assertThat(personalized.getContent())
                .contains("Claude 发布新模型", "PostgreSQL 性能更新")
                .doesNotContain("Flutter 发布稳定版");
        assertThat(canonical.getContent()).isEqualTo(original);
        assertThat(personalized.getSummary()).isNotBlank();
    }

    @Test
    void matchesEnglishInterestsCaseInsensitivelyAndKeepsCanonicalOrder() {
        Report personalized = service.personalize(report(), List.of("postgresql", "CLAUDE"));

        assertThat(personalized.getContent().indexOf("Claude"))
                .isLessThan(personalized.getContent().indexOf("PostgreSQL"));
    }

    @Test
    void fallsBackToCanonicalReportWhenNoSectionMatchesOrMarkdownCannotBeSplit() {
        Report canonical = report();
        assertThat(service.personalize(canonical, List.of("完全不存在的兴趣"))).isSameAs(canonical);

        Report plain = report();
        plain.setContent("没有二级标题的正文");
        assertThat(service.personalize(plain, List.of("正文"))).isSameAs(plain);
        assertThatNoException().isThrownBy(() -> service.personalize(canonical, List.of()));
    }

    private Report report() {
        Report report = new Report();
        report.setId(42L);
        report.setEdition("morning");
        report.setTitle("测试早报");
        report.setSummary("公共摘要");
        report.setRunId("run-1");
        report.setCreatedAt(LocalDateTime.of(2026, 7, 24, 8, 0));
        report.setContent("""
                # AI 每日简报

                ## 1. Claude 发布新模型

                Anthropic 更新了 Claude 推理能力。

                ## 2. Flutter 发布稳定版

                移动端开发体验获得提升。

                ## 3. PostgreSQL 性能更新

                数据库查询性能得到改进。
                """);
        return report;
    }
}
