package com.gpmanager;
import java.util.*;
import lombok.*;
/** Source-backed quantities and their distinct source/encounter owners, in observation order. */
@AllArgsConstructor
class Cy {
static final Cy NONE = new Cy(false, "", "General", Aj.GENERIC, null,
Collections.<Integer, Long>emptyMap(), Collections.emptyList());
final boolean matched;
final String note;
final String activityName;
final Aj context;
final String encounterId;
final Map<Integer, Long> matchedQuantities;
final List<Cy> sources;
}
