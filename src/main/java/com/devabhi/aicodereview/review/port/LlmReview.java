package com.devabhi.aicodereview.review.port;

import java.util.List;

import com.devabhi.aicodereview.review.model.RawFinding;

/** What the model returned: a summary plus untrusted findings. */
public record LlmReview(String summary, List<RawFinding> findings) {
}