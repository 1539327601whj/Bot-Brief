package com.ai.daily.service;

import com.ai.daily.entity.TopicGenerationStatus;
import com.ai.daily.mapper.TopicGenerationStatusMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TopicGenerationStatusService {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final TopicGenerationStatusMapper statusMapper;

    public void markReady(LocalDate date, String window, String topic, String runId) {
        record(date, window, topic, TopicGenerationStatus.READY, "已生成", runId);
    }

    public void record(LocalDate date, String window, String topic, String status, String message, String runId) {
        if (date == null || !ReportWindows.isGenerationWindow(window) || topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("生成状态参数无效");
        }
        String normalized = normalizeStatus(status);
        String topicKey = topic.trim();
        TopicGenerationStatus existing = statusMapper.findOne(date, window, topicKey);
        if (existing != null && TopicGenerationStatus.READY.equals(existing.getStatus())
                && !TopicGenerationStatus.READY.equals(normalized)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(BEIJING);
        if (existing == null) {
            TopicGenerationStatus row = new TopicGenerationStatus();
            row.setSectionDate(date);
            row.setWindowKey(window);
            row.setTopicKey(topicKey);
            row.setStatus(normalized);
            row.setMessage(trim(message));
            row.setRunId(runId);
            row.setUpdatedAt(now);
            statusMapper.insert(row);
            return;
        }
        existing.setStatus(normalized);
        existing.setMessage(trim(message));
        existing.setRunId(runId);
        existing.setUpdatedAt(now);
        statusMapper.updateById(existing);
    }

    public void reopenUnready(LocalDate date, String window, String topic) {
        TopicGenerationStatus existing = find(date, window, topic);
        if (existing == null || existing.getId() == null) return;
        if (TopicGenerationStatus.READY.equals(existing.getStatus())) return;
        statusMapper.deleteById(existing.getId());
    }

    public TopicGenerationStatus find(LocalDate date, String window, String topic) {
        if (date == null || window == null || topic == null) return null;
        try {
            TopicGenerationStatus exact = statusMapper.findOne(date, window, topic.trim());
            if (exact != null) return exact;
            return statusMapper.findOne(date, window, topic.trim().toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    public List<TopicGenerationStatus> listForDate(LocalDate date) {
        if (date == null) return List.of();
        return statusMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TopicGenerationStatus>()
                        .eq(TopicGenerationStatus::getSectionDate, date)
        );
    }

    static String normalizeStatus(String status) {
        if (TopicGenerationStatus.READY.equals(status)
                || TopicGenerationStatus.SKIPPED_NO_NEWS.equals(status)
                || TopicGenerationStatus.FAILED.equals(status)) {
            return status;
        }
        throw new IllegalArgumentException("状态只支持 ready / skipped_no_news / failed");
    }

    private static String trim(String message) {
        if (message == null || message.isBlank()) return null;
        String value = message.trim();
        return value.length() > 255 ? value.substring(0, 255) : value;
    }
}
