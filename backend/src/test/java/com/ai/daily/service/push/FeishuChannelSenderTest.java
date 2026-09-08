package com.ai.daily.service.push;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.service.PushChannelValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuChannelSenderTest {

    @Test
    void buildsSchema2CardWithColoredMarkdown() {
        FeishuChannelSender sender = new FeishuChannelSender(
                new RestTemplate(), new PushChannelValidator(), new ProviderResponseValidator(new ObjectMapper()));
        Report report = new Report();
        report.setTitle("【14:35】区块链日报 2026-09-07");
        report.setEdition("personal");
        report.setContent("""
                ## 区块链日报

                **今日概览：** 市场静待技术迭代。

                ### 1. 今日无重大区块链事件
                """);

        Map<String, Object> card = sender.buildCard(report);

        assertThat(card.get("schema")).isEqualTo("2.0");
        assertThat(card.get("body")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) card.get("body");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements = (List<Map<String, Object>>) body.get("elements");
        assertThat(elements).hasSize(1);
        assertThat(elements.get(0).get("tag")).isEqualTo("markdown");
        assertThat(elements.get(0).get("content").toString())
                .contains("<font color=\"blue\">**▎ 区块链日报**</font>")
                .contains("<text_tag color='violet'>今日概览</text_tag>")
                .contains("<font color=\"orange\">**1. 今日无重大区块链事件**</font>");
    }
}
