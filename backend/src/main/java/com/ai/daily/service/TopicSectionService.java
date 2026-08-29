package com.ai.daily.service;

import com.ai.daily.entity.Report;
import com.ai.daily.entity.TopicSection;
import com.ai.daily.mapper.TopicSectionMapper;
import com.ai.daily.service.impl.ReportServiceImpl;
import com.ai.daily.util.MarkdownUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TopicSectionService extends ServiceImpl<TopicSectionMapper, TopicSection> {

    public boolean saveSection(LocalDate sectionDate, String edition, String topic, String title, String content, String summary, String runId) {
        if (!Report.isPersonalizedEdition(edition)) {
            throw new IllegalArgumentException("主题段落只支持 morning / evening");
        }
        String topicKey = topic == null ? "" : topic.trim();
        if (topicKey.isEmpty()) {
            throw new IllegalArgumentException("主题不能为空");
        }
        if (!ReportServiceImpl.hasSubstantiveContent(content)) {
            throw new IllegalArgumentException("主题段落缺少实质正文");
        }
        if (baseMapper.findId(sectionDate, edition, topicKey) != null) {
            return false;
        }
        String resolvedSummary = summary;
        if (resolvedSummary == null || resolvedSummary.isBlank()) {
            resolvedSummary = MarkdownUtils.stripToPlainText(content, 100);
        }
        TopicSection section = new TopicSection();
        section.setEdition(edition);
        section.setSectionDate(sectionDate);
        section.setTopicKey(topicKey);
        section.setTitle(title);
        section.setContent(content);
        section.setSummary(resolvedSummary);
        section.setRunId(runId);
        section.setCreatedAt(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        try {
            return this.save(section);
        } catch (DuplicateKeyException e) {
            if (baseMapper.findId(sectionDate, edition, topicKey) != null) {
                return false;
            }
            throw e;
        }
    }

    public List<TopicSection> findFor(LocalDate date, String edition, List<String> topics) {
        if (date == null || edition == null || topics == null || topics.isEmpty()) return List.of();
        List<TopicSection> stored = this.lambdaQuery()
                .eq(TopicSection::getSectionDate, date)
                .eq(TopicSection::getEdition, edition)
                .list();
        Map<String, TopicSection> byKey = new LinkedHashMap<>();
        for (TopicSection section : stored) {
            byKey.putIfAbsent(section.getTopicKey().toLowerCase(Locale.ROOT), section);
        }
        List<TopicSection> ordered = new ArrayList<>();
        for (String topic : topics) {
            if (topic == null || topic.isBlank()) continue;
            TopicSection match = byKey.get(topic.trim().toLowerCase(Locale.ROOT));
            if (match != null) ordered.add(match);
        }
        return ordered;
    }
}
