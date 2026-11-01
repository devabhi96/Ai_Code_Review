package com.devabhi.aicodereview.llm;

/** What came back: the tool arguments as JSON text, plus usage numbers for cost tracking. */
public record ModelReply(String toolInputJson, String model, int inputTokens, int outputTokens,
                         long latencyMs) {
}