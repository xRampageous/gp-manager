package com.gpmanager;
import java.util.*;
@FunctionalInterface
interface FlowValuator {
List<Flow> value(Map<Integer, Long> quantityDeltas);
/**
* Values flows using the caller's evidence-capture time. Existing valuators remain
* source-compatible and may ignore the timestamp until they support price provenance.
*/
default List<Flow> value(Map<Integer, Long> quantityDeltas, long priceCapturedAtEpochMillis) {
return value(quantityDeltas);
}

/**
* Applies an action-aware normalization after the engine has confirmed a consumption.
* Synthetic valuators keep their observed flows unchanged; the canonical valuator may
* normalize only the explicitly evidenced action and must preserve all source provenance.
*
* <p>Callers must invoke this after action settlement and before custody or booking. The
* timestamp is the same observation timestamp used to value the committed inventory delta.
* A {@code null} result means the confirmed action had an unsupported or conflicting price
* basis; callers must keep the original captured flows and route the receipt to Review.
*/
default List<Flow> normalizeConsumedFlows(List<Flow> flows, ActionKind actionKind, long priceCapturedAtEpochMillis) {
return flows;
}

/**
* Whether the current world/economy permits ordinary automatic market quotes. Synthetic
* test valuators default to the deterministic NORMAL fixture; the canonical
* {@link ItemValuationService} overrides this with its observed economy snapshot.
*/
default boolean automaticMarketQuotesAvailable() {
return true;
}
}
