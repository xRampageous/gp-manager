package com.gpmanager;

import java.util.Locale;

/** Display-name defaults and phrase matching shared by the model and the classifiers. */
class Phrases {
    /** Blank activity names fall back to the General bucket. */
    static String normalizeActivity(String value) {
        return nonBlank(value, "General");
    }

    static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    /** Trimmed lower-case text; null reads as empty. */
    static String lower(String value) {
        return lower(value, true);
    }

    /** Some chat/target rules intentionally distinguish leading spaces. */
    static String lower(String value, boolean trim) {
        return value == null ? "" : (trim ? value.trim() : value).toLowerCase(Locale.ROOT);
    }

    static boolean empty(String text) {
        return text == null || text.isEmpty();
    }

    static boolean empty(java.util.Collection<?> values) {
        return values == null || values.isEmpty();
    }

    static boolean empty(java.util.Map<?, ?> values) {
        return values == null || values.isEmpty();
    }

    static boolean blank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /** The trimmed text, or the fallback when it is blank. */
    static String nonBlank(String text, String fallback) {
        return blank(text) ? fallback : text.trim();
    }

    /** Trimmed lower-case text, or null when blank. */
    static String norm(String value) {
        String key = lower(value);
        return key.isEmpty() ? null : key;
    }

    /** True when the text contains any of the words. */
    static boolean has(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    /** True when any non-null term contains the already lower-case needle. */
    static boolean containsFolded(String lowerNeedle, String... terms) {
        for (String term : terms) {
            if (term != null && term.toLowerCase(Locale.ROOT).contains(lowerNeedle)) return true;
        }
        return false;
    }

    /**
     * A compiled table clause: contains, =exact, ^prefix, $suffix, %pattern, ~case-insensitive
     * exact (Unicode equalsIgnoreCase), with optional !.
     */
    static class Clause {
        String text;
        int kind;
        boolean negated;
        java.util.regex.Pattern pattern;

        Clause(String spec) {
            if (spec.startsWith("@")) spec = GameData.msg(spec.substring(1));
            negated = spec.startsWith("!");
            if (negated) spec = spec.substring(1);
            kind = spec.isEmpty() ? -1 : "=^$%~".indexOf(spec.charAt(0));
            text = kind < 0 ? spec : spec.substring(1);
            pattern = kind == 3 ? GameData.pattern(text) : null;
        }
    }

    /** Alternatives of conjunctions; all parsing and regex compilation happens at table load. */
    static boolean has(String text, Clause[][] rules) {
        if (text == null) return false;
        for (Clause[] clauses : rules) if (containsAll(text, clauses)) return true;
        return false;
    }

    static boolean containsAll(String text, Clause[] clauses) {
        for (Clause clause : clauses) {
            boolean hit =
                    clause.kind == 0
                            ? text.equals(clause.text)
                            : clause.kind == 1
                                    ? text.startsWith(clause.text)
                                    : clause.kind == 2
                                            ? text.endsWith(clause.text)
                                            : clause.kind == 3
                                                    ? clause.pattern.matcher(text).matches()
                                                    : clause.kind == 4
                                                            ? text.equalsIgnoreCase(clause.text)
                                                            : text.contains(clause.text);
            if (hit == clause.negated) return false;
        }
        return true;
    }
}
