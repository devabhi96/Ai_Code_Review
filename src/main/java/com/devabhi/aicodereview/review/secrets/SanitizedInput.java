package com.devabhi.aicodereview.review.secrets;

import java.util.List;

import com.devabhi.aicodereview.review.diff.NumberedDiffFormatter;

/**
 * Review input after redaction. The constructor is package-private: only SecretGate can create
 * one, so any code that accepts a SanitizedInput is guaranteed to receive redacted text.
 */
public final class SanitizedInput {

    private final String title;
    private final String description;
    private final List<SanitizedFile> files;
    private final List<SecretFinding> secretFindings;

    SanitizedInput(String title, String description, List<SanitizedFile> files,
                   List<SecretFinding> secretFindings) {
        this.title = title;
        this.description = description;
        this.files = files;
        this.secretFindings = secretFindings;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public List<SanitizedFile> files() {
        return files;
    }

    public List<SecretFinding> secretFindings() {
        return secretFindings;
    }

    /** Plain-text view of everything that may be sent to the model. Step 4 builds on this. */
    public String promptText() {
        NumberedDiffFormatter formatter = new NumberedDiffFormatter();
        StringBuilder sb = new StringBuilder();
        sb.append("PR title: ").append(title == null ? "" : title).append('\n');
        sb.append("PR description:\n").append(description == null ? "" : description).append("\n\n");
        for (SanitizedFile file : files) {
            sb.append("=== FILE: ").append(file.path()).append(" ===\n");
            sb.append(formatter.format(file.patch())).append('\n');
            if (file.context() != null && !file.context().isBlank()) {
                sb.append("--- full file at head commit ---\n").append(file.context()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}