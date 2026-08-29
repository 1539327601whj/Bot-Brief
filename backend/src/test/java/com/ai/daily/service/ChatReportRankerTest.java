package com.ai.daily.service;

import com.ai.daily.entity.Report;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatReportRankerTest {

    @Test
    void techQuestionPrefersAiBriefsOverRecentEtfReports() {
        Report morning = report(1, "morning", "【早间版】AI 每日简报 2026-08-29",
                "阿里发布万相 2.0，DeepSeek 更新推理接口。", LocalDateTime.of(2026, 8, 29, 8, 0));
        Report evening = report(2, "evening", "【晚间版】AI 每日简报 2026-08-28",
                "OpenAI 调整 API 定价。", LocalDateTime.of(2026, 8, 28, 20, 0));
        Report etf = report(3, "market_watch_evening",
                "【ETF市场数据简报晚间版】沪深300ETF / 纳指100ETF 2026-08-29",
                "沪深300ETF 少买，PE 分位 71%。", LocalDateTime.of(2026, 8, 29, 18, 0));

        List<Report> selected = ChatReportRanker.select(
                "最近有哪些 AI 大模型更新？",
                List.of(etf, morning, evening));

        assertThat(selected).extracting(Report::getEdition)
                .containsExactly("morning", "evening");
        assertThat(selected).noneMatch(report -> Report.isSharedPublicEdition(report.getEdition()));
    }

    @Test
    void marketQuestionKeepsEtfReports() {
        Report morning = report(1, "morning", "【早间版】AI 每日简报 2026-08-29",
                "大模型更新。", LocalDateTime.of(2026, 8, 29, 8, 0));
        Report etf = report(2, "market_watch_evening",
                "【ETF市场数据简报晚间版】沪深300ETF / 纳指100ETF 2026-08-29",
                "沪深300ETF 行情价 4.615。", LocalDateTime.of(2026, 8, 29, 18, 0));

        List<Report> selected = ChatReportRanker.select("沪深300ETF 今天估值怎么看", List.of(morning, etf));

        assertThat(selected).extracting(Report::getEdition).containsExactly("market_watch_evening");
    }

    @Test
    void techQuestionDoesNotFallBackToLatestEtfWhenNoKeywordHit() {
        Report etf = report(1, "market_watch_evening",
                "【ETF市场数据简报晚间版】沪深300ETF / 纳指100ETF 2026-08-29",
                "仓位备忘：按计划买。", LocalDateTime.of(2026, 8, 29, 18, 0));
        Report morning = report(2, "morning", "【早间版】AI 每日简报 2026-08-20",
                "行业综述。", LocalDateTime.of(2026, 8, 20, 8, 0));

        List<Report> selected = ChatReportRanker.select("解释一下 RAG 技术", List.of(etf, morning));

        assertThat(selected).extracting(Report::getId).containsExactly(2L);
    }

    @Test
    void classifyAndKeywordsRecognizeModelUpdates() {
        assertThat(ChatReportRanker.classify("最近有哪些 AI 大模型更新？"))
                .isEqualTo(ChatReportRanker.Intent.TECH);
        assertThat(ChatReportRanker.extractKeywords("最近有哪些 AI 大模型更新？"))
                .contains("ai", "大模型", "更新");
        assertThat(ChatReportRanker.extractKeywords("最近有哪些 AI 大模型更新？"))
                .noneMatch(word -> word.length() == 1);
        assertThat(ChatReportRanker.classify("最近的 AI 安全新闻有哪些？"))
                .isEqualTo(ChatReportRanker.Intent.TECH);
    }

    private static Report report(long id, String edition, String title, String summary, LocalDateTime createdAt) {
        Report report = new Report();
        report.setId(id);
        report.setEdition(edition);
        report.setTitle(title);
        report.setSummary(summary);
        report.setContent(summary);
        report.setCreatedAt(createdAt);
        return report;
    }
}
