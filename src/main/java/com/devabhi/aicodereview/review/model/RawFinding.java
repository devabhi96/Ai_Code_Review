package com.devabhi.aicodereview.review.model;

/**
 * One finding exactly as the model reported it. UNTRUSTED: every field may be null, out of
 * range or invented. Only FindingValidator turns a RawFinding into a Finding.
 */
public record RawFinding(String file, Integer line, Integer endLine, String side,
                         String category, String severity, Double confidence, String cwe,
                         String title, String explanation, String evidence,
                         SuggestedFix suggestedFix) {
}