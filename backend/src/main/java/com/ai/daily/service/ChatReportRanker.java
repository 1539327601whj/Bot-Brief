package com.ai.daily.service;

import com.ai.daily.entity.Report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话检索：按问题意图给简报打分，避免科技问答被最近的 ETF 日报顶掉。
 */
public final class ChatReportRanker {

    enum Intent { TECH, MARKET, GENERAL }

    private static final Pattern ENGLISH = Pattern.compile("[a-z][a-z0-9+\\-]{1,}");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]{2,8}");

    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "是", "在", "有", "和", "与", "或", "等", "吗", "呢", "吧", "啊",
            "请问", "一下", "什么", "怎么", "哪些", "最近", "今日", "今天", "有没有",
            "相关", "信息", "内容", "这个", "那个", "如何", "能否", "可否", "告诉", "我"
    );

    private static final String[] TECH_HINTS = {
            "ai", "gpt", "openai", "claude", "gemini", "deepseek", "llm", "rag", "agent",
            "大模型", "科技", "早报", "晚报", "开源", "芯片", "英伟达", "智谱", "通义",
            "文心", "kimi", "anthropic", "模型发布", "人工智能", "安全", "漏洞", "项目"
    };

    private static final String[] MARKET_HINTS = {
            "etf", "估值", "沪深", "纳指", "溢价", "行情", "仓位", "510300", "513100",
            "a股", "指数", "pe分位", "分位", "少买", "多买"
    };

    private static final String[] TECH_KEYWORDS = {
            "ai", "gpt", "openai", "claude", "gemini", "deepseek", "llm", "rag", "agent",
            "大模型", "模型", "科技", "早报", "晚报", "简报", "开源", "发布", "更新",
            "版本", "芯片", "英伟达", "智谱", "通义", "文心", "kimi", "anthropic",
            "人工智能", "智能体"
    };

    private static final String[] MARKET_KEYWORDS = {
            "etf", "估值", "沪深", "纳指", "溢价", "行情", "仓位", "510300", "513100",
            "a股", "指数", "分位"
    };

    private ChatReportRanker() {
    }

    static Intent classify(String question) {
        String text = normalize(question);
        int tech = countHints(text, TECH_HINTS);
        int market = countHints(text, MARKET_HINTS);
        if (tech > market && tech > 0) return Intent.TECH;
        if (market > tech && market > 0) return Intent.MARKET;
        return Intent.GENERAL;
    }

    static List<String> extractKeywords(String question) {
        String text = normalize(question);
        Set<String> keywords = new LinkedHashSet<>();
        addContained(text, TECH_KEYWORDS, keywords);
        addContained(text, MARKET_KEYWORDS, keywords);

        Matcher english = ENGLISH.matcher(text);
        while (english.find()) {
            keywords.add(english.group());
        }

        Matcher runs = CJK_RUN.matcher(question == null ? "" : question);
        while (runs.find()) {
            String run = runs.group();
            if (run.length() >= 2 && run.length() <= 6 && !STOPWORDS.contains(run)) {
                keywords.add(run);
            }
        }
        keywords.removeIf(word -> word.length() < 2 || STOPWORDS.contains(word));
        return new ArrayList<>(keywords);
    }

    public static List<Report> select(String question, List<Report> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Intent intent = classify(question);
        List<String> keywords = extractKeywords(question);

        List<Scored> scored = new ArrayList<>();
        for (Report report : candidates) {
            int score = score(report, intent, keywords);
            if (score > 0) {
                scored.add(new Scored(report, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(item -> item.report().getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder())));

        List<Report> matched = scored.stream().limit(5).map(Scored::report).toList();
        if (!matched.isEmpty()) return matched;
        return fallback(intent, candidates);
    }

    static int score(Report report, Intent intent, List<String> keywords) {
        boolean techEdition = isTechEdition(report.getEdition());
        boolean marketEdition = isMarketEdition(report.getEdition());
        int value = 0;
        String title = safe(report.getTitle());
        String summary = safe(report.getSummary());
        String content = safe(report.getContent());
        for (String keyword : keywords) {
            if (title.contains(keyword)) value += 8;
            else if (summary.contains(keyword)) value += 3;
            else if (content.contains(keyword)) value += 1;
        }
        if (intent == Intent.TECH && techEdition) value += 16;
        if (intent == Intent.TECH && marketEdition) value -= 40;
        if (intent == Intent.MARKET && marketEdition) value += 16;
        if (intent == Intent.MARKET && techEdition) value -= 40;
        return value;
    }

    static boolean isTechEdition(String edition) {
        return Report.isPublicDigest(edition) || Report.isPersonalizedEdition(edition);
    }

    static boolean isMarketEdition(String edition) {
        return Report.isSharedPublicEdition(edition);
    }

    private static List<Report> fallback(Intent intent, List<Report> candidates) {
        return candidates.stream()
                .filter(report -> switch (intent) {
                    case TECH -> isTechEdition(report.getEdition());
                    case MARKET -> isMarketEdition(report.getEdition());
                    case GENERAL -> true;
                })
                .sorted(Comparator.comparing(Report::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .toList();
    }

    private static int countHints(String text, String[] hints) {
        int count = 0;
        for (String hint : hints) {
            if (text.contains(hint)) count++;
        }
        return count;
    }

    private static void addContained(String text, String[] terms, Set<String> keywords) {
        for (String term : terms) {
            if (text.contains(term)) keywords.add(term);
        }
    }

    private static String normalize(String question) {
        return question == null ? "" : question.toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record Scored(Report report, int score) {
    }
}
