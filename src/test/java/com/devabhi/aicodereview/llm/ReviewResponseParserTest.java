package com.devabhi.aicodereview.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.devabhi.aicodereview.review.model.RawFinding;
import com.devabhi.aicodereview.review.port.LlmReview;

class ReviewResponseParserTest {

    private final ReviewResponseParser parser = new ReviewResponseParser();

    @Test
    void parsesTheDocumentedFormat() {
        String json = """
                {"summary": "Adds lookup.", "findings": [{
                  "file": "UserDao.java", "line": 12, "side": "RIGHT", "category": "security",
                  "severity": "critical", "confidence": 0.95, "cwe": "CWE-89",
                  "title": "SQL injection", "explanation": "Input is concatenated.",
                  "evidence": "String sql = query + name;",
                  "suggested_fix": {"description": "Use a PreparedStatement."}
                }]}
                """;
        LlmReview review = parser.parse(json);
        assertThat(review.summary()).isEqualTo("Adds lookup.");
        assertThat(review.findings()).hasSize(1);
        RawFinding f = review.findings().get(0);
        assertThat(f.file()).isEqualTo("UserDao.java");
        assertThat(f.line()).isEqualTo(12);
        assertThat(f.endLine()).isNull();
        assertThat(f.side()).isEqualTo("RIGHT");
        assertThat(f.category()).isEqualTo("security");
        assertThat(f.severity()).isEqualTo("critical");
        assertThat(f.confidence()).isEqualTo(0.95);
        assertThat(f.cwe()).isEqualTo("CWE-89");
        assertThat(f.title()).isEqualTo("SQL injection");
        assertThat(f.evidence()).isEqualTo("String sql = query + name;");
        assertThat(f.suggestedFix().description()).isEqualTo("Use a PreparedStatement.");
        assertThat(f.suggestedFix().replacementCode()).isNull();
    }

    @Test
    void mapsEndLineAndReplacementCode() {
        String json = """
                {"summary": "s", "findings": [{"file": "A.java", "line": 3, "end_line": 5,
                  "suggested_fix": {"description": "d", "replacement_code": "int x = 1;"}}]}
                """;
        RawFinding f = parser.parse(json).findings().get(0);
        assertThat(f.endLine()).isEqualTo(5);
        assertThat(f.suggestedFix().replacementCode()).isEqualTo("int x = 1;");
    }

    @Test
    void emptyFindingsIsAValidAnswer() {
        LlmReview review = parser.parse("{\"summary\": \"No defects.\", \"findings\": []}");
        assertThat(review.findings()).isEmpty();
        assertThat(review.summary()).isEqualTo("No defects.");
    }

    @Test
    void unknownFieldsAreIgnored() {
        String json = "{\"summary\": \"s\", \"thoughts\": \"hmm\", \"findings\": "
                + "[{\"file\": \"A.java\", \"line\": 1, \"mood\": \"happy\"}]}";
        assertThat(parser.parse(json).findings()).hasSize(1);
    }

    @Test
    void aNullEntryStaysNullSoTheValidatorCanDropIt() {
        LlmReview review = parser.parse("{\"summary\": \"s\", \"findings\": [null]}");
        assertThat(review.findings()).hasSize(1).containsNull();
    }

    @Test
    void rejectsOutputThatIsNotTheSchema() {
        List<String> bad = List.of(
                "not json",
                "{}",
                "{\"summary\": \"s\"}",
                "{\"findings\": \"oops\"}",
                "[]",
                "{\"findings\": [{\"line\": \"abc\"}]}");
        for (String json : bad) {
            assertThatThrownBy(() -> parser.parse(json))
                    .as(json)
                    .isInstanceOf(InvalidModelOutputException.class);
        }
    }

    @Test
    void nullOrBlankInputIsRejected() {
        for (String json : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(InvalidModelOutputException.class);
        }
    }

    @Test
    void capsTheNumberOfFindings() {
        String one = "{\"file\": \"a\", \"line\": 1}";
        String json = "{\"summary\": \"s\", \"findings\": ["
                + String.join(",", Collections.nCopies(60, one)) + "]}";
        assertThat(parser.parse(json).findings()).hasSize(ReviewResponseParser.MAX_FINDINGS);
    }

    @Test
    void errorMessagesNeverEchoModelOutput() {
        assertThatThrownBy(() -> parser.parse("{\"findings\": \"TOPSECRET-VALUE\"}"))
                .isInstanceOf(InvalidModelOutputException.class)
                .hasMessageNotContaining("TOPSECRET-VALUE");
    }
}