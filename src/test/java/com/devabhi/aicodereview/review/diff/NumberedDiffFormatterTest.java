package com.devabhi.aicodereview.review.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

class NumberedDiffFormatterTest {

    private final DiffParser parser = new DiffParser();
    private final NumberedDiffFormatter formatter = new NumberedDiffFormatter();

    private ParsedPatch pr1() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/pr1-UserRepository.java.patch")) {
            assertThat(in).isNotNull();
            return parser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private List<String> lines() throws IOException {
        return Arrays.asList(formatter.format(pr1()).split("\n", -1));
    }

    @Test
    void firstLineIsTheHunkHeaderWithItsHeading() throws IOException {
        assertThat(lines().get(0))
                .isEqualTo("@@ -13,6 +13,14 @@ public UserRepository(Connection connection) {");
    }

    @Test
    void addedLineShowsItsNewFileLineNumber() throws IOException {
        assertThat(lines())
                .contains("+   16 |     public String findByName(String name) throws SQLException {");
    }

    @Test
    void emptyAddedLineKeepsItsNumber() throws IOException {
        assertThat(lines()).contains("+   23 | ");
    }

    @Test
    void contextLineIsNumberedWithoutAPlus() throws IOException {
        assertThat(lines()).contains("    13 |         this.connection = connection;");
    }

    @Test
    void removedLineHasNoNumber() throws IOException {
        assertThat(lines()).contains("-" + " ".repeat(6) + "| }");
    }

    @Test
    void noNewlineMarkerNeverReachesTheModel() throws IOException {
        assertThat(formatter.format(pr1())).doesNotContain("No newline");
    }

    @Test
    void everyNumberedLineIsAValidRightLine() throws IOException {
        ParsedPatch patch = pr1();
        Set<Integer> numbered = new TreeSet<>();
        for (String line : formatter.format(patch).split("\n", -1)) {
            if (line.startsWith("@@") || line.startsWith("-")) {
                continue;
            }
            numbered.add(Integer.parseInt(line.substring(2, 6).trim()));
        }
        assertThat(numbered).isEqualTo(patch.validRightLines());
    }

    @Test
    void emptyPatchGivesEmptyString() {
        assertThat(formatter.format(parser.parse(""))).isEmpty();
    }
}