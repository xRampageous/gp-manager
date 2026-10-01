package com.gpmanager;
import java.util.*;
import lombok.*;
import static com.gpmanager.FlowFilters.*;
class LootExpectation {
@Getter(AccessLevel.PACKAGE)
final String note;
@Getter(AccessLevel.PACKAGE)
final String activityName;
@Getter(AccessLevel.PACKAGE)
final Context context;
@Getter(AccessLevel.PACKAGE)
final String encounterId;
final Map<Integer, Long> remaining;
int remainingTicks;
LootExpectation(Map<Integer, Long> expected, int remainingTicks, String note, String activityName) {
this(expected, remainingTicks, note, activityName, Context.LOOT, null);
}

LootExpectation(Map<Integer, Long> expected, int remainingTicks, String note, String activityName, Context context,
String encounterId) {
this.note = ModelText.orEmpty(note);
this.activityName = ModelText.nonBlank(activityName, "General");
this.context = context == null ? Context.LOOT : context;
this.encounterId = encounterId;
this.remaining = positiveEntries(expected);
this.remainingTicks = Math.max(1, remainingTicks);
}

/** Takes this expectation's outstanding quantities out of {@code available}; returns what it took. */
Map<Integer, Long> consumeMatching(Map<Integer, Long> available) {
var matched = new HashMap<Integer, Long>();
for (Integer itemId : new ArrayList<>(remaining.keySet())) {
long consumed = take(available, itemId, take(remaining, itemId, available.getOrDefault(itemId, 0L)));
if (consumed > 0L) matched.put(itemId, consumed);
}
return matched;
}

void tick() { remainingTicks--; }
boolean isExpired() { return remainingTicks <= 0; }
boolean isComplete() {
return remaining.isEmpty();
}
}
