package com.ai.daily.service;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.TopicSection;
import com.ai.daily.util.MarkdownUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportAssemblyService {

    private final TopicSectionService topicSectionService;
    private final ReportService reportService;

    public Report assembleAndPersist(Long userId, LocalDate date, LocalTime displayTime, List<String> topics) {
        return assembleAndPersistFocuses(userId, date, displayTime, TopicFocus.fromTopics(topics));
    }

    public Report assembleAndPersistItems(
            Long userId, LocalDate date, LocalTime displayTime, List<SubscriptionDTO.TopicScheduleItemDTO> items) {
        return assembleAndPersistFocuses(userId, date, displayTime, TopicFocus.fromItems(items));
    }

    public Report assembleAndPersistFocuses(
            Long userId, LocalDate date, LocalTime displayTime, List<TopicFocus> focuses) {
        if (userId == null || date == null || displayTime == null) return null;
        if (focuses == null || focuses.isEmpty()) return null;
        LocalTime minute = displayTime.withSecond(0).withNano(0);
        Report existing = reportService.getByUserEditionDateAndTime(userId, Report.PERSONAL, date, minute);
        if (existing != null) return existing;

        Assembled assembled = assemble(date, minute, focuses);
        if (assembled == null) return null;
        return reportService.saveUserReport(
                userId, date, minute, assembled.title(), assembled.content(), assembled.summary());
    }

    public Report assembleEphemeral(Long reportId, LocalDate date, LocalTime displayTime, List<String> topics) {
        return assembleEphemeralFocuses(reportId, date, displayTime, TopicFocus.fromTopics(topics));
    }

    public Report assembleEphemeralItems(
            Long reportId, LocalDate date, LocalTime displayTime, List<SubscriptionDTO.TopicScheduleItemDTO> items) {
        return assembleEphemeralFocuses(reportId, date, displayTime, TopicFocus.fromItems(items));
    }

    public Report assembleEphemeralFocuses(
            Long reportId, LocalDate date, LocalTime displayTime, List<TopicFocus> focuses) {
        if (date == null || displayTime == null || focuses == null || focuses.isEmpty()) return null;
        LocalTime minute = displayTime.withSecond(0).withNano(0);
        Assembled assembled = assemble(date, minute, focuses);
        if (assembled == null) return null;
        Report report = new Report();
        report.setId(reportId);
        report.setEdition(Report.PERSONAL);
        report.setReportDate(date);
        report.setDisplayTime(minute);
        report.setTitle(assembled.title());
        report.setContent(assembled.content());
        report.setSummary(assembled.summary());
        return report;
    }

    public Report assembleForWebIfReady(Long userId, LocalDate date, LocalTime displayTime, List<String> topics) {
        return assembleForWebIfReadyFocuses(userId, date, displayTime, TopicFocus.fromTopics(topics));
    }

    public Report assembleForWebIfReadyItems(
            Long userId, LocalDate date, LocalTime displayTime, List<SubscriptionDTO.TopicScheduleItemDTO> items) {
        return assembleForWebIfReadyFocuses(userId, date, displayTime, TopicFocus.fromItems(items));
    }

    public Report assembleForWebIfReadyFocuses(
            Long userId, LocalDate date, LocalTime displayTime, List<TopicFocus> focuses) {
        if (userId == null || date == null || displayTime == null) return null;
        LocalTime minute = displayTime.withSecond(0).withNano(0);
        Report existing = reportService.getByUserEditionDateAndTime(userId, Report.PERSONAL, date, minute);
        if (existing != null) return existing;
        return assembleAndPersistFocuses(userId, date, minute, focuses);
    }

    private Assembled assemble(LocalDate date, LocalTime minute, List<TopicFocus> focuses) {
        List<TopicSection> sections = resolveSections(date, minute, focuses);
        if (sections.isEmpty()) return null;
        boolean onlyDigest = focuses.stream().allMatch(TopicFocus::usePublicDigest);
        String title;
        String content;
        if (onlyDigest && sections.size() == 1) {
            TopicSection digest = sections.get(0);
            title = digest.getTitle() != null && !digest.getTitle().isBlank()
                    ? digest.getTitle()
                    : titleFor(date, minute);
            content = digest.getContent();
        } else {
            title = titleFor(date, minute);
            content = render(date, minute, sections);
        }
        return new Assembled(title, content, MarkdownUtils.stripToPlainText(content, 100));
    }

    private List<TopicSection> resolveSections(LocalDate date, LocalTime minute, List<TopicFocus> focuses) {
        List<TopicSection> sections = new ArrayList<>();
        List<String> regular = focuses.stream()
                .filter(focus -> !focus.usePublicDigest())
                .map(TopicFocus::topic)
                .toList();
        for (TopicFocus focus : focuses) {
            if (!focus.usePublicDigest()) continue;
            String edition = DigestTopics.publicEditionFor(focus.topic(), minute);
            if (edition == null) continue;
            Report digest = reportService.getLatestByEditionForDate(edition, date);
            if (digest != null && digest.getContent() != null && !digest.getContent().isBlank()) {
                TopicSection section = new TopicSection();
                section.setTopicKey(focus.topic());
                section.setTitle(digest.getTitle());
                section.setContent(digest.getContent());
                sections.add(section);
            }
        }
        if (!regular.isEmpty()) {
            sections.addAll(topicSectionService.findFor(date, ReportWindows.of(minute), regular));
        }
        return sections;
    }

    static String titleFor(LocalDate date, LocalTime time) {
        return "【" + ReportWindows.format(time) + "】我的简报 " + date;
    }

    private record Assembled(String title, String content, String summary) {
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
