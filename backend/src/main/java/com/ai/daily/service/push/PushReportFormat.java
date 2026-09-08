package com.ai.daily.service.push;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把简报标题和章节标题改成各渠道能看清的层级，避免标题和正文长得一样。
 */
public final class PushReportFormat {

    enum Kind { H1, H2, H3, HR, OTHER }

    record Line(Kind kind, String text, String raw) {}

    private static final Pattern CHANGE_TOKEN = Pattern.compile(
            "(?<![.\\d])((?:[↑↓]\\s*)?(?:\\+[\\d.]+(?:%|pt|点)|-[\\d.]+(?:%|pt|点)|0(?:\\.00)?(?:%|pt|点)))(?!\\d)");
    private static final Pattern SECTION_LABEL = Pattern.compile("\\*\\*([^*\\n]{1,20})[：:]\\*\\*");

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
        return wecomMarkdown(title, content, 0);
    }

    public static String wecomMarkdown(String title, String content, int maxBytes) {
        String rendered = renderWecom(title, content, false);
        if (maxBytes <= 0 || PushContentLimits.utf8Bytes(rendered) <= maxBytes) {
            return rendered;
        }
        rendered = renderWecom(title, content, true);
        if (PushContentLimits.utf8Bytes(rendered) <= maxBytes) {
            return rendered;
        }
        return PushContentLimits.truncateToBytes(rendered, maxBytes);
    }

    private static String renderWecom(String title, String content, boolean tightAShare) {
        StringBuilder out = new StringBuilder();
        out.append("<font color=\"warning\">**").append(plain(title)).append("**</font>\n");
        boolean leading = true;
        String section = "";
        for (Line line : parse(content)) {
            if (leading && (line.kind == Kind.HR || isBlank(line) || isLeadTitle(line, title) || line.kind == Kind.H1)) {
                continue;
            }
            leading = false;
            switch (line.kind) {
                case H1, H2 -> {
                    section = plain(line.text);
                    out.append("\n> <font color=\"warning\">**").append(section).append("**</font>\n");
                }
                case H3 -> out.append("\n<font color=\"info\">**").append(plain(line.text)).append("**</font>\n");
                case HR -> out.append('\n');
                case OTHER -> {
                    String text = line.raw;
                    if (section.contains("A股观察")) {
                        text = compactAShareBullet(text, tightAShare);
                    }
                    out.append(colorizeChanges(text, "warning", "info", "comment")).append('\n');
                }
            }
        }
        return compact(out.toString());
    }

    static String compactAShareBullet(String line, boolean tight) {
        if (line == null || line.isBlank()) return line;
        String text = line
                .replace("动态PE ", "PE ")
                .replace("亿元", "亿")
                .replace("万元", "万")
                .replace("主力净流入", "主力流入");
        text = shortenTrendClause(text);
        text = text.replace("风险：机械筛选未覆盖基本面、公告和行业事件。", "风险：未覆盖基本面与公告。");
        if (tight) {
            text = text.replaceAll("趋势：[^。]*。\\s*", "");
            text = text.replaceAll("风险：.*$", "").stripTrailing();
        }
        return text;
    }

    static String shortenTrendClause(String line) {
        String label = "趋势：";
        int start = line.indexOf(label);
        if (start < 0) return line;
        int from = start + label.length();
        int risk = line.indexOf("风险：", from);
        int end = risk >= 0 ? risk : line.length();
        String body = line.substring(from, end).strip();
        int sep = body.indexOf('；');
        if (sep > 0) {
            body = body.substring(0, sep) + "。";
        }
        StringBuilder out = new StringBuilder(line.substring(0, from)).append(body);
        if (risk >= 0) {
            if (!body.endsWith("。") && !body.endsWith(" ")) out.append(' ');
            out.append(line.substring(risk));
        }
        return out.toString();
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
        boolean started = false;
        for (Line line : parse(content)) {
            if (leading && (line.kind == Kind.HR || isBlank(line) || isLeadTitle(line, title))) {
                continue;
            }
            leading = false;
            switch (line.kind) {
                case H1 -> {
                    if (started) out.append("\n<hr>\n");
                    out.append('\n').append(feishuHeading("indigo", "▎ ", line.text)).append('\n');
                    started = true;
                }
                case H2 -> {
                    if (started) out.append("\n<hr>\n");
                    out.append('\n').append(feishuHeading("blue", "▎ ", line.text)).append('\n');
                    started = true;
                }
                case H3 -> {
                    out.append('\n').append(feishuHeading("orange", "", line.text)).append('\n');
                    started = true;
                }
                case HR -> out.append('\n');
                case OTHER -> {
                    out.append(colorizeFeishuLine(line.raw)).append('\n');
                    started = true;
                }
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

    static String colorizeWecomChanges(String text) {
        return colorizeChanges(text, "warning", "info", "comment");
    }

    static String colorizeFeishuLine(String text) {
        return colorizeChanges(colorizeFeishuLabels(text), "red", "green", "grey");
    }

    static String colorizeFeishuLabels(String text) {
        if (text == null || text.isEmpty()) return text;
        return SECTION_LABEL.matcher(text).replaceAll("<text_tag color='violet'>$1</text_tag>");
    }

    static String colorizeChanges(String text, String upColor, String downColor, String flatColor) {
        if (text == null || text.isEmpty()) return text;
        Matcher matcher = CHANGE_TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            String body = token.replace("↑", "").replace("↓", "").replace(" ", "");
            String color = body.startsWith("+") ? upColor : body.startsWith("-") ? downColor : flatColor;
            matcher.appendReplacement(out, Matcher.quoteReplacement("<font color=\"" + color + "\">" + token + "</font>"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String feishuHeading(String color, String prefix, String text) {
        return "<font color=\"" + color + "\">**" + prefix + plain(text) + "**</font>";
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
