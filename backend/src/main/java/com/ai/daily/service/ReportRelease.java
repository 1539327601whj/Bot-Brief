package com.ai.daily.service;

import com.ai.daily.entity.Report;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 公共/个人简报都按展示时刻放出：生成可以提前，网页不能提前看见。
 */
public final class ReportRelease {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private ReportRelease() {}

    public static boolean isReleased(Report report) {
        return isReleased(report, LocalDateTime.now(BEIJING));
    }

    public static boolean isReleased(Report report, LocalDateTime now) {
        if (report == null || now == null) return false;
        LocalDate date = reportDate(report);
        LocalTime release = releaseTime(report);
        if (date == null || release == null) return true;
        return !now.isBefore(LocalDateTime.of(date, release));
    }

    public static LocalDate reportDate(Report report) {
        if (report == null) return null;
        if (report.getReportDate() != null) return report.getReportDate();
        return report.getCreatedAt() == null ? null : report.getCreatedAt().toLocalDate();
    }

    public static LocalTime releaseTime(Report report) {
        if (report == null) return null;
        if (report.getDisplayTime() != null) return report.getDisplayTime();
        if (Report.isPersonalizedEdition(report.getEdition())) return null;
        return ReportWindows.publicDisplayTime(report.getEdition());
    }
}
