package com.gpmanager;
import java.util.*;
import lombok.*;
/** Source-backed quantities and their distinct source/encounter owners, in observation order. */
@AllArgsConstructor
class Cy {
static final Cy NONE = new Cy(false, "", "General", Aj.GENERIC, null,
Collections.<Integer, Long>emptyMap(), Collections.emptyList());
boolean matched;
String note;
String activityName;
Aj context;
String encounterId;
Map<Integer, Long> matchedQuantities;
List<Cy> sources;
}
