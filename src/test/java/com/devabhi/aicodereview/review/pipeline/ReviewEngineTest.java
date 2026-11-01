package com.devabhi.aicodereview.review.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.devabhi.aicodereview.review.filter.ChangedFile;
import com.devabhi.aicodereview.review.filter.SkippedFile;
import com.devabhi.aicodereview.review.model.DropReason;
import com.devabhi.aicodereview.review.model.DroppedFinding;
import com.devabhi.aicodereview.review.model.Finding;
import com.devabhi.aicodereview.review.model.RawFinding;
import com.devabhi.aicodereview.review.port.LlmException;
import com.devabhi.aicodereview.review.port.LlmReview;
import com.devabhi.aicodereview.review.secrets.SanitizedFile;
import com.devabhi.aicodereview.review.secrets.SecretFinding;

class ReviewEngineTest {

    private static final String BASE = "src/main/java/com/demo/";
    private static final String SQL_EVIDENCE =
            "String sql = \"SELECT name FROM users WHERE name = '\" + name + \"'\";";
    private static final String AWS_KEY = "AKIA" + "Q7ZK3MWXP2LD9RBT";

    private static String fixture(String name) throws IOException {
        try (InputStream in = ReviewEngineTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The 8 real files of test PRs #1 to #3 (webhook-practice), in one review. */
    private static List<ChangedFile> prFiles() throws IOException {
        return List.of(
                new ChangedFile(BASE + "UserRepository.java", "modified", null, fixture("pr1-UserRepository.java.patch")),
                new ChangedFile("logo.png", "added", null, null),
                new ChangedFile(BASE + "NewName.java", "renamed", BASE + "OldName.java", fixture("pr2-NewName.java.patch")),
                new ChangedFile(BASE + "Obsolete.java", "removed", null, fixture("pr2-Obsolete.java.patch")),
                new ChangedFile("app.js", "added", null, fixture("pr3-app.js.patch")),
                new ChangedFile("dist/bundle.min.js", "added", null, fixture("pr3-bundle.min.js.patch")),
                new ChangedFile("package-lock.json", "modified", null, fixture("pr3-package-lock.json.patch")),
                new ChangedFile("script.py", "added", null, fixture("pr3-script.py.patch")));
    }

    private static ChangedFile fileWithSecret() {
        String patch = "@@ -0,0 +1,3 @@\n+class Config {\n+    String key = \"" + AWS_KEY + "\";\n+}";
        return new ChangedFile("src/Config.java", "added", null, patch);
    }

    private static RawFinding finding(String file, int line, String category, String severity,
                                      String title, String evidence) {
        return new RawFinding(file, line, null, "RIGHT", category, severity, 0.9, null,
                title, "Explanation.", evidence, null);
    }

    private static ScriptedLlm emptyReview() {
        return new ScriptedLlm(input -> new LlmReview("ok", List.of()));
    }

    @Test
    void fullChainKeepsRealFindingsAndDropsInventedOnes() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(input -> new LlmReview("Adds search.", List.of(
                finding(BASE + "UserRepository.java", 17, "security", "critical", "SQL injection", SQL_EVIDENCE),
                finding("app.js", 3, "bug", "medium", "Possible undefined access", "return user.name;"),
                finding("Ghost.java", 1, "bug", "low", "Invented file", "int ghost = 1;"),
                finding("script.py", 4, "bug", "low", "File the model never saw", "return open(path).read()"))));

        ReviewOutcome outcome = new ReviewEngine(llm)
                .review(ReviewRequest.of("Add search", "Adds search", prFiles()));

        assertThat(llm.calls).isEqualTo(1);
        assertThat(llm.lastInput.files()).extracting(SanitizedFile::path)
                .containsExactly(BASE + "UserRepository.java", BASE + "NewName.java", "app.js");
        assertThat(outcome.findings()).extracting(Finding::file, Finding::line)
                .containsExactly(tuple(BASE + "UserRepository.java", 17), tuple("app.js", 3));
        assertThat(outcome.dropped()).extracting(DroppedFinding::reason)
                .containsExactly(DropReason.FILE_NOT_IN_PR, DropReason.FILE_NOT_IN_PR);
        assertThat(outcome.skipped()).extracting(SkippedFile::path)
                .containsExactlyInAnyOrder("logo.png", BASE + "Obsolete.java", "dist/bundle.min.js",
                        "package-lock.json", "script.py");
        assertThat(outcome.summary()).isEqualTo("Adds search.");
        assertThat(outcome.llmError()).isNull();
    }

    @Test
    void secretsNeverReachTheModelButAreStillReported() {
        ScriptedLlm llm = emptyReview();
        ReviewRequest request = new ReviewRequest("Rotate " + AWS_KEY, "Key is " + AWS_KEY,
                List.of(fileWithSecret()), Map.of("src/Config.java", "// old key " + AWS_KEY),
                List.of(), true);

        ReviewOutcome outcome = new ReviewEngine(llm).review(request);

        assertThat(llm.lastInput.promptText())
                .doesNotContain(AWS_KEY).contains("[REDACTED:aws_access_key_id]");
        assertThat(outcome.secretFindings()).extracting(SecretFinding::line, SecretFinding::type)
                .containsExactly(tuple(2, "aws_access_key_id"));
        assertThat(outcome.secretFindings().toString()).doesNotContain(AWS_KEY);
    }

    @Test
    void whenTheModelIsDisabledSecretScanningStillRuns() {
        ScriptedLlm llm = emptyReview();
        ReviewRequest request = new ReviewRequest("t", "d", List.of(fileWithSecret()),
                Map.of(), List.of(), false);

        ReviewOutcome outcome = new ReviewEngine(llm).review(request);

        assertThat(llm.calls).isZero();
        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.secretFindings()).hasSize(1);
        assertThat(outcome.llmError()).isNull();
    }

