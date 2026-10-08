package com.gpmanager;
import java.util.*;
import lombok.*;
import static com.gpmanager.Ae.nonNeg;
import static com.gpmanager.Ag.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
/**
* The durable account state. Contains only facts needed after restart: owners and sessions,
* bounded daily rollups plus the lifetime archive they fold into and minimal unresolved claims.
*
* <p>Schema {@value #CURRENT_SCHEMA_VERSION} is the 1.0 data schema. There is no upgrade path:
* files written before 1.0 are never read, and anything newer is preserved read-only.
*/
class SavedState {
/** The 1.0 data schema. Nothing older is read: 1.0 starts fresh with no upgrade path. */
static final int CURRENT_SCHEMA_VERSION = 108;
/** Newest UTC daily rollups retained for chart/window detail; older days fold into the archive. */
@Setter
int schemaVersion = CURRENT_SCHEMA_VERSION;
@Setter
long savedAtEpochMillis;
@Setter
long revision;
/** RS profile key that owns this file; null/empty means unbound. */
@Setter
String ownerKey;
Ad generalSession;
Ad customSession;
boolean generalSuspendedByCustom;
List<Ad> history = new ArrayList<>();
/** UTC date of the last receipt-retention sweep, empty before the first tick. */
String lastReceiptRetentionDayUtc = "";
/** IANA timezone captured for this profile's local-date analytics. Empty means uninitialized. */
String profileTimeZoneId = "";
/** Bounded PvP retention state. */
Cf pkHistory = new Cf();
/** Reusable My Grinds definitions: setup metadata only, never financial history. */
List<Ap> savedGrinds = new ArrayList<>();
/**
* Bounded GE custody continuity: the minimum durable state that keeps exact economics across
* restart for offers whose placement was observed. Physical custody is ownership-neutral; a
* realized Market settlement is canonical once.
*/
List<Aa> geCustody = new ArrayList<>();
/**
* Pooled tracked-basis continuity: available per-item previously-counted value, reserved totals
* held by open SELL custody, the per-item realization fence and the basis epoch.
*/
@Setter
TrackedBasisState trackedBasis;
/**
* Schema-105 compact evolution: optional bounded proven action-label presentation metadata on
* canonical retained transactions (for example an exact "Ice Burst" name). It is revalidated
* on load, never a financial input, and disappears with its retained receipt.
*/
List<By> pendingClaims;
/**
* Schema-101 physical continuity for an unresolved local PvM death; null on schema-100 files
* and whenever no death awaits reclaim. Evidence only — never valuation, fees or labels.
*/
Bw pendingDeathReclaim;
SavedState() {
}

/**
* Revalidates optional schema-105 action-label presentation metadata after a load. Malformed,
* oversized or non-compatible values degrade to null; a malformed label never fails the load,
* never backfills and never infers a spell name from rune recipes.
*/
void aar() {
 aar(generalSession);
 aar(customSession);
 for (Ad /* session */a : getHistory()) aar(a);
}

static void aar(Ad /* session */a) {
 if (a == null) return;
 for (Ac /* transaction */b : a.getTransactions()) {
  if (b != null) b.aax();
 }
}

List<Ap> getSavedGrinds() {
 if (savedGrinds == null) savedGrinds = new ArrayList<>();
 return savedGrinds;
}

void setSavedGrinds(List<Ap> /* values */a) {
 savedGrinds = a == null ? new ArrayList<>() : new ArrayList<>(a);
}

List<Aa> getGeCustody() {
 if (geCustody == null) geCustody = new ArrayList<>();
 return geCustody;
}

void setGeCustody(List<Aa> /* values */a) {
 geCustody = new ArrayList<>();
 if (a != null) {
  for (Aa /* value */b : a) {
   if (b != null && !b.getOfferId().isEmpty()) geCustody.add(b);
  }
 }
}

TrackedBasisState getTrackedBasis() {
 if (trackedBasis == null) trackedBasis = new TrackedBasisState();
 return trackedBasis;
}

/**
* One reusable My Grind definition: setup metadata and default targets only. It never owns
* transactions, Net, Recent Loot, Active-Time progress or correction state; those stay in the
* canonical Ad history linked through the stable {@code grindId}.
*/
@Setter
static class Ap {
 String grindId;
 String name;
 Long netTargetGp;
 Long activeTimeTargetMillis;
 boolean favorite;
 boolean archived;
 Ap() {
 }
 Ap(String /* grindId */b, String /* name */c, Long /* netTargetGp */d, Long /* activeTimeTargetMillis */e, boolean /* favorite */f) {
  this.grindId = b;
  this.name = c;
  this.netTargetGp = d;
  this.activeTimeTargetMillis = e;
  this.favorite = f;
 }
 String getGrindId() { return axw(grindId); }
 String getName() { return axw(name); }
 Long getNetTargetGp() { return netTargetGp != null && netTargetGp > 0L ? netTargetGp : null; }
 Long getActiveTimeTargetMillis() {
  return activeTimeTargetMillis != null && activeTimeTargetMillis > 0L ? activeTimeTargetMillis : null;
 }
}

SavedState(Ad /* generalSession */a, Ad /* customSession */b, boolean /* generalSuspendedByCustom */c, List<Ad> /* history */d) {
 this.savedAtEpochMillis = Ae.now();
 this.generalSession = a;
 this.customSession = b;
 this.generalSuspendedByCustom = c;
 this.history = new ArrayList<>(d == null ? new ArrayList<>() : d);
}

/** True only for the 1.0 schema; anything else is preserved read-only and never rewritten. */
boolean ye() {
 return schemaVersion == CURRENT_SCHEMA_VERSION;
}

/** The owner that was tracking when this state was saved. */
Ad getActiveSession() {
 return customSession != null ? customSession : generalSession;
}

List<Ad> getHistory() {
 if (history == null) history = new ArrayList<>();
 return history;
}

String getLastReceiptRetentionDayUtc() {
 return axw(lastReceiptRetentionDayUtc);
}

void setLastReceiptRetentionDayUtc(String /* day */a) {
 lastReceiptRetentionDayUtc = a == null ? "" : a.trim();
}

String getProfileTimeZoneId() {
 return axw(profileTimeZoneId);
}

void setProfileTimeZoneId(String /* value */a) {
 profileTimeZoneId = a == null ? "" : a.trim();
}

Cf getPkHistory() {
 if (pkHistory == null) pkHistory = new Cf();
 return pkHistory;
}

List<By> getPendingClaims() {
 return pendingClaims == null ? emptyList() : unmodifiableList(pendingClaims);
}

void setPendingClaims(List<By> /* values */a) {
 pendingClaims = empty(a) ? null : new ArrayList<>(a);
}

void setPendingDeathReclaim(Bw /* value */a) {
 pendingDeathReclaim = a == null || a.isEmpty() ? null : a;
}

/**
* The durable physical continuity of an unresolved local PvM death: the exact quantities the
* gravestone/retrieval service holds, the still-unsettled held-at-death whitelist, and the
* bounded lifecycle counters. Identity and quantity only — no GP valuation, quote, fee,
* coffer or presentation data. Canonical transactions remain the sole financial authority.
*/
static class Bw {
 List<Bg> outstandingItems;
 List<Bg> heldAtDeath;
 int wipeTicksRemaining;
 int gravestoneAgeTicks;
 Bw() {
 }
 Bw(List<Bg> /* outstandingItems */b, List<Bg> /* heldAtDeath */c, int /* wipeTicksRemaining */d,
 int /* gravestoneAgeTicks */e) {
  this.outstandingItems = copyItems(b);
  this.heldAtDeath = copyItems(c);
  this.wipeTicksRemaining = max(0, d);
  this.gravestoneAgeTicks = max(0, e);
 }
 List<Bg> getOutstandingItems() {
  return outstandingItems == null ? emptyList() : unmodifiableList(outstandingItems);
 }
 List<Bg> getHeldAtDeath() {
  return heldAtDeath == null ? emptyList() : unmodifiableList(heldAtDeath);
 }
 int getWipeTicksRemaining() { return max(0, wipeTicksRemaining); }
 int getGravestoneAgeTicks() { return max(0, gravestoneAgeTicks); }
 boolean isEmpty() {
  return getOutstandingItems().isEmpty() && getHeldAtDeath().isEmpty();
 }
 static List<Bg> copyItems(List<Bg> /* values */b) {
  if (empty(b)) return null;
  var /* copy */c = new ArrayList<Bg>();
  for (Bg /* value */d : b) {
   if (d != null && d.isValid()) c.add(d);
  }
  return c.isEmpty() ? null : c;
 }
}

/** One persisted item identity and quantity for {@link Bw}. */
static class Bg {
 int itemId;
 long quantity;
 Bg() {
 }
 Bg(int /* itemId */b, long /* quantity */c) {
  this.itemId = b;
  this.quantity = nonNeg(c);
 }
 long getQuantity() { return nonNeg(quantity); }
 boolean isValid() {
  return itemId > 0 && getQuantity() > 0L;
 }
}

/**
* The one durable evidence exception: an unresolved key/chest claim that must survive restart.
* Idempotent by {@code claimId}; a claim-closing transaction carries the same id as
* {@code sourceClaimId}, while a partly settled claim keeps its reduced {@code quantity}.
* Claims intentionally carry no cross-session ownership edge.
*/
static class By {
 String claimId;
 int itemOrKeyId;
 long quantity;
 long createdAtEpochMillis;
 By() {
 }
 By(String /* claimId */b, int /* itemOrKeyId */c, long /* quantity */d, long /* createdAtEpochMillis */e) {
  this.claimId = b;
  this.itemOrKeyId = c;
  this.quantity = nonNeg(d);
  this.createdAtEpochMillis = nonNeg(e);
 }
 String getClaimId() { return axw(claimId); }
 long getQuantity() { return nonNeg(quantity); }
 long getCreatedAtEpochMillis() { return nonNeg(createdAtEpochMillis); }
 boolean isValid() {
  return !getClaimId().isEmpty() && itemOrKeyId > 0 && getQuantity() > 0L;
 }
}
}
