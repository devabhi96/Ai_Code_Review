package com.devabhi.aicodereview.review.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the "patch" field of a GitHub PR file (hunks only, no file headers).
 * Malformed input throws IllegalArgumentException; the caller skips that file with a reason.
 */
public final class DiffParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@ ?(.*)$");

    public ParsedPatch parse(String patch) {
        if (patch == null || patch.isBlank()) {
            return new ParsedPatch(List.of());
        }
        List<Hunk> hunks = new ArrayList<>();
        HunkBuilder current = null;

        for (String line : patch.split("\r?\n", -1)) {
            Matcher m = HUNK_HEADER.matcher(line);
            if (m.matches()) {
                if (current != null) {
                    hunks.add(current.build());
                }
                current = new HunkBuilder(m);
                continue;
            }
            if (current == null) {
                continue; // anything before the first @@ is ignored
            }
            if (line.startsWith("\\")) {
                continue; // "\ No newline at end of file"
            }
            if (line.isEmpty() && current.isComplete()) {
                continue; // trailing newline
            }
            current.add(line);
        }
        if (current != null) {
            hunks.add(current.build());
        }
        return new ParsedPatch(List.copyOf(hunks));
    }

    private static final class HunkBuilder {
        private final int oldStart;
        private final int oldCount;
        private final int newStart;
        private final int newCount;
        private final String heading;
        private final List<DiffLine> lines = new ArrayList<>();
        private int nextOld;
        private int nextNew;
        private int remainingOld;
        private int remainingNew;

        HunkBuilder(Matcher m) {
            oldStart = Integer.parseInt(m.group(1));
            oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            newStart = Integer.parseInt(m.group(3));
            newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
            heading = m.group(5);
            nextOld = oldStart;
            nextNew = newStart;
            remainingOld = oldCount;
            remainingNew = newCount;
        }

        boolean isComplete() {
            return remainingOld == 0 && remainingNew == 0;
        }

        void add(String line) {
            char c = line.isEmpty() ? ' ' : line.charAt(0);
            String text = line.isEmpty() ? "" : line.substring(1);
            switch (c) {
                case ' ' -> {
                    require(remainingOld > 0 && remainingNew > 0, line);
                    lines.add(new DiffLine(LineType.CONTEXT, nextOld++, nextNew++, text));
                    remainingOld--;
                    remainingNew--;
                }
                case '+' -> {
                    require(remainingNew > 0, line);
                    lines.add(new DiffLine(LineType.ADDED, null, nextNew++, text));
                    remainingNew--;
                }
                case '-' -> {
                    require(remainingOld > 0, line);
                    lines.add(new DiffLine(LineType.REMOVED, nextOld++, null, text));
                    remainingOld--;
                }
                default -> throw new IllegalArgumentException("Unexpected diff line: " + line);
            }
        }

        private static void require(boolean ok, String line) {
            if (!ok) {
                throw new IllegalArgumentException("Line exceeds hunk header counts: " + line);
            }
        }

        Hunk build() {
            if (!isComplete()) {
                throw new IllegalArgumentException("Truncated hunk starting at old line " + oldStart);
            }
            return new Hunk(oldStart, oldCount, newStart, newCount, heading, List.copyOf(lines));
        }
    }
}