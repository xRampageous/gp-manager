package com.gpmanager;

import java.util.*;

/**
 * Observed player action behind a settled inventory change. Presentation-only evidence: it names
 * what the player did (drank, ate, decanted, cast) so HUD/receipts/Live/Ledger can use honest
 * wording. It never changes {@link TransactionType}, valuation, counted state or session ownership.
 *
 * <p>Persisted on {@link Transaction} by wire name; absent on rows saved before B10 and on rows
 * whose evidence never supported a specific verb. Unsupported evidence must not acquire a more
 * confident verb — callers fall back to {@link #SUPPLIES} or a generic label rather than guessing.
 */
enum ActionKind {
    /** Drink intent plus a settled dose/vial decrease. */
    DRINK,
    /** Eat intent plus settled food loss (partial foods use the observed portion). */
    EAT,
    /** Herblore production: ingredients in, potion out. Not every paired transform. */
    MIX,
    /** Dose-conserving redistribution (3+1 → 4). Never implies doses were drunk. */
    DECANT,
    BURY,
    OFFER,
    SCATTER,
    /** Supported rune consumption. Spell name only when evidenced. Ledger detail only. */
    CAST,
    /** Supported ammunition consumption. Ledger detail only. */
    FIRE,
    /** Untradeable key audit rows are inspectable in Ledger only. */
    DEFERRED_CLAIM,
    /** Ambiguous charge-load settlements are explanatory Ledger notes only. */
    CHARGE_LOAD_AMBIGUOUS,
    COOK,
    BURN,
    /** Settled supply loss whose specific action is not evidenced. */
    SUPPLIES,
    /** A bank-paid fee (the boat recovery): one action row named by its own note. */
    FEE;

    /** The constant's name, lowercased with hyphens: the wire name saved on every row. */
    String wireName = name().toLowerCase(Locale.ROOT).replace('_', '-');

    /** Stable persisted identifier. */
    String wireName() {
        return wireName;
    }

    static ActionKind fromWireName(String value) {
        return Phrases.blank(value) ? null : BY_WIRE.get(Phrases.lower(value));
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
