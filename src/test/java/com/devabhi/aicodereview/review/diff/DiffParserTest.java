package com.devabhi.aicodereview.review.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class DiffParserTest {

    private final DiffParser parser = new DiffParser();

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ParsedPatch pr1() throws IOException {
        return parser.parse(fixture("pr1-UserRepository.java.patch"));
    }

    private static List<Integer> range(int from, int to) {
        return IntStream.rangeClosed(from, to).boxed().toList();
    }

    @Test
    void parsesTwoHunksWithHeaderNumbers() throws IOException {
        ParsedPatch p = pr1();
        assertThat(p.hunks()).hasSize(2);
        Hunk first = p.hunks().get(0);
        assertThat(first.oldStart()).isEqualTo(13);
        assertThat(first.oldCount()).isEqualTo(6);
        assertThat(first.newStart()).isEqualTo(13);
        assertThat(first.newCount()).isEqualTo(14);
    }

    @Test
    void addedLinesIncludeTheEmptyAddedLine() throws IOException {
        // 16..23 in hunk 1 (23 is the empty "+" line), 50..54 in hunk 2
        assertThat(pr1().addedLines())
                .containsExactly(16, 17, 18, 19, 20, 21, 22, 23, 50, 51, 52, 53, 54);
    }

    @Test
    void validRightLinesAreContextPlusAdded() throws IOException {
        List<Integer> expected = new ArrayList<>(range(13, 26));
        expected.addAll(range(47, 54));
        assertThat(pr1().validRightLines()).containsExactlyElementsOf(expected);
    }

    @Test
    void removedLineKeepsOldNumberAndHasNoNewNumber() throws IOException {
        Hunk second = pr1().hunks().get(1);
        List<DiffLine> removed = second.lines().stream()
                .filter(l -> l.type() == LineType.REMOVED).toList();
        assertThat(removed).hasSize(1);
        assertThat(removed.get(0).oldLine()).isEqualTo(42);
        assertThat(removed.get(0).newLine()).isNull();
        assertThat(removed.get(0).text()).isEqualTo("}");
    }

    @Test
    void noNewlineMarkerIsNotACodeLine() throws IOException {
        // 3 context + 1 removed + 5 added = 9; the two "\ No newline" lines add nothing
        assertThat(pr1().hunks().get(1).lines()).hasSize(9);
    }

    @Test
    void lineTextHasNoDiffPrefix() throws IOException {
        DiffLine line = pr1().hunks().get(0).lines().stream()
                .filter(l -> l.newLine() != null && l.newLine() == 16)
                .findFirst().orElseThrow();
        assertThat(line.type()).isEqualTo(LineType.ADDED);
        assertThat(line.text())
                .isEqualTo("    public String findByName(String name) throws SQLException {");
    }

    @Test
    void emptyOrNullPatchGivesNoHunks() {
        assertThat(parser.parse(null).hunks()).isEmpty();
        assertThat(parser.parse("").hunks()).isEmpty();
    }

    @Test
    void newFilePatchStartsAtLineOne() {
        ParsedPatch p = parser.parse("@@ -0,0 +1,2 @@\n+a\n+b");
        assertThat(p.hunks().get(0).oldStart()).isZero();
        assertThat(p.addedLines()).containsExactly(1, 2);
    }

    @Test
    void truncatedHunkIsRejected() {
        assertThatThrownBy(() -> parser.parse("@@ -1,3 +1,3 @@\n a\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyRealFixtureParsesAndMatchesItsHunkCounts() throws Exception {
        Path dir = Path.of(getClass().getResource("/fixtures").toURI());
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> patches = files.filter(f -> f.toString().endsWith(".patch")).toList();
            assertThat(patches).isNotEmpty();
            for (Path f : patches) {
                ParsedPatch p = parser.parse(Files.readString(f));
                for (Hunk h : p.hunks()) {
                    long oldN = h.lines().stream().filter(l -> l.type() != LineType.ADDED).count();
                    long newN = h.lines().stream().filter(l -> l.type() != LineType.REMOVED).count();
                    assertThat(oldN).as(f.getFileName().toString()).isEqualTo(h.oldCount());
                    assertThat(newN).as(f.getFileName().toString()).isEqualTo(h.newCount());
                }
            }
        }
    }
}