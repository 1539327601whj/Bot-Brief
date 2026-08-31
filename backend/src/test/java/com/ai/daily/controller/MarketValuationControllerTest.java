package com.ai.daily.controller;

import com.ai.daily.dto.MarketValuationIngestDTO;
import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.service.MarketValuationHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarketValuationControllerTest {

    private MarketValuationHistoryService service;
    private MarketValuationController controller;

    @BeforeEach
    void setUp() {
        service = mock(MarketValuationHistoryService.class);
        controller = new MarketValuationController();
        ReflectionTestUtils.setField(controller, "marketValuationHistoryService", service);
        ReflectionTestUtils.setField(controller, "ingestToken", "secret");
    }

    @Test
    void rejectsMissingAndWrongTokens() {
        assertThat(controller.ingest(null, mock(MarketValuationIngestDTO.class)).getCode()).isEqualTo(401);
        assertThat(controller.latest("wrong", "000300", "pe", 7).getCode()).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @Test
    void acceptsCorrectTokenForReadAndWrite() {
        MarketValuationIngestDTO dto = mock(MarketValuationIngestDTO.class);
        List<MarketValuationHistory> rows = List.of(mock(MarketValuationHistory.class));
        when(service.latest("000300", "pe", 7)).thenReturn(rows);

        assertThat(controller.ingest("secret", dto).getCode()).isEqualTo(200);
        assertThat(controller.latest("secret", "000300", "pe", 7).getData()).isEqualTo(rows);
        verify(service).upsert(dto);
        verify(service).latest("000300", "pe", 7);
    }
}
