package com.ai.daily.util;

import java.util.regex.Pattern;

public final class MarkdownUtils {

    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]*)`");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]*\\)");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*");
    private static final Pattern BOLD_ITALIC = Pattern.compile("(\\*\\*|__|\\*|_)(.+?)\\1");
    private static final Pattern BLOCKQUOTE = Pattern.compile("(?m)^\\s*>\\s?");
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*(?:[-*+]|\\d+\\.)\\s+");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("(?m)^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_WHITESPACE = Pattern.compile("\\s+");

    private MarkdownUtils() {}

    public static String stripToPlainText(String markdown, int maxLen) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String s = markdown;
        s = CODE_BLOCK.matcher(s).replaceAll(" ");
        s = IMAGE.matcher(s).replaceAll(" ");
        s = LINK.matcher(s).replaceAll("$1");
        s = INLINE_CODE.matcher(s).replaceAll("$1");
        s = HORIZONTAL_RULE.matcher(s).replaceAll(" ");
        s = HEADING.matcher(s).replaceAll("");
        s = BLOCKQUOTE.matcher(s).replaceAll("");
        s = LIST_MARKER.matcher(s).replaceAll("");
        s = BOLD_ITALIC.matcher(s).replaceAll("$2");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = MULTI_WHITESPACE.matcher(s).replaceAll(" ").trim();

        if (maxLen > 0 && s.length() > maxLen) {
            s = s.substring(0, maxLen) + "…";
        }
        return s;
    }

    public static String toSimpleHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        String[] blocks = markdown.replace("\r\n", "\n").split("\n\n");
        StringBuilder html = new StringBuilder();
        for (String rawBlock : blocks) {
            String block = rawBlock.strip();
            if (block.isEmpty()) continue;
            if (block.matches("(?s)-{3,}|\\*{3,}|_{3,}")) {
                html.append("<hr>");
                continue;
            }
            if (block.startsWith("### ")) {
                html.append("<h3>").append(inline(block.substring(4))).append("</h3>");
                continue;
            }
            if (block.startsWith("## ")) {
                html.append("<h2>").append(inline(block.substring(3))).append("</h2>");
                continue;
            }
            if (block.startsWith("# ")) {
                html.append("<h1>").append(inline(block.substring(2))).append("</h1>");
                continue;
            }
            String[] lines = block.split("\n");
            boolean list = lines.length > 0 && lines[0].matches("\\s*(?:[-*+]|\\d+\\.)\\s+.*");
            if (list) {
                html.append("<ul>");
                for (String line : lines) {
                    html.append("<li>").append(inline(line.replaceFirst("\\s*(?:[-*+]|\\d+\\.)\\s+", ""))).append("</li>");
                }
                html.append("</ul>");
                continue;
            }
            html.append("<p>").append(inline(block.replace("\n", "<br>"))).append("</p>");
        }
        return html.toString();
    }

    private static String inline(String text) {
        String escaped = escape(text);
        escaped = escaped.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        escaped = escaped.replaceAll("__(.+?)__", "<strong>$1</strong>");
        escaped = escaped.replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "<em>$1</em>");
        escaped = escaped.replaceAll("`([^`]+)`", "<code>$1</code>");
        escaped = escaped.replaceAll(
                "\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)",
                "<a href=\"$2\" target=\"_blank\" rel=\"noopener noreferrer\">$1</a>");
        return escaped;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
