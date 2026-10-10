package com.gpmanager;

import lombok.*;

import java.util.*;

/** Source-backed quantities and their distinct source/encounter owners, in observation order. */
@AllArgsConstructor
class LootMatch {
    static final LootMatch NONE =
            new LootMatch(
                    false,
                    "",
                    "General",
                    Context.GENERIC,
                    null,
                    Collections.<Integer, Long>emptyMap(),
                    Collections.emptyList());
    boolean matched;
    String note;
    String activityName;
    Context context;
    String encounterId;
    Map<Integer, Long> matchedQuantities;
    List<LootMatch> sources;
}
