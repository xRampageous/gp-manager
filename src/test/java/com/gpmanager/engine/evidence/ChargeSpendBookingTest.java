package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ChargeSpendBookingTest
{
    private static ChargeDelta observe(
        MeasuredChargeReadTracker tracker,
        ChargeRead read)
    {
        return tracker.observe(read, "single-target", System.currentTimeMillis());
    }

    @Test
    public void booksOnlyCompleteExactBlowpipeMeasurement()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertEquals(null, observe(tracker, blowpipe(100, 1_000)));
        ChargeDelta delta = observe(tracker, blowpipe(96, 997));

        long result = tryBook(
            delta, "Toxic blowpipe", priced(
                new Flow(12934, "Zulrah's scales", -4L, 2, -8L, PriceSource.GRAND_EXCHANGE),
                new Flow(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -300L,
                    PriceSource.GRAND_EXCHANGE)), java.util.Collections.emptySet());
        assertTrue(result > 0L);
        assertEquals(308L, result);
    }

    @Test
    public void missingExtraOrMismatchedPriceFailsClosedAsWholeRead()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        observe(tracker, blowpipe(100, 1_000));
        ChargeDelta delta = observe(tracker, blowpipe(96, 997));

        assertNoCost(tryBook(delta, "Toxic blowpipe", Collections.singletonList(
            new Flow(12934, "Zulrah's scales", -4L, 2, -8L, PriceSource.GRAND_EXCHANGE)), java.util.Collections.emptySet()));
        assertNoCost(tryBook(delta, "Toxic blowpipe", priced(
            new Flow(12934, "Zulrah's scales", -5L, 2, -10L, PriceSource.GRAND_EXCHANGE),
            new Flow(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -300L,
                PriceSource.GRAND_EXCHANGE)), java.util.Collections.emptySet()));
        assertNoCost(tryBook(delta, "Toxic blowpipe", priced(
            new Flow(12934, "Zulrah's scales", -4L, 2, -8L, PriceSource.GRAND_EXCHANGE),
            new Flow(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -299L,
                PriceSource.GRAND_EXCHANGE)), java.util.Collections.emptySet()));
        assertNoCost(tryBook(delta, "Toxic blowpipe", priced(
            new Flow(12934, "Zulrah's scales", -4L, 2, -8L, PriceSource.GRAND_EXCHANGE),
            new Flow(ItemID.ADAMANT_DART, "Adamant dart", -3L, 0, 0L,
                PriceSource.UNPRICED)), java.util.Collections.emptySet()));
    }

    @Test
    public void pendingLoadReviewBlocksMatchingMeasuredSpendUntilResolved()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        observe(tracker, blowpipe(100, 1_000));
        ChargeDelta delta = observe(tracker, blowpipe(96, 997));
        List<Flow> flows = priced(
            new Flow(12934, "Zulrah's scales", -4L, 2, -8L, PriceSource.GRAND_EXCHANGE),
            new Flow(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -300L,
                PriceSource.GRAND_EXCHANGE));

        long pending = tryBook(
            delta, "Toxic blowpipe", flows, Collections.singleton(12934));
        assertNoCost(pending);
        long resolved = tryBook(
            delta, "Toxic blowpipe", flows, Collections.emptySet());
        assertTrue(resolved > 0L);
    }

    @Test
    public void aggregateCostOverflowFailsClosedAtBookingBoundary()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        observe(tracker, ChargeRead.parseCheckMessage(
            "Darts: Adamant dart x 4,000,000,000,000,000,000. "
                + "Scales: 4,000,000,000,000,000,000 (1.0%)."));
        ChargeDelta delta = observe(tracker, ChargeRead.parseCheckMessage(
            "Darts: Adamant dart x 0. Scales: 0 (0.0%)."));

        long result = tryBook(delta, "Toxic blowpipe", priced(
            new Flow(12934, "Zulrah's scales", -4_000_000_000_000_000_000L,
                2, -8_000_000_000_000_000_000L, PriceSource.GRAND_EXCHANGE),
            new Flow(ItemID.ADAMANT_DART, "Adamant dart", -4_000_000_000_000_000_000L,
                2, -8_000_000_000_000_000_000L, PriceSource.GRAND_EXCHANGE)), java.util.Collections.emptySet());

        assertNoCost(result);
    }

    @Test
    public void tridentRecipeComesFromMeasuredChargeDifferenceAndCoinsUseFaceValue()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        observe(tracker, ChargeRead.parseCheckMessage("Your Trident of the seas has 10 charges."));
        ChargeDelta delta = observe(tracker,
            ChargeRead.parseCheckMessage("Your Trident of the seas has 8 charges."));

        long result = tryBook(delta, "Trident of the seas", priced(
            new Flow(560, "Death rune", -2L, 100, -200L, PriceSource.GRAND_EXCHANGE),
            new Flow(562, "Chaos rune", -2L, 90, -180L, PriceSource.GRAND_EXCHANGE),
            new Flow(554, "Fire rune", -10L, 5, -50L, PriceSource.GRAND_EXCHANGE),
            new Flow(995, "Coins", -20L, 1, -20L, PriceSource.FACE_VALUE)), java.util.Collections.emptySet());
        assertTrue(result > 0L);
        assertEquals(450L, result);
    }

    @Test
    public void engineAddsMeasuredChargeSpendAsCountedLedgerConsumption()
    {
        Engine engine = new Engine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        engine.ensureSession(1_000L);
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        observe(tracker, blowpipe(100, 1_000));
        ChargeDelta delta = observe(tracker, blowpipe(96, 997));

        Transaction transaction = engine.bookChargeSpend(
            delta,
            "Toxic blowpipe",
            priced(
                new Flow(12934, "Zulrah's scales", -4L, 2, -8L, PriceSource.GRAND_EXCHANGE),
                new Flow(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -300L,
                    PriceSource.GRAND_EXCHANGE)),
            1_200L);

        assertNotNull(transaction);
        assertEquals(TransactionType.CONSUMPTION, transaction.getType());
        assertTrue(transaction.isCounted());
        assertEquals(ActionKind.FIRE, transaction.getActionKind());
        assertEquals(-308L, transaction.getNet());
    }

    @Test
    public void manualOverrideIsAValidChargeComponentSource()
    {
        ChargeDelta delta = tridentDelta();
        long result = tryBook(delta, "Trident of the seas", priced(
            new Flow(560, "Death rune", -2L, 150, -300L, PriceSource.MANUAL_OVERRIDE),
            new Flow(562, "Chaos rune", -2L, 90, -180L, PriceSource.GRAND_EXCHANGE),
            new Flow(554, "Fire rune", -10L, 5, -50L, PriceSource.GRAND_EXCHANGE),
            new Flow(995, "Coins", -20L, 1, -20L, PriceSource.FACE_VALUE)),
            Collections.emptySet());

        assertTrue("a manual override is a valid shared-policy valuation", result > 0L);
        assertEquals(550L, result);
    }

    @Test
    public void unpricedUnknownAndRetiredSourcesFailClosed()
    {
        ChargeDelta delta = tridentDelta();
        for (PriceSource source : new PriceSource[] {
            PriceSource.UNPRICED, PriceSource.UNKNOWN, PriceSource.DEFERRED_CLAIM,
            PriceSource.CURRENCY_PROXY, PriceSource.HIGH_ALCHEMY, PriceSource.OFFER_PRICE})
        {
            int unit = source == PriceSource.UNPRICED ? 0 : 100;
            assertNoCost(tryBook(delta, "Trident of the seas", priced(
                new Flow(560, "Death rune", -2L, unit, -2L * unit, source),
                new Flow(562, "Chaos rune", -2L, 90, -180L, PriceSource.GRAND_EXCHANGE),
                new Flow(554, "Fire rune", -10L, 5, -50L, PriceSource.GRAND_EXCHANGE),
                new Flow(995, "Coins", -20L, 1, -20L, PriceSource.FACE_VALUE)),
                Collections.emptySet()));
        }
    }

    @Test
    public void coinComponentsRequireFaceValueUnitOne()
    {
        ChargeDelta delta = tridentDelta();
        assertNoCost(tryBook(delta, "Trident of the seas", priced(
            new Flow(560, "Death rune", -2L, 100, -200L, PriceSource.GRAND_EXCHANGE),
            new Flow(562, "Chaos rune", -2L, 90, -180L, PriceSource.GRAND_EXCHANGE),
            new Flow(554, "Fire rune", -10L, 5, -50L, PriceSource.GRAND_EXCHANGE),
            new Flow(995, "Coins", -20L, 1, -20L, PriceSource.GRAND_EXCHANGE)),
            Collections.emptySet()));
        assertNoCost(tryBook(delta, "Trident of the seas", priced(
            new Flow(560, "Death rune", -2L, 100, -200L, PriceSource.GRAND_EXCHANGE),
            new Flow(562, "Chaos rune", -2L, 90, -180L, PriceSource.GRAND_EXCHANGE),
            new Flow(554, "Fire rune", -10L, 5, -50L, PriceSource.GRAND_EXCHANGE),
            new Flow(995, "Coins", -20L, 2, -40L, PriceSource.FACE_VALUE)),
            Collections.emptySet()));
        assertTrue(tryBook(delta, "Trident of the seas", priced(
            new Flow(560, "Death rune", -2L, 100, -200L, PriceSource.GRAND_EXCHANGE),
            new Flow(562, "Chaos rune", -2L, 90, -180L, PriceSource.GRAND_EXCHANGE),
            new Flow(554, "Fire rune", -10L, 5, -50L, PriceSource.GRAND_EXCHANGE),
            new Flow(995, "Coins", -20L, 1, -20L, PriceSource.FACE_VALUE)),
            Collections.emptySet()) > 0L);
    }

    private static ChargeDelta tridentDelta()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        observe(tracker, ChargeRead.parseCheckMessage("Your Trident of the seas has 10 charges."));
        return observe(tracker,
            ChargeRead.parseCheckMessage("Your Trident of the seas has 8 charges."));
    }

    private static ChargeRead blowpipe(long scales, long darts)
    {
        return ChargeRead.parseCheckMessage(
            "Darts: Adamant dart x " + darts + ". Scales: " + scales + " (1.0%).");
    }

    private static List<Flow> priced(Flow... flows)
    {
        List<Flow> list = new ArrayList<>();
        Collections.addAll(list, flows);
        return list;
    }

    private static void assertNoCost(long cost)
    {
        assertEquals(0L, cost);
    }

    private static long tryBook(ChargeDelta delta, String weapon, List<Flow> losses,
        java.util.Set<Integer> pendingLoadComponentIds)
    {
        return delta.exactCost(losses, pendingLoadComponentIds);
    }
}
