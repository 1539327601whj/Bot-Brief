package com.ai.daily.service.push;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把简报标题和章节标题改成各渠道能看清的层级，避免标题和正文长得一样。
 */
public final class PushReportFormat {

    enum Kind { H1, H2, H3, HR, OTHER }

    record Line(Kind kind, String text, String raw) {}

    private PushReportFormat() {}

    public static String bodyWithoutLeadTitle(String title, String content) {
        StringBuilder out = new StringBuilder();
        boolean leading = true;
        for (Line line : parse(content)) {
            if (leading && (line.kind == Kind.HR || isBlank(line) || isLeadTitle(line, title))) {
                continue;
            }
            leading = false;
            out.append(line.raw).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    public static String wecomMarkdown(String title, String content) {
        StringBuilder out = new StringBuilder();
        out.append("<font color=\"warning\">**").append(plain(title)).append("**</font>\n");
        boolean leading = true;
        for (Line line : parse(content)) {
            if (leading && (line.kind == Kind.HR || isBlank(line) || isLeadTitle(line, title))) {
                continue;
            }
            leading = false;
            switch (line.kind) {
                case H1, H2 -> out.append("\n> <font color=\"warning\">**").append(plain(line.text)).append("**</font>\n");
                case H3 -> out.append("\n<font color=\"info\">**").append(plain(line.text)).append("**</font>\n");
                case HR -> out.append('\n');
                case OTHER -> out.append(line.raw).append('\n');
            }
        }
        return compact(out.toString());
    }

    public static String dingtalkMarkdown(String title, String content) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(plain(title)).append('\n');
        boolean leading = true;
        for (Line line : parse(content)) {
            if (leading && (line.kind == Kind.HR || isBlank(line) || isLeadTitle(line, title))) {
                continue;
            }
            leading = false;
            switch (line.kind) {
                case H1, H2 -> out.append("\n## ").append(plain(line.text)).append('\n');
                case H3 -> out.append("\n### ").append(plain(line.text)).append('\n');
                case HR -> out.append("\n---\n");
                case OTHER -> out.append(line.raw).append('\n');
            }
        }
        return compact(out.toString());
    }

    public static String feishuMarkdown(String title, String content) {
        StringBuilder out = new StringBuilder();
        boolean leading = true;
        for (Line line : parse(content)) {
            if (leading && (line.kind == Kind.HR || isBlank(line) || isLeadTitle(line, title))) {
                continue;
            }
            leading = false;
            switch (line.kind) {
                case H1, H2 -> out.append("\n**▎ ").append(plain(line.text)).append("**\n");
                case H3 -> out.append("\n**· ").append(plain(line.text)).append("**\n");
                case HR -> out.append('\n');
                case OTHER -> out.append(line.raw).append('\n');
            }
        }
        return compact(out.toString());
    }

    public static String feishuHeaderTemplate(String edition) {
        if (edition == null) return "blue";
        return switch (edition) {
            case "morning" -> "orange";
            case "evening" -> "indigo";
            case "market_watch", "market_watch_evening" -> "turquoise";
            default -> "blue";
        };
    }

    static List<Line> parse(String content) {
        List<Line> lines = new ArrayList<>();
        if (content == null || content.isBlank()) return lines;
        for (String raw : content.replace("\r\n", "\n").split("\n", -1)) {
            String stripped = raw.strip();
            if (isNonReportMeta(stripped)) {
                continue;
            }
            if (stripped.matches("-{3,}|\\*{3,}|_{3,}")) {
                lines.add(new Line(Kind.HR, "", raw));
                continue;
            }
            if (stripped.matches("(?i)^#{1,6}\\s+.+")) {
                int hashes = 0;
                while (hashes < stripped.length() && stripped.charAt(hashes) == '#') hashes++;
                String text = stripped.substring(hashes).strip();
                Kind kind = hashes <= 1 ? Kind.H1 : hashes == 2 ? Kind.H2 : Kind.H3;
                lines.add(new Line(kind, text, raw));
                continue;
            }
            if (stripped.matches("^>\\s*\\*\\*.+\\*\\*\\s*$")) {
                String text = stripped.replaceAll("^>\\s*\\*\\*", "").replaceAll("\\*\\*\\s*$", "").strip();
                lines.add(new Line(Kind.H1, text, raw));
                continue;
            }
            lines.add(new Line(Kind.OTHER, raw, raw));
        }
        return lines;
    }

    static boolean isNonReportMeta(String text) {
        if (text == null || text.isBlank()) return false;
        if (text.contains("ETF_DATA_REFRESH") || text.contains("<!--")) return true;
        if (text.contains("仅作研究线索") || text.contains("候选基于公开量价")) return true;
        return text.contains("数据说明：") && text.contains("不构成投资建议");
    }

    private static boolean isLeadTitle(Line line, String title) {
        if (line.kind != Kind.H1 && line.kind != Kind.H2) return false;
        String left = normalize(line.text);
        String right = normalize(title);
        return !left.isEmpty() && left.equals(right);
    }

    private static boolean isBlank(Line line) {
        return line.kind == Kind.OTHER && line.raw.strip().isEmpty();
    }

    private static String plain(String text) {
        if (text == null) return "";
        String value = text.strip()
                .replaceFirst("^📋\\s*", "")
                .replaceAll("^\\*\\*(.+?)\\*\\*$", "$1");
        return value.replace("<", "＜").replace(">", "＞");
    }

    private static String normalize(String text) {
        return plain(text).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String compact(String text) {
        return text.replaceAll("\n{3,}", "\n\n").strip();
    }
}
