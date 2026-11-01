package com.devabhi.aicodereview.review.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SecretRedactorTest {

    // Built from pieces so this file never contains a token-shaped literal.
    private static final String AWS_KEY = "AKIA" + "Q7ZK3MWXP2LD9RBT";
    private static final String AWS_SECRET = "Q8w7E6r5T4y3U2i1O0p9".repeat(2);
    private static final String SLACK = "xoxb-" + "1234567890-" + "abcdefghijKLMNOPqrst";
    private static final String BEGIN = "-----BEGIN RSA " + "PRIVATE KEY-----";
    private static final String END = "-----END RSA " + "PRIVATE KEY-----";

    private final SecretRedactor redactor = new SecretRedactor();

    @Test
    void redactsAwsAccessKeyId() {
        assertThat(redactor.redactText("key=" + AWS_KEY))
                .isEqualTo("key=[REDACTED:aws_access_key_id]");
    }

    @Test
    void redactsAwsSecretAccessKey() {
        String line = "aws_secret_access_key = \"" + AWS_SECRET + "\"";
        assertThat(redactor.redactText(line))
                .isEqualTo("aws_secret_access_key = \"[REDACTED:aws_secret_access_key]\"");
    }

    @Test
    void redactsOnlyTheValueOfGenericAssignments() {
        assertThat(redactor.redactText("String apiKey = \"Zk93mQ7vL2xP8wRt\";"))
                .isEqualTo("String apiKey = \"[REDACTED:generic_secret]\";");
    }

    @Test
    void redactsJsonStyleAssignments() {
        assertThat(redactor.redactText("\"password\": \"Zk93mQ7vL2xP8wRt\""))
                .isEqualTo("\"password\": \"[REDACTED:generic_secret]\"");
    }

    @Test
    void ignoresPlaceholdersAndExamples() {
        List<String> lines = List.of(
                "String key = \"AKIAIOSFODNN7EXAMPLE\";",
                "password = \"changeme123\"",
                "String apiKey = \"${API_KEY}\";",
                "String secret = \"test-secret-value\";",
                "String token = \"your_token_here\";",
                "String tokenType = \"refresh_token_value\";",
                "String password = \"aaaaaaaaaaaa\";");
        for (String line : lines) {
            assertThat(redactor.redactText(line)).isEqualTo(line);
        }
    }

    @Test
    void redactsAMultiLinePrivateKeyAndKeepsTheLineCount() {
        String text = "before\n" + BEGIN + "\nMIIEowIBAAKCAQEAfakefake\nMOREBODY\n" + END + "\nafter";
        String result = redactor.redactText(text);
        assertThat(result.split("\n", -1)).hasSize(6);
        assertThat(result).doesNotContain("MIIEow").doesNotContain("MOREBODY");
        assertThat(result).startsWith("before\n[REDACTED:private_key]").endsWith("\nafter");
    }

    @Test
    void redactsTwoSecretsOnOneLine() {
        assertThat(redactor.redactText("a=" + AWS_KEY + " b=" + SLACK))
                .isEqualTo("a=[REDACTED:aws_access_key_id] b=[REDACTED:slack_token]");
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertThat(redactor.redactText(null)).isNull();
        assertThat(redactor.redactText("")).isEmpty();
    }
}