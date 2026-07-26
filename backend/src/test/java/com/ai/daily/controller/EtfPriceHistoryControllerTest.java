package com.ai.daily.controller;

import com.ai.daily.dto.EtfPriceHistoryIngestDTO;
import com.ai.daily.entity.EtfPriceHistory;
import com.ai.daily.service.EtfPriceHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EtfPriceHistoryControllerTest {

    private EtfPriceHistoryService service;
    private EtfPriceHistoryController controller;

    @BeforeEach
    void setUp() {
        service = mock(EtfPriceHistoryService.class);
        controller = new EtfPriceHistoryController(service);
        ReflectionTestUtils.setField(controller, "ingestToken", "secret");
    }

    @Test
    void rejectsMissingAndWrongTokens() {
        assertThat(controller.ingest(null, List.of(mock(EtfPriceHistoryIngestDTO.class))).getCode()).isEqualTo(401);
        assertThat(controller.latest("wrong", "510300", 120, "QFQ").getCode()).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @Test
    void acceptsCorrectTokenForReadAndWrite() {
        List<EtfPriceHistoryIngestDTO> prices = List.of(mock(EtfPriceHistoryIngestDTO.class));
        List<EtfPriceHistory> histories = List.of(mock(EtfPriceHistory.class));
        when(service.latest("510300", 120, "QFQ")).thenReturn(histories);

        assertThat(controller.ingest("secret", prices).getCode()).isEqualTo(200);
        assertThat(controller.latest("secret", "510300", 120, "QFQ").getData()).isEqualTo(histories);
        verify(service).upsertBatch(prices);
        verify(service).latest("510300", 120, "QFQ");
    }
}
