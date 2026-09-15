package com.gpmanager.engine.evidence;

/**
 * Base type for a time-boxed evidence signal used to classify an inventory
 * change (bank transfer, consumption, own-drop, etc.).
 *
 * <p>The core P1 bug this package exists to prevent: a single shared
 * "is the bank open" flag was previously used both to detect a genuine
 * deposit/withdrawal AND, incidentally, to suppress unrelated inventory
 * drops (bone burial, potion drinking, rune casting) that merely happened
 * to settle while the bank UI was still open or had just closed. One piece
 * of evidence with one lifetime cannot correctly describe two different
 * real-world events with two different lifetimes.
 *
 * <p>Each {@code TimedEvidence} instance represents evidence from exactly
 * one source (for example: "the bank interface widget is visible this
 * tick" or "the player confirmed a Bury/Eat/Drink menu action") and decays
 * on its own schedule via {@link #tick()}. Callers hold a nullable
 * reference to the relevant evidence object; a {@code null} reference (or
 * an expired one) means that source currently has no bearing on
 * classification. This lets {@code GpManagerEngine} keep, for example,
 * "hard" bank-container evidence (a real deposit/withdraw menu action)
 * alive across a bank-close tick while unrelated "soft" bank-open evidence
 * (the widget was merely visible) is dropped immediately on close — so a
 * burial that settles after the bank UI closes is classified as
 * {@code CONSUMPTION}, not {@code TRANSFER}.
 *
 * <p>This is intentionally a small scaffold, not a general accounting
 * framework. {@code GpManagerEngine} currently models bank transfer
 * evidence with dedicated tick counters rather than this base type, chosen
 * for minimal risk to the existing, heavily-regression-tested settlement
 * state machine (see {@code ConsumptionBurialEngineTest} and
 * {@code FollowupBoundaryReviewTest}). {@code GpManagerEngine}'s
 * consumption-intent and own-drop-intent evidence already extend this
 * class. Future per-source evidence (for example, charge/dose counters
 * needed for full equipment-charge accounting) should extend this type
 * rather than adding another ad-hoc boolean + tick-counter pair. See this
 * package's {@code README.md} for why charge/container accounting is not
 * implemented yet.
 */
public abstract class TimedEvidence
{
    private int ticksRemaining;

    protected TimedEvidence(int ticks)
    {
        this.ticksRemaining = Math.max(1, ticks);
    }

    /**
     * Extends this evidence's remaining lifetime. Never shortens it — a
     * fresh, weaker refresh must not cut short evidence armed with a
     * longer window by an earlier, stronger signal.
     */
    public final void refresh(int ticks)
    {
        ticksRemaining = Math.max(ticksRemaining, Math.max(1, ticks));
    }

    /**
     * Advances this evidence by one game tick.
     *
     * @return true once this call has exhausted the remaining lifetime
     *         (the caller should discard/null out its reference now).
     */
    public final boolean tick()
    {
        return --ticksRemaining <= 0;
    }

    /** True once {@link #tick()} has exhausted the lifetime. */
    public final boolean isExpired()
    {
        return ticksRemaining <= 0;
    }

    public final int ticksRemaining()
    {
        return ticksRemaining;
    }
}
