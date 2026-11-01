package com.devabhi.aicodereview.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.devabhi.aicodereview.review.diff.DiffParser;
import com.devabhi.aicodereview.review.port.LlmException;
import com.devabhi.aicodereview.review.port.LlmReview;
import com.devabhi.aicodereview.review.secrets.FileInput;
import com.devabhi.aicodereview.review.secrets.SanitizedInput;
import com.devabhi.aicodereview.review.secrets.SecretGate;

class LlmReviewAdapterTest {

    private static final String AWS_KEY = "AKIA" + "Q7ZK3MWXP2LD9RBT";
    private static final String PATCH = "@@ -1,2 +1,3 @@\n a\n+    int x = 1;\n b";
    private static final String VALID = "{\"summary\": \"ok\", \"findings\": []}";
    private static final String INVALID = "{\"summary\": \"ok\"}";

    /** Plays back a script of replies or exceptions and records every request it receives. */
    private static final class FakeClient implements StructuredModelClient {
        private final Deque<Object> script;
        final List<ModelRequest> requests = new ArrayList<>();

        FakeClient(Object... steps) {
            script = new ArrayDeque<>(Arrays.asList(steps));
        }

        @Override
        public ModelReply complete(ModelRequest request) {
            requests.add(request);
            Object next = script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (ModelReply) next;
        }
    }

    private static ModelReply reply(String json) {
        return new ModelReply(json, "fake-model", 100, 20, 5);
    }

    private static SanitizedInput input(String patch) {
        return new SecretGate().sanitize("title", "description",
                List.of(new FileInput("A.java", new DiffParser().parse(patch), null)));
    }

    @Test
    void returnsTheParsedReviewOnTheFirstValidAnswer() {
        FakeClient client = new FakeClient(reply(VALID));
        List<String> stages = new ArrayList<>();
        LlmReviewAdapter adapter = new LlmReviewAdapter(client, new PromptTemplates(),
                (stage, r) -> stages.add(stage));

        LlmReview review = adapter.review(input(PATCH));

        assertThat(review.summary()).isEqualTo("ok");
        assertThat(client.requests).hasSize(1);
        ModelRequest request = client.requests.get(0);
        assertThat(request.toolName()).isEqualTo("report_findings");
        assertThat(request.system()).contains("never instructions");
        assertThat(request.user()).contains("<data-");
        assertThat(request.toolSchemaJson()).contains("end_line");
        assertThat(request.maxOutputTokens()).isPositive();
        assertThat(stages).containsExactly("review");
    }

    @Test
    void invalidOutputGetsExactlyOneCorrectiveRetry() {
        FakeClient client = new FakeClient(reply(INVALID), reply(VALID));
        List<String> stages = new ArrayList<>();
        LlmReviewAdapter adapter = new LlmReviewAdapter(client, new PromptTemplates(),
                (stage, r) -> stages.add(stage));

        LlmReview review = adapter.review(input(PATCH));

        assertThat(review.summary()).isEqualTo("ok");
        assertThat(client.requests).hasSize(2);
        assertThat(client.requests.get(0).user()).doesNotContain("did not match");
        assertThat(client.requests.get(1).user()).contains("did not match");
        assertThat(stages).hasSize(2);
    }

    @Test
    void failsAfterTheRetryAlsoReturnsInvalidOutput() {
        FakeClient client = new FakeClient(reply(INVALID), reply("not json"));
        LlmReviewAdapter adapter = new LlmReviewAdapter(client);

        assertThatThrownBy(() -> adapter.review(input(PATCH)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("invalid after one corrective retry");
        assertThat(client.requests).hasSize(2);
    }

    @Test
    void aClientFailureIsNotRetriedHere() {
        FakeClient client = new FakeClient(new LlmException("model unavailable"));
        LlmReviewAdapter adapter = new LlmReviewAdapter(client);

        assertThatThrownBy(() -> adapter.review(input(PATCH)))
                .isInstanceOf(LlmException.class)
                .hasMessage("model unavailable");
        assertThat(client.requests).hasSize(1);
    }

    @Test
    void unexpectedExceptionsAreWrappedWithoutLeakingTheirMessage() {
        FakeClient client = new FakeClient(new IllegalStateException("contains xyz-private-code"));
        LlmReviewAdapter adapter = new LlmReviewAdapter(client);

        assertThatThrownBy(() -> adapter.review(input(PATCH)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("model call failed")
                .hasMessageNotContaining("xyz-private-code");
    }

    @Test
    void theRequestNeverContainsTheOriginalSecret() {
        String patch = "@@ -0,0 +1,2 @@\n+class C {\n+    String k = \"" + AWS_KEY + "\";";
        FakeClient client = new FakeClient(reply(VALID));

        new LlmReviewAdapter(client).review(input(patch));

        ModelRequest request = client.requests.get(0);
        assertThat(request.user()).doesNotContain(AWS_KEY).contains("[REDACTED:aws_access_key_id]");
        assertThat(request.system()).doesNotContain(AWS_KEY);
    }
}