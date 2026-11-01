package com.devabhi.aicodereview.llm;

/** The report_findings tool the model is forced to call (schema from PRD 7.5). */
public final class ReviewTool {

    public static final String NAME = "report_findings";

    public static final String DESCRIPTION =
            "Report the defects found in the pull request, plus a short summary of the change.";

    public static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "summary": {"type": "string"},
                "findings": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "file": {"type": "string"},
                      "line": {"type": "integer"},
                      "end_line": {"type": "integer"},
                      "side": {"type": "string", "enum": ["RIGHT"]},
                      "category": {"type": "string",
                        "enum": ["bug", "security", "performance", "maintainability", "test_gap"]},
                      "severity": {"type": "string",
                        "enum": ["critical", "high", "medium", "low", "info"]},
                      "confidence": {"type": "number"},
                      "cwe": {"type": "string"},
                      "title": {"type": "string"},
                      "explanation": {"type": "string"},
                      "evidence": {"type": "string"},
                      "suggested_fix": {
                        "type": "object",
                        "properties": {
                          "description": {"type": "string"},
                          "replacement_code": {"type": "string"}
                        },
                        "required": ["description"]
                      }
                    },
                    "required": ["file", "line", "category", "severity", "confidence",
                                 "title", "explanation", "evidence"]
                  }
                }
              },
              "required": ["summary", "findings"]
            }
            """;

    private ReviewTool() {
    }
}