package com.gpmanager;
import static com.gpmanager.GameData.msg;
import lombok.AllArgsConstructor;
import java.util.*;
/**
* Observed player action behind a settled inventory change. Presentation-only evidence:
* it names what the player did (drank, ate, decanted, cast) so HUD/receipts/Live/Ledger
* can use honest wording. It never changes {@link TransactionType}, valuation, counted
* state or session ownership.
*
* <p>Persisted on {@link Transaction} by wire name; absent on rows saved before
* B10 and on rows whose evidence never supported a specific verb. Unsupported evidence
* must not acquire a more confident verb — callers fall back to {@link #SUPPLIES} or a
* generic label rather than guessing.
*/
@AllArgsConstructor
enum ActionKind {
/** Drink intent plus a settled dose/vial decrease. */
DRINK("drink"),
/** Eat intent plus settled food loss (partial foods use the observed portion). */
EAT("eat"),
/** Herblore production: ingredients in, potion out. Not every paired transform. */
MIX("mix"),
/** Dose-conserving redistribution (3+1 → 4). Never implies doses were drunk. */
DECANT("decant"), BURY("bury"), OFFER("offer"), SCATTER("scatter"),
/** Supported rune consumption. Spell name only when evidenced. Ledger detail only. */
CAST("cast"),
/** Supported ammunition consumption. Ledger detail only. */
FIRE("fire"),
/** Routine recovered ammunition (Ava's / quiver return). Ledger detail only. */
RECOVER_AMMO("recover-ammo"),
/** Untradeable key audit rows are inspectable in Ledger only. */
DEFERRED_CLAIM("deferred-claim"),
/** Ambiguous charge-load settlements are explanatory Ledger notes only. */
CHARGE_LOAD_AMBIGUOUS(msg("ep")), COOK("cook"), BURN("burn"),
/** Settled supply loss whose specific action is not evidenced. */
SUPPLIES("supplies");
final String wireName;
/** Stable persisted identifier. */
String wireName() {
return wireName;
}

static ActionKind fromWireName(String value) {
return ModelText.blank(value) ? null : BY_WIRE.get(value.trim().toLowerCase(Locale.ROOT));
}

/** Wire and constant names, lowercased, read on every getActionKind() (a hot path). */
static final Map<String, ActionKind> BY_WIRE = new HashMap<>();
static {
for (ActionKind kind : values()) {
BY_WIRE.put(kind.wireName, kind);
BY_WIRE.put(kind.name().toLowerCase(Locale.ROOT), kind);
}
}
}
