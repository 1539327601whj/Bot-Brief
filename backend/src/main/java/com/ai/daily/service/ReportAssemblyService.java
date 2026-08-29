package com.ai.daily.service;

import com.ai.daily.entity.Report;
import com.ai.daily.entity.TopicSection;
import com.ai.daily.util.MarkdownUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportAssemblyService {

    private final TopicSectionService topicSectionService;
    private final ReportService reportService;

    public Report assembleAndPersist(Long userId, String edition, LocalDate date, List<String> topics) {
        if (userId == null || !Report.isPersonalizedEdition(edition) || date == null) return null;
        if (topics == null || topics.isEmpty()) return null;
        Report existing = reportService.getByUserEditionDate(userId, edition, date);
        if (existing != null) return existing;

        List<TopicSection> sections = topicSectionService.findFor(date, edition, topics);
        if (sections.isEmpty()) return null;

        String content = render(date, edition, sections);
        String title = titleFor(edition, date);
        String summary = MarkdownUtils.stripToPlainText(content, 100);
        return reportService.saveUserReport(userId, date, edition, title, content, summary);
    }

    public Report assembleEphemeral(Long reportId, String edition, LocalDate date, List<String> topics) {
        if (!Report.isPersonalizedEdition(edition) || date == null || topics == null || topics.isEmpty()) return null;
        List<TopicSection> sections = topicSectionService.findFor(date, edition, topics);
        if (sections.isEmpty()) return null;
        Report report = new Report();
        report.setId(reportId);
        report.setEdition(edition);
        report.setReportDate(date);
        report.setTitle(titleFor(edition, date));
        report.setContent(render(date, edition, sections));
        report.setSummary(MarkdownUtils.stripToPlainText(report.getContent(), 100));
        return report;
    }

    public Report assembleForWebIfReady(Long userId, String edition, LocalDate date, List<String> topics) {
        if (userId == null || !Report.isPersonalizedEdition(edition) || date == null) return null;
        Report existing = reportService.getByUserEditionDate(userId, edition, date);
        if (existing != null) return existing;
        if (!reportService.publicReportExists(edition, date)) return null;
        return assembleAndPersist(userId, edition, date, topics);
    }

    static String titleFor(String edition, LocalDate date) {
        String label = "evening".equals(edition) ? "晚间版" : "早间版";
        return "【" + label + "】我的简报 " + date;
    }

    static String render(LocalDate date, String edition, List<TopicSection> sections) {
        String label = "evening".equals(edition) ? "晚间版" : "早间版";
        StringBuilder content = new StringBuilder();
        content.append("# 🎯 我的").append(label).append(" · ").append(date).append("\n\n---\n\n");
        for (int i = 0; i < sections.size(); i++) {
            if (i > 0) content.append("\n\n");
            content.append(sections.get(i).getContent().strip());
        }
        if (!content.isEmpty() && content.charAt(content.length() - 1) != '\n') {
            content.append('\n');
        }
        return content.toString();
    }
}
