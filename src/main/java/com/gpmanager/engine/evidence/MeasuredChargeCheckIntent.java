package com.gpmanager;
/**
* One-shot identity evidence linking a Check menu action to its numeric chat read.
* The click provides no quantity and can never authorize a cost on its own.
*/
class MeasuredChargeCheckIntent {
ChargeRead.Variant variant;
String targetIdentity;
int expiresAfterTick;
synchronized boolean arm(ChargeRead.Variant variant, String targetIdentity, int currentTick, int validTicks) {
clear();
if (variant == null || !variant.isImplemented() || ModelText.blank(targetIdentity) || validTicks <= 0) {
return false;
}
this.variant = variant;
this.targetIdentity = targetIdentity;
this.expiresAfterTick = currentTick + validTicks;
return true;
}

/** Consume only a matching exact Check message before the short click window expires. */
synchronized String consume(ChargeRead read, int currentTick) {
if (variant == null || targetIdentity == null || currentTick > expiresAfterTick
|| read == null || !read.bookable || read.variant != variant) {
clear();
return null;
}
String identity = targetIdentity;
clear();
return identity;
}

synchronized void clear() {
variant = null;
targetIdentity = null;
expiresAfterTick = 0;
}
}
