package com.gpmanager;
import java.util.*;
import lombok.*;
/** A load target's candidate losses; only a measured Check can reconcile their quantities. */
@AllArgsConstructor
class El {
 Ar.V variant;
 String targetIdentity;
 List<Ab> remaining;
 List<Ab> ambiguousCandidates;
}
