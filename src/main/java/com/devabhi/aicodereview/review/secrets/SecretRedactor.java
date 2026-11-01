package com.devabhi.aicodereview.review.secrets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds secrets in text and replaces them with [REDACTED:type]. Line structure is never
 * changed, so line numbers stay valid. Use one Session per file: it tracks multi-line private keys.
 */
public final class SecretRedactor {

    public record LineResult(String text, List<String> types) {
    }

    private record Rule(String type, Pattern pattern, int valueGroup) {
    }

    private record Hit(int rule, int start, int end, String type) {
    }

    private static final String GENERIC = "generic_secret";

    // Order matters: when two rules hit the same span, the earlier (more specific) rule wins.
    private static final List<Rule> RULES = List.of(
            new Rule("aws_access_key_id",
                    Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), 0),
            new Rule("aws_secret_access_key",
                    Pattern.compile("(?i)aws_?secret_?access_?key[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9/+=]{40})"), 1),
            new Rule("github_token",
                    Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"), 0),
            new Rule("github_token",
                    Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{22,}\\b"), 0),
            new Rule("slack_token",
                    Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"), 0),
            new Rule(GENERIC,
                    Pattern.compile("(?i)\\b[\\w.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)"
                            + "[\\w.-]*[\"']?\\s*[:=]\\s*[\"']([^\"'\\s]{8,})[\"']"), 1));

    private static final Pattern KEY_BEGIN =
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY(?: BLOCK)?-----");
    private static final Pattern KEY_END =
            Pattern.compile("-----END [A-Z ]*PRIVATE KEY(?: BLOCK)?-----");
    private static final Pattern PLAIN_LOWERCASE = Pattern.compile("^[a-z][a-z_.-]*$");

    private static final List<String> PLACEHOLDER_PARTS = List.of(
            "example", "placeholder", "changeme", "change-me", "redacted",
            "your_", "your-", "${", "{{", "<");
    private static final List<String> PLACEHOLDER_PREFIXES = List.of(
            "test", "fake", "mock", "dummy", "sample", "xxx");

    public Session newSession() {
        return new Session();
    }

    /** Redacts a multi-line text (PR title, description, file content). Null stays null. */
    public String redactText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Session session = newSession();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = session.redactLine(lines[i]).text();
        }
        return String.join("\n", lines);
    }

    public static final class Session {

        private boolean insidePrivateKey;

        private Session() {
        }

        public LineResult redactLine(String line) {
            if (insidePrivateKey) {
                if (KEY_END.matcher(line).find()) {
                    insidePrivateKey = false;
                }
                return new LineResult(marker("private_key"), List.of());
            }
            Matcher begin = KEY_BEGIN.matcher(line);
            if (begin.find()) {
                String prefix = line.substring(0, begin.start());
                Matcher end = KEY_END.matcher(line);
                if (end.find(begin.end())) {
                    return new LineResult(prefix + marker("private_key") + line.substring(end.end()),
                            List.of("private_key"));
                }
                insidePrivateKey = true;
                return new LineResult(prefix + marker("private_key"), List.of("private_key"));
            }
            return redactPlainLine(line);
        }
    }

    private static LineResult redactPlainLine(String line) {
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < RULES.size(); i++) {
            Rule rule = RULES.get(i);
            Matcher m = rule.pattern().matcher(line);
            while (m.find()) {
                int start = m.start(rule.valueGroup());
                if (start < 0) {
                    continue;
                }
                int end = m.end(rule.valueGroup());
                if (isPlaceholder(line.substring(start, end), GENERIC.equals(rule.type()))) {
                    continue;
                }
                hits.add(new Hit(i, start, end, rule.type()));
            }
        }
        if (hits.isEmpty()) {
            return new LineResult(line, List.of());
        }
        hits.sort(Comparator.comparingInt(Hit::start).thenComparingInt(Hit::rule));
        StringBuilder out = new StringBuilder();
        List<String> types = new ArrayList<>();
        int cursor = 0;
        for (Hit hit : hits) {
            if (hit.start() < cursor) {
                continue; // overlaps a span we already redacted
            }
            out.append(line, cursor, hit.start()).append(marker(hit.type()));
            types.add(hit.type());
            cursor = hit.end();
        }
        out.append(line, cursor, line.length());
        return new LineResult(out.toString(), List.copyOf(types));
    }

    private static boolean isPlaceholder(String value, boolean generic) {
        String v = value.toLowerCase(Locale.ROOT);
        for (String part : PLACEHOLDER_PARTS) {
            if (v.contains(part)) {
                return true;
            }
        }
        for (String prefix : PLACEHOLDER_PREFIXES) {
            if (v.startsWith(prefix)) {
                return true;
            }
        }
        if (v.chars().distinct().count() == 1) {
            return true; // aaaaaaaa, ********
        }
        // config keys and plain words ("refresh_token_value") are not secrets
        return generic && PLAIN_LOWERCASE.matcher(value).matches();
    }

    private static String marker(String type) {
        return "[REDACTED:" + type + "]";
    }
}