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
}
