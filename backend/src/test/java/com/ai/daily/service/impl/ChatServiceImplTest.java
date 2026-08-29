package com.ai.daily.service.impl;

import com.ai.daily.dto.ChatMessageDTO;
import com.ai.daily.dto.ChatResponseDTO;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.TopicSection;
import com.ai.daily.service.AiClientService;
import com.ai.daily.service.ReportQueryService;
import com.ai.daily.service.ReportService;
import com.ai.daily.service.TopicSectionService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private ReportQueryService reportQueryService;
    @Mock
    private TopicSectionService topicSectionService;
    @Mock
    private ReportService reportService;
    @Mock
    private AiClientService aiClientService;

    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatServiceImpl(reportQueryService, topicSectionService, reportService, aiClientService);
        lenient().when(aiClientService.chat(anyList(), anyDouble(), anyInt())).thenReturn("综合主题段作答");
        lenient().when(reportQueryService.pageVisible(
                        any(), anyBoolean(), anyBoolean(), any(), nullable(String.class),
                        nullable(LocalDateTime.class), nullable(LocalDateTime.class), nullable(String.class)))
                .thenReturn(emptyPage());
    }

    @Test
    void techQuestionUsesTopicSectionsInsteadOfEtfReports() {
        when(topicSectionService.listRecent(any(LocalDate.class), anyList(), anyInt()))
                .thenReturn(List.of(section("AI大模型", "阿里发布万相 2.0，DeepSeek 更新推理接口。")));
        Report personal = report(11, Report.PERSONAL, "我的简报", "万相");
        when(reportService.getByUserEditionDate(eq(1L), eq(Report.PERSONAL), any())).thenReturn(personal);
        when(reportQueryService.pageVisible(
                eq(1L), eq(false), eq(true), any(), eq("market_watch_evening"),
                any(), nullable(LocalDateTime.class), nullable(String.class)))
                .thenReturn(pageOf(report(3, "market_watch_evening",
                        "【ETF市场数据简报晚间版】沪深300ETF", "沪深300ETF 少买。")));

        ChatResponseDTO response = service.chat("最近有哪些 AI 大模型更新？", List.of(), 1L);

        assertThat(response.getAnswer()).isEqualTo("综合主题段作答");
        assertThat(response.getSources()).extracting(ChatResponseDTO.SourceItem::getId).contains(11L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiClientService.AiMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiClientService).chat(captor.capture(), eq(0.3), eq(2048));
        String prompt = captor.getValue().stream().map(AiClientService.AiMessage::content).reduce("", String::concat);
        assertThat(prompt).contains("万相").contains("DeepSeek");
        assertThat(prompt).doesNotContain("沪深300ETF");
    }

    @Test
    void followUpInheritsPreviousTechIntentAndTopics() {
        when(topicSectionService.listRecent(any(LocalDate.class), anyList(), anyInt()))
                .thenReturn(List.of(section("AI大模型", "DeepSeek 更新推理接口。")));
        ChatMessageDTO previous = new ChatMessageDTO();
        previous.setRole("user");
        previous.setContent("最近有哪些 AI 大模型更新？");

        service.chat("还有呢", List.of(previous), 1L);

        verify(topicSectionService).listRecent(any(LocalDate.class), anyList(), anyInt());
        verify(reportQueryService, never()).pageVisible(
                any(), anyBoolean(), anyBoolean(), any(), eq("market_watch_evening"),
                any(), nullable(LocalDateTime.class), nullable(String.class));
    }

    @Test
    void marketQuestionSkipsTopicSections() {
        when(reportQueryService.pageVisible(
                eq(1L), eq(false), eq(true), any(), eq("market_watch_evening"),
                any(), nullable(LocalDateTime.class), nullable(String.class)))
                .thenReturn(pageOf(report(3, "market_watch_evening",
                        "【ETF市场数据简报晚间版】沪深300ETF", "沪深300ETF PE 分位 71%。")));

        ChatResponseDTO response = service.chat("沪深300ETF 今天估值怎么看", List.of(), 1L);

        assertThat(response.getSources()).extracting(ChatResponseDTO.SourceItem::getEdition)
                .containsExactly("market_watch_evening");
        verify(topicSectionService, never()).listRecent(any(), any(), anyInt());
    }

    private static Page<Report> emptyPage() {
        return pageOf();
    }

    private static Page<Report> pageOf(Report... reports) {
        Page<Report> page = new Page<>();
        page.setRecords(List.of(reports));
        return page;
    }

    private static Report report(long id, String edition, String title, String content) {
        Report report = new Report();
        report.setId(id);
        report.setEdition(edition);
        report.setTitle(title);
        report.setContent(content);
        report.setSummary(content);
        report.setCreatedAt(LocalDateTime.of(2026, 8, 29, 18, 0));
        return report;
    }

    private static TopicSection section(String topic, String content) {
        TopicSection section = new TopicSection();
        section.setTopicKey(topic);
        section.setTitle("【" + topic + "】2026-08-29");
        section.setContent(content);
        section.setSummary(content);
        section.setEdition("w06_12");
        section.setSectionDate(LocalDate.of(2026, 8, 29));
        section.setCreatedAt(LocalDateTime.of(2026, 8, 29, 8, 0));
        return section;
    }
}
