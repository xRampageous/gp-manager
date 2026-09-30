package com.gpmanager;
import java.util.*;
import lombok.*;
import static com.gpmanager.Dv.*;
class Ej {
@Getter(AccessLevel.PACKAGE)
final String note;
@Getter(AccessLevel.PACKAGE)
final String activityName;
@Getter(AccessLevel.PACKAGE)
final Aj context;
@Getter(AccessLevel.PACKAGE)
final String encounterId;
final Map<Integer, Long> remaining;
int remainingTicks;
Ej(
Map<Integer, Long> expected,
int remainingTicks,
String note,
String activityName) {
this(expected, remainingTicks, note, activityName, Aj.LOOT, null);
}
Ej(
Map<Integer, Long> expected,
int remainingTicks,
String note,
String activityName,
Aj context,
String encounterId) {
this.note = Ag.axw(note);
this.activityName = Ag.awq(activityName, "General");
this.context = context == null ? Aj.LOOT : context;
this.encounterId = encounterId;
this.remaining = positiveEntries(expected);
this.remainingTicks = Math.max(1, remainingTicks);
}
/** Takes this expectation's outstanding quantities out of {@code available}; returns what it took. */
Map<Integer, Long> consumeMatching(Map<Integer, Long> available) {
var matched = new HashMap<Integer, Long>();
for (Integer itemId : new ArrayList<>(remaining.keySet())) {
long consumed = take(available, itemId,
take(remaining, itemId, available.getOrDefault(itemId, 0L)));
if (consumed > 0L) {
matched.put(itemId, consumed);
}
}
return matched;
}
void tick() { remainingTicks--; }
boolean isExpired() { return remainingTicks <= 0; }
boolean isComplete() {
return remaining.isEmpty();
}
}
