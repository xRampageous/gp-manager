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
TrackingIdentity identity;
long scopeGeneration;
long expectedBaseRevision;
SavedState state;
String json;
Cs(TrackingIdentity /* identity */a, long /* scopeGeneration */b, long /* expectedBaseRevision */c, SavedState /* state */d) {
 this(a, b, c, d, null);
}

Cs(TrackingIdentity /* identity */a, long /* scopeGeneration */b, long /* expectedBaseRevision */c, SavedState /* state */d, String /* json */e) {
 if (d == null) throw new IllegalArgumentException("state");
 this.identity = a;
 this.scopeGeneration = b;
 this.expectedBaseRevision = Ae.nonNeg(c);
 this.state = d;
 this.json = e;
}
}
