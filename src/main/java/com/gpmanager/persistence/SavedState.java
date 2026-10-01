package com.gpmanager;
import java.util.*;
import lombok.*;
import static com.gpmanager.SafeMath.nonNeg;
import static com.gpmanager.ModelText.*;
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
Session generalSession;
Session customSession;
boolean generalSuspendedByCustom;
List<Session> history = new ArrayList<>();
/** UTC date of the last receipt-retention sweep, empty before the first tick. */
String lastReceiptRetentionDayUtc = "";
/** IANA timezone captured for this profile's local-date analytics. Empty means uninitialized. */
String profileTimeZoneId = "";
/** Bounded PvP retention state. */
PkHistoryState pkHistory = new PkHistoryState();
/** Reusable My Grinds definitions: setup metadata only, never financial history. */
List<SavedGrind> savedGrinds = new ArrayList<>();
/**
* Bounded GE custody continuity: the minimum durable state that keeps exact economics across
* restart for offers whose placement was observed. Physical custody is ownership-neutral; a
* realized Market settlement is canonical once.
*/
List<GeRecord> geCustody = new ArrayList<>();
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
List<PendingClaim> pendingClaims;
/**
* Schema-101 physical continuity for an unresolved local PvM death; null on schema-100 files
* and whenever no death awaits reclaim. Evidence only — never valuation, fees or labels.
*/
PendingDeathReclaim pendingDeathReclaim;
SavedState() {
}

/**
* Revalidates optional schema-105 action-label presentation metadata after a load. Malformed,
* oversized or non-compatible values degrade to null; a malformed label never fails the load,
* never backfills and never infers a spell name from rune recipes.
*/
void normalizeActionLabels() {
normalizeActionLabels(generalSession);
normalizeActionLabels(customSession);
for (Session session : getHistory()) normalizeActionLabels(session);
}

static void normalizeActionLabels(Session session) {
if (session == null) return;
for (Transaction transaction : session.getTransactions()) {
if (transaction != null) transaction.normalizeObservedActionLabel();
}
}

List<SavedGrind> getSavedGrinds() {
if (savedGrinds == null) savedGrinds = new ArrayList<>();
return savedGrinds;
}

void setSavedGrinds(List<SavedGrind> values) {
savedGrinds = values == null ? new ArrayList<>() : new ArrayList<>(values);
}

List<GeRecord> getGeCustody() {
if (geCustody == null) geCustody = new ArrayList<>();
return geCustody;
}

