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
        Engine engine = session("Vorkath");
        engine.getActiveSession().addTransaction(exactCast(T0 + 1_000L, "Ice Barrage", 1, 1, 1), 2_000);
        engine.getActiveSession().addTransaction(exactCast(T0 + 2_000L, "Ice Barrage", 1, 1, 1), 2_000);
        engine.getActiveSession().addTransaction(exactCast(T0 + 3_000L, "Ice Barrage", 1, 1, 1), 2_000);

        LiveSnapshot snapshot = LiveSnapshot.capture(engine, T0 + 4_000L, LiveContext.NONE);

        assertEquals("repeated exact spells are one updating row", 1, snapshot.recent.size());
        LiveSnapshot.Recent row = snapshot.recent.get(0);
        assertEquals("Ice Barrage", row.name);
        assertEquals("the row counts the receipts it speaks for", 3, row.receipts);
        assertEquals(-540L, row.value);
        assertEquals("Cast \u00b7 \u00d73", LivePage.metaOf(row));

        engine.getActiveSession().addTransaction(exactCast(T0 + 5_000L, "Blood Barrage", 1, 1, 1), 2_000);
        LiveSnapshot bubbled = LiveSnapshot.capture(engine, T0 + 6_000L, LiveContext.NONE);
        assertEquals(2, bubbled.recent.size());
        assertEquals("the newest group bubbles to the top", "Blood Barrage", bubbled.recent.get(0).name);
        assertEquals("Ice Barrage", bubbled.recent.get(1).name);
        assertEquals(3, bubbled.recent.get(1).receipts);
    }

    @Test
    public void genericCastCompositionsStaySeparateAndGeneric()
    {
        Engine engine = session("Vorkath");
        engine.getActiveSession().addTransaction(genericCast(T0 + 1_000L, 1, 1, 1), 2_000);
        // 2/2/2 would be Ice Rush's exact cost (named since 2026-09-28); 3/3/3 is no spell's.
        engine.getActiveSession().addTransaction(genericCast(T0 + 2_000L, 3, 3, 3), 2_000);
        engine.getActiveSession().addTransaction(bloodGenericCast(T0 + 3_000L), 2_000);

        LiveSnapshot snapshot = LiveSnapshot.capture(engine, T0 + 4_000L, LiveContext.NONE);
        assertEquals("same normalised composition groups, a different one splits", 2, snapshot.recent.size());
        for (LiveSnapshot.Recent row : snapshot.recent)
        {
            assertEquals("generic casts never receive an inferred spell name", "Cast", row.name);
        }
        LiveSnapshot.Recent grouped = snapshot.recent.stream()
            .filter(row -> row.receipts == 2).findFirst().orElseThrow(AssertionError::new);
        assertEquals(-720L, grouped.value);
        assertEquals("3 rune types \u00b7 \u00d72", LivePage.metaOf(grouped));
    }

    @Test
    public void liveRecentStaysBoundedToOnePageOfSemanticRows()
    {
        Engine engine = session("Vorkath");
        for (int i = 0; i < LiveSnapshot.RECENT_ROWS + 2; i++)
        {
            engine.getActiveSession().addTransaction(
                gain(T0 + 1_000L + i * 1_000L, "Item " + i, 2_000 + i, 1L, 100, 100L), 2_000);
        }
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, T0 + 20_000L, LiveContext.NONE);

        assertEquals("the Live preview is bounded", LiveSnapshot.RECENT_ROWS, snapshot.recent.size());
        assertEquals("the newest rows are kept", "Item 16", snapshot.recent.get(0).name);
        assertEquals("Item 2", snapshot.recent.get(LiveSnapshot.RECENT_ROWS - 1).name);
    }

    @Test
    public void unpricedFinancialRowsAreNotReviewButCanonicalReviewStillIs()
    {
        Engine engine = session("Vorkath");
        engine.getActiveSession().addTransaction(unpricedGain(T0 + 1_000L, "Unidentified mineral", 8), 2_000);
        engine.getActiveSession().addTransaction(review(T0 + 2_000L, "Unknown rune", 777), 2_000);

        LiveSnapshot snapshot = LiveSnapshot.capture(engine, T0 + 3_000L, LiveContext.NONE);

        assertEquals("only the canonical review transaction needs a decision", 1, snapshot.reviewCount);
        LiveSnapshot.Recent unpriced = rowNamed(snapshot.recent, "Unidentified mineral");
        assertNotNull(unpriced);
        assertTrue("the unpriced financial row keeps its incomplete marker", unpriced.unpriced);
        assertFalse("an unpriced financial row is not a review decision", "review".equals(unpriced.tag));
        assertNull("an unpriced gain opens Gains, not a Costs view", unpriced.ledgerCostView);
        LiveSnapshot.Recent review = rowNamed(snapshot.recent, "Unknown rune");
        assertNotNull(review);
        assertEquals("review", review.tag);
        assertTrue(review.ledgerReview);
    }

    @Test
    public void measuredResourceRowsLiveOnlyInTheLedgerChargesChip()
    {
        Engine engine = session("Vorkath");
        engine.getActiveSession().addTransaction(gain(T0 + 500L, "Older gain", 999, 1L, 50, 50L), 2_000);
        engine.getActiveSession().addTransaction(blowpipeSpend(T0 + 1_000L), 2_000);
        engine.getActiveSession().addTransaction(blowpipeSpend(T0 + 2_000L), 2_000);

        LiveSnapshot snapshot = LiveSnapshot.capture(engine, T0 + 3_000L, LiveContext.NONE);

        // Owner 2026-09-29: charge use never shows in Recent; it lives in the Ledger's Charges chip.
        assertNull("scales stay out of Recent", rowNamed(snapshot.recent, "Zulrah's scales"));
        assertNull("darts stay out of Recent", rowNamed(snapshot.recent, "Dragon dart"));
        assertEquals("only the gain remains in Recent", 1, snapshot.recent.size());
        assertEquals("Older gain", snapshot.recent.get(0).name);

        LedgerData ledger = LedgerData.capture(engine, T0 + 3_000L,
            new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                LedgerData.CostView.CHARGES, "", null, null, null, null));
        assertEquals("both measured resources live in Charges",
            2, ledger.costCounts[LedgerData.CostView.CHARGES.ordinal()]);
    }

    @Test
    public void exactSpellRowCarriesStableDeepLinkIdentity()
    {
        Engine engine = session("Vorkath");
        Transaction newest = exactCast(T0 + 2_000L, "Ice Barrage", 1, 1, 1);
        engine.getActiveSession().addTransaction(exactCast(T0 + 1_000L, "Ice Barrage", 1, 1, 1), 2_000);
        engine.getActiveSession().addTransaction(newest, 2_000);

        LiveSnapshot snapshot = LiveSnapshot.capture(engine, T0 + 3_000L, LiveContext.NONE);
        LiveSnapshot.Recent row = snapshot.recent.get(0);

        assertEquals("the representative receipt is the newest one", newest.getId(), row.receiptId);
        assertNotNull(row.contributionId);
        LedgerData.Entry entry = LivePage.ledgerEntryFor(row);
        assertEquals("deep links never smuggle a search string", "", entry.search);
        assertEquals(newest.getId(), entry.highlightTransactionId);
        LedgerData linked = LedgerData.capture(engine, T0 + 3_000L, entry);
        assertNotNull("the stable identity resolves to its group detail", linked.detail);
        assertTrue(linked.detail.group.containsTransaction(newest.getId()));
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static Engine session(String name)
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.startCustomSession(name, SessionMode.GENERAL, T0);
        return engine;
    }

    private static LiveSnapshot.Recent rowNamed(List<LiveSnapshot.Recent> rows, String name)
    {
        for (LiveSnapshot.Recent row : rows)
        {
            if (name.equals(row.name))
            {
                return row;
            }
        }
        return null;
    }

    private static Transaction exactCast(long at, String name, int deathQty, int chaosQty, int waterQty)
    {
        Transaction transaction = genericCast(at, deathQty, chaosQty, waterQty);
        transaction.setObservedActionLabel(ActionLabel.of(name));
        return transaction;
    }

    private static Transaction genericCast(long at, int deathQty, int chaosQty, int waterQty)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                rune(DEATH, "Death rune", -deathQty, 100),
                rune(CHAOS, "Chaos rune", -chaosQty, 50),
                rune(WATER, "Water rune", -waterQty, 30)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(ActionKind.CAST);
        return transaction;
    }

    private static Transaction bloodGenericCast(long at)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                rune(DEATH, "Death rune", -1L, 100),
                rune(BLOOD, "Blood rune", -1L, 200),
                rune(WATER, "Water rune", -1L, 30)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(ActionKind.CAST);
        return transaction;
    }

    private static Flow rune(int id, String name, long quantity, int unit)
    {
        return new Flow(id, name, quantity, unit, quantity * unit, PriceSource.GRAND_EXCHANGE);
    }

    private static Transaction blowpipeSpend(long at)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "Measured charge spend \u00b7 Toxic blowpipe", "Vorkath", true,
            Arrays.asList(
                new Flow(11230, "Dragon dart", -2L, 100, -200L, PriceSource.GRAND_EXCHANGE),
                new Flow(12934, "Zulrah's scales", -1L, 110, -110L, PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "Measured Check difference for Toxic blowpipe.", null);
        transaction.setActionKind(ActionKind.FIRE);
        return transaction;
    }

    private static Transaction gain(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Transaction(at, null, TransactionType.GAIN, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value,
                PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.LIKELY, "Test gain", null);
    }

    private static Transaction unpricedGain(long at, String name, int itemId)
    {
        return new Transaction(at, null, TransactionType.GAIN, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, 8L, 0, 0L, PriceSource.UNPRICED)),
            ClassificationConfidence.LIKELY, "Unpriced test gain", null);
    }

    private static Transaction review(long at, String name, int itemId)
    {
        return new Transaction(at, null, TransactionType.UNCERTAIN, Context.GENERIC, "", "Vorkath",
            true, Collections.singletonList(new Flow(itemId, name, -1L, 0, 0L)),
            ClassificationConfidence.UNCERTAIN, "Awaiting a decision", null);
    }
}
