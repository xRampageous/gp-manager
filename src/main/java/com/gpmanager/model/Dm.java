package com.gpmanager;
import static com.gpmanager.Ae.nonNeg;
import static java.lang.Math.*;
/**
* Personal item-split provenance helpers. Splits adjust personal Net share via
* the correction timeline — never Party settlement, never fake Used eats.
*/
class Dm {
static final String SPLIT_SHARE_MARK = "Split share";
static final String SPLIT_KEEP_PREFIX = "Split keep ";
static String reason(long /* keepQuantity */a, long /* totalQuantity */b, String /* optionalNote */c) {
 long /* total */d = nonNeg(b);
 long /* keep */e = nonNeg(min(a, d));
 var /* sb */f = new StringBuilder(SPLIT_KEEP_PREFIX).append(e).append('/').append(d)
 .append(" · ").append(SPLIT_SHARE_MARK);
 if (c != null) {
  String /* trimmed */g = c.trim();
  if (!g.isEmpty()) f.append(" · ").append(g.length() > 80 ? g.substring(0, 80) : g);
 }
 String /* value */h = f.toString();
 return h.length() > 200 ? h.substring(0, 200) : h;
}

static final java.util.Map<String, Boolean> SPLIT_TEXT = new java.util.concurrent.ConcurrentHashMap<>();
static boolean xr(String /* reasonOrExplanation */a) {
 if (Ag.empty(a)) return false;
 // Receipt texts are shared strings read on every tick: decide each distinct text once.
 if (SPLIT_TEXT.size() > 10_000) SPLIT_TEXT.clear();
 return SPLIT_TEXT.computeIfAbsent(a,
 /* text */b -> Ag.has(b.toLowerCase(java.util.Locale.ROOT), "split keep", "split share"));
}
}
