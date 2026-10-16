package com.gpmanager;

import static java.lang.Math.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Game-knowledge tables (item ids, region ids, names) bundled under /com/gpmanager/model as
 * tab-separated resources: one row per line, {@code #} lines are comments. GameDataTablesTest
 * checks every table against RuneLite's named constants.
 */
class GameData {
    /** Rule tables alone are cached: callers of legacy rows still receive independent arrays. */
    static final Map<String, List<String[]>> ROWS = new HashMap<>();

    static final Map<String, Map<String, Phrases.Clause[][]>> RULES = new HashMap<>();

    static synchronized List<String[]> cachedRows(String name) {
        return ROWS.computeIfAbsent(name, GameData::rows);
    }

    /** Whole-column text aliases may contain the existing alternative/conjunction grammar. */
    static String text(String value) {
        return value.matches("@[\\w.-]+") ? msg(value.substring(1)) : value;
    }

    /** The rows of {@code name}.tsv; a missing table is a packaging bug, so it fails loudly. */
    static List<String[]> rows(String name) {
        InputStream stream =
                GameData.class.getResourceAsStream("/com/gpmanager/model/" + name + ".tsv");
        if (stream == null) throw new IllegalStateException("Missing game data table " + name);
        var rows = new ArrayList<String[]>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isEmpty() && !line.startsWith("#")) rows.add(line.split("\t", -1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    /** {@code name}.tsv keyed by its first-column id, valued by its second column. */
    static Map<Integer, String> byId(String name) {
        var map = new HashMap<Integer, String>();
        for (String[] row : rows(name)) map.put(Integer.parseInt(row[0]), row[1]);
        return map;
    }

    /** Ordered first-column names; useful for catalogues whose first match wins. */
    static List<String> names(String name) {
        var names = new ArrayList<String>();
        for (String[] row : rows(name)) names.add(row[0]);
        return names;
    }

    /** Exact first-column names mapped to their second-column labels. */
    static Map<String, String> byName(String name) {
        var map = new HashMap<String, String>();
        for (String[] row : rows(name)) map.put(row[0], row[1]);
        return map;
    }

    /** Ordered boolean rules: | alternatives, + conjunctions, compiled once by each catalogue. */
    static synchronized Map<String, Phrases.Clause[][]> rules(String name) {
        if (RULES.containsKey(name)) return RULES.get(name);
        var compiled = new LinkedHashMap<String, Phrases.Clause[][]>();
        for (String[] row : cachedRows(name)) {
            compiled.put(
                    row[0],
                    Arrays.stream(text(row[1]).split("\\|", -1))
                            .filter(d -> !d.isEmpty())
                            .map(
                                    d ->
                                            Arrays.stream(d.split("\\+", -1))
                                                    .map(Phrases.Clause::new)
                                                    .toArray(Phrases.Clause[]::new))
                            .toArray(Phrases.Clause[][]::new));
        }
        RULES.put(name, compiled);
        return compiled;
    }

    static final Map<String, String> TEXT = byName("text");
    static final Map<String, Pattern> PATTERNS = new HashMap<>();

    static {
        for (String[] row : rows("patterns"))
            PATTERNS.put(
                    row[0],
                    Pattern.compile(
                            row[1].startsWith("@") ? msg(row[1].substring(1)) : row[1],
                            Integer.parseInt(row[2])));
    }

    static Pattern pattern(String key) {
        return Objects.requireNonNull(PATTERNS.get(key), "Missing pattern " + key);
    }

    /**
     * Text keyed by a prefix and an enum constant ("verb.TRADE"), or the fallback when it has no
     * row.
     */
    static String msg(String key, String fallback) {
        return TEXT.getOrDefault(key, fallback);
    }

    /** Long user-facing text from text.tsv; a test checks every key the code uses. */
    static String msg(String key) {
        String text = TEXT.get(key);
        if (text == null) throw new IllegalStateException("Missing text " + key);
        return text;
    }
}

/**
 * Saturating long/int arithmetic shared by the accounting model; overflow clamps toward the sign of
 * the addend.
 */
class SafeMath {
    /** The wall clock every accounting read uses; the rework's replay sets it to recorded time. */
    static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    static long now() {
        return clock.getAsLong();
    }

    /** Absolute value that saturates instead of overflowing on {@code Long.MIN_VALUE}. */
    /** The value, or zero when it is negative. */
    static long nonNeg(long value) {
        return max(0L, value);
    }

    static long abs(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    static long safeAdd(long left, long right) {
        try {
            return addExact(left, right);
        } catch (ArithmeticException ex) {
            return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    static long safeSubtract(long left, long right) {
        try {
            return subtractExact(left, right);
        } catch (ArithmeticException ex) {
            return right < 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    static long safeMultiply(long left, long right) {
        try {
            return multiplyExact(left, right);
        } catch (ArithmeticException ex) {
            return (left >= 0L) == (right >= 0L) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** A long clamped into the int range (unit prices). */
    static int toInt(long value) {
        return (int) max(Integer.MIN_VALUE, min(Integer.MAX_VALUE, value));
    }

    /** Non-negative count sum saturating at {@link Integer#MAX_VALUE}. */
    static int safeAddCount(int left, int right) {
        long sum = (long) max(0, left) + max(0, right);
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}

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
