package com.devabhi.aicodereview.publish;

import java.util.regex.Pattern;

/**
 * Makes model-written text safe to post on GitHub (PRD section 10, item 7). A zero-width space
 * after @ or # stops GitHub from notifying people or cross-linking issues.
 */
public final class TextSanitizer {

    private static final String ZWSP = "\u200B";

    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:(?:https?|ftp)://|www\\.)\\S+");
    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9_])@(?=[A-Za-z0-9])");
    private static final Pattern ISSUE_REF = Pattern.compile("(?<![A-Za-z0-9&])#(?=\\d)");

    private TextSanitizer() {
    }

    /** For titles, explanations and summaries (rendered as Markdown prose). */
    public static String prose(String text, int maxChars) {
        String s = text == null ? "" : text;
        s = CONTROL.matcher(s).replaceAll("");
        s = IMAGE.matcher(s).replaceAll("[image removed]");
        s = LINK.matcher(s).replaceAll("$1");
        s = URL.matcher(s).replaceAll("[link removed]");
        s = s.replace("<", "&lt;");
        s = MENTION.matcher(s).replaceAll("@" + ZWSP);
        s = ISSUE_REF.matcher(s).replaceAll("#" + ZWSP);
        if (s.length() > maxChars) {
            s = s.substring(0, Math.max(0, maxChars - 1)) + "\u2026";
        }
        return s;
    }

    /**
     * Code goes inside a fenced block and must be shown verbatim, so it is not escaped.
     * Instead the fence is made longer than any backtick run inside the code, so the code
     * can never close the block early.
     */
    public static String fenceFor(String code) {
        int longest = 0;
        int run = 0;
        for (char c : code == null ? new char[0] : code.toCharArray()) {
            if (c == '`') {
                run++;
                longest = Math.max(longest, run);
            } else {
                run = 0;
            }
        }
        return "`".repeat(Math.max(3, longest + 1));
    }
}