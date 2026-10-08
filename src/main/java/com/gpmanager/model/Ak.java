package com.gpmanager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
/**
* Game-knowledge tables (item ids, region ids, names) bundled under /com/gpmanager/model as
* tab-separated resources: one row per line, {@code #} lines are comments. GameDataTablesTest checks every
* table against RuneLite's named constants.
*/
class Ak {
/** Rule tables alone are cached: callers of legacy rows still receive independent arrays. */
static final Map<String, List<String[]>> X = new HashMap<>();
static final Map<String, Map<String, Ag.P[][]>> Q = new HashMap<>();
static synchronized List<String[]> x(String a) { return X.computeIfAbsent(a, Ak::rows); }
/** One outcome's row, for labels initialized once in an existing enum constructor. */
static String[] v(String a, String b) {
 return x(a).stream().filter(c -> c[2].equals(b)).findFirst().orElseThrow();
}
/** Whole-column text aliases may contain the existing alternative/conjunction grammar. */
static String t(String a) { return a.matches("@[\\w.-]+") ? msg(a.substring(1)) : a; }

/** The rows of {@code name}.tsv; a missing table is a packaging bug, so it fails loudly. */
static List<String[]> rows(String /* name */a) {
 InputStream /* stream */b = Ak.class.getResourceAsStream("/com/gpmanager/model/" + a + ".tsv");
 if (b == null) throw new IllegalStateException("Missing game data table " + a);
 var /* rows */c = new ArrayList<String[]>();
 try (BufferedReader /* reader */d = new BufferedReader(new InputStreamReader(b, StandardCharsets.UTF_8))) {
  for (String /* line */f = d.readLine(); f != null; f = d.readLine()) {
   if (!f.isEmpty() && !f.startsWith("#")) c.add(f.split("\t", -1));
  }
 } catch (IOException e) {
  throw new UncheckedIOException(e);
 }
 return c;
}

/** {@code name}.tsv keyed by its first-column id, valued by its second column. */
static Map<Integer, String> byId(String /* name */a) {
 var /* map */b = new HashMap<Integer, String>();
 for (String[] /* row */c : rows(a)) b.put(Integer.parseInt(c[0]), c[1]);
 return b;
}

/** Ordered first-column names; useful for catalogues whose first match wins. */
static List<String> names(String /* name */a) {
 var /* names */b = new ArrayList<String>();
 for (String[] /* row */c : rows(a)) b.add(c[0]);
 return b;
}

/** Exact first-column names mapped to their second-column labels. */
static Map<String, String> byName(String /* name */a) {
 var /* map */b = new HashMap<String, String>();
 for (String[] /* row */c : rows(a)) b.put(c[0], c[1]);
 return b;
}

/** Ordered boolean rules: | alternatives, + conjunctions, compiled once by each catalogue. */
static synchronized Map<String, Ag.P[][]> r(String a) {
 if (Q.containsKey(a)) return Q.get(a);
 var b = new LinkedHashMap<String, Ag.P[][]>();
 for (String[] c : x(a)) {
  b.put(c[0], Arrays.stream(t(c[1]).split("\\|", -1)).filter(d -> !d.isEmpty())
  .map(d -> Arrays.stream(d.split("\\+", -1)).map(Ag.P::new).toArray(Ag.P[]::new))
  .toArray(Ag.P[][]::new));
 }
 Q.put(a, b);
 return b;
}

static final Map<String, String> TEXT = byName("text");
static final Map<String, Pattern> PATTERNS = new HashMap<>();
static {
 for (String[] /* row */b : rows("d18"))
 PATTERNS.put(b[0], Pattern.compile(b[1].startsWith("@") ? msg(b[1].substring(1)) : b[1], Integer.parseInt(b[2])));
}

static Pattern pattern(String /* key */a) {
 return Objects.requireNonNull(PATTERNS.get(a), "Missing pattern " + a);
}

/** Text keyed by a prefix and an enum constant ("verb.TRADE"), or the fallback when it has no row. */
static String msg(String /* key */a, String /* fallback */b) {
 return TEXT.getOrDefault(a, b);
}

/** Long user-facing text from text.tsv; a test checks every key the code uses. */
static String msg(String /* key */a) {
 String /* text */b = TEXT.get(a);
 if (b == null) throw new IllegalStateException("Missing text " + a);
 return b;
}
}
