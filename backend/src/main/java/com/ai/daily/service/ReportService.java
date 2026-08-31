package com.ai.daily.service;

import com.ai.daily.entity.Report;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDate;

/**
 * Report 服务接口
 */
public interface ReportService extends IService<Report> {

    /**
     * 保存公共简报。同一 run 不重复写；同一业务日同一版次再次入库时覆盖正文，便于刷新行情。
     */
    boolean saveReport(LocalDate reportDate, String edition, String title, String content, String summary, String runId);

    /**
     * 保存或返回用户当日拼装简报。同一用户同一业务日同一展示时刻已存在时返回已有记录。
     */
    Report saveUserReport(Long userId, LocalDate reportDate, java.time.LocalTime displayTime, String title, String content, String summary);

    /**
     * 获取最新公共简报
     */
    Report getLatestReport();

    Report getLatestByEdition(String edition);

    Report getLatestByEditionForDate(String edition, LocalDate date);

    Report getByUserEditionDate(Long userId, String edition, LocalDate date);

    Report getByUserEditionDateAndTime(Long userId, String edition, LocalDate date, java.time.LocalTime displayTime);

    java.util.List<Report> listForUserOnDate(Long userId, LocalDate date);

    Report getLatestForUser(Long userId, String edition);

    Report getLatestPublicMarketWatch();

    boolean publicReportExists(String edition, LocalDate date);
}