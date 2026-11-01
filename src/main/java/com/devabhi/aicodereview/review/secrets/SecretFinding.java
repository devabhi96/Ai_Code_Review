package com.devabhi.aicodereview.review.secrets;

import java.util.Map;

/** A locally detected secret. Never contains the secret value itself. */
public record SecretFinding(String file, int line, String type, String severity,
                            String title, String explanation) {

    private static final Map<String, String> LABELS = Map.of(
            "aws_access_key_id", "AWS access key ID",
            "aws_secret_access_key", "AWS secret access key",
            "github_token", "GitHub token",
            "slack_token", "Slack token",
            "private_key", "private key",
            "generic_secret", "hardcoded credential");

    static SecretFinding of(String file, int line, String type) {
        String label = LABELS.getOrDefault(type, "secret");
        return new SecretFinding(file, line, type, "critical",
                "Possible " + label + " committed in code",
                "Detected a possible " + label + " on this line. The value was redacted before any "
                        + "analysis and is not shown here. Rotate or revoke it now: deleting it from "
                        + "this PR does not remove it from git history.");
    }
}