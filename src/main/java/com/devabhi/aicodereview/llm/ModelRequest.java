package com.devabhi.aicodereview.llm;

/** Provider-neutral request: one system prompt, one user message, one forced tool. */
public record ModelRequest(String system, String user, String toolName, String toolDescription,
                           String toolSchemaJson, int maxOutputTokens) {
}