    @Test
    void aModelFailureStillReturnsTheSecretFindings() {
        ScriptedLlm llm = new ScriptedLlm(input -> {
            throw new LlmException("model unavailable");
        });

        ReviewOutcome outcome = new ReviewEngine(llm)
                .review(ReviewRequest.of("t", "d", List.of(fileWithSecret())));

        assertThat(outcome.llmError()).isEqualTo("model unavailable");
        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.summary()).isNull();
        assertThat(outcome.secretFindings()).hasSize(1);
    }

    @Test
    void nothingReviewableMeansTheModelIsNotCalled() {
        ScriptedLlm llm = emptyReview();
        List<ChangedFile> files = List.of(
                new ChangedFile("package-lock.json", "modified", null, "@@ -1 +1 @@\n-a\n+b"),
                new ChangedFile("logo.png", "added", null, null));

        ReviewOutcome outcome = new ReviewEngine(llm).review(ReviewRequest.of("t", "d", files));

        assertThat(llm.calls).isZero();
        assertThat(outcome.skipped()).hasSize(2);
        assertThat(outcome.llmError()).isNull();
    }

    @Test
    void aMalformedPatchIsSkippedWithAReasonAndOtherFilesStillGetReviewed() throws IOException {
        ScriptedLlm llm = emptyReview();
        List<ChangedFile> files = List.of(
                new ChangedFile("Broken.java", "modified", null, "@@ -1,3 +1,3 @@\n a\n"),
                new ChangedFile("app.js", "added", null, fixture("pr3-app.js.patch")));

        ReviewOutcome outcome = new ReviewEngine(llm).review(ReviewRequest.of("t", "d", files));

        assertThat(outcome.skipped()).extracting(SkippedFile::path, SkippedFile::reason)
                .containsExactly(tuple("Broken.java", ReviewEngine.REASON_UNPARSEABLE));
        assertThat(llm.lastInput.files()).extracting(SanitizedFile::path).containsExactly("app.js");
    }
}