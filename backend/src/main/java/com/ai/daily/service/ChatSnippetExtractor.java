package com.ai.daily.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 从整份简报里抽出和问题最相关的段落，避免只截标题区和导语。
 */
public final class ChatSnippetExtractor {

    private ChatSnippetExtractor() {
    }

    public static String extract(String content, List<String> keywords, int maxChars) {
        if (content == null || content.isBlank()) return "";
        String text = content.strip();
        int limit = Math.max(80, maxChars);
        if (text.length() <= limit) return text;

        List<String> blocks = split(text);
        if (blocks.size() <= 1 || keywords == null || keywords.isEmpty()) {
            return text.substring(0, limit) + "…";
        }

        List<ScoredBlock> scored = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            int score = score(blocks.get(index), keywords);
            if (score > 0) {
                scored.add(new ScoredBlock(blocks.get(index), score, index));
            }
        }
        if (scored.isEmpty()) {
            return text.substring(0, limit) + "…";
        }

        scored.sort(Comparator.comparingInt(ScoredBlock::score).reversed()
                .thenComparingInt(ScoredBlock::index));
        List<ScoredBlock> chosen = new ArrayList<>();
        int used = 0;
        for (ScoredBlock block : scored) {
            if (used >= limit) break;
            chosen.add(block);
            used += block.text().length();
        }
        chosen.sort(Comparator.comparingInt(ScoredBlock::index));
        String joined = chosen.stream().map(ScoredBlock::text).collect(Collectors.joining("\n\n"));
        return joined.length() > limit ? joined.substring(0, limit) + "…" : joined;
    }

    static List<String> split(String text) {
        String[] parts = text.split("(?m)(?=^#{1,3}\\s+)");
        List<String> blocks = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            for (String paragraph : part.split("\\n\\s*\\n")) {
                String trimmed = paragraph.strip();
                if (trimmed.length() >= 8) {
                    blocks.add(trimmed);
                }
            }
        }
        return blocks;
    }

    private static int score(String block, List<String> keywords) {
        String lower = block.toLowerCase(Locale.ROOT);
        int value = 0;
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) continue;
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                value += keyword.length() >= 4 ? 3 : 2;
            }
        }
        return value;
    }

    private record ScoredBlock(String text, int score, int index) {
    }
}
