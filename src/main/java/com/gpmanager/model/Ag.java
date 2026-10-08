package com.gpmanager;
import java.util.Locale;
/** Display-name defaults and phrase matching shared by the model and the classifiers. */
class Ag {
/** Blank activity names fall back to the General bucket. */
static String aas(String /* value */a) {
 return awq(a, "General");
}

static String axw(String /* text */a) {
 return a == null ? "" : a;
}

/** Trimmed lower-case text; null reads as empty. */
static String lower(String /* value */a) {
 return lower(a, true);
}

/** Some chat/target rules intentionally distinguish leading spaces. */
static String lower(String a, boolean b) {
 return a == null ? "" : (b ? a.trim() : a).toLowerCase(Locale.ROOT);
}

static boolean empty(String /* text */a) {
 return a == null || a.isEmpty();
}

static boolean empty(java.util.Collection<?> /* values */a) {
 return a == null || a.isEmpty();
}

static boolean empty(java.util.Map<?, ?> /* values */a) {
 return a == null || a.isEmpty();
}

static boolean blank(String /* text */a) {
 return a == null || a.trim().isEmpty();
}

/** The trimmed text, or the fallback when it is blank. */
static String awq(String /* text */a, String /* fallback */b) {
 return blank(a) ? b : a.trim();
}

/** Trimmed lower-case text, or null when blank. */
static String norm(String /* value */a) {
 String /* key */b = lower(a);
 return b.isEmpty() ? null : b;
}

/** True when the text contains any of the words. */
static boolean has(String /* text */a, String... /* words */b) {
 for (String /* word */c : b) {
  if (a.contains(c)) return true;
 }
 return false;
}

static boolean qk(String /* text */a, String... /* words */b) {
 for (String /* word */c : b) {
  if (!a.contains(c)) return false;
 }
 return true;
}

/** A compiled table clause: contains, =exact, ^prefix, $suffix, %pattern, ~case-insensitive exact (Unicode equalsIgnoreCase), with optional !. */
static class P {
 String s;
 int k;
 boolean n;
 java.util.regex.Pattern p;
 P(String a) {
  if (a.startsWith("@")) a = Ak.msg(a.substring(1));
  n = a.startsWith("!");
  if (n) a = a.substring(1);
  k = a.isEmpty() ? -1 : "=^$%~".indexOf(a.charAt(0));
  s = k < 0 ? a : a.substring(1);
  p = k == 3 ? Ak.pattern(s) : null;
 }
}

/** Alternatives of conjunctions; all parsing and regex compilation happens at table load. */
static boolean has(String a, P[][] b) {
 if (a == null) return false;
 for (P[] c : b) if (qk(a, c)) return true;
 return false;
}

static boolean qk(String a, P[] b) {
 for (P c : b) {
  boolean d = c.k == 0 ? a.equals(c.s) : c.k == 1 ? a.startsWith(c.s)
  : c.k == 2 ? a.endsWith(c.s) : c.k == 3 ? c.p.matcher(a).matches() : c.k == 4 ? a.equalsIgnoreCase(c.s) : a.contains(c.s);
  if (d == c.n) return false;
 }
 return true;
}
}
