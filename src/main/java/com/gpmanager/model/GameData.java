package com.gpmanager;
import static com.gpmanager.GameData.msg;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
/**
* Game-knowledge tables (item ids, region ids, names) bundled under /com/gpmanager/model as
* tab-separated resources: one row per line, {@code #} lines are comments. GameDataTablesTest checks every
* table against RuneLite's named constants.
*/
class GameData {
/** The rows of {@code name}.tsv; a missing table is a packaging bug, so it fails loudly. */
static List<String[]> rows(String name) {
 InputStream stream = GameData.class.getResourceAsStream("/com/gpmanager/model/" + name + ".tsv");
 if (stream == null) throw new IllegalStateException("Missing game data table " + name);
 var rows = new ArrayList<String[]>();
 try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
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

static final Map<String, String> TEXT = byName("text");
static final Map<String, Pattern> PATTERNS = new HashMap<>();
static {
 for (String[] row : rows("d18"))
 PATTERNS.put(row[0], Pattern.compile(row[1], Integer.parseInt(row[2])));
}

static Pattern pattern(String key) {
 return Objects.requireNonNull(PATTERNS.get(key), "Missing pattern " + key);
}

/** Text keyed by a prefix and an enum constant ("verb.TRADE"), or the fallback when it has no row. */
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
