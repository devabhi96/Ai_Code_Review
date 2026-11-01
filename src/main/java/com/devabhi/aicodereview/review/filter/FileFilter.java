package com.devabhi.aicodereview.review.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Decides which changed files are worth reviewing; every skip has a human-readable reason. */
public final class FileFilter {

    public static final String REASON_DELETED = "deleted file";
    public static final String REASON_IGNORED = "ignored by repository config";
    public static final String REASON_LOCKFILE = "lockfile";
    public static final String REASON_GENERATED = "generated, build output or vendored code";
    public static final String REASON_RENAMED_ONLY = "renamed without content changes";
    public static final String REASON_NO_PATCH = "no diff available (binary or too large)";
    public static final String REASON_UNSUPPORTED = "unsupported language";

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("java", "js", "jsx", "ts", "tsx", "mjs", "cjs");
    private static final Set<String> LOCKFILES =
            Set.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "npm-shrinkwrap.json");
    private static final Set<String> GENERATED_DIRS =
            Set.of("node_modules", "dist", "build", "target", "vendor", "generated");
    private static final List<String> GENERATED_SUFFIXES =
            List.of(".min.js", ".min.css", ".map", ".bundle.js");

    public FilterResult filter(List<ChangedFile> files, List<String> ignoreGlobs) {
        List<Pattern> ignore = ignoreGlobs.stream().map(FileFilter::globToRegex).toList();
        List<ChangedFile> reviewable = new ArrayList<>();
        List<SkippedFile> skipped = new ArrayList<>();
        for (ChangedFile file : files) {
            String reason = skipReason(file, ignore);
            if (reason == null) {
                reviewable.add(file);
            } else {
                skipped.add(new SkippedFile(file.filename(), reason));
            }
        }
        return new FilterResult(List.copyOf(reviewable), List.copyOf(skipped));
    }

    private String skipReason(ChangedFile file, List<Pattern> ignore) {
        String path = file.filename();
        if ("removed".equals(file.status())) {
            return REASON_DELETED;
        }
        for (Pattern p : ignore) {
            if (p.matcher(path).matches()) {
                return REASON_IGNORED;
            }
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (LOCKFILES.contains(name) || name.endsWith(".lock")) {
            return REASON_LOCKFILE;
        }
        if (isGenerated(path, name)) {
            return REASON_GENERATED;
        }
        if (file.patch() == null || file.patch().isBlank()) {
            return "renamed".equals(file.status()) ? REASON_RENAMED_ONLY : REASON_NO_PATCH;
        }
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        if (!SUPPORTED_EXTENSIONS.contains(ext)) {
            return ext.isEmpty()
                    ? REASON_UNSUPPORTED + " (no file extension)"
                    : REASON_UNSUPPORTED + ": ." + ext;
        }
        return null;
    }

    private static boolean isGenerated(String path, String name) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (GENERATED_DIRS.contains(parts[i])) {
                return true;
            }
        }
        String lower = name.toLowerCase();
        return GENERATED_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    /** Glob to regex: ** crosses directories, * stays inside one, ? is one character. */
    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                        sb.append("(?:.*/)?");
                        i += 3;
                    } else {
                        sb.append(".*");
                        i += 2;
                    }
                } else {
                    sb.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return Pattern.compile(sb.append("$").toString());
    }
}