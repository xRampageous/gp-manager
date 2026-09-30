package com.gpmanager;
/**
* One presentation law for "is this GP/h trustworthy yet". A computed rolling rate is only
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
* @param rollingRateAvailable canonical metric availability (data present and retained)
* @param elapsedMillis        canonical Active Time of the Grind
*/
static boolean wm(boolean rollingRateAvailable, long elapsedMillis) {
return rollingRateAvailable && elapsedMillis >= MIN_ACTIVE_MILLIS;
}
/**
* One shared law for a completed-Grind full-session rate (Net / canonical Active Time). The
* rolling window is irrelevant here: the full-session rate needs an exact accounting
* projection plus the same minimum Active Time floor.
*
* @param accountingProjectionAvailable canonical Net availability
* @param activeMillis                  canonical Active Time of the completed Grind
*/
static boolean wn(long activeMillis) {
return activeMillis >= MIN_ACTIVE_MILLIS;
}
}
