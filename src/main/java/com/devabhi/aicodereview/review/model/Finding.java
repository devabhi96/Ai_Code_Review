package com.devabhi.aicodereview.review.model;

/** A finding that passed validation: its anchor is a real RIGHT-side line (or range) in the diff. */
public record Finding(String file, int line, int endLine, Category category, Severity severity,
                      double confidence, String cwe, String title, String explanation,
                      String evidence, SuggestedFix suggestedFix) {
}