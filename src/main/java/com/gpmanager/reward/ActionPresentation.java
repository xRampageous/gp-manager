package com.gpmanager.reward;

import com.gpmanager.engine.ActionEvidence;
import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.util.QuantityFormatter;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The one action-to-presentation mapping shared by HUD+, latest-change notices, floating
 * drops, the Live timeline and the Ledger (B10).
 *
 * <p>Two questions are answered here and nowhere else:
 * <ul>
 *   <li><b>Wording</b> — which verb a settled change carries ("Drank", "Decanted",
 *       "Runes used") and how its receipt line reads.</li>
 *   <li><b>Surfaces</b> — which surfaces may show the event at all. Routine repetitive
 *       actions (runes, ammunition, routine recovered ammunition) reach the Ledger only;
 *       every other surface shows nothing for them. This is visibility policy, not an
 *       accounting exclusion: the rows still count toward net, session/run totals and
 *       aggregate metrics.</li>
 * </ul>
 *
 * <p>Never reads or changes {@link com.gpmanager.model.TransactionType}, valuation,
 * counted state or ownership.
 */
public final class ActionPresentation
{
    /** A place an individual settled change can be shown. */
    public enum Surface
    {
        /** HUD+ tray card / expanded receipts. */
        HUD_PLUS_TRAY,
        /** HUD+ satellite Gained/Spent capsule (supply-detail list). */
        HUD_PLUS_CAPSULE,
        /** HUD latest-change notice. */
        LATEST_CHANGE,
        /** Floating GP drop / legacy HUD receipt. */
        FLOATING_DROP,
        /** Live tab recent-activity rows. */
        LIVE_TIMELINE,
        /** Ledger detail rows — always inspectable. */
        LEDGER
    }

    private static final Set<Surface> ALL_SURFACES =
        Collections.unmodifiableSet(EnumSet.allOf(Surface.class));
    private static final Set<Surface> LEDGER_ONLY =
        Collections.unmodifiableSet(EnumSet.of(Surface.LEDGER));

    private ActionPresentation()
    {
    }

    /** Surfaces allowed for an action kind; null (no evidence) keeps every surface. */
    public static Set<Surface> surfacesFor(@Nullable ActionKind kind)
    {
        if (kind == ActionKind.DEFERRED_CLAIM
            || kind == ActionKind.CHARGE_LOAD_AMBIGUOUS
            || (kind != null && kind.isRoutineRepetitive()))
        {
            return LEDGER_ONLY;
        }
        return ALL_SURFACES;
    }

    /** True when {@code surface} may show this transaction as an individual event. */
    public static boolean showsOn(@Nullable ProfitTransaction transaction, Surface surface)
    {
        if (transaction == null || surface == null)
        {
            return false;
        }
        return surfacesFor(transaction.getActionKind()).contains(surface);
    }

    /**
     * Tray source name for a dose/partial-consume card: the completed verb when a
     * specific action is evidenced, else the honest generic "Supplies used". Never
     * "Mixed"/"Processing" — a drink is not production.
     */
    public static String doseTraySource(@Nullable ProfitTransaction transaction)
    {
        ActionKind kind = transaction == null ? null : transaction.getActionKind();
        return kind == null ? ActionKind.SUPPLIES.completedVerb() : kind.completedVerb();
    }

    /**
     * Receipt verb for a loss-only card. Returns null when the transaction carries no
     * action evidence so callers keep their existing Used/Dropped/Destroyed wording.
     */
    @Nullable
    public static String lossVerb(@Nullable ProfitTransaction transaction)
    {
        ActionKind kind = transaction == null ? null : transaction.getActionKind();
        if (kind == null)
        {
            return null;
        }
        switch (kind)
        {
            case DRINK:
            case EAT:
            case SUPPLIES:
            case CAST:
            case FIRE:
                return kind.completedVerb();
            default:
                // Bury/Offer/Scatter keep the XP-confirmed process-spend wording (Buried/Offered).
                return null;
        }
    }

    /**
     * Completed receipt line: "Drank · Prayer potion · 1 dose", "Ate · Shark · 1",
     * "Decanted · Prayer potion", "Mixed · Prayer potion · 3", "Runes used · Fire rune · 5".
     * Returns null when no action is evidenced so callers keep their item identity.
     */
    @Nullable
    public static String receiptLine(@Nullable ProfitTransaction transaction)
    {
        if (transaction == null)
        {
            return null;
        }
        ActionKind kind = transaction.getActionKind();
        if (kind == null)
        {
            return null;
        }
        if (kind == ActionKind.DEFERRED_CLAIM)
        {
            // The audit row's live status/chest name is its own Ledger note.
            return null;
        }
        if (kind == ActionKind.CHARGE_LOAD_AMBIGUOUS)
        {
            // The exact explanatory note is stored on the row itself.
            return null;
        }
        List<ItemFlow> flows = transaction.getFlows();
        String verb = kind.completedVerb();
        switch (kind)
        {
            case DRINK:
            case SUPPLIES:
            {
                int doses = ActionEvidence.dosesConsumed(flows);
                ItemFlow spent = firstLoss(flows);
                if (doses > 0 && spent != null)
                {
                    return verb + " · " + ActionEvidence.doseBaseName(spent.getItemName())
                        + " · " + doses + (doses == 1 ? " dose" : " doses");
                }
                return spent == null ? verb : verb + " · " + spent.getItemName()
                    + " · " + QuantityFormatter.compactNumber(Math.abs(spent.getQuantityDelta()));
            }
            case DECANT:
            {
                ItemFlow any = firstLoss(flows);
                return any == null ? verb : verb + " · " + ActionEvidence.doseBaseName(any.getItemName());
            }
            case MIX:
            case COOK:
            case BURN:
            case RECOVER_AMMO:
            {
                ItemFlow made = firstGain(flows);
                return made == null ? verb : verb + " · " + made.getItemName()
                    + " · " + QuantityFormatter.compactNumber(made.getQuantityDelta());
            }
            default:
            {
                ItemFlow spent = firstLoss(flows);
                return spent == null ? verb : verb + " · " + spent.getItemName()
                    + " · " + QuantityFormatter.compactNumber(Math.abs(spent.getQuantityDelta()));
            }
        }
    }

    @Nullable
    private static ItemFlow firstLoss(List<ItemFlow> flows)
    {
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getQuantityDelta() < 0L)
            {
                return flow;
            }
        }
        return null;
    }

    @Nullable
    private static ItemFlow firstGain(List<ItemFlow> flows)
    {
        for (ItemFlow flow : flows)
        {
            if (flow != null && flow.getQuantityDelta() > 0L)
            {
                return flow;
            }
        }
        return null;
    }
}
