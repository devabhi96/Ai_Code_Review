package com.devabhi.aicodereview.review.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.Test;

class FileFilterTest {

    private static final String PATCH = "@@ -1 +1 @@\n-a\n+b";
    private static final String BASE = "src/main/java/com/demo/";

    private final FileFilter filter = new FileFilter();

    /** Mirrors the 8 real files from test PRs #1 to #3 in webhook-practice. */
    private static List<ChangedFile> pullRequestFiles() {
        return List.of(
                new ChangedFile(BASE + "UserRepository.java", "modified", null, PATCH),
                new ChangedFile("logo.png", "added", null, null),
                new ChangedFile(BASE + "NewName.java", "renamed", BASE + "OldName.java", PATCH),
                new ChangedFile(BASE + "Obsolete.java", "removed", null, PATCH),
                new ChangedFile("app.js", "added", null, PATCH),
                new ChangedFile("dist/bundle.min.js", "added", null, PATCH),
                new ChangedFile("package-lock.json", "modified", null, PATCH),
                new ChangedFile("script.py", "added", null, PATCH));
    }

    @Test
    void keepsOnlySupportedSourceFiles() {
        FilterResult result = filter.filter(pullRequestFiles(), List.of());
        assertThat(result.reviewable()).extracting(ChangedFile::filename)
                .containsExactlyInAnyOrder(BASE + "UserRepository.java", BASE + "NewName.java", "app.js");
    }

    @Test
    void everySkippedFileGetsItsOwnReason() {
        FilterResult result = filter.filter(pullRequestFiles(), List.of());
        assertThat(result.skipped()).extracting(SkippedFile::path, SkippedFile::reason)
                .containsExactlyInAnyOrder(
                        tuple("logo.png", FileFilter.REASON_NO_PATCH),
                        tuple(BASE + "Obsolete.java", FileFilter.REASON_DELETED),
                        tuple("dist/bundle.min.js", FileFilter.REASON_GENERATED),
                        tuple("package-lock.json", FileFilter.REASON_LOCKFILE),
                        tuple("script.py", FileFilter.REASON_UNSUPPORTED + ": .py"));
    }

    @Test
    void renamedFileIsReviewedUnderItsNewPath() {
        FilterResult result = filter.filter(pullRequestFiles(), List.of());
        ChangedFile renamed = result.reviewable().stream()
                .filter(f -> f.filename().endsWith("NewName.java")).findFirst().orElseThrow();
        assertThat(renamed.previousFilename()).isEqualTo(BASE + "OldName.java");
    }

    @Test
    void userGlobsSkipMatchingFiles() {
        FilterResult result = filter.filter(pullRequestFiles(),
                List.of(BASE + "User*.java", "**/*.js"));
        assertThat(result.skipped()).extracting(SkippedFile::path, SkippedFile::reason)
                .contains(tuple(BASE + "UserRepository.java", FileFilter.REASON_IGNORED),
                          tuple("app.js", FileFilter.REASON_IGNORED));
        assertThat(result.reviewable()).extracting(ChangedFile::filename)
                .containsExactly(BASE + "NewName.java");
    }

    @Test
    void doubleStarGlobCrossesDirectories() {
        assertThat(FileFilter.globToRegex("docs/**").matcher("docs/a/b.java").matches()).isTrue();
        assertThat(FileFilter.globToRegex("*.java").matcher("a/B.java").matches()).isFalse();
    }

    @Test
    void renameWithoutChangesIsExplainedNotCalledBinary() {
        FilterResult result = filter.filter(
                List.of(new ChangedFile(BASE + "B.java", "renamed", BASE + "A.java", null)), List.of());
        assertThat(result.skipped()).extracting(SkippedFile::reason)
                .containsExactly(FileFilter.REASON_RENAMED_ONLY);
    }
}