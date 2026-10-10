package com.gpmanager;

import lombok.*;

import java.util.*;

/** A load target's candidate losses; only a measured Check can reconcile their quantities. */
@AllArgsConstructor
class LoadTarget {
    ChargeCheck.ChargedItem variant;
    String targetIdentity;
    List<Flow> remaining;
    List<Flow> ambiguousCandidates;

    /** When the load was clicked; 0 when there was no load. */
    long clickedAt;
}
