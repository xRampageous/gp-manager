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
static String activity(String name) {
String value = name == null ? "" : name.trim();
for (String[] row : SHORT) {
if (row[0].equalsIgnoreCase(value)) {
return row[1];
}
}
for (String[] row : PHRASES) {
value = value.replaceAll("(?i)" + java.util.regex.Pattern.quote(row[0]), row[1]);
}
int open = value.lastIndexOf(" (");
boolean raid = value.startsWith("ToB") || value.startsWith("CoX") || value.startsWith("ToA");
return open > 0 && value.endsWith(")") && !raid ? value.substring(0, open) : value;
}
/**
* Reads a GP amount with an optional k/m/b suffix; unreadable or negative amounts return 0.
* Target inputs validate their whole-string syntax first; the display minimum is permissive.
*/
static long axx(String raw) {
if (raw == null) {
return 0L;
}
String text = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
if (text.isEmpty()) {
return 0L;
}
long apf = 1L;
if (text.endsWith("k") || text.endsWith("m") || text.endsWith("b")) {
char suffix = text.charAt(text.length() - 1);
apf = suffix == 'k' ? 1_000L : suffix == 'm' ? 1_000_000L : 1_000_000_000L;
text = text.substring(0, text.length() - 1).trim();
}
try {
long value = round(Double.parseDouble(text) * apf);
return nonNeg(value);
} catch (NumberFormatException ex) {
return 0L;
}
}
/** {@code +1.2k}, {@code −36.0k}, {@code 0}. Uses a true minus sign. */
static String signed(long value) {
if (value == 0L) {
return "0";
}
return (value > 0L ? "+" : "−") + compact(value);
}
/**
* Unsigned compact with fixed precision so columns line up:
* {@code 812} · {@code 1.2k} · {@code 36.0k} · {@code 248k} · {@code 1.76M} · {@code 12.4M} · {@code 212M} · {@code 1.02B}.
*/
static String compact(long value) {
long abs = Ae.abs(value);
if (abs < 1_000L) {
return Long.toString(abs);
}
if (abs < 100_000L) {
return String.format(ROOT, "%.1fk", abs / 1_000d);
}
if (abs < 1_000_000L) {
return round(abs / 1_000d) + "k";
}
if (abs < 10_000_000L) {
return String.format(ROOT, "%.2fM", abs / 1_000_000d);
}
if (abs < 100_000_000L) {
return String.format(ROOT, "%.1fM", abs / 1_000_000d);
}
if (abs < 1_000_000_000L) {
return round(abs / 1_000_000d) + "M";
}
if (abs < 10_000_000_000L) {
return String.format(ROOT, "%.2fB", abs / 1_000_000_000d);
}
return String.format(ROOT, "%.1fB", abs / 1_000_000_000d);
}
/** {@code +124,318} with grouping and a true minus sign. */
static String ru(long value) {
if (value == 0L) {
return "0";
}
return (value > 0L ? "+" : "−") + EXACT.format(abs(value));
}
static String exact(long value) {
return EXACT.format(value);
}
/** Rate without unit: {@code 248k}. Callers add {@code /h}. */
/** Rates read like figures: {@code 248k}, or {@code −13.0k} when the hour is losing. */
static String rate(long perHour) {
return perHour < 0L ? "\u2212" + compact(perHour) : compact(perHour);
}
/** {@code h:mm:ss} for the session clock; {@code m:ss} under an hour. */
static String clock(long millis) {
long total = nonNeg(millis) / 1000L;
long h = total / 3600L;
long m = (total % 3600L) / 60L;
long s = total % 60L;
return h > 0L
? String.format(ROOT, "%d:%02d:%02d", h, m, s)
: String.format(ROOT, "%d:%02d", m, s);
}
/** Short duration for labels: {@code 8m 04s}, {@code 1h 12m}, {@code 2d 3h}. */
static String duration(long millis) {
return duration(millis, true);
}
static String awc(LocalDate day, long now, ZoneId zone) {
ZoneId asa = zone == null ? ZoneId.systemDefault() : zone;
LocalDate today = Instant.ofEpochMilli(now).atZone(asa).toLocalDate();
long ago = ChronoUnit.DAYS.between(day, today);
if (ago == 0L) {
return "Today";
}
if (ago == 1L) {
return "Yesterday";
}
String pattern = day.getYear() == today.getYear() ? "EEE d MMM" : "d MMM yyyy";
return day.format(DateTimeFormatter.ofPattern(pattern, ROOT));
}
/** Duration without seconds for tight cells: {@code 30m}, {@code 7h 40m}, {@code 2d 3h}. */
static String ra(long millis) {
return duration(millis, false);
}
static String duration(long millis, boolean seconds) {
long total = nonNeg(millis) / 1000L;
long d = total / 86_400L;
long h = (total % 86_400L) / 3600L;
long m = (total % 3600L) / 60L;
long s = total % 60L;
if (d > 0L) {
return d + "d " + h + "h";
}
if (h > 0L) {
return h + "h " + String.format(ROOT, "%02d", m) + "m";
}
return m + "m" + (seconds ? String.format(ROOT, " %02ds", s) : "");
}
/** A target time in the form the Targets field reads back exactly: {@code 30m}, {@code 26h 30m}. */
static String akk(long millis) {
long minutes = nonNeg(millis) / 60_000L;
return minutes >= 60L ? minutes / 60L + "h " + minutes % 60L + "m" : minutes + "m";
}
/** {@code ×27} quantity marker. */
static String times(long quantity) {
return "×" + EXACT.format(abs(quantity));
}
/** {@code 62%} clamped. */
static String percent(double fraction) {
int pct = (int) round(max(0d, min(1d, fraction)) * 100d);
return pct + "%";
}
/** Day and clock for a session start: {@code Today 20:45}, {@code Yesterday 09:12}, {@code Mon 8 Sep 20:45}. */
static String when(long epochMillis, long now) {
ZoneId zone = ZoneId.systemDefault();
LocalDate day = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate();
String clock = Instant.ofEpochMilli(epochMillis).atZone(zone)
.format(DateTimeFormatter.ofPattern("HH:mm", ROOT));
return awc(day, now, zone) + " " + clock;
}
/** Day, clock and seconds: same-minute restore points stay distinguishable (owner 2026-10-01, F22). */
static String whenSeconds(long epochMillis, long now) {
ZoneId zone = ZoneId.systemDefault();
return when(epochMillis, now) + ":"
+ Instant.ofEpochMilli(epochMillis).atZone(zone)
.format(DateTimeFormatter.ofPattern("ss", ROOT));
}
/** Relative age: {@code 3m ago}, {@code 2h ago}, {@code just now}. */
static String age(long ageMillis) {
long s = nonNeg(ageMillis) / 1000L;
if (s < 45L) {
return "just now";
}
if (s < 3600L) {
return (s / 60L) + "m ago";
}
if (s < 86_400L) {
return (s / 3600L) + "h ago";
}
return (s / 86_400L) + "d ago";
}
}
