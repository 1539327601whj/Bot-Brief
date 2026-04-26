package com.ai.daily.service.impl;

import com.ai.daily.dto.ChatResponseDTO;
import com.ai.daily.entity.Report;
import com.ai.daily.service.ChatService;
import com.ai.daily.service.ReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 对话 Service 实现
 */
@Service
public class ChatServiceImpl implements ChatService {

    @Value("${deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${deepseek.model:deepseek-chat}")
    private String deepseekModel;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    @Autowired
    private ReportService reportService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ChatResponseDTO chat(String question) {
        ChatResponseDTO response = new ChatResponseDTO();
        
        // 1. 检索相关简报
        List<Report> reports = searchReports(question);
        
        if (reports.isEmpty()) {
            response.setAnswer("抱歉，数据库中暂无相关简报内容。");
            response.setSources(new ArrayList<>());
            return response;
        }

        // 2. 构建上下文
        String context = buildContext(reports);
        
        // 3. 调用 DeepSeek API
        String answer = callDeepSeek(question, context);
        
        // 4. 设置响应
        response.setAnswer(answer);
        response.setSources(reports.stream().limit(5).map(r -> {
            ChatResponseDTO.SourceItem source = new ChatResponseDTO.SourceItem();
            source.setId(r.getId());
            source.setTitle(r.getTitle());
            source.setEdition(r.getEdition());
            source.setCreatedAt(r.getCreatedAt() != null ? r.getCreatedAt().toString() : "");
            return source;
        }).collect(Collectors.toList()));
        
        return response;
    }

    /**
     * 简单关键词检索
     */
    private List<Report> searchReports(String question) {
        // 获取最近的 20 条简报作为候选
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Report> page = 
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Report>();
        wrapper.orderByDesc(Report::getCreatedAt);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Report> result = 
            reportService.page(page, wrapper);

        if (result.getRecords().isEmpty()) {
            return new ArrayList<>();
        }

        // 简单关键词匹配
        List<String> keywords = extractKeywords(question);
        List<Report> matched = new ArrayList<>();
        
        for (Report report : result.getRecords()) {
            String text = (report.getTitle() + " " + report.getContent() + " " + report.getSummary()).toLowerCase();
            for (String keyword : keywords) {
                if (text.contains(keyword.toLowerCase())) {
                    matched.add(report);
                    break;
                }
            }
        }

        // 如果没有匹配，返回最近的 5 条
        if (matched.isEmpty()) {
            return result.getRecords().stream().limit(5).collect(Collectors.toList());
        }
        
        return matched.stream().limit(5).collect(Collectors.toList());
    }

    /**
     * 提取关键词
     */
    private List<String> extractKeywords(String question) {
        // 简单分词（去除停用词）
        String[] stopWords = {"的", "了", "是", "在", "有", "和", "与", "或", "等", "吗", "呢", "吧", "啊"};
        String text = question.toLowerCase();
        List<String> keywords = new ArrayList<>();
        
        for (String word : text.split("")) {
            if (word.length() >= 2 && !containsAny(word, stopWords)) {
                keywords.add(word);
            }
        }
        
        // 添加常见 AI 相关词
        String[] aiTerms = {"ai", "gpt", "openai", "claude", "gemini", "deepseek", "llm", 
                           "大模型", "模型", "发布", "更新", "版本", "功能", "技术"};
        for (String term : aiTerms) {
            if (text.contains(term)) {
                keywords.add(term);
            }
        }
        
        return keywords.stream().distinct().limit(10).collect(Collectors.toList());
    }

    private boolean containsAny(String text, String[] words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    /**
     * 构建上下文
     */
    private String buildContext(List<Report> reports) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个AI资讯助手。以下是相关的历史简报内容：\n\n");
        
        for (int i = 0; i < reports.size(); i++) {
            Report r = reports.get(i);
            sb.append(String.format("【简报%d】%s（%s）\n%s\n\n", 
                i + 1, r.getTitle(), 
                r.getCreatedAt() != null ? r.getCreatedAt().toString() : "",
                r.getContent()));
        }
        
        sb.append("请根据以上简报内容回答用户的问题。如果简报中没有相关信息，请说明暂时无法从已知内容中获取答案。");
        return sb.toString();
    }

    /**
     * 调用 DeepSeek API
     */
    private String callDeepSeek(String question, String context) {
        if (deepseekApiKey == null || deepseekApiKey.isEmpty()) {
            return "⚠️ 未配置 DEEPSEEK_API_KEY，无法生成回答。请在环境变量中配置 DeepSeek API Key。\n\n" +
                   "获取方式：https://platform.deepseek.com/api_keys";
        }

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(deepseekBaseUrl + "/chat/completions");
            request.setHeader("Authorization", "Bearer " + deepseekApiKey);
            request.setHeader("Content-Type", "application/json");

            String prompt = context + "\n\n用户问题：" + question + "\n\n回答：";
            
            String jsonBody = String.format(
                "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"temperature\":0.7,\"max_tokens\":2048}",
                deepseekModel,
                prompt.replace("\"", "\\\"").replace("\n", "\\n")
            );
            
            request.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));

            try (CloseableHttpResponse httpResponse = client.execute(request)) {
                String responseBody = EntityUtils.toString(httpResponse.getEntity());
                
                // 解析响应
                JsonNode root = objectMapper.readTree(responseBody);
                if (root.has("error")) {
                    return "❌ API 调用失败：" + root.get("error").get("message").asText();
                }
                
                return root.path("choices").path(0).path("message").path("content").asText();
            }
        } catch (Exception e) {
            return "❌ 调用 DeepSeek API 时发生错误：" + e.getMessage();
        }
    }
}
