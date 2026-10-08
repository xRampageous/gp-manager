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
* <p>Call {@link #lu()} before processing RuneLite's login slot replay. Snapshots
* received until {@link #tg()} establish baselines and produce no transitions. On
* relog, starting another seed clears old slot state so the replay cannot look like new activity.</p>
*/
@Singleton
class Bj {
Map<Integer, Snapshot> bySlot = new LinkedHashMap<>();
boolean loginSeed;
/** Begin a login/relogin baseline pass; all observations are suppressed until it is finished. */
synchronized void lu() {
 bySlot.clear();
 loginSeed = true;
}

/** Finish the baseline pass after the caller has received the initial offer-slot replay. */
synchronized void tg() {
 loginSeed = false;
}

/**
* Observe one immutable slot snapshot. Repeated identical snapshots, a first empty slot and all
* login-seed observations return an empty Optional.
*/
synchronized Optional<Transition> observe(Snapshot /* current */a) {
 Objects.requireNonNull(a, "current");
 Snapshot /* previous */b = bySlot.put(a.slot, a);
 if (loginSeed || a.equals(b) || a.state == GrandExchangeOfferState.EMPTY
 && (b == null || b.state == GrandExchangeOfferState.EMPTY)) {
  return Optional.empty();
 }
 return Optional.of(new Transition(b, a));
}

/**
* Take an immutable point-in-time copy of the latest per-slot observations. The returned map
* cannot be used to mutate this ledger.
*/
synchronized Map<Integer, Snapshot> snapshots() {
 return Collections.unmodifiableMap(new LinkedHashMap<>(bySlot));
}

/** The offer side a state belongs to; null for an empty slot. */
static Aa.Side sideOf(GrandExchangeOfferState /* state */a) {
 return a == GrandExchangeOfferState.BUYING || a == GrandExchangeOfferState.BOUGHT
 || a == GrandExchangeOfferState.CANCELLED_BUY ? Aa.Side.BUY
 : a == GrandExchangeOfferState.SELLING || a == GrandExchangeOfferState.SOLD
 || a == GrandExchangeOfferState.CANCELLED_SELL ? Aa.Side.SELL : null;
}

static boolean isActive(GrandExchangeOfferState /* state */a) {
 return a == GrandExchangeOfferState.BUYING || a == GrandExchangeOfferState.SELLING;
}

static boolean isTerminal(GrandExchangeOfferState /* state */a) {
 return sideOf(a) != null && !isActive(a);
}

/** Immutable copy of the API fields used by the offer ledger. */
@EqualsAndHashCode
@AllArgsConstructor
static class Snapshot {
 int slot;
 GrandExchangeOfferState state;
 int itemId;
 int totalQuantity;
 /** RuneLite's quantity bought or sold so far, copied from getQuantitySold(). */
 int quantityTraded;
 long price;
 /** Raw getSpent() observation; the model does not assign sell-side or tax semantics. */
 long spent;
 static Snapshot fromOffer(int /* slot */b, GrandExchangeOffer /* offer */c) {
  return new Snapshot(b, c.getState(), c.getItemId(), c.getTotalQuantity(),
  c.getQuantitySold(), c.getPrice(), c.getSpent());
 }
}

/** One observed slot change: the previous snapshot (null when first seen) and the current one. */
@AllArgsConstructor
static class Transition {
 Snapshot previous;
 Snapshot current;
}
}
