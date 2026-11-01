package com.devabhi.aicodereview.review.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a parsed patch for the model. Added and context lines carry their new-file
 * line number; removed lines carry none, so nothing can be anchored to them.
 */
public final class NumberedDiffFormatter {

    private static final String BLANK_NUMBER = "    ";

    public String format(ParsedPatch patch) {
        List<String> out = new ArrayList<>();
        for (Hunk h : patch.hunks()) {
            String header = String.format("@@ -%d,%d +%d,%d @@",
                    h.oldStart(), h.oldCount(), h.newStart(), h.newCount());
            if (h.sectionHeading() != null && !h.sectionHeading().isBlank()) {
                header += " " + h.sectionHeading();
            }
            out.add(header);
            for (DiffLine l : h.lines()) {
                out.add(switch (l.type()) {
                    case ADDED -> row('+', number(l.newLine()), l.text());
                    case CONTEXT -> row(' ', number(l.newLine()), l.text());
                    case REMOVED -> row('-', BLANK_NUMBER, l.text());
                });
            }
        }
        return String.join("\n", out);
    }

    private static String number(int n) {
        return String.format("%4d", n);
    }

    private static String row(char prefix, String number, String text) {
        return prefix + " " + number + " | " + text;
    }
}