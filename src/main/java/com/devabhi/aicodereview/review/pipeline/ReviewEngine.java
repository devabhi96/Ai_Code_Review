package com.devabhi.aicodereview.review.pipeline;

import java.util.ArrayList;
import java.util.List;

import com.devabhi.aicodereview.review.diff.DiffParser;
import com.devabhi.aicodereview.review.diff.ParsedPatch;
import com.devabhi.aicodereview.review.filter.ChangedFile;
import com.devabhi.aicodereview.review.filter.FileFilter;
import com.devabhi.aicodereview.review.filter.FilterResult;
import com.devabhi.aicodereview.review.filter.SkippedFile;
import com.devabhi.aicodereview.review.port.LlmException;
import com.devabhi.aicodereview.review.port.LlmPort;
import com.devabhi.aicodereview.review.port.LlmReview;
import com.devabhi.aicodereview.review.secrets.FileInput;
import com.devabhi.aicodereview.review.secrets.SanitizedInput;
import com.devabhi.aicodereview.review.secrets.SecretGate;
import com.devabhi.aicodereview.review.validate.FindingValidator;
import com.devabhi.aicodereview.review.validate.ValidationResult;

/** Filter, parse, redact, ask the model, validate. No GitHub, no database, no network of its own. */
public final class ReviewEngine {

    static final String REASON_UNPARSEABLE = "diff could not be parsed";
    static final String REASON_EMPTY = "empty diff";

    private final LlmPort llm;
    private final SecretGate gate = new SecretGate();
    private final FileFilter filter = new FileFilter();
    private final FindingValidator validator = new FindingValidator();
    private final DiffParser parser = new DiffParser();

    public ReviewEngine(LlmPort llm) {
        this.llm = llm;
    }

    public ReviewOutcome review(ReviewRequest request) {
        FilterResult filtered = filter.filter(request.files(), request.ignoreGlobs());
        List<SkippedFile> skipped = new ArrayList<>(filtered.skipped());
        List<FileInput> inputs = new ArrayList<>();

        for (ChangedFile file : filtered.reviewable()) {
            try {
                ParsedPatch patch = parser.parse(file.patch());
                if (patch.hunks().isEmpty()) {
                    skipped.add(new SkippedFile(file.filename(), REASON_EMPTY));
                } else {
                    inputs.add(new FileInput(file.filename(), patch, request.contextOf(file.filename())));
                }
            } catch (IllegalArgumentException e) {
                skipped.add(new SkippedFile(file.filename(), REASON_UNPARSEABLE));
            }
        }
        if (inputs.isEmpty()) {
            return new ReviewOutcome(null, List.of(), List.of(), List.of(), List.copyOf(skipped), null);
        }

        // The hard gate: everything below this line only ever sees redacted text.
        SanitizedInput sanitized = gate.sanitize(request.title(), request.description(), inputs);

        if (!request.llmEnabled()) {
            return new ReviewOutcome(null, List.of(), List.of(), sanitized.secretFindings(),
                    List.copyOf(skipped), null);
        }
        try {
            LlmReview review = llm.review(sanitized);
            if (review == null) {
                throw new LlmException("model returned no result");
            }
            ValidationResult validated = validator.validate(review.findings(), sanitized);
            return new ReviewOutcome(review.summary(), validated.accepted(), validated.dropped(),
                    sanitized.secretFindings(), List.copyOf(skipped), null);
        } catch (LlmException e) {
            return new ReviewOutcome(null, List.of(), List.of(), sanitized.secretFindings(),
                    List.copyOf(skipped), e.getMessage());
        }
    }
}