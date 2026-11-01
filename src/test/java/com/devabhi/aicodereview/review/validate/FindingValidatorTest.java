package com.devabhi.aicodereview.review.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.devabhi.aicodereview.review.diff.DiffParser;
import com.devabhi.aicodereview.review.model.Category;
import com.devabhi.aicodereview.review.model.DropReason;
import com.devabhi.aicodereview.review.model.DroppedFinding;
import com.devabhi.aicodereview.review.model.Finding;
import com.devabhi.aicodereview.review.model.RawFinding;
import com.devabhi.aicodereview.review.model.Severity;
import com.devabhi.aicodereview.review.model.SuggestedFix;
import com.devabhi.aicodereview.review.secrets.FileInput;
import com.devabhi.aicodereview.review.secrets.SecretGate;
import com.devabhi.aicodereview.review.secrets.SanitizedInput;

class FindingValidatorTest {

    private static final String FILE = "src/main/java/com/demo/UserRepository.java";
    private static final String SQL_EVIDENCE =
            "String sql = \"SELECT name FROM users WHERE name = '\" + name + \"'\";";
    // new-file lines: 1 a, 2 x = foo(1);, 3 b, 4 x = foo(1);, 5 c
    private static final String TWO_LOOKALIKES =
            "@@ -1,3 +1,5 @@\n a\n+x = foo(1);\n b\n+x = foo(1);\n c";

    private final FindingValidator validator = new FindingValidator();

    private SanitizedInput prInput() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/pr1-UserRepository.java.patch")) {
            assertThat(in).isNotNull();
            return inputFrom(FILE, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static SanitizedInput inputFrom(String path, String patch) {
        return new SecretGate().sanitize("t", "d",
                List.of(new FileInput(path, new DiffParser().parse(patch), null)));
    }

    private static RawFinding raw(String file, Integer line, Integer endLine, String side,
                                  String category, String severity, Double confidence,
                                  String title, String evidence) {
        return new RawFinding(file, line, endLine, side, category, severity, confidence,
                "CWE-89", title, "Explanation text.", evidence, null);
    }

    private static RawFinding valid(int line, String evidence) {
        return raw(FILE, line, null, "RIGHT", "security", "high", 0.9, "SQL injection", evidence);
    }

    private ValidationResult run(SanitizedInput input, RawFinding... findings) {
        return validator.validate(Arrays.asList(findings), input);
    }

    @Test
    void acceptsAValidFindingAndNormalizesEnums() throws IOException {
        ValidationResult result = run(prInput(), valid(17, SQL_EVIDENCE));
        assertThat(result.dropped()).isEmpty();
        assertThat(result.accepted()).hasSize(1);
        Finding f = result.accepted().get(0);
        assertThat(f.line()).isEqualTo(17);
        assertThat(f.endLine()).isEqualTo(17);
        assertThat(f.category()).isEqualTo(Category.SECURITY);
        assertThat(f.severity()).isEqualTo(Severity.HIGH);
        assertThat(f.cwe()).isEqualTo("CWE-89");
    }

    @Test
    void whitespaceDifferencesInEvidenceAreIgnored() throws IOException {
        String messy = "  String   sql =  \"SELECT name FROM users WHERE name = '\" + name + \"'\";\n";
        assertThat(run(prInput(), valid(17, messy)).accepted()).hasSize(1);
    }

    @Test
    void evidenceInTheSecondHunkAndAsPartOfALineIsAccepted() throws IOException {
        ValidationResult result = run(prInput(), valid(53, "return name.trim().toUpperCase();"));
        assertThat(result.accepted()).extracting(Finding::line).containsExactly(53);
    }

    @Test
    void reanchorsToTheOneNearbyLineThatContainsTheEvidence() throws IOException {
        ValidationResult result = run(prInput(), valid(18, SQL_EVIDENCE)); // model was off by one
        assertThat(result.dropped()).isEmpty();
        assertThat(result.accepted()).extracting(Finding::line, Finding::endLine)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(17, 17));
    }

    @Test
    void dropsWhenTheEvidenceIsNotNearTheReportedLine() throws IOException {
        ValidationResult result = run(prInput(), valid(22, SQL_EVIDENCE)); // 5 lines away
        assertThat(result.accepted()).isEmpty();
        assertThat(result.dropped()).extracting(DroppedFinding::reason)
                .containsExactly(DropReason.EVIDENCE_MISMATCH);
    }

    @Test
    void dropsWhenSeveralNearbyLinesMatchButNotTheReportedOne() {
        SanitizedInput input = inputFrom("A.java", TWO_LOOKALIKES);
        ValidationResult result = run(input,
                raw("A.java", 3, null, "RIGHT", "bug", "low", 0.8, "t", "x = foo(1);"));
        assertThat(result.dropped()).extracting(DroppedFinding::reason)
                .containsExactly(DropReason.EVIDENCE_AMBIGUOUS);
    }

    @Test
    void prefersTheReportedLineWhenSeveralLinesMatch() {
        SanitizedInput input = inputFrom("A.java", TWO_LOOKALIKES);
        ValidationResult result = run(input,
                raw("A.java", 2, null, "RIGHT", "bug", "low", 0.8, "t", "x = foo(1);"));
        assertThat(result.accepted()).extracting(Finding::line).containsExactly(2);
    }

