package com.devabhi.aicodereview.review.diff;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public record ParsedPatch(List<Hunk> hunks) {

    /** New-file line numbers a RIGHT-side comment may attach to (added + context). */
    public Set<Integer> validRightLines() {
        return hunks.stream()
                .flatMap(h -> h.lines().stream())
                .filter(l -> l.type() != LineType.REMOVED)
                .map(DiffLine::newLine)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** New-file line numbers of added lines only. */
    public Set<Integer> addedLines() {
        return hunks.stream()
                .flatMap(h -> h.lines().stream())
                .filter(l -> l.type() == LineType.ADDED)
                .map(DiffLine::newLine)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}