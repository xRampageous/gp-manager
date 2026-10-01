package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** PRE-R5C.2C: cross-surface terminology, Market separation, Review parity and zero-audit polish. */
public class CrossSurfaceConsistencyTest
{
    private static final long T0 = System.currentTimeMillis() - 60_000L;
    private static final int COINS = 995;
    private static final int SHARK = 385;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    // ── Live ───────────────────────────────────────────────────────────────────────────────────

    @Test
    public void liveSeparatesRealizedMarketWithoutDoubleDescribingIt() throws Exception
    {
        MarketFixture market = new MarketFixture();
        market.sell(0, 561, 10);

        LiveSnapshot snapshot = LiveSnapshot.capture(market.engine, market.now + 1_000L, null);
        assertTrue("a realized Market result is separated", snapshot.marketResult > 0L);
        assertEquals(0, snapshot.marketPending);
        assertEquals("the canonical Net is unchanged by the separation",
            market.engine.getMetrics(market.now + 1_000L).net, snapshot.net);

        LivePage page = onEdt(() ->
        {
            LivePage created = new LivePage(new LiveNoop(), id -> null);
            created.apply(snapshot);
            return created;
        });
        List<String> labels = labels(page.body());
        assertFalse("visible Revenue is gone", labels.contains("Revenue"));
        assertEquals("the Market cell shows the realized result", Fmt.signed(snapshot.marketResult),
            onEdt(() -> HeroProbe.cell(page.hero, "MARKET")));
        assertEquals("ordinary Gains exclude the whole Market transaction",
            Fmt.signed(snapshot.gains), onEdt(() -> HeroProbe.cell(page.hero, "GAINS")));
        AccountingProjection.MarketFold fold =
            AccountingProjection.marketFold(
                market.engine.getActiveSession().getTransactions());
        assertEquals("the canonical session still books the gross Market legs",
            snapshot.gains + fold.revenue,
            market.engine.getMetrics(market.now + 1_000L).revenue);
        assertEquals("the hero Net stays canonical", Fmt.signed(snapshot.net).replace('−', '-'),
            onEdt(() -> HeroProbe.netText(page.hero)));
    }

    @Test
    public void pendingMarketIsInformationalAndNeverEntersNet() throws Exception
    {
        MarketFixture market = new MarketFixture();
        market.pendingSell(0, 561, 10);

        LiveSnapshot snapshot = LiveSnapshot.capture(market.engine, market.now + 1_000L, null);
        assertEquals("pending Market is not a realized result", 0L, snapshot.marketResult);
        assertEquals("pending Market context is counted separately", 1, snapshot.marketPending);
        assertEquals("pending Market never alters Net",
            market.engine.getMetrics(market.now + 1_000L).net, snapshot.net);

        LivePage page = onEdt(() ->
        {
            LivePage created = new LivePage(new LiveNoop(), id -> null);
            created.apply(snapshot);
            return created;
        });
        assertEquals("open", onEdt(() -> HeroProbe.cell(page.hero, "MARKET")));
    }

