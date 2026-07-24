package com.ai.daily.service;

import com.ai.daily.entity.Report;
import com.ai.daily.util.MarkdownUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReportPersonalizationService {

    private static final Pattern SECTION_HEADING = Pattern.compile("(?m)^##\\s+.+$");
    private static final Pattern ASCII_WORD = Pattern.compile("^[a-z0-9][a-z0-9+.#/-]*$", Pattern.CASE_INSENSITIVE);
    private static final int SUMMARY_LENGTH = 160;

    private static final Map<String, List<String>> PRESET_ALIASES = Map.ofEntries(
            Map.entry("AI大模型", List.of("人工智能", "大模型", "LLM", "GPT", "Claude", "Gemini", "DeepSeek", "Qwen")),
            Map.entry("Web开发", List.of("Web", "前端", "后端", "浏览器", "JavaScript", "TypeScript", "React", "Vue", "Spring", "API")),
            Map.entry("移动端", List.of("移动端", "Android", "iOS", "Flutter", "React Native")),
            Map.entry("云原生", List.of("云原生", "Kubernetes", "K8s", "容器", "Docker", "Serverless")),
            Map.entry("数据库", List.of("数据库", "MySQL", "PostgreSQL", "Redis", "向量数据库")),
            Map.entry("安全", List.of("安全", "漏洞", "攻击", "隐私", "鉴权", "供应链安全")),
            Map.entry("DevOps", List.of("DevOps", "CI/CD", "GitHub Actions", "部署", "可观测性")),
            Map.entry("数据分析", List.of("数据分析", "数据工程", "BI", "分析平台")),
            Map.entry("机器学习", List.of("机器学习", "深度学习", "训练", "推理", "MLOps")),
            Map.entry("区块链", List.of("区块链", "Web3", "智能合约", "加密货币"))
    );

    public PreparedReport prepare(Report canonical) {
        if (canonical == null || canonical.getContent() == null) return new PreparedReport(canonical, "", List.of());
        Matcher matcher = SECTION_HEADING.matcher(canonical.getContent());
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) starts.add(matcher.start());
        if (starts.isEmpty()) return new PreparedReport(canonical, canonical.getContent(), List.of());

        String preamble = canonical.getContent().substring(0, starts.get(0)).stripTrailing();
        List<PreparedSection> sections = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            int end = index + 1 < starts.size() ? starts.get(index + 1) : canonical.getContent().length();
            String markdown = canonical.getContent().substring(starts.get(index), end);
            sections.add(new PreparedSection(markdown, MarkdownUtils.stripToPlainText(markdown, 0).toLowerCase(Locale.ROOT)));
        }
        return new PreparedReport(canonical, preamble, sections);
    }

    public Report personalize(Report canonical, List<String> interests) {
        return personalize(prepare(canonical), interests);
    }

    public Report personalize(PreparedReport prepared, List<String> interests) {
        Report canonical = prepared.canonical();
        if (canonical == null || interests == null || interests.isEmpty() || prepared.sections().isEmpty()) return canonical;

        Set<Integer> matches = new LinkedHashSet<>();
        for (String interest : interests) {
            if (interest == null || interest.isBlank()) continue;
            List<String> terms = PRESET_ALIASES.getOrDefault(interest, List.of(interest));
            for (int index = 0; index < prepared.sections().size(); index++) {
                if (matches(prepared.sections().get(index).plainText(), terms)) matches.add(index);
            }
        }
        if (matches.isEmpty()) return canonical;

        StringBuilder content = new StringBuilder(prepared.preamble());
        for (Integer index : matches.stream().sorted().toList()) {
            if (content.length() > 0 && !endsWithBlankLine(content)) content.append("\n\n");
            content.append(prepared.sections().get(index).markdown().strip());
        }
        String filtered = content.toString().stripTrailing() + "\n";
        Report personalized = copy(canonical);
        personalized.setContent(filtered);
        personalized.setSummary(MarkdownUtils.stripToPlainText(filtered, SUMMARY_LENGTH));
        return personalized;
    }

    private boolean matches(String text, List<String> terms) {
        for (String term : terms) {
            String normalized = term.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) continue;
            if (ASCII_WORD.matcher(normalized).matches()) {
                Pattern word = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(normalized) + "(?![a-z0-9])", Pattern.CASE_INSENSITIVE);
                if (word.matcher(text).find()) return true;
            } else if (text.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean endsWithBlankLine(StringBuilder content) {
        int length = content.length();
        return length >= 2 && content.charAt(length - 1) == '\n' && content.charAt(length - 2) == '\n';
    }

    private Report copy(Report source) {
        Report copy = new Report();
        copy.setId(source.getId());
        copy.setEdition(source.getEdition());
        copy.setTitle(source.getTitle());
        copy.setContent(source.getContent());
        copy.setSummary(source.getSummary());
        copy.setRunId(source.getRunId());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    public record PreparedReport(Report canonical, String preamble, List<PreparedSection> sections) {}

    public record PreparedSection(String markdown, String plainText) {}
}