    @Test
    void dropsAFileThatIsNotInThePr() throws IOException {
        ValidationResult result = run(prInput(),
                raw("Other.java", 17, null, "RIGHT", "security", "high", 0.9, "t", SQL_EVIDENCE));
        assertThat(result.dropped()).extracting(DroppedFinding::reason)
                .containsExactly(DropReason.FILE_NOT_IN_PR);
    }

    @Test
    void dropsLinesOutsideTheDiff() throws IOException {
        ValidationResult result = run(prInput(),
                valid(30, "some evidence long enough"), valid(40, "some evidence long enough"));
        assertThat(result.dropped()).extracting(DroppedFinding::reason)
                .containsExactly(DropReason.LINE_NOT_IN_DIFF, DropReason.LINE_NOT_IN_DIFF);
    }

    @Test
    void dropsNonRightSide() throws IOException {
        ValidationResult result = run(prInput(),
                raw(FILE, 17, null, "LEFT", "security", "high", 0.9, "t", SQL_EVIDENCE));
        assertThat(result.dropped()).extracting(DroppedFinding::reason)
                .containsExactly(DropReason.INVALID_SIDE);
    }

    @Test
    void acceptsAContiguousRangeInsideOneHunk() throws IOException {
        ValidationResult result = run(prInput(),
                raw(FILE, 16, 22, "RIGHT", "bug", "medium", 0.8, "t",
                        "public String findByName(String name) throws SQLException {"));
        assertThat(result.accepted()).extracting(Finding::line, Finding::endLine)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(16, 22));
    }

    @Test
    void dropsRangesThatLeaveTheDiffOrRunBackwards() throws IOException {
        String ev = "String sql = \"SELECT name FROM users WHERE id = ?\";";
        ValidationResult result = run(prInput(),
                raw(FILE, 25, 28, "RIGHT", "bug", "low", 0.8, "t", ev),   // 27, 28 not in diff
                raw(FILE, 20, 18, "RIGHT", "bug", "low", 0.8, "t", ev));  // backwards
        assertThat(result.dropped()).extracting(DroppedFinding::reason)
                .containsExactly(DropReason.INVALID_RANGE, DropReason.INVALID_RANGE);
    }

    @Test
    void dropsUnknownEnumValues() throws IOException {
        ValidationResult result = run(prInput(),
                raw(FILE, 17, null, "RIGHT", "style", "high", 0.9, "t", SQL_EVIDENCE),
                raw(FILE, 17, null, "RIGHT", "security", "blocker", 0.9, "t", SQL_EVIDENCE));
        assertThat(result.dropped()).extracting(DroppedFinding::reason)
                .containsExactly(DropReason.INVALID_ENUM, DropReason.INVALID_ENUM);
    }

    @Test
    void dropsBadConfidence() throws IOException {
        ValidationResult result = run(prInput(),
                raw(FILE, 17, null, "RIGHT", "security", "high", 1.5, "t", SQL_EVIDENCE),
                raw(FILE, 17, null, "RIGHT", "security", "high", null, "t", SQL_EVIDENCE));
        assertThat(result.dropped()).extracting(DroppedFinding::reason)
                .containsExactly(DropReason.INVALID_CONFIDENCE, DropReason.INVALID_CONFIDENCE);
    }

    @Test
    void dropsEvidenceThatIsTooShortToProveAnything() throws IOException {
        ValidationResult result = run(prInput(), valid(21, "}"));
        assertThat(result.dropped()).extracting(DroppedFinding::reason)
                .containsExactly(DropReason.EVIDENCE_TOO_SHORT);
    }

    @Test
    void dropsMissingRequiredFields() throws IOException {
        ValidationResult result = run(prInput(),
                raw(FILE, 17, null, "RIGHT", "security", "high", 0.9, "  ", SQL_EVIDENCE),
                raw(FILE, null, null, "RIGHT", "security", "high", 0.9, "t", SQL_EVIDENCE));
        assertThat(result.dropped()).extracting(DroppedFinding::reason)
                .containsExactly(DropReason.MALFORMED, DropReason.MALFORMED);
    }

    @Test
    void anInvalidCweIsClearedButTheFindingIsKept() throws IOException {
        RawFinding r = new RawFinding(FILE, 17, null, "RIGHT", "security", "high", 0.9,
                "SQL-1", "t", "e", SQL_EVIDENCE, null);
        assertThat(run(prInput(), r).accepted()).extracting(Finding::cwe).containsExactly((String) null);
    }

    @Test
    void oneBadFindingNeverAffectsTheOthers() throws IOException {
        List<RawFinding> list = Arrays.asList(
                valid(17, SQL_EVIDENCE), null, valid(40, "some evidence long enough"));
        ValidationResult result = validator.validate(list, prInput());
        assertThat(result.accepted()).hasSize(1);
        assertThat(result.dropped()).extracting(DroppedFinding::reason)
                .containsExactly(DropReason.MALFORMED, DropReason.LINE_NOT_IN_DIFF);
    }

    @Test
    void anOversizedFixIsRemovedButTheFindingIsKept() throws IOException {
        RawFinding r = new RawFinding(FILE, 17, null, "RIGHT", "security", "high", 0.9, "CWE-89",
                "t", "e", SQL_EVIDENCE, new SuggestedFix("d", "x".repeat(6000)));
        assertThat(run(prInput(), r).accepted()).extracting(Finding::suggestedFix)
                .containsExactly((SuggestedFix) null);
    }
}