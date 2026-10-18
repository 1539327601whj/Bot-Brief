package com.ai.daily.screener.v2.ai;

import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseRows;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 缓存读写的降级：表坏了只丢缓存，不让解读端点 500。 */
class AnalysisCacheServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 12, 0);

    private final WarehouseRepository repository = mock(WarehouseRepository.class);
    private final AnalysisCacheService service = new AnalysisCacheService(repository);

    @Test
    void getDelegatesToRepositoryAndReturnsRow() {
        WarehouseRows.CachedAnalysis row = new WarehouseRows.CachedAnalysis(
                "STOCK:600519", "snap", "rules", "prompt", "{}", NOW, NOW.plusMinutes(30));
        when(repository.findAnalysisCache("STOCK:600519", "snap", "rules", "prompt", NOW))
                .thenReturn(Optional.of(row));

        assertThat(service.get("STOCK:600519", "snap", "rules", "prompt", NOW))
                .contains(row);
    }

    @Test
    void blankKeySkipsTheDatabase() {
        assertThat(service.get("", "snap", "rules", "prompt", NOW)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void readFailureDegradesToMiss() {
        when(repository.findAnalysisCache(anyString(), anyString(), anyString(),
                anyString(), any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThat(service.get("STOCK:600519", "snap", "rules", "prompt", NOW)).isEmpty();
    }

    @Test
    void putStoresExpiryFromTtl() {
        service.put("STOCK:600519", "snap", "rules", "prompt", "{}",
                Duration.ofMinutes(30), NOW);

        ArgumentCaptor<WarehouseRows.CachedAnalysis> captor =
                ArgumentCaptor.forClass(WarehouseRows.CachedAnalysis.class);
        verify(repository).upsertAnalysisCache(captor.capture());
        assertThat(captor.getValue().expiresAt()).isEqualTo(NOW.plusMinutes(30));
        assertThat(captor.getValue().createdAt()).isEqualTo(NOW);
    }

    @Test
    void writeFailureDoesNotThrow() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db down"))
                .when(repository).upsertAnalysisCache(any());

        assertThatCode(() -> service.put("STOCK:600519", "snap", "rules", "prompt", "{}",
                Duration.ofMinutes(30), NOW)).doesNotThrowAnyException();
    }
}
