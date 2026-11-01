package com.devabhi.aicodereview.llm;

import com.devabhi.aicodereview.review.port.LlmException;
import com.devabhi.aicodereview.review.port.LlmPort;
import com.devabhi.aicodereview.review.port.LlmReview;
import com.devabhi.aicodereview.review.secrets.SanitizedInput;

/**
 * LlmPort on top of any StructuredModelClient. Invalid output gets exactly one corrective
 * retry; after that the whole review step fails (the engine still reports secret findings).
 */
public final class LlmReviewAdapter implements LlmPort {

    static final int MAX_OUTPUT_TOKENS = 4096;

    static final String CORRECTION = """

            Your previous answer did not match the report_findings schema, so it could not be used. Answer again by calling report_findings exactly once with valid arguments. Do not add any other text.
            """;

    private final StructuredModelClient client;
    private final PromptTemplates prompts;
    private final UsageRecorder usage;
    private final ReviewResponseParser parser = new ReviewResponseParser();

    public LlmReviewAdapter(StructuredModelClient client, PromptTemplates prompts, UsageRecorder usage) {
        this.client = client;
        this.prompts = prompts;
        this.usage = usage;
    }

    public LlmReviewAdapter(StructuredModelClient client) {
        this(client, new PromptTemplates(), UsageRecorder.NOOP);
    }

    @Override
    public LlmReview review(SanitizedInput input) {
        String user = prompts.userMessage(input);
        LlmReview first = attempt(user);
        if (first != null) {
            return first;
        }
        LlmReview second = attempt(user + CORRECTION);
        if (second != null) {
            return second;
        }
        throw new LlmException("model output was invalid after one corrective retry");
    }

    /** One model call; returns null when the output does not match the schema. */
    private LlmReview attempt(String user) {
        ModelReply reply = call(new ModelRequest(prompts.systemPrompt(), user, ReviewTool.NAME,
                ReviewTool.DESCRIPTION, ReviewTool.SCHEMA_JSON, MAX_OUTPUT_TOKENS));
        usage.record("review", reply);
        try {
            return parser.parse(reply.toolInputJson());
        } catch (InvalidModelOutputException e) {
            return null;
        }
    }

    private ModelReply call(ModelRequest request) {
        try {
            ModelReply reply = client.complete(request);
            if (reply == null) {
                throw new LlmException("model returned no reply");
            }
            return reply;
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            // Deliberately not chained: an unexpected exception could carry prompt text.
            throw new LlmException("model call failed (" + e.getClass().getSimpleName() + ")");
        }
    }
}