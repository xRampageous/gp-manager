package com.gpmanager;
import java.util.*;
import lombok.*;
/** Source-backed quantities and their distinct source/encounter owners, in observation order. */
@AllArgsConstructor
class LootMatch {
static final LootMatch NONE = new LootMatch(false, "", "General", Context.GENERIC, null,
Collections.<Integer, Long>emptyMap(), Collections.emptyList());
final boolean matched;
final String note;
final String activityName;
final Context context;
final String encounterId;
final Map<Integer, Long> matchedQuantities;
final List<LootMatch> sources;
}
