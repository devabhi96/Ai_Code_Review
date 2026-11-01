package com.devabhi.aicodereview.review.pipeline;

import java.util.List;

import com.devabhi.aicodereview.review.filter.SkippedFile;
import com.devabhi.aicodereview.review.model.DroppedFinding;
import com.devabhi.aicodereview.review.model.Finding;
import com.devabhi.aicodereview.review.secrets.SecretFinding;

/**
 * Result of one review. summary is model text and still UNTRUSTED: sanitize before publishing.
 * llmError is set when the model step failed; secretFindings are then still valid.
 */
public record ReviewOutcome(String summary, List<Finding> findings, List<DroppedFinding> dropped,
                            List<SecretFinding> secretFindings, List<SkippedFile> skipped,
                            String llmError) {
}