void setGeCustody(List<GeRecord> values) {
geCustody = new ArrayList<>();
if (values != null) {
for (GeRecord value : values) {
if (value != null && !value.getOfferId().isEmpty()) geCustody.add(value);
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
* canonical Session history linked through the stable {@code grindId}.
*/
@Setter
static class SavedGrind {
String grindId;
String name;
Long netTargetGp;
Long activeTimeTargetMillis;
boolean favorite;
boolean archived;
SavedGrind() {
}
SavedGrind(String grindId, String name, Long netTargetGp, Long activeTimeTargetMillis, boolean favorite) {
this.grindId = grindId;
this.name = name;
this.netTargetGp = netTargetGp;
this.activeTimeTargetMillis = activeTimeTargetMillis;
this.favorite = favorite;
}
String getGrindId() { return orEmpty(grindId); }
String getName() { return orEmpty(name); }
Long getNetTargetGp() { return netTargetGp != null && netTargetGp > 0L ? netTargetGp : null; }
Long getActiveTimeTargetMillis() {
return activeTimeTargetMillis != null && activeTimeTargetMillis > 0L ? activeTimeTargetMillis : null;
}
}

SavedState(Session generalSession, Session customSession, boolean generalSuspendedByCustom, List<Session> history) {
this.savedAtEpochMillis = System.currentTimeMillis();
this.generalSession = generalSession;
this.customSession = customSession;
this.generalSuspendedByCustom = generalSuspendedByCustom;
this.history = new ArrayList<>(history == null ? new ArrayList<>() : history);
}

/** True only for the 1.0 schema; anything else is preserved read-only and never rewritten. */
boolean isSupportedSchema() {
return schemaVersion == CURRENT_SCHEMA_VERSION;
}

/** The owner that was tracking when this state was saved. */
Session getActiveSession() {
return customSession != null ? customSession : generalSession;
}

List<Session> getHistory() {
if (history == null) history = new ArrayList<>();
return history;
}

String getLastReceiptRetentionDayUtc() {
return orEmpty(lastReceiptRetentionDayUtc);
}

void setLastReceiptRetentionDayUtc(String day) {
lastReceiptRetentionDayUtc = day == null ? "" : day.trim();
}

String getProfileTimeZoneId() {
return orEmpty(profileTimeZoneId);
}

void setProfileTimeZoneId(String value) {
profileTimeZoneId = value == null ? "" : value.trim();
}

PkHistoryState getPkHistory() {
if (pkHistory == null) pkHistory = new PkHistoryState();
return pkHistory;
}

List<PendingClaim> getPendingClaims() {
return pendingClaims == null ? emptyList() : unmodifiableList(pendingClaims);
}

void setPendingClaims(List<PendingClaim> values) {
pendingClaims = empty(values) ? null : new ArrayList<>(values);
}

void setPendingDeathReclaim(PendingDeathReclaim value) {
pendingDeathReclaim = value == null || value.isEmpty() ? null : value;
}

/**
* The durable physical continuity of an unresolved local PvM death: the exact quantities the
* gravestone/retrieval service holds, the still-unsettled held-at-death whitelist, and the
* bounded lifecycle counters. Identity and quantity only — no GP valuation, quote, fee,
* coffer or presentation data. Canonical transactions remain the sole financial authority.
*/
static class PendingDeathReclaim {
List<DeathItem> outstandingItems;
List<DeathItem> heldAtDeath;
int wipeTicksRemaining;
int gravestoneAgeTicks;
PendingDeathReclaim() {
}
PendingDeathReclaim(List<DeathItem> outstandingItems, List<DeathItem> heldAtDeath, int wipeTicksRemaining,
int gravestoneAgeTicks) {
this.outstandingItems = copyItems(outstandingItems);
this.heldAtDeath = copyItems(heldAtDeath);
this.wipeTicksRemaining = max(0, wipeTicksRemaining);
this.gravestoneAgeTicks = max(0, gravestoneAgeTicks);
}
List<DeathItem> getOutstandingItems() {
return outstandingItems == null ? emptyList() : unmodifiableList(outstandingItems);
}
List<DeathItem> getHeldAtDeath() {
return heldAtDeath == null ? emptyList() : unmodifiableList(heldAtDeath);
}
int getWipeTicksRemaining() { return max(0, wipeTicksRemaining); }
int getGravestoneAgeTicks() { return max(0, gravestoneAgeTicks); }
boolean isEmpty() {
return getOutstandingItems().isEmpty() && getHeldAtDeath().isEmpty();
}
static List<DeathItem> copyItems(List<DeathItem> values) {
if (empty(values)) return null;
var copy = new ArrayList<DeathItem>();
for (DeathItem value : values) {
if (value != null && value.isValid()) copy.add(value);
}
return copy.isEmpty() ? null : copy;
}
}

/** One persisted item identity and quantity for {@link PendingDeathReclaim}. */
static class DeathItem {
int itemId;
long quantity;
DeathItem() {
}
DeathItem(int itemId, long quantity) {
this.itemId = itemId;
this.quantity = nonNeg(quantity);
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
static class PendingClaim {
String claimId;
int itemOrKeyId;
long quantity;
long createdAtEpochMillis;
PendingClaim() {
}
PendingClaim(String claimId, int itemOrKeyId, long quantity, long createdAtEpochMillis) {
this.claimId = claimId;
this.itemOrKeyId = itemOrKeyId;
this.quantity = nonNeg(quantity);
this.createdAtEpochMillis = nonNeg(createdAtEpochMillis);
}
String getClaimId() { return orEmpty(claimId); }
long getQuantity() { return nonNeg(quantity); }
long getCreatedAtEpochMillis() { return nonNeg(createdAtEpochMillis); }
boolean isValid() {
return !getClaimId().isEmpty() && itemOrKeyId > 0 && getQuantity() > 0L;
}
}
}
