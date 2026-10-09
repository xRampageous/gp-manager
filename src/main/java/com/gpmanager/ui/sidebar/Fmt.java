package com.gpmanager;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.gpmanager.Ae.nonNeg;
import static java.util.Locale.*;
import static java.lang.Math.*;
/** Number, time and label formatting shared by the sidebar (compact by default, exact on demand). */
class Fmt {
static final NumberFormat EXACT = NumberFormat.getIntegerInstance(UK);
/** Curated short activity names (master plan, Activity presentation): exact names first. */
static final List<String[]> SHORT = Ak.rows("d8");
/** Phrases shortened wherever they appear ("Theatre of Blood (Hard Mode)" becomes "ToB (HM)"). */
static final List<String[]> PHRASES = Ak.rows("d6");
/**
* The short activity name every surface shows: curated names (KBD, GE Clerk), raid names with
* their mode (ToB (HM)), and any other trailing "(qualifier)" dropped.
*/
static String activity(String /* name */a) {
 String /* value */b = a == null ? "" : a.trim();
 for (String[] /* row */c : SHORT) {
  if (c[0].equalsIgnoreCase(b)) return c[1];
 }
 for (String[] /* row */d : PHRASES) b = b.replaceAll("(?i)" + java.util.regex.Pattern.quote(d[0]), d[1]);
 int /* open */e = b.lastIndexOf(" (");
 boolean /* raid */f = b.startsWith("ToB") || b.startsWith("CoX") || b.startsWith("ToA");
 return e > 0 && b.endsWith(")") && !f ? b.substring(0, e) : b;
}

/**
* Reads a GP amount with an optional k/m/b suffix; unreadable or negative amounts return 0.
* Target inputs validate their whole-string syntax first; the display minimum is permissive.
*/
static long axx(String /* raw */a) {
 if (a == null) return 0L;
 String /* text */c = Ag.lower(a).replace(",", "").replace("_", "");
 if (c.isEmpty()) return 0L;
 long /* apf */d = 1L;
 if (c.endsWith("k") || c.endsWith("m") || c.endsWith("b")) {
  char /* suffix */e = c.charAt(c.length() - 1);
  d = e == 'k' ? 1_000L : e == 'm' ? 1_000_000L : 1_000_000_000L;
  c = c.substring(0, c.length() - 1).trim();
 }
 try {
  long /* value */f = round(Double.parseDouble(c) * d);
  return nonNeg(f);
 } catch (NumberFormatException /* ex */g) {
  return 0L;
 }
}

/** {@code +1.2k}, {@code −36.0k}, {@code 0}. Uses a true minus sign. */
static String signed(long /* value */a) {
 if (a == 0L) return "0";
 return (a > 0L ? "+" : "−") + compact(a);
}

/**
* Unsigned compact with fixed precision so columns line up:
* {@code 812} · {@code 1.2k} · {@code 36.0k} · {@code 248k} · {@code 1.76M} · {@code 12.4M} · {@code 212M} · {@code 1.02B}.
*/
static String compact(long /* value */a) {
 long /* abs */b = Ae.abs(a);
 if (b < 1_000L) return Long.toString(b);
 // Cut off, never rounded up, like the game's own stack numbers: 999,600 is 999k (owner 1.3.1).
 if (b < 100_000L) return String.format(ROOT, "%.1fk", b / 100 / 10d);
 if (b < 1_000_000L) return b / 1_000 + "k";
 if (b < 10_000_000L) return String.format(ROOT, "%.2fM", b / 10_000 / 100d);
 if (b < 100_000_000L) return String.format(ROOT, "%.1fM", b / 100_000 / 10d);
 if (b < 1_000_000_000L) return b / 1_000_000 + "M";
 if (b < 10_000_000_000L) return String.format(ROOT, "%.2fB", b / 10_000_000 / 100d);
 return String.format(ROOT, "%.1fB", b / 100_000_000 / 10d);
}

/** {@code +124,318} with grouping and a true minus sign. */
static String ru(long /* value */a) {
 if (a == 0L) return "0";
 return (a > 0L ? "+" : "−") + EXACT.format(abs(a));
}

static String exact(long /* value */a) {
 return EXACT.format(a);
}

/** Rate without unit: {@code 248k}. Callers add {@code /h}. */
/** Rates read like figures: {@code 248k}, or {@code −13.0k} when the hour is losing. */
static String rate(long /* perHour */a) {
 return a < 0L ? "\u2212" + compact(a) : compact(a);
}

/** {@code h:mm:ss} for the session clock; {@code m:ss} under an hour. */
static String clock(long /* millis */a) {
 long /* total */b = nonNeg(a) / 1000L;
 long h = b / 3600L;
 long m = (b % 3600L) / 60L;
 long s = b % 60L;
 return h > 0L ? String.format(ROOT, "%d:%02d:%02d", h, m, s) : String.format(ROOT, "%d:%02d", m, s);
}

/** Short duration for labels: {@code 8m 04s}, {@code 1h 12m}, {@code 2d 3h}. */
static String duration(long /* millis */a) {
 return duration(a, true);
}

static String awc(LocalDate /* day */a, long /* now */b, ZoneId /* zone */c) {
 ZoneId /* asa */e = c == null ? ZoneId.systemDefault() : c;
 LocalDate /* today */f = Instant.ofEpochMilli(b).atZone(e).toLocalDate();
 long /* ago */g = ChronoUnit.DAYS.between(a, f);
 if (g == 0L) return "Today";
 if (g == 1L) return "Yesterday";
 String /* pattern */h = a.getYear() == f.getYear() ? "EEE d MMM" : "d MMM yyyy";
 return a.format(DateTimeFormatter.ofPattern(h, ROOT));
}

/** Duration without seconds for tight cells: {@code 30m}, {@code 7h 40m}, {@code 2d 3h}. */
static String ra(long /* millis */a) {
 return duration(a, false);
}

static String duration(long /* millis */a, boolean /* seconds */b) {
 long /* total */c = nonNeg(a) / 1000L;
 long d = c / 86_400L;
 long h = (c % 86_400L) / 3600L;
 long m = (c % 3600L) / 60L;
 long s = c % 60L;
 if (d > 0L) return d + "d " + h + "h";
 if (h > 0L) return h + "h " + String.format(ROOT, "%02d", m) + "m";
 return m + "m" + (b ? String.format(ROOT, " %02ds", s) : "");
}

/** A target time in the form the Targets field reads back exactly: {@code 30m}, {@code 26h 30m}. */
static String akk(long /* millis */a) {
 long /* minutes */b = nonNeg(a) / 60_000L;
 return b >= 60L ? b / 60L + "h " + b % 60L + "m" : b + "m";
}

/** {@code ×27} quantity marker. */
static String times(long /* quantity */a) {
 return "×" + EXACT.format(abs(a));
}

/** {@code 62%} clamped. */
static String percent(double /* fraction */a) {
 int /* pct */b = (int) round(max(0d, min(1d, a)) * 100d);
 return b + "%";
}

/** Day and clock for a session start: {@code Today 20:45}, {@code Yesterday 09:12}, {@code Mon 8 Sep 20:45}. */
static String when(long /* epochMillis */a, long /* now */b) {
 ZoneId /* zone */c = ZoneId.systemDefault();
 LocalDate /* day */d = Instant.ofEpochMilli(a).atZone(c).toLocalDate();
 String /* clock */e = Instant.ofEpochMilli(a).atZone(c).format(DateTimeFormatter.ofPattern("HH:mm", ROOT));
 return awc(d, b, c) + " " + e;
}

/** Day, clock and seconds: same-minute restore points stay distinguishable (owner 2026-10-01, F22). */
static String whenSeconds(long /* epochMillis */a, long /* now */b) {
 ZoneId /* zone */c = ZoneId.systemDefault();
 return when(a, b) + ":" + Instant.ofEpochMilli(a).atZone(c)
 .format(DateTimeFormatter.ofPattern("ss", ROOT));
}

/** Relative age: {@code 3m ago}, {@code 2h ago}, {@code just now}. */
static String age(long /* ageMillis */a) {
 long s = nonNeg(a) / 1000L;
 if (s < 45L) return "just now";
 if (s < 3600L) return (s / 60L) + "m ago";
 if (s < 86_400L) return (s / 3600L) + "h ago";
 return (s / 86_400L) + "d ago";
}
}
