package com.ai.daily.service;

import com.ai.daily.entity.Report;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDate;

/**
 * Report 服务接口
 */
public interface ReportService extends IService<Report> {

    /**
     * 保存新简报。同一业务日同一版次或同一 ingestKey 已存在时返回 false，不重复写入。
     */
    boolean saveReport(LocalDate reportDate, String edition, String title, String content, String summary, String runId);

    /**
     * 获取最新简报
     */
    Report getLatestReport();

    Report getLatestByEdition(String edition);

    Report getLatestByEditionForDate(String edition, LocalDate date);
}