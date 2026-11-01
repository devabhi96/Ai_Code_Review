package com.devabhi.aicodereview.review.validate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.devabhi.aicodereview.review.diff.DiffLine;
import com.devabhi.aicodereview.review.diff.Hunk;
import com.devabhi.aicodereview.review.diff.LineType;
import com.devabhi.aicodereview.review.model.Category;
import com.devabhi.aicodereview.review.model.DropReason;
import com.devabhi.aicodereview.review.model.DroppedFinding;
import com.devabhi.aicodereview.review.model.Finding;
import com.devabhi.aicodereview.review.model.RawFinding;
import com.devabhi.aicodereview.review.model.Severity;
import com.devabhi.aicodereview.review.model.SuggestedFix;
import com.devabhi.aicodereview.review.secrets.SanitizedFile;
import com.devabhi.aicodereview.review.secrets.SanitizedInput;

/**
 * Treats model output as untrusted (PRD 7.2 step 7). Validation runs against the SANITIZED
 * diff, which is the text the model actually saw, so evidence quotes can match.
 */
public final class FindingValidator {

    // Starting values; tune them with the eval set, not by feel.
    static final int NEARBY_LINES = 2;
    static final int MAX_RANGE_LINES = 20;
    static final int MIN_EVIDENCE_CHARS = 5;
    static final int MAX_TITLE_CHARS = 200;
    static final int MAX_EXPLANATION_CHARS = 2000;
    static final int MAX_REPLACEMENT_CHARS = 5000;

    private static final Pattern CWE = Pattern.compile("CWE-\\d{1,5}");

    public ValidationResult validate(List<RawFinding> raw, SanitizedInput input) {
        Map<String, FileLines> files = index(input);
        List<Finding> accepted = new ArrayList<>();
        List<DroppedFinding> dropped = new ArrayList<>();
        for (RawFinding r : raw == null ? List.<RawFinding>of() : raw) {
            Outcome outcome = check(r, files);
            if (outcome.finding() != null) {
                accepted.add(outcome.finding());
            } else {
                dropped.add(new DroppedFinding(r, outcome.reason(), outcome.detail()));
            }
        }
        return new ValidationResult(List.copyOf(accepted), List.copyOf(dropped));
    }

    private Outcome check(RawFinding r, Map<String, FileLines> files) {
        if (r == null) {
            return Outcome.drop(DropReason.MALFORMED, "null entry");
        }
        if (blank(r.file()) || blank(r.title()) || blank(r.explanation())
                || blank(r.evidence()) || r.line() == null) {
            return Outcome.drop(DropReason.MALFORMED, "missing required field");
        }
        if (r.title().length() > MAX_TITLE_CHARS || r.explanation().length() > MAX_EXPLANATION_CHARS) {
            return Outcome.drop(DropReason.MALFORMED, "text too long");
        }
        Category category = parseEnum(Category.class, r.category());
        Severity severity = parseEnum(Severity.class, r.severity());
        if (category == null || severity == null) {
            return Outcome.drop(DropReason.INVALID_ENUM,
                    "category=" + r.category() + ", severity=" + r.severity());
        }
        Double confidence = r.confidence();
        if (confidence == null || confidence.isNaN() || confidence < 0 || confidence > 1) {
            return Outcome.drop(DropReason.INVALID_CONFIDENCE, String.valueOf(confidence));
        }
        if (r.side() != null && !"RIGHT".equalsIgnoreCase(r.side())) {
            return Outcome.drop(DropReason.INVALID_SIDE, r.side());
        }
        FileLines file = files.get(r.file());
        if (file == null) {
            return Outcome.drop(DropReason.FILE_NOT_IN_PR, r.file());
        }

        int line = r.line();
        int end = r.endLine() == null ? line : r.endLine();
        if (!file.has(line)) {
            return Outcome.drop(DropReason.LINE_NOT_IN_DIFF, r.file() + ":" + line);
        }
        if (end < line || end - line + 1 > MAX_RANGE_LINES) {
            return Outcome.drop(DropReason.INVALID_RANGE, line + ".." + end);
        }
        for (int l = line; l <= end; l++) {
            if (!file.has(l) || file.hunkOf(l) != file.hunkOf(line)) {
                return Outcome.drop(DropReason.INVALID_RANGE,
                        line + ".." + end + " leaves the hunk at line " + l);
            }
        }

        String evidence = normalize(r.evidence());
        if (evidence.length() < MIN_EVIDENCE_CHARS) {
            return Outcome.drop(DropReason.EVIDENCE_TOO_SHORT, evidence);
        }
        int anchorLine = line;
        int anchorEnd = end;
        if (!file.rangeText(line, end).contains(evidence)) {
            List<Integer> nearby = new ArrayList<>();
            for (int l = line - NEARBY_LINES; l <= line + NEARBY_LINES; l++) {
                if (l != line && file.has(l) && file.hunkOf(l) == file.hunkOf(line)
                        && file.text(l).contains(evidence)) {
                    nearby.add(l);
                }
            }
            if (nearby.isEmpty()) {
                return Outcome.drop(DropReason.EVIDENCE_MISMATCH,
                        "evidence not found at or near line " + line);
            }
            if (nearby.size() > 1) {
                return Outcome.drop(DropReason.EVIDENCE_AMBIGUOUS,
                        "evidence matches lines " + nearby + " near line " + line);
            }
            anchorLine = nearby.get(0); // re-anchor to the one line that really has the code
            anchorEnd = anchorLine;
        }

        String cwe = r.cwe() != null && CWE.matcher(r.cwe().trim()).matches() ? r.cwe().trim() : null;
        SuggestedFix fix = r.suggestedFix();
        if (fix != null && fix.replacementCode() != null
                && fix.replacementCode().length() > MAX_REPLACEMENT_CHARS) {
            fix = null; // keep the finding, drop the oversized fix
        }
        return Outcome.ok(new Finding(r.file(), anchorLine, anchorEnd, category, severity, confidence,
                cwe, r.title().strip(), r.explanation().strip(), r.evidence().strip(), fix));
    }

    private static Map<String, FileLines> index(SanitizedInput input) {
        Map<String, FileLines> map = new HashMap<>();
        for (SanitizedFile f : input.files()) {
            FileLines lines = new FileLines();
            List<Hunk> hunks = f.patch().hunks();
            for (int h = 0; h < hunks.size(); h++) {
                for (DiffLine l : hunks.get(h).lines()) {
                    if (l.type() != LineType.REMOVED) {
                        lines.put(l.newLine(), h, normalize(l.text()));
                    }
                }
            }
            map.put(f.path(), lines);
        }
        return map;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private record Outcome(Finding finding, DropReason reason, String detail) {
        static Outcome ok(Finding finding) {
            return new Outcome(finding, null, null);
        }

        static Outcome drop(DropReason reason, String detail) {
            return new Outcome(null, reason, detail);
        }
    }

    /** New-file lines a RIGHT-side comment may use: normalized text and hunk index per line. */
    private static final class FileLines {
        private final Map<Integer, String> text = new HashMap<>();
        private final Map<Integer, Integer> hunk = new HashMap<>();

        void put(int line, int hunkIndex, String normalizedText) {
            text.put(line, normalizedText);
            hunk.put(line, hunkIndex);
        }

        boolean has(int line) {
            return text.containsKey(line);
        }

        int hunkOf(int line) {
            return hunk.get(line);
        }

        String text(int line) {
            return text.get(line);
        }

        String rangeText(int from, int to) {
            StringBuilder sb = new StringBuilder();
            for (int l = from; l <= to; l++) {
                if (l > from) {
                    sb.append(' ');
                }
                sb.append(text.get(l));
            }
            return sb.toString();
        }
    }
}