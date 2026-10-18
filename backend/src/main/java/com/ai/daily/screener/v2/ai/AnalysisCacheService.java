package com.ai.daily.screener.v2.ai;

import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseRows;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * LLM 解读的持久缓存。
 *
 * <p><b>缓存不是优化，是纪律。</b>解读按次计费、一次要几秒到几十秒；
 * 同一份快照重复点击不应该再打一次模型。键里的四个字段任何一项变化都必须失效：
 * 标的、快照 ID、规则版本、提示词版本。少任何一项，都会出现「分数已经变了，
 * 解读还在讲旧结论」。
 *
 * <p><b>缓存故障不能让解读 500。</b>读失败按未命中处理，写失败只记 WARN——
 * 用户要的是这一次的解读，不是缓存表的健康度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisCacheService {

    private final WarehouseRepository repository;

    public Optional<WarehouseRows.CachedAnalysis> get(
            String instrumentCode, String snapshotId, String rulesVersion,
            String promptVersion, LocalDateTime now) {
        if (isBlank(instrumentCode) || isBlank(snapshotId)
                || isBlank(rulesVersion) || isBlank(promptVersion)) {
            return Optional.empty();
        }
        try {
            return repository.findAnalysisCache(instrumentCode, snapshotId,
                    rulesVersion, promptVersion, now);
        } catch (DataAccessException e) {
            log.warn("读取解读缓存失败，按未命中处理 instrument={} snapshot={}：{}",
                    instrumentCode, snapshotId, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String instrumentCode, String snapshotId, String rulesVersion,
                    String promptVersion, String payloadJson,
                    Duration ttl, LocalDateTime now) {
        if (isBlank(instrumentCode) || isBlank(snapshotId) || payloadJson == null) {
            return;
        }
        try {
            repository.upsertAnalysisCache(new WarehouseRows.CachedAnalysis(
                    instrumentCode, snapshotId, rulesVersion, promptVersion,
                    payloadJson, now, now.plus(ttl)));
        } catch (DataAccessException e) {
            log.warn("写入解读缓存失败，本次结果照常返回 instrument={} snapshot={}：{}",
                    instrumentCode, snapshotId, e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
