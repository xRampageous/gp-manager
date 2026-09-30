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
static String reason(long keepQuantity, long totalQuantity, String optionalNote) {
long total = nonNeg(totalQuantity);
long keep = nonNeg(min(keepQuantity, total));
var sb = new StringBuilder(SPLIT_KEEP_PREFIX)
.append(keep).append('/').append(total)
.append(" · ").append(SPLIT_SHARE_MARK);
if (optionalNote != null) {
String trimmed = optionalNote.trim();
if (!trimmed.isEmpty()) {
sb.append(" · ").append(trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed);
}
}
String value = sb.toString();
return value.length() > 200 ? value.substring(0, 200) : value;
}
static final java.util.Map<String, Boolean> SPLIT_TEXT = new java.util.concurrent.ConcurrentHashMap<>();
static boolean xr(String reasonOrExplanation) {
if (Ag.empty(reasonOrExplanation)) {
return false;
}
// Receipt texts are shared strings read on every tick: decide each distinct text once.
if (SPLIT_TEXT.size() > 10_000) {
SPLIT_TEXT.clear();
}
return SPLIT_TEXT.computeIfAbsent(reasonOrExplanation,
text -> Ag.has(text.toLowerCase(), "split keep", "split share"));
}
}
