package com.ai.daily.service;

import com.ai.daily.entity.TopicGenerationStatus;
import com.ai.daily.mapper.TopicGenerationStatusMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicGenerationStatusServiceTest {

    @Test
    void readyStatusIsNotDowngradedToSkipped() {
        TopicGenerationStatusMapper mapper = mock(TopicGenerationStatusMapper.class);
        TopicGenerationStatusService service = new TopicGenerationStatusService(mapper);
        TopicGenerationStatus existing = new TopicGenerationStatus();
        existing.setStatus(TopicGenerationStatus.READY);
        when(mapper.findOne(any(), any(), any())).thenReturn(existing);

        service.record(LocalDate.of(2026, 8, 31), ReportWindows.W06_12, "区块链",
                TopicGenerationStatus.SKIPPED_NO_NEWS, "无资讯", "run-2");

        verify(mapper, never()).updateById(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void reopenUnreadyDeletesSkippedButKeepsReady() {
        TopicGenerationStatusMapper mapper = mock(TopicGenerationStatusMapper.class);
        TopicGenerationStatusService service = new TopicGenerationStatusService(mapper);

        TopicGenerationStatus skipped = new TopicGenerationStatus();
        skipped.setId(8L);
        skipped.setStatus(TopicGenerationStatus.SKIPPED_NO_NEWS);
        when(mapper.findOne(any(), any(), any())).thenReturn(skipped);
        service.reopenUnready(LocalDate.of(2026, 9, 1), ReportWindows.W12_18, "马斯克");
        verify(mapper).deleteById(8L);

        TopicGenerationStatus ready = new TopicGenerationStatus();
        ready.setId(9L);
        ready.setStatus(TopicGenerationStatus.READY);
        when(mapper.findOne(any(), any(), any())).thenReturn(ready);
        service.reopenUnready(LocalDate.of(2026, 9, 1), ReportWindows.W12_18, "马斯克");
        verify(mapper, never()).deleteById(9L);
    }
}