    @Test
    public void liveReviewCountEqualsLedgerReviewTruthForTheSameScope()
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, T0);
        Transaction uncertain = new Transaction(T0 + 1_000L, null, TransactionType.UNCERTAIN,
            Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(777, "Unknown rune", -1L, 0, 0L)),
            ClassificationConfidence.UNCERTAIN, "Awaiting a decision", null);
        engine.getActiveSession().addTransaction(uncertain, 100);
        engine.getActiveSession().addTransaction(unpriced(T0 + 2_000L), 100);

        LiveSnapshot live = LiveSnapshot.capture(engine, T0 + 3_000L, null);
        LedgerData ledger = captureLedger(engine, T0 + 3_000L);
        assertEquals("Live and Ledger share one owner-decision truth",
            ledger.review.scopeCount, live.reviewCount);
        assertEquals(1, live.reviewCount);

        Engine quiet = engine();
        quiet.startCustomSession("Vorkath", SessionMode.GENERAL, T0);
        quiet.getActiveSession().addTransaction(unpriced(T0 + 1_000L), 100);
        LiveSnapshot quietLive = LiveSnapshot.capture(quiet, T0 + 2_000L, null);
        LedgerData quietLedger = captureLedger(quiet, T0 + 2_000L);
        assertEquals("unpriced alone is never Review", 0, quietLive.reviewCount);
        assertEquals(quietLedger.review.scopeCount, quietLive.reviewCount);
    }

    @Test
    public void currentGrindNetAgreesBetweenLiveAndLedger()
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, T0);
        engine.getActiveSession().addTransaction(gain(T0 + 1_000L, "Dragon bones", 536, 3_200L), 100);
        engine.getActiveSession().addTransaction(food(T0 + 2_000L, "Shark", 950L), 100);

        LiveSnapshot live = LiveSnapshot.capture(engine, T0 + 3_000L, null);
        LedgerData ledger = captureLedger(engine, T0 + 3_000L);
        assertEquals("same canonical current-Grind Net", ledger.net, live.net);
    }

    // ── Grinds ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void grindsBiggestCostPrefersTheExactAllCostActionName()
    {
        Session session = new Session("Grind", 1_000L);
        session.addTransaction(cast("Ice Burst", T0), 100);

        GrindHistory.Highlight cost = GrindHistory.biggestCost(session);
        assertNotNull(cost);
        assertEquals("Ice Burst", cost.name);
        assertTrue(cost.net < 0L);
    }

    @Test
    public void grindsBiggestCostKeepsTheLeadContributionForMixedReceipts()
    {
        Session session = new Session("Grind", 1_000L);
        Transaction mixed = new Transaction(T0, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(561, "Nature rune", -10L, 100, -1_000L, PriceSource.GRAND_EXCHANGE),
                new Flow(COINS, "Coins", 120L, 1, 120L, PriceSource.FACE_VALUE)),
            ClassificationConfidence.CONFIRMED, "High alchemy", null);
        mixed.setActionKind(ActionKind.CAST);
        mixed.setObservedActionLabel(ActionLabel.of("High Level Alchemy"));
        session.addTransaction(mixed, 100);

        GrindHistory.Highlight cost = GrindHistory.biggestCost(session);
        assertNotNull(cost);
        assertEquals("a mixed receipt keeps its honest lead contribution", "Nature rune", cost.name);
    }

    @Test
    public void grindsBiggestCostNeverUsesTheWeaponAsConsumedIdentity()
    {
        Session session = new Session("Grind", 1_000L);
        Transaction measured = new Transaction(T0, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "Measured charge spend \u00b7 Toxic blowpipe", "Vorkath", true,
            Collections.singletonList(new Flow(12934, "Zulrah's scales", -3L, 110, -330L,
                PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "Measured Check difference.", null);
        measured.setActionKind(ActionKind.FIRE);
        session.addTransaction(measured, 100);

        GrindHistory.Highlight cost = GrindHistory.biggestCost(session);
        assertNotNull(cost);
        assertEquals("the booked consumed resource is the cost identity", "Zulrah's scales", cost.name);
    }

    @Test
    public void grindsGenericCastNeverBecomesASpellName()
    {
        Session session = new Session("Grind", 1_000L);
        // Runes no spell pays (a Nature rune too): an unnamed Cast keeps its largest rune as the lead.
        Transaction generic = new Transaction(T0, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(562, "Chaos rune", -4L, 106, -424L, PriceSource.GRAND_EXCHANGE),
                new Flow(561, "Nature rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
        generic.setActionKind(ActionKind.CAST);
        session.addTransaction(generic, 100);

        GrindHistory.Highlight cost = GrindHistory.biggestCost(session);
        assertNotNull(cost);
        assertEquals("the largest rune stays the honest lead for a generic cast", "Chaos rune", cost.name);
    }

    // ── Insights ───────────────────────────────────────────────────────────────────────────────

    // ── Ledger zero-state audit ────────────────────────────────────────────────────────────────

    @Test
    public void ledgerZeroStateHidesReviewAndKeepsCorrectedReachable() throws Exception
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, T0);
        engine.getActiveSession().addTransaction(gain(T0 + 1_000L, "Dragon bones", 536, 3_200L), 100);
        LedgerData data = captureLedger(engine, T0 + 2_000L);

        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(new LedgerNoop(), id -> null);
            created.apply(data);
            return created;
        });
        List<String> labels = onEdt(() -> labels(page.body()));
        assertFalse("zero Review hides its table", labels.stream().anyMatch(label -> label.contains("REVIEW")));
        assertFalse("Corrected is closed by default",
            labels.stream().anyMatch(label -> label.startsWith("CORRECTED")));

        onEdt(() ->
        {
            LedgerPageProbe.toggleCorrected(page);
            return null;
        });
        assertTrue("Corrected history stays reachable",
            onEdt(() -> labels(page.body())).contains("Nothing corrected in this scope"));
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static LedgerData captureLedger(Engine engine, long now)
    {
        return LedgerData.capture(engine, now, LedgerData.Entry.current());
    }

    private static Engine engine()
    {
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (java.util.Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                int unit = id == COINS ? 1 : 7;
                PriceSource source = id == COINS ? PriceSource.FACE_VALUE
                    : PriceSource.GRAND_EXCHANGE;
                flows.add(new Flow(id, id == COINS ? "Coins" : "Rune " + id, delta.getValue(),
                    unit, delta.getValue() * unit, source));
            }
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig()
        {
            @Override
            public ReceiptRetentionPeriod receiptRetentionDays()
            {
                return ReceiptRetentionPeriod.DAYS_365;
            }
        });
    }

    private static Transaction gain(long at, String name, int itemId, long value)
    {
        return new Transaction(at, null, TransactionType.GAIN, Context.GENERIC, "", "Vorkath",
            true, Collections.singletonList(new Flow(itemId, name, 1L, (int) value, value,
                PriceSource.GRAND_EXCHANGE)), ClassificationConfidence.LIKELY, "Loot", null);
    }

    private static Transaction food(long at, String name, long value)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(SHARK, name, -1L, (int) value, -value,
                PriceSource.GRAND_EXCHANGE)), ClassificationConfidence.CONFIRMED, "Food", null);
        transaction.setActionKind(ActionKind.EAT);
        return transaction;
    }

    private static Transaction unpriced(long at)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(SHARK, "Shark", -1L, 0, 0L,
                PriceSource.UNPRICED)), ClassificationConfidence.CONFIRMED, "Unpriced food", null);
        transaction.setActionKind(ActionKind.EAT);
        return transaction;
    }

    private static Transaction cast(String label, long at)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(560, "Death rune", -2L, 188, -376L, PriceSource.GRAND_EXCHANGE),
                new Flow(562, "Chaos rune", -4L, 106, -424L, PriceSource.GRAND_EXCHANGE),
                new Flow(555, "Water rune", -4L, 5, -20L, PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(ActionKind.CAST);
        if (label != null)
        {
            transaction.setObservedActionLabel(ActionLabel.of(label));
        }
        return transaction;
    }

    /** Engine-level GE custody fixture: realized or pending market settlements. */
    private static final class MarketFixture
    {
        final Engine engine;
        final OfferLedger ledger = new OfferLedger();
        final Map<Integer, Long> inventory = new HashMap<>();
        long now = T0;

        MarketFixture()
        {
            engine = engine();
            engine.startCustomSession("Trading", SessionMode.AUTO, now);
            inventory.put(COINS, 100_000L);
            inventory.put(561, 10L);
            engine.setBaseline(new ContainerSnapshot(inventory));
        }

        void sell(int slot, int item, int price)
        {
            // Known coverage for the sold quantity at the observed quote, so the sale realizes a
            // proven result.
            engine.getActiveSession().addTransaction(new Transaction(now - 1_000L, null,
                TransactionType.GAIN, Context.GENERIC, "", "Vorkath", true,
                Collections.singletonList(new Flow(item, "Rune " + item, 10L, 7, 70L,
                    PriceSource.GRAND_EXCHANGE)),
                ClassificationConfidence.CONFIRMED, "Loot", null), 500);
            offer(slot, GrandExchangeOfferState.SELLING, item, 10, 0, price, 0);
            inventory.remove(item);
            settle();
            offer(slot, GrandExchangeOfferState.SOLD, item, 10, 10, price, 10 * price);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + 10L * price);
            settle();
        }

        void pendingSell(int slot, int item, int price)
        {
            offer(slot, GrandExchangeOfferState.SELLING, item, 10, 0, price, 0);
            inventory.remove(item);
            settle();
        }

        private void settle()
        {
            now += 600L;
            engine.markInventoryDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
            for (int i = 0; i < 3; i++)
            {
                engine.processIfDirty(snapshot, now);
                now += 600L;
            }
        }

        private void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent)
        {
            now += 600L;
            OfferLedger.Transition transition = ledger.observe(
                new OfferLedger.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.noteGeOfferObservation(transition, "Rune " + item, now);
            }
        }
    }

    private static final class LiveNoop implements LivePage.Actions
    {
        @Override
        public void editTarget()
        {
        }

        @Override public void togglePause() { }

        @Override public void openLedger(LedgerData.Entry entry) { }
    }

    private static final class LedgerNoop implements LedgerPage.Actions
    {
        @Override public void openScopeMenu(JComponent anchor) { }
        @Override public void costViewChanged(LedgerData.CostView view) { }
        @Override public void searchChanged(String text) { }
        @Override public LedgerData.CorrectionPreview preview(String id,
            Correction correction) { return null; }
        @Override public LedgerPage.CorrectionOutcome correct(String id,
            Correction correction, long previewRevision)
        { return LedgerPage.CorrectionOutcome.REFUSED; }
        @Override public void split(String id) { }
        @Override public void undoCorrection() { }
        @Override public void decideAll(ReviewDecision decision) { }
        @Override public void refresh() { }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private static List<String> labels(Component component)
    {
        List<String> out = new ArrayList<>();
        walk(component, out);
        return out;
    }

    private static void walk(Component component, List<String> out)
    {
        if (component instanceof JLabel)
        {
            String text = ((JLabel) component).getText();
            if (text != null && !text.isEmpty())
            {
                out.add(text);
            }
        }
        if (component instanceof AbstractButton)
        {
            String text = ((AbstractButton) component).getText();
            if (text != null && !text.isEmpty())
            {
                out.add(text);
            }
        }
        if (component instanceof Container)
        {
            for (Component child : ((Container) component).getComponents())
            {
                walk(child, out);
            }
        }
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            return callable.call();
        }
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
        {
            try { value.set(callable.call()); }
            catch (Throwable ex) { failure.set(ex); }
        });
        if (failure.get() != null)
        {
            throw new AssertionError(failure.get());
        }
        return value.get();
    }
}
