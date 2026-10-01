package com.gpmanager;
/**
* One presentation law for "is this GP/h trustworthy yet". A computed rate is only
* meaningful once the Grind has real Active Time behind it; before that the canonical number is
* a data-availability artifact, not a rate, and every surface must render the unavailable form
* ("—") instead of a fabricated 0/h.
*
* <p>The minimum window is the historical accepted value (the retired {@code RateAvailability}
* used the same 60 seconds); it is presentation-only and never affects accounting.</p>
*/
class RateReadiness {
/** Minimum Active Time before a computed rate is presentation-trustworthy. */
static final long MIN_ACTIVE_MILLIS = 60_000L;
/**
* GP/h is Net over canonical Active Time; it is shown once that Active Time reaches the floor.
*
* @param activeMillis                  canonical Active Time of the Grind
*/
static boolean isFullRateEstablished(long activeMillis) {
return activeMillis >= MIN_ACTIVE_MILLIS;
}
}
