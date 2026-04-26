package com.ai.daily.service;

import com.ai.daily.entity.Report;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * Report 服务接口
 */
public interface ReportService extends IService<Report> {

    /**
     * 保存新简报
     */
    void saveReport(String edition, String title, String content, String summary, String runId);

    /**
     * 获取最新简报
     */
    Report getLatestReport();
}