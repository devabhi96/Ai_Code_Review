package com.devabhi.aicodereview.review.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.devabhi.aicodereview.review.diff.DiffParser;
import com.devabhi.aicodereview.review.diff.ParsedPatch;

class SecretGateTest {

    // Built from pieces so this file never contains a token-shaped literal
    // (GitHub push protection and gitleaks would flag it).
    private static final String AWS_KEY = "AKIA" + "Q7ZK3MWXP2LD9RBT";
    private static final String OLD_AWS_KEY = "AKIA" + "M4NB8VCX1ZQW6ERT";
    private static final String GH_TOKEN = "ghp_" + "a1B2c3D4e5F6g7H8i9J0".repeat(2);
    private static final String SLACK_TOKEN = "xoxb-" + "1234567890-" + "abcdefghijKLMNOPqrst";
    private static final String DB_PASSWORD = "Zk93mQ7vL2xP8wRt";
    private static final String PEM_BODY = "MIIEowIBAAKCAQEAfakefakefake";
    private static final String BEGIN = "-----BEGIN RSA " + "PRIVATE KEY-----";
    private static final String END = "-----END RSA " + "PRIVATE KEY-----";
    private static final List<String> ALL_SECRETS =
            List.of(AWS_KEY, OLD_AWS_KEY, GH_TOKEN, SLACK_TOKEN, DB_PASSWORD, PEM_BODY);

    // old lines: 2 context + 1 removed = 3; new lines: 2 context + 8 added = 10
    private static final String PATCH = String.join("\n",
            "@@ -1,3 +1,10 @@ class Config {",
            " class Config {",
            "-    String oldKey = \"" + OLD_AWS_KEY + "\";",
            "+    String awsKey = \"" + AWS_KEY + "\";",
            "+    String token = \"" + GH_TOKEN + "\";",
            "+    String dbPassword = \"" + DB_PASSWORD + "\";",
            "+    String slack = \"" + SLACK_TOKEN + "\";",
            "+" + BEGIN,
            "+" + PEM_BODY,
            "+" + END,
            "+    int safe = 42;",
            " }");

    private final DiffParser parser = new DiffParser();
    private final SecretGate gate = new SecretGate();

    private SanitizedInput sanitize(String title, String description, String context) {
        ParsedPatch patch = parser.parse(PATCH);
        return gate.sanitize(title, description,
                List.of(new FileInput("src/Config.java", patch, context)));
    }

    @Test
    void llmPromptNeverContainsAnyOriginalSecret() {
        String context = "String k = \"" + AWS_KEY + "\";\n" + BEGIN + "\n" + PEM_BODY + "\n" + END;
        String prompt = sanitize("Fix config", "Uses " + GH_TOKEN + " for CI", context).promptText();
        for (String secret : ALL_SECRETS) {
            assertThat(prompt).as("leaked secret starting with " + secret.substring(0, 6))
                    .doesNotContain(secret);
        }
    }

    @Test
    void promptShowsMarkersInPlaceOfSecrets() {
        String prompt = sanitize("t", "d", null).promptText();
        assertThat(prompt).contains("[REDACTED:aws_access_key_id]", "[REDACTED:github_token]",
                "[REDACTED:slack_token]", "[REDACTED:generic_secret]", "[REDACTED:private_key]");
    }

    @Test
    void titleAndDescriptionAreRedactedToo() {
        SanitizedInput input = sanitize("Rotate " + AWS_KEY, "token: " + GH_TOKEN, null);
        assertThat(input.title()).isEqualTo("Rotate [REDACTED:aws_access_key_id]");
        assertThat(input.description()).isEqualTo("token: [REDACTED:github_token]");
    }

    @Test
    void secretFindingsExistOnlyForAddedLinesAndUseNewLineNumbers() {
        assertThat(sanitize("t", "d", null).secretFindings())
                .extracting(SecretFinding::line, SecretFinding::type)
                .containsExactly(
                        tuple(2, "aws_access_key_id"),
                        tuple(3, "github_token"),
                        tuple(4, "generic_secret"),
                        tuple(5, "slack_token"),
                        tuple(6, "private_key"));
    }

    @Test
    void findingsAreCriticalAndNeverContainTheSecret() {
        for (SecretFinding finding : sanitize("t", "d", null).secretFindings()) {
            assertThat(finding.severity()).isEqualTo("critical");
            assertThat(finding.explanation()).contains("Rotate");
            for (String secret : ALL_SECRETS) {
                assertThat(finding.toString()).doesNotContain(secret);
            }
        }
    }

    @Test
    void redactionNeverChangesLineNumbers() {
        ParsedPatch original = parser.parse(PATCH);
        ParsedPatch redacted = sanitize("t", "d", null).files().get(0).patch();
        assertThat(redacted.validRightLines()).isEqualTo(original.validRightLines());
        assertThat(redacted.addedLines()).isEqualTo(original.addedLines());
    }

    @Test
    void codeWithoutSecretsPassesThroughUntouched() {
        String prompt = sanitize("t", "d", null).promptText();
        assertThat(prompt).contains("int safe = 42;").contains("class Config {");
    }

    @Test
    void secretInTheHunkHeadingIsRedacted() {
        ParsedPatch patch = parser.parse("@@ -1,1 +1,1 @@ String k = \"" + AWS_KEY + "\";\n a");
        SanitizedInput input = gate.sanitize("t", "d", List.of(new FileInput("A.java", patch, null)));
        assertThat(input.promptText()).doesNotContain(AWS_KEY).contains("[REDACTED:aws_access_key_id]");
    }

    @Test
    void nullDescriptionAndContextAreHandled() {
        SanitizedInput input = sanitize("t", null, null);
        assertThat(input.promptText()).contains("PR description:");
    }
}