package com.ai.daily.service;

import com.ai.daily.dto.OpsDeliveryDTO;
import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 脚本/运营 webhook 直推成功后补记 push_log，让首页「今日投递」和真实送达对齐。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsDeliveryService {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final ReportService reportService;
    private final PushChannelService pushChannelService;
    private final PushLogService pushLogService;

    public int record(OpsDeliveryDTO dto) {
        if (dto == null) return 0;
        String channelType = normalizeType(dto.getChannelType());
        LocalDate date = dto.getReportDate() != null ? dto.getReportDate() : LocalDate.now(BEIJING);
        Report report = resolveReport(dto, date);
        if (report == null || report.getId() == null) {
            log.warn("运营投递无法记账：找不到对应简报 edition={} date={}", dto.getEdition(), date);
            return 0;
        }
        List<PushChannel> channels = pushChannelService.listEnabledByType(channelType);
        if (channels.isEmpty()) {
            log.info("运营投递没有可记账的 {} 渠道", channelType);
            return 0;
        }
        boolean success = dto.getSuccess() == null || Boolean.TRUE.equals(dto.getSuccess());
        String edition = dto.getEdition() == null || dto.getEdition().isBlank() ? "ops" : dto.getEdition().trim();
        int written = 0;
        for (PushChannel channel : channels) {
            if (channel.getUserId() == null || channel.getId() == null) continue;
            String dispatchKey = "ops:" + date + ":" + edition + ":" + channel.getUserId() + ":" + channel.getId();
            Long logId = pushLogService.claimScheduled(
                    channel.getUserId(), report.getId(), channel.getId(), channel.getChannelType(), dispatchKey);
            if (logId == null) continue;
            if (success) {
                pushLogService.markSuccess(logId);
            } else {
                pushLogService.markFailed(logId, dto.getErrorMessage());
            }
            written++;
        }
        return written;
    }

    private Report resolveReport(OpsDeliveryDTO dto, LocalDate date) {
        if (dto.getReportId() != null) {
            Report byId = reportService.getById(dto.getReportId());
            if (byId != null) return byId;
        }
        String edition = dto.getEdition();
        if (edition != null && !edition.isBlank()) {
            Report dated = reportService.getLatestByEditionForDate(edition, date);
            if (dated != null) return dated;
            return reportService.getLatestByEdition(edition);
        }
        return reportService.getLatestReport();
    }

    private static String normalizeType(String channelType) {
        if (channelType == null || channelType.isBlank()) return "wechat";
        return channelType.trim().toLowerCase();
    }
}
