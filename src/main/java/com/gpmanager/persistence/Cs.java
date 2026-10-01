package com.gpmanager;
/**
* Immutable destination and fencing for one persistence attempt.
*
* <p>Queued and retry writes must carry this intent so a later account switch
* cannot redirect the payload. {@code expectedBaseRevision} is the on-disk
* revision observed when the snapshot was taken; under the write lock the
* repository refuses the save unless disk still matches.
*
* <p>A non-null {@code json} is the payload, serialized once under the engine lock; {@code state}
* is then only its header (revision, savedAt), already final and never serialized.
*/
class Cs {
final TrackingIdentity identity;
final long scopeGeneration;
final long expectedBaseRevision;
final SavedState state;
final String json;
Cs(TrackingIdentity identity, long scopeGeneration, long expectedBaseRevision, SavedState state) {
 this(identity, scopeGeneration, expectedBaseRevision, state, null);
}

Cs(TrackingIdentity identity, long scopeGeneration, long expectedBaseRevision, SavedState state, String json) {
 if (state == null) throw new IllegalArgumentException("state");
 this.identity = identity;
 this.scopeGeneration = scopeGeneration;
 this.expectedBaseRevision = Ae.nonNeg(expectedBaseRevision);
 this.state = state;
 this.json = json;
}
}
