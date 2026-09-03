package com.ai.daily.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
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

    private static final String H1_STYLE = "margin:0 0 16px;color:#111827;font-size:22px;font-weight:750;line-height:1.3;letter-spacing:-0.02em;";
    private static final String H2_STYLE = "margin:28px 0 10px;padding:0 0 6px;border-bottom:2px solid #e0e7ff;color:#4f46e5;font-size:18px;font-weight:750;line-height:1.35;";
    private static final String H3_STYLE = "margin:18px 0 6px;padding:0 0 0 10px;border-left:3px solid #0d9488;color:#0f766e;font-size:15px;font-weight:700;line-height:1.4;";
    private static final String P_STYLE = "margin:0 0 12px;color:#4b5563;font-size:15px;font-weight:400;line-height:1.8;";
    private static final String LI_STYLE = "margin:0 0 6px;color:#4b5563;font-size:15px;line-height:1.7;";
    private static final Pattern HEADING_LINE = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern LIST_LINE = Pattern.compile("^\\s*(?:[-*+]|\\d+\\.)\\s+(.+)$");

    public static String toSimpleHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        StringBuilder html = new StringBuilder();
        List<String> listItems = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                flushList(html, listItems);
                flushParagraph(html, paragraph);
                continue;
            }
            if (line.matches("-{3,}|\\*{3,}|_{3,}")) {
                flushList(html, listItems);
                flushParagraph(html, paragraph);
                html.append("<hr style=\"border:none;border-top:1px solid #e5e7eb;margin:20px 0;\">");
                continue;
            }
            Matcher heading = HEADING_LINE.matcher(line);
            if (heading.matches()) {
                flushList(html, listItems);
                flushParagraph(html, paragraph);
                int level = Math.min(heading.group(1).length(), 3);
                html.append("<h").append(level).append(" style=\"").append(headingStyle(level)).append("\">")
                        .append(inline(heading.group(2))).append("</h").append(level).append(">");
                continue;
            }
            Matcher list = LIST_LINE.matcher(line);
            if (list.matches()) {
                flushParagraph(html, paragraph);
                listItems.add(list.group(1));
                continue;
            }
            flushList(html, listItems);
            if (!paragraph.isEmpty()) paragraph.append("<br>");
            paragraph.append(inline(line));
        }
        flushList(html, listItems);
        flushParagraph(html, paragraph);
        return html.toString();
    }

    private static String headingStyle(int level) {
        if (level <= 1) return H1_STYLE;
        if (level == 2) return H2_STYLE;
        return H3_STYLE;
    }

    private static void flushList(StringBuilder html, List<String> items) {
        if (items.isEmpty()) return;
        html.append("<ul style=\"margin:0 0 14px;padding-left:20px;\">");
        for (String item : items) {
            html.append("<li style=\"").append(LI_STYLE).append("\">").append(inline(item)).append("</li>");
        }
        html.append("</ul>");
        items.clear();
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.isEmpty()) return;
        html.append("<p style=\"").append(P_STYLE).append("\">").append(paragraph).append("</p>");
        paragraph.setLength(0);
    }

    private static String inline(String text) {
        String escaped = escape(text);
        escaped = escaped.replaceAll("\\*\\*(.+?)\\*\\*", "<strong style=\"color:#1f2937;font-weight:700;\">$1</strong>");
        escaped = escaped.replaceAll("__(.+?)__", "<strong style=\"color:#1f2937;font-weight:700;\">$1</strong>");
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
