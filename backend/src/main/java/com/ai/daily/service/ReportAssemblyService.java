package com.ai.daily.service;

import com.ai.daily.entity.Report;
import com.ai.daily.entity.TopicSection;
import com.ai.daily.util.MarkdownUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportAssemblyService {

    private final TopicSectionService topicSectionService;
    private final ReportService reportService;

    public Report assembleAndPersist(Long userId, LocalDate date, LocalTime displayTime, List<String> topics) {
        if (userId == null || date == null || displayTime == null) return null;
        if (topics == null || topics.isEmpty()) return null;
        LocalTime minute = displayTime.withSecond(0).withNano(0);
        Report existing = reportService.getByUserEditionDateAndTime(userId, Report.PERSONAL, date, minute);
        if (existing != null) return existing;

        List<TopicSection> sections = topicSectionService.findFor(date, ReportWindows.of(minute), topics);
        if (sections.isEmpty()) return null;

        String content = render(date, minute, sections);
        String title = titleFor(date, minute);
        String summary = MarkdownUtils.stripToPlainText(content, 100);
        return reportService.saveUserReport(userId, date, minute, title, content, summary);
    }

    public Report assembleEphemeral(Long reportId, LocalDate date, LocalTime displayTime, List<String> topics) {
        if (date == null || displayTime == null || topics == null || topics.isEmpty()) return null;
        LocalTime minute = displayTime.withSecond(0).withNano(0);
        List<TopicSection> sections = topicSectionService.findFor(date, ReportWindows.of(minute), topics);
        if (sections.isEmpty()) return null;
        Report report = new Report();
        report.setId(reportId);
        report.setEdition(Report.PERSONAL);
        report.setReportDate(date);
        report.setDisplayTime(minute);
        report.setTitle(titleFor(date, minute));
        report.setContent(render(date, minute, sections));
        report.setSummary(MarkdownUtils.stripToPlainText(report.getContent(), 100));
        return report;
    }

    public Report assembleForWebIfReady(Long userId, LocalDate date, LocalTime displayTime, List<String> topics) {
        if (userId == null || date == null || displayTime == null) return null;
        LocalTime minute = displayTime.withSecond(0).withNano(0);
        Report existing = reportService.getByUserEditionDateAndTime(userId, Report.PERSONAL, date, minute);
        if (existing != null) return existing;
        return assembleAndPersist(userId, date, minute, topics);
    }

    static String titleFor(LocalDate date, LocalTime time) {
        return "【" + ReportWindows.format(time) + "】我的简报 " + date;
    }

    static String render(LocalDate date, LocalTime time, List<TopicSection> sections) {
        StringBuilder content = new StringBuilder();
        content.append("# 🎯 我的简报 ").append(ReportWindows.format(time)).append(" · ").append(date).append("\n\n---\n\n");
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
