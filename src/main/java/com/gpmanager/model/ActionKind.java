package com.gpmanager.model;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Observed player action behind a settled inventory change. Presentation-only evidence:
 * it names what the player did (drank, ate, decanted, cast) so HUD/receipts/Live/Ledger
 * can use honest wording. It never changes {@link TransactionType}, valuation, counted
 * state or session ownership.
 *
 * <p>Persisted on {@link ProfitTransaction} by wire name; absent on rows saved before
 * B10 and on rows whose evidence never supported a specific verb. Unsupported evidence
 * must not acquire a more confident verb — callers fall back to {@link #SUPPLIES} or a
 * generic label rather than guessing.
 */
public enum ActionKind
{
    /** Drink intent plus a settled dose/vial decrease. */
    DRINK("drink", "Drinking", "Drank"),
    /** Eat intent plus settled food loss (partial foods use the observed portion). */
    EAT("eat", "Eating", "Ate"),
    /** Herblore production: ingredients in, potion out. Not every paired transform. */
    MIX("mix", "Mixing", "Mixed"),
    /** Dose-conserving redistribution (3+1 → 4). Never implies doses were drunk. */
    DECANT("decant", "Decanting", "Decanted"),
    BURY("bury", "Burying", "Buried"),
    OFFER("offer", "Offering", "Offered"),
    SCATTER("scatter", "Scattering", "Scattered"),
    /** Supported rune consumption. Spell name only when evidenced. Ledger detail only. */
    CAST("cast", "Casting", "Runes used"),
    /** Supported ammunition consumption. Ledger detail only. */
    FIRE("fire", "Firing", "Ammunition used"),
    /** Routine recovered ammunition (Ava's / quiver return). Ledger detail only. */
    RECOVER_AMMO("recover-ammo", "Recovering", "Ammunition recovered"),
    /** Untradeable key audit rows are inspectable in Ledger only. */
    DEFERRED_CLAIM("deferred-claim", "Opening chest", "Key held"),
    /** Ambiguous charge-load settlements are explanatory Ledger notes only. */
    CHARGE_LOAD_AMBIGUOUS("charge-load-ambiguous", "Charge load", "Charge load"),
    COOK("cook", "Cooking", "Cooked"),
    BURN("burn", "Cooking", "Burnt"),
    /** Settled supply loss whose specific action is not evidenced. */
    SUPPLIES("supplies", "Supplies", "Supplies used");

    private final String wireName;
    private final String ongoingLabel;
    private final String completedVerb;

    ActionKind(String wireName, String ongoingLabel, String completedVerb)
    {
        this.wireName = wireName;
        this.ongoingLabel = ongoingLabel;
        this.completedVerb = completedVerb;
    }

    /** Stable persisted identifier. */
    public String wireName()
    {
        return wireName;
    }

    /** Present-tense label while the action is in progress. */
    public String ongoingLabel()
    {
        return ongoingLabel;
    }

    /** Past-tense receipt verb: "Drank", "Decanted", "Runes used". */
    public String completedVerb()
    {
        return completedVerb;
    }

    /** True for actions whose per-event receipts belong in Ledger detail only. */
    public boolean isRoutineRepetitive()
    {
        return this == CAST || this == FIRE || this == RECOVER_AMMO;
    }

    @Nullable
    public static ActionKind fromWireName(@Nullable String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return null;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        for (ActionKind kind : values())
        {
            if (kind.wireName.equals(lower) || kind.name().toLowerCase(Locale.ROOT).equals(lower))
            {
                return kind;
            }
        }
        return null;
    }

    /** Reverse lookup for tray source names that carry a completed verb. */
    @Nullable
    public static ActionKind fromCompletedVerb(@Nullable String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return null;
        }
        String trimmed = value.trim();
        for (ActionKind kind : values())
        {
            if (kind.completedVerb.equalsIgnoreCase(trimmed))
            {
                return kind;
            }
        }
        return null;
    }
}
