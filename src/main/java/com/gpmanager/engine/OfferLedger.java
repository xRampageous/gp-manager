package com.gpmanager;
import java.util.*;
import net.runelite.api.*;
import lombok.*;
import javax.inject.Singleton;
/**
* Slot-keyed, observation-only record of the Grand Exchange offer state exposed by RuneLite. It
* screens duplicate snapshots and hands each real change to custody as a previous/current pair;
* it assigns no financial meaning to any field and never books anything.
*
* <p>Call {@link #beginLoginSeed()} before processing RuneLite's login slot replay. Snapshots
* received until {@link #finishLoginSeed()} establish baselines and produce no transitions. On
* relog, starting another seed clears old slot state so the replay cannot look like new activity.</p>
*/
@Singleton
class OfferLedger {
final Map<Integer, Snapshot> bySlot = new LinkedHashMap<>();
boolean loginSeed;
/** Begin a login/relogin baseline pass; all observations are suppressed until it is finished. */
synchronized void beginLoginSeed() {
bySlot.clear();
loginSeed = true;
}

/** Finish the baseline pass after the caller has received the initial offer-slot replay. */
synchronized void finishLoginSeed() {
loginSeed = false;
}

/**
* Observe one immutable slot snapshot. Repeated identical snapshots, a first empty slot and all
* login-seed observations return an empty Optional.
*/
synchronized Optional<Transition> observe(Snapshot current) {
Objects.requireNonNull(current, "current");
Snapshot previous = bySlot.put(current.slot, current);
if (loginSeed || current.equals(previous) || current.state == GrandExchangeOfferState.EMPTY
&& (previous == null || previous.state == GrandExchangeOfferState.EMPTY)) {
return Optional.empty();
}
return Optional.of(new Transition(previous, current));
}

/**
* Take an immutable point-in-time copy of the latest per-slot observations. The returned map
* cannot be used to mutate this ledger.
*/
synchronized Map<Integer, Snapshot> snapshots() {
return Collections.unmodifiableMap(new LinkedHashMap<>(bySlot));
}

/** The offer side a state belongs to; null for an empty slot. */
static GeRecord.Side sideOf(GrandExchangeOfferState state) {
return state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
|| state == GrandExchangeOfferState.CANCELLED_BUY ? GeRecord.Side.BUY
: state == GrandExchangeOfferState.SELLING || state == GrandExchangeOfferState.SOLD
|| state == GrandExchangeOfferState.CANCELLED_SELL ? GeRecord.Side.SELL : null;
}

static boolean isActive(GrandExchangeOfferState state) {
return state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING;
}

static boolean isTerminal(GrandExchangeOfferState state) {
return sideOf(state) != null && !isActive(state);
}

/** Immutable copy of the API fields used by the offer ledger. */
@EqualsAndHashCode
@AllArgsConstructor
static class Snapshot {
final int slot;
final GrandExchangeOfferState state;
final int itemId;
final int totalQuantity;
/** RuneLite's quantity bought or sold so far, copied from getQuantitySold(). */
final int quantityTraded;
final long price;
/** Raw getSpent() observation; the model does not assign sell-side or tax semantics. */
final long spent;
static Snapshot fromOffer(int slot, GrandExchangeOffer offer) {
return new Snapshot(slot, offer.getState(), offer.getItemId(), offer.getTotalQuantity(),
offer.getQuantitySold(), offer.getPrice(), offer.getSpent());
}
}

/** One observed slot change: the previous snapshot (null when first seen) and the current one. */
@AllArgsConstructor
static class Transition {
final Snapshot previous;
final Snapshot current;
}
}
