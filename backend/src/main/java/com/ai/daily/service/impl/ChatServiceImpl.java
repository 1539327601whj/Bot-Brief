package com.ai.daily.service.impl;

import com.ai.daily.dto.ChatMessageDTO;
import com.ai.daily.dto.ChatResponseDTO;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.TopicSection;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.AiClientService;
import com.ai.daily.service.ChatPromptBuilder;
import com.ai.daily.service.ChatReportRanker;
import com.ai.daily.service.ChatSectionRanker;
import com.ai.daily.service.ChatService;
import com.ai.daily.service.ChatSnippetExtractor;
import com.ai.daily.service.ReportPersonalizationService;
import com.ai.daily.service.ReportQueryService;
import com.ai.daily.service.ReportService;
import com.ai.daily.service.ReportWindows;
import com.ai.daily.service.SubscriptionPreferences;
import com.ai.daily.service.SubscriptionService;
import com.ai.daily.service.TopicSectionService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final int HISTORY_LIMIT = 6;
    private static final int SNIPPET_CHARS = 900;
    private static final int MAX_PASSAGES = 6;

    private final ReportQueryService reportQueryService;
    private final TopicSectionService topicSectionService;
    private final ReportService reportService;
    private final AiClientService aiClientService;
    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;

    @Override
    public ChatResponseDTO chat(String question, List<ChatMessageDTO> history, Long userId) {
        ChatResponseDTO response = new ChatResponseDTO();
        String asked = question == null ? "" : question.strip();
        if (asked.length() > 500) {
            asked = asked.substring(0, 500);
        }
        List<ChatMessageDTO> turns = sanitize(history);
        String retrievalQuery = retrievalQuery(asked, turns);
        ChatReportRanker.Intent intent = classify(asked, turns);

        List<Passage> passages = retrieve(retrievalQuery, intent, userId);
        if (passages.isEmpty()) {
            response.setAnswer("抱歉，没有检索到与这个问题匹配的科技日报或市场观察。可以换个主题，或先确认对应简报已经入库。");
            response.setSources(List.of());
            return response;
        }

        List<String> materials = new ArrayList<>();
        for (int index = 0; index < passages.size(); index++) {
            Passage passage = passages.get(index);
            materials.add(ChatPromptBuilder.material(index + 1, passage.heading(), passage.body()));
        }

        List<AiClientService.AiMessage> messages = new ArrayList<>();
        messages.add(new AiClientService.AiMessage("system", ChatPromptBuilder.systemPrompt()));
        for (ChatMessageDTO turn : turns) {
            messages.add(new AiClientService.AiMessage(turn.getRole(), turn.getContent()));
        }
        messages.add(new AiClientService.AiMessage("user", ChatPromptBuilder.userMessage(asked, materials)));

        response.setAnswer(aiClientService.chat(messages, 0.3, 2048));
        response.setSources(toSources(passages));
        return response;
    }

    private List<Passage> retrieve(String question, ChatReportRanker.Intent intent, Long userId) {
        boolean allowPublicDigest = SecurityUtils.canReadPublicDigest();
        List<String> keywords = ReportPersonalizationService.expandTerms(ChatReportRanker.extractKeywords(question));
        List<String> topics = visibleSectionTopics(question, userId, allowPublicDigest);
        List<Passage> passages = new ArrayList<>();

        if (intent != ChatReportRanker.Intent.MARKET && (allowPublicDigest || !topics.isEmpty())) {
            LocalDate since = LocalDate.now(BEIJING).minusDays(21);
            List<TopicSection> sections = topicSectionService.listRecent(
                    since, topics.isEmpty() ? null : topics, topics.isEmpty() ? 80 : 40);
            for (TopicSection section : ChatSectionRanker.select(sections, keywords, topics, 5)) {
                passages.add(fromSection(section, keywords, userId, allowPublicDigest));
            }
        }

        List<Long> usedIds = passages.stream().map(Passage::reportId).filter(id -> id != null).toList();
        List<Report> reports = ChatReportRanker.select(question, loadReports(userId, intent, allowPublicDigest));
        for (Report report : reports) {
            if (passages.size() >= MAX_PASSAGES) break;
            if (report.getId() != null && usedIds.contains(report.getId())) continue;
            passages.add(fromReport(report, keywords));
        }
        if (passages.size() > MAX_PASSAGES) {
            return new ArrayList<>(passages.subList(0, MAX_PASSAGES));
        }
        return passages;
    }

    private List<Report> loadReports(Long userId, ChatReportRanker.Intent intent, boolean allowPublicDigest) {
        LocalDateTime start = LocalDate.now(BEIJING).minusDays(intent == ChatReportRanker.Intent.MARKET ? 14 : 21)
                .atStartOfDay();
        Map<Long, Report> unique = new LinkedHashMap<>();
        if (intent != ChatReportRanker.Intent.MARKET) {
            if (allowPublicDigest) {
                addReports(unique, pageReports(userId, "morning", start, 20, allowPublicDigest));
                addReports(unique, pageReports(userId, "evening", start, 20, allowPublicDigest));
            }
            addReports(unique, pageReports(userId, Report.PERSONAL, start, 15, allowPublicDigest));
        }
        if (intent != ChatReportRanker.Intent.TECH) {
            addReports(unique, pageReports(userId, "market_watch_evening", start, 15, allowPublicDigest));
            addReports(unique, pageReports(userId, "market_watch_morning", start, 5, allowPublicDigest));
        }
        return new ArrayList<>(unique.values());
    }

    private List<Report> pageReports(Long userId, String edition, LocalDateTime start, int size, boolean allowPublicDigest) {
        IPage<Report> result = reportQueryService.pageVisible(
                userId, false, allowPublicDigest, new Page<>(1, size), edition, start, null, null);
        return result == null || result.getRecords() == null ? List.of() : result.getRecords();
    }

    private static void addReports(Map<Long, Report> unique, List<Report> reports) {
        for (Report report : reports) {
            if (report != null && report.getId() != null) {
                unique.putIfAbsent(report.getId(), report);
            }
        }
    }

    private Passage fromSection(TopicSection section, List<String> keywords, Long userId, boolean allowPublicDigest) {
        String body = ChatSnippetExtractor.extract(
                firstNonBlank(section.getContent(), section.getSummary()), keywords, SNIPPET_CHARS);
        String date = section.getSectionDate() == null ? "" : section.getSectionDate().toString();
        String topic = section.getTopicKey() == null ? "主题" : section.getTopicKey();
        String title = firstNonBlank(section.getTitle(), "【" + topic + "】" + date);
        String createdAt = section.getCreatedAt() != null ? section.getCreatedAt().toString() : date;
        return new Passage(
                resolveReportId(section, userId, allowPublicDigest),
                title,
                section.getEdition(),
                createdAt,
                date + " · " + topic + " · " + (section.getEdition() == null ? "" : section.getEdition()),
                body
        );
    }

    private Passage fromReport(Report report, List<String> keywords) {
        String raw = firstNonBlank(report.getContent(), report.getSummary());
        String body = ChatSnippetExtractor.extract(raw, keywords, SNIPPET_CHARS);
        String createdAt = report.getCreatedAt() != null ? report.getCreatedAt().toString() : "";
        String date = report.getReportDate() != null ? report.getReportDate().toString() : createdAt;
        String edition = report.getEdition() == null ? "" : report.getEdition();
        return new Passage(
                report.getId(),
                report.getTitle(),
                edition,
                createdAt,
                date + " · " + edition + " · " + (report.getTitle() == null ? "" : report.getTitle()),
                body
        );
    }

    private Long resolveReportId(TopicSection section, Long userId, boolean allowPublicDigest) {
        if (section.getSectionDate() == null) return null;
        Report mine = reportService.getByUserEditionDate(userId, Report.PERSONAL, section.getSectionDate());
        if (mine != null) return mine.getId();
        if (!allowPublicDigest) return null;
        Report published = reportService.getLatestByEditionForDate(
                ReportWindows.digestStyle(section.getEdition()), section.getSectionDate());
        return published == null ? null : published.getId();
    }

    private List<String> visibleSectionTopics(String question, Long userId, boolean allowPublicDigest) {
        List<String> matched = ReportPersonalizationService.matchingTopics(question);
        if (allowPublicDigest) {
            return matched;
        }
        List<String> subscribed = subscribedTopics(userId);
        if (subscribed.isEmpty()) return List.of();
        if (matched.isEmpty()) return subscribed;
        return matched.stream().filter(topic -> containsIgnoreCase(subscribed, topic)).toList();
    }

    private List<String> subscribedTopics(Long userId) {
        if (userId == null) return List.of();
        try {
            Subscription subscription = subscriptionService.getOrCreateForUser(userId);
            List<String> topics = subscriptionPreferences.enabledTopics(subscription);
            return topics == null ? List.of() : topics.stream()
                    .filter(topic -> topic != null && !topic.isBlank())
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static boolean containsIgnoreCase(List<String> topics, String topic) {
        if (topic == null) return false;
        for (String item : topics) {
            if (item != null && item.equalsIgnoreCase(topic)) return true;
        }
        return false;
    }

    private List<ChatResponseDTO.SourceItem> toSources(List<Passage> passages) {
        Map<String, ChatResponseDTO.SourceItem> unique = new LinkedHashMap<>();
        for (Passage passage : passages) {
            ChatResponseDTO.SourceItem item = new ChatResponseDTO.SourceItem();
            item.setId(passage.reportId());
            item.setTitle(passage.title());
            item.setEdition(passage.edition() == null ? "" : passage.edition());
            item.setCreatedAt(passage.createdAt());
            String key = passage.reportId() != null ? "r-" + passage.reportId() : "t-" + passage.title();
            unique.putIfAbsent(key, item);
            if (unique.size() == 5) break;
        }
        return new ArrayList<>(unique.values());
    }

    private static String retrievalQuery(String question, List<ChatMessageDTO> history) {
        List<String> keywords = ChatReportRanker.extractKeywords(question);
        if (keywords.size() >= 2) return question;
        String lastUser = lastUserQuestion(history);
        if (lastUser == null || lastUser.isBlank()) return question;
        return lastUser + " " + question;
    }

    private static ChatReportRanker.Intent classify(String question, List<ChatMessageDTO> history) {
        ChatReportRanker.Intent intent = ChatReportRanker.classify(question);
        if (intent != ChatReportRanker.Intent.GENERAL || question.codePointCount(0, question.length()) > 24) {
            return intent;
        }
        String lastUser = lastUserQuestion(history);
        if (lastUser == null) return intent;
        ChatReportRanker.Intent previous = ChatReportRanker.classify(lastUser);
        return previous == ChatReportRanker.Intent.GENERAL ? intent : previous;
    }

    private static String lastUserQuestion(List<ChatMessageDTO> history) {
        if (history == null) return null;
        for (int index = history.size() - 1; index >= 0; index--) {
            ChatMessageDTO turn = history.get(index);
            if (turn != null && "user".equals(turn.getRole()) && turn.getContent() != null && !turn.getContent().isBlank()) {
                return turn.getContent();
            }
        }
        return null;
    }

    private static List<ChatMessageDTO> sanitize(List<ChatMessageDTO> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<ChatMessageDTO> clean = new ArrayList<>();
        for (ChatMessageDTO item : history) {
            if (item == null || item.getContent() == null || item.getContent().isBlank()) continue;
            if (!"user".equals(item.getRole()) && !"assistant".equals(item.getRole())) continue;
            ChatMessageDTO copy = new ChatMessageDTO();
            copy.setRole(item.getRole());
            String content = item.getContent().strip();
            copy.setContent(content.length() > 800 ? content.substring(0, 800) : content);
            clean.add(copy);
            if (clean.size() == HISTORY_LIMIT) break;
        }
        return clean;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null ? "" : second;
    }

    private record Passage(
            Long reportId,
            String title,
            String edition,
            String createdAt,
            String heading,
            String body
    ) {
    }
}
