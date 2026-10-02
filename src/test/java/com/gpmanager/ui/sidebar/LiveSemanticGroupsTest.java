package com.gpmanager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Live Recent Activity is rebuilt from the shared semantic projection as living groups. */
public class LiveSemanticGroupsTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final int DEATH = 560;
    private static final int CHAOS = 562;
    private static final int WATER = 555;
    private static final int BLOOD = 565;

    @Test
    public void repeatedExactSpellUpdatesOneLiveRowAndBubbles()
    {
        Am engine = session("Vorkath");
        engine.getActiveSession().kf(exactCast(T0 + 1_000L, "Ice Barrage", 1, 1, 1), 2_000);
        engine.getActiveSession().kf(exactCast(T0 + 2_000L, "Ice Barrage", 1, 1, 1), 2_000);
        engine.getActiveSession().kf(exactCast(T0 + 3_000L, "Ice Barrage", 1, 1, 1), 2_000);

        Ca snapshot = Ca.capture(engine, T0 + 4_000L, Dz.NONE);

        assertEquals("repeated exact spells are one updating row", 1, snapshot.recent.size());
        Ca.Recent row = snapshot.recent.get(0);
        assertEquals("Ice Barrage", row.name);
        assertEquals("the row counts the receipts it speaks for", 3, row.receipts);
        assertEquals(-540L, row.value);
        assertEquals("Cast \u00b7 \u00d73", LivePage.metaOf(row));

        engine.getActiveSession().kf(exactCast(T0 + 5_000L, "Blood Barrage", 1, 1, 1), 2_000);
        Ca bubbled = Ca.capture(engine, T0 + 6_000L, Dz.NONE);
        assertEquals(2, bubbled.recent.size());
        assertEquals("the newest group bubbles to the top", "Blood Barrage", bubbled.recent.get(0).name);
        assertEquals("Ice Barrage", bubbled.recent.get(1).name);
        assertEquals(3, bubbled.recent.get(1).receipts);
    }

    @Test
    public void genericCastCompositionsStaySeparateAndGeneric()
    {
        Am engine = session("Vorkath");
        engine.getActiveSession().kf(genericCast(T0 + 1_000L, 1, 1, 1), 2_000);
        // 2/2/2 would be Ice Rush's exact cost (named since 2026-09-28); 3/3/3 is no spell's.
        engine.getActiveSession().kf(genericCast(T0 + 2_000L, 3, 3, 3), 2_000);
        engine.getActiveSession().kf(bloodGenericCast(T0 + 3_000L), 2_000);

        Ca snapshot = Ca.capture(engine, T0 + 4_000L, Dz.NONE);
        assertEquals("same normalised composition groups, a different one splits", 2, snapshot.recent.size());
        for (Ca.Recent row : snapshot.recent)
        {
            assertEquals("generic casts never receive an inferred spell name", "Cast", row.name);
        }
        Ca.Recent grouped = snapshot.recent.stream()
            .filter(row -> row.receipts == 2).findFirst().orElseThrow(AssertionError::new);
        assertEquals(-720L, grouped.value);
        assertEquals("3 rune types \u00b7 \u00d72", LivePage.metaOf(grouped));
    }

    @Test
    public void liveRecentStaysBoundedToOnePageOfSemanticRows()
    {
        Am engine = session("Vorkath");
        for (int i = 0; i < Ca.RECENT_ROWS + 2; i++)
        {
            engine.getActiveSession().kf(
                gain(T0 + 1_000L + i * 1_000L, "Item " + i, 2_000 + i, 1L, 100, 100L), 2_000);
        }
        Ca snapshot = Ca.capture(engine, T0 + 20_000L, Dz.NONE);

        assertEquals("the Live preview is bounded", Ca.RECENT_ROWS, snapshot.recent.size());
        assertEquals("the newest rows are kept", "Item 16", snapshot.recent.get(0).name);
        assertEquals("Item 2", snapshot.recent.get(Ca.RECENT_ROWS - 1).name);
    }

    @Test
    public void unpricedFinancialRowsAreNotReviewButCanonicalReviewStillIs()
    {
        Am engine = session("Vorkath");
        engine.getActiveSession().kf(unpricedGain(T0 + 1_000L, "Unidentified mineral", 8), 2_000);
        engine.getActiveSession().kf(review(T0 + 2_000L, "Unknown rune", 777), 2_000);

        Ca snapshot = Ca.capture(engine, T0 + 3_000L, Dz.NONE);

        assertEquals("only the canonical review transaction needs a decision", 1, snapshot.reviewCount);
        Ca.Recent unpriced = rowNamed(snapshot.recent, "Unidentified mineral");
        assertNotNull(unpriced);
        assertTrue("the unpriced financial row keeps its incomplete marker", unpriced.unpriced);
        assertFalse("an unpriced financial row is not a review decision", "review".equals(unpriced.tag));
        assertNull("an unpriced gain opens Gains, not a Costs view", unpriced.ledgerCostView);
        Ca.Recent review = rowNamed(snapshot.recent, "Unknown rune");
        assertNotNull(review);
        assertEquals("review", review.tag);
        assertTrue(review.ledgerReview);
    }

    @Test
    public void measuredResourceRowsLiveOnlyInTheLedgerChargesChip()
    {
        Am engine = session("Vorkath");
        engine.getActiveSession().kf(gain(T0 + 500L, "Older gain", 999, 1L, 50, 50L), 2_000);
        engine.getActiveSession().kf(blowpipeSpend(T0 + 1_000L), 2_000);
        engine.getActiveSession().kf(blowpipeSpend(T0 + 2_000L), 2_000);

        Ca snapshot = Ca.capture(engine, T0 + 3_000L, Dz.NONE);

        // Owner 2026-09-29: charge use never shows in Recent; it lives in the Ledger's Charges chip.
        assertNull("scales stay out of Recent", rowNamed(snapshot.recent, "Zulrah's scales"));
        assertNotNull("owner 1.1: darts are Supplies like arrows", rowNamed(snapshot.recent, "Dragon dart"));
        assertEquals("the darts and the gain are in Recent", 2, snapshot.recent.size());
        assertNotNull(rowNamed(snapshot.recent, "Older gain"));

        Ao ledger = Ao.capture(engine, T0 + 3_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.CHARGES, "", null, null, null, null));
        assertEquals("only the scales live in Charges",
            1, ledger.costCounts[Ao.Bs.CHARGES.ordinal()]);
        assertEquals("the darts are Supplies", 1, ledger.costCounts[Ao.Bs.SUPPLIES.ordinal()]);
    }

    @Test
    public void exactSpellRowCarriesStableDeepLinkIdentity()
    {
        Am engine = session("Vorkath");
        Ac newest = exactCast(T0 + 2_000L, "Ice Barrage", 1, 1, 1);
        engine.getActiveSession().kf(exactCast(T0 + 1_000L, "Ice Barrage", 1, 1, 1), 2_000);
        engine.getActiveSession().kf(newest, 2_000);

        Ca snapshot = Ca.capture(engine, T0 + 3_000L, Dz.NONE);
        Ca.Recent row = snapshot.recent.get(0);

        assertEquals("the representative receipt is the newest one", newest.getId(), row.receiptId);
        assertNotNull(row.contributionId);
        Ao.Entry entry = LivePage.yn(row);
        assertEquals("deep links never smuggle a search string", "", entry.search);
        assertEquals(newest.getId(), entry.highlightTransactionId);
        Ao linked = Ao.capture(engine, T0 + 3_000L, entry);
        assertNotNull("the stable identity resolves to its group detail", linked.detail);
        assertTrue(linked.detail.group.containsTransaction(newest.getId()));
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static Am session(String name)
    {
        Am engine = PresentationLifecycleTest.engine();
        engine.ajl(name, Cx.GENERAL, T0);
        return engine;
    }

    private static Ca.Recent rowNamed(List<Ca.Recent> rows, String name)
    {
        for (Ca.Recent row : rows)
        {
            if (name.equals(row.name))
            {
                return row;
            }
        }
        return null;
    }

    private static Ac exactCast(long at, String name, int deathQty, int chaosQty, int waterQty)
    {
        Ac transaction = genericCast(at, deathQty, chaosQty, waterQty);
        transaction.ahu(Bb.of(name));
        return transaction;
    }

    private static Ac genericCast(long at, int deathQty, int chaosQty, int waterQty)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Vorkath", true, Arrays.asList(
                rune(DEATH, "Death rune", -deathQty, 100),
                rune(CHAOS, "Chaos rune", -chaosQty, 50),
                rune(WATER, "Water rune", -waterQty, 30)),
            Bd.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(Au.CAST);
        return transaction;
    }

    private static Ac bloodGenericCast(long at)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Vorkath", true, Arrays.asList(
                rune(DEATH, "Death rune", -1L, 100),
                rune(BLOOD, "Blood rune", -1L, 200),
                rune(WATER, "Water rune", -1L, 30)),
            Bd.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(Au.CAST);
        return transaction;
    }

    private static Ab rune(int id, String name, long quantity, int unit)
    {
        return new Ab(id, name, quantity, unit, quantity * unit, Av.GRAND_EXCHANGE);
    }

    private static Ac blowpipeSpend(long at)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "Measured charge spend \u00b7 Toxic blowpipe", "Vorkath", true,
            Arrays.asList(
                new Ab(11230, "Dragon dart", -2L, 100, -200L, Av.GRAND_EXCHANGE),
                new Ab(12934, "Zulrah's scales", -1L, 110, -110L, Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "Measured Check difference for Toxic blowpipe.", null);
        transaction.setActionKind(Au.FIRE);
        return transaction;
    }

    private static Ac gain(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Ac(at, null, Ai.GAIN, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(itemId, name, quantity, unitPrice, value,
                Av.GRAND_EXCHANGE)),
            Bd.LIKELY, "Test gain", null);
    }

    private static Ac unpricedGain(long at, String name, int itemId)
    {
        return new Ac(at, null, Ai.GAIN, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(itemId, name, 8L, 0, 0L, Av.UNPRICED)),
            Bd.LIKELY, "Unpriced test gain", null);
    }

    private static Ac review(long at, String name, int itemId)
    {
        return new Ac(at, null, Ai.UNCERTAIN, Aj.GENERIC, "", "Vorkath",
            true, Collections.singletonList(new Ab(itemId, name, -1L, 0, 0L)),
            Bd.UNCERTAIN, "Awaiting a decision", null);
    }
}
