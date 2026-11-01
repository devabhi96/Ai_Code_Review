package com.devabhi.aicodereview.review.diff;

/** One line of a hunk. oldLine is null for ADDED, newLine is null for REMOVED. */
public record DiffLine(LineType type, Integer oldLine, Integer newLine, String text) {
}