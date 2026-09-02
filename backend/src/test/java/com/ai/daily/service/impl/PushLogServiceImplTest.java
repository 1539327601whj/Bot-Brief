package com.ai.daily.service.impl;

import com.ai.daily.entity.PushLog;
import com.ai.daily.mapper.PushLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushLogServiceImplTest {

    @Test
    void claimScheduledInsertsNewKey() {
        PushLogMapper mapper = mock(PushLogMapper.class);
        when(mapper.insert(any())).thenAnswer(invocation -> {
            PushLog log = invocation.getArgument(0);
            log.setId(11L);
            return 1;
        });
        PushLogServiceImpl service = spy(new PushLogServiceImpl());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        doReturn(null).when(service).getOne(any());

        assertThat(service.claimScheduled(1L, 20L, 10L, "wechat", "scheduled:2026-08-31:20:00:1:10"))
                .isEqualTo(11L);
        verify(mapper).insert(any());
    }

    @Test
    void claimScheduledRetriesFailedKey() {
        PushLog failed = new PushLog();
        failed.setId(7L);
        failed.setStatus("failed");
        failed.setErrorMessage("企业微信返回失败");
        PushLogServiceImpl service = spy(new PushLogServiceImpl());
        doReturn(failed).when(service).getOne(any());
        doReturn(true).when(service).updateById(failed);

        assertThat(service.claimScheduled(1L, 22L, 10L, "wechat", "scheduled:2026-08-31:20:00:1:10"))
                .isEqualTo(7L);
        assertThat(failed.getStatus()).isEqualTo("sending");
        assertThat(failed.getReportId()).isEqualTo(22L);
        assertThat(failed.getErrorMessage()).isNull();
    }

    @Test
    void claimScheduledSkipsSuccessfulKey() {
        PushLog ok = new PushLog();
        ok.setId(8L);
        ok.setStatus("success");
        PushLogServiceImpl service = spy(new PushLogServiceImpl());
        doReturn(ok).when(service).getOne(any());

        assertThat(service.claimScheduled(1L, 22L, 10L, "wechat", "scheduled:2026-08-31:20:00:1:10"))
                .isNull();
        verify(service, never()).updateById(any());
    }

    @Test
    void claimScheduledSkipsRecentSendingKey() {
        PushLog sending = new PushLog();
        sending.setId(9L);
        sending.setStatus("sending");
        sending.setPushedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusSeconds(20));
        PushLogServiceImpl service = spy(new PushLogServiceImpl());
        doReturn(sending).when(service).getOne(any());

        assertThat(service.claimScheduled(1L, 22L, 10L, "wechat", "scheduled:2026-08-31:20:00:1:10"))
                .isNull();
        verify(service, never()).updateById(any());
    }

    @Test
    void claimScheduledTreatsDuplicateInsertAsSkip() {
        PushLogMapper mapper = mock(PushLogMapper.class);
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("uk_push_log_dispatch_key"));
        PushLogServiceImpl service = spy(new PushLogServiceImpl());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        doReturn(null).when(service).getOne(any());

        assertThat(service.claimScheduled(1L, 20L, 10L, "wechat", "scheduled:2026-08-31:20:00:1:10"))
                .isNull();
    }

    @Test
    void claimScheduledRetriesFailedDuplicateInsert() {
        PushLog failed = new PushLog();
        failed.setId(13L);
        failed.setStatus("failed");
        PushLogMapper mapper = mock(PushLogMapper.class);
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("uk_push_log_dispatch_key"));
        PushLogServiceImpl service = spy(new PushLogServiceImpl());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        doReturn(null, failed).when(service).getOne(any());
        doReturn(true).when(service).updateById(failed);

        assertThat(service.claimScheduled(1L, 20L, 10L, "wechat", "scheduled:2026-09-02:15:10:1:10"))
                .isEqualTo(13L);
        assertThat(failed.getStatus()).isEqualTo("sending");
        verify(service).updateById(failed);
    }
}
