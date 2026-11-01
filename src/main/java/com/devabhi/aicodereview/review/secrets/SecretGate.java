package com.devabhi.aicodereview.review.secrets;

import java.util.ArrayList;
import java.util.List;

import com.devabhi.aicodereview.review.diff.DiffLine;
import com.devabhi.aicodereview.review.diff.Hunk;
import com.devabhi.aicodereview.review.diff.LineType;
import com.devabhi.aicodereview.review.diff.ParsedPatch;

/** The hard gate: nothing reaches the model, logs or the database without passing through here. */
public final class SecretGate {

    private final SecretRedactor redactor;

    public SecretGate() {
        this(new SecretRedactor());
    }

    SecretGate(SecretRedactor redactor) {
        this.redactor = redactor;
    }

    public SanitizedInput sanitize(String title, String description, List<FileInput> files) {
        List<SanitizedFile> sanitizedFiles = new ArrayList<>();
        List<SecretFinding> findings = new ArrayList<>();

        for (FileInput file : files) {
            SecretRedactor.Session session = redactor.newSession();
            List<Hunk> hunks = new ArrayList<>();
            for (Hunk hunk : file.patch().hunks()) {
                List<DiffLine> lines = new ArrayList<>();
                for (DiffLine line : hunk.lines()) {
                    SecretRedactor.LineResult result = session.redactLine(line.text());
                    lines.add(new DiffLine(line.type(), line.oldLine(), line.newLine(), result.text()));
                    if (line.type() == LineType.ADDED) {
                        for (String type : result.types()) {
                            findings.add(SecretFinding.of(file.path(), line.newLine(), type));
                        }
                    }
                }
                hunks.add(new Hunk(hunk.oldStart(), hunk.oldCount(), hunk.newStart(), hunk.newCount(),
                        redactor.redactText(hunk.sectionHeading()), List.copyOf(lines)));
            }
            sanitizedFiles.add(new SanitizedFile(file.path(), new ParsedPatch(List.copyOf(hunks)),
                    redactor.redactText(file.context())));
        }
        return new SanitizedInput(redactor.redactText(title), redactor.redactText(description),
                List.copyOf(sanitizedFiles), List.copyOf(findings));
    }
}