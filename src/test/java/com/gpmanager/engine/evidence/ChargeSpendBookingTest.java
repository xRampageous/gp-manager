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
    private static Cm observe(
        MeasuredChargeReadTracker tracker,
        Ar read)
    {
        return tracker.observe(read, "single-target", System.currentTimeMillis());
    }

    @Test
    public void booksOnlyCompleteExactBlowpipeMeasurement()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertEquals(null, observe(tracker, blowpipe(100, 1_000)));
        Cm delta = observe(tracker, blowpipe(96, 997));

        long result = tryBook(
            delta, "Toxic blowpipe", priced(
                new Ab(12934, "Zulrah's scales", -4L, 2, -8L, Av.GRAND_EXCHANGE),
                new Ab(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -300L,
                    Av.GRAND_EXCHANGE)), java.util.Collections.emptySet());
        assertTrue(result > 0L);
        assertEquals(308L, result);
    }

    @Test
    public void missingExtraOrMismatchedPriceFailsClosedAsWholeRead()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        observe(tracker, blowpipe(100, 1_000));
        Cm delta = observe(tracker, blowpipe(96, 997));

        assertNoCost(tryBook(delta, "Toxic blowpipe", Collections.singletonList(
            new Ab(12934, "Zulrah's scales", -4L, 2, -8L, Av.GRAND_EXCHANGE)), java.util.Collections.emptySet()));
        assertNoCost(tryBook(delta, "Toxic blowpipe", priced(
            new Ab(12934, "Zulrah's scales", -5L, 2, -10L, Av.GRAND_EXCHANGE),
            new Ab(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -300L,
                Av.GRAND_EXCHANGE)), java.util.Collections.emptySet()));
        assertNoCost(tryBook(delta, "Toxic blowpipe", priced(
            new Ab(12934, "Zulrah's scales", -4L, 2, -8L, Av.GRAND_EXCHANGE),
            new Ab(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -299L,
                Av.GRAND_EXCHANGE)), java.util.Collections.emptySet()));
        assertNoCost(tryBook(delta, "Toxic blowpipe", priced(
            new Ab(12934, "Zulrah's scales", -4L, 2, -8L, Av.GRAND_EXCHANGE),
            new Ab(ItemID.ADAMANT_DART, "Adamant dart", -3L, 0, 0L,
                Av.UNPRICED)), java.util.Collections.emptySet()));
    }

    @Test
    public void pendingLoadReviewBlocksMatchingMeasuredSpendUntilResolved()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        observe(tracker, blowpipe(100, 1_000));
        Cm delta = observe(tracker, blowpipe(96, 997));
        List<Ab> flows = priced(
            new Ab(12934, "Zulrah's scales", -4L, 2, -8L, Av.GRAND_EXCHANGE),
            new Ab(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -300L,
                Av.GRAND_EXCHANGE));

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
        observe(tracker, Ar.acs(
            "Darts: Adamant dart x 4,000,000,000,000,000,000. "
                + "Scales: 4,000,000,000,000,000,000 (1.0%)."));
        Cm delta = observe(tracker, Ar.acs(
            "Darts: Adamant dart x 0. Scales: 0 (0.0%)."));

        long result = tryBook(delta, "Toxic blowpipe", priced(
            new Ab(12934, "Zulrah's scales", -4_000_000_000_000_000_000L,
                2, -8_000_000_000_000_000_000L, Av.GRAND_EXCHANGE),
            new Ab(ItemID.ADAMANT_DART, "Adamant dart", -4_000_000_000_000_000_000L,
                2, -8_000_000_000_000_000_000L, Av.GRAND_EXCHANGE)), java.util.Collections.emptySet());

        assertNoCost(result);
    }

    @Test
    public void tridentRecipeComesFromMeasuredChargeDifferenceAndCoinsUseFaceValue()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        observe(tracker, Ar.acs("Your Trident of the seas has 10 charges."));
        Cm delta = observe(tracker,
            Ar.acs("Your Trident of the seas has 8 charges."));

        long result = tryBook(delta, "Trident of the seas", priced(
            new Ab(560, "Death rune", -2L, 100, -200L, Av.GRAND_EXCHANGE),
            new Ab(562, "Chaos rune", -2L, 90, -180L, Av.GRAND_EXCHANGE),
            new Ab(554, "Fire rune", -10L, 5, -50L, Av.GRAND_EXCHANGE),
            new Ab(995, "Coins", -20L, 1, -20L, Av.FACE_VALUE)), java.util.Collections.emptySet());
        assertTrue(result > 0L);
        assertEquals(450L, result);
    }

    @Test
    public void engineAddsMeasuredChargeSpendAsCountedLedgerConsumption()
    {
        Am engine = new Am(
            deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        engine.rm(1_000L);
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        observe(tracker, blowpipe(100, 1_000));
        Cm delta = observe(tracker, blowpipe(96, 997));

        Ac transaction = engine.mj(
            delta,
            "Toxic blowpipe",
            priced(
                new Ab(12934, "Zulrah's scales", -4L, 2, -8L, Av.GRAND_EXCHANGE),
                new Ab(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -300L,
                    Av.GRAND_EXCHANGE)),
            1_200L);

        assertNotNull(transaction);
        assertEquals(Ai.CONSUMPTION, transaction.getType());
        assertTrue(transaction.isCounted());
        assertEquals(Au.FIRE, transaction.getActionKind());
        assertEquals(-308L, transaction.getNet());
    }

    @Test
    public void manualOverrideIsAValidChargeComponentSource()
    {
        Cm delta = tridentDelta();
        long result = tryBook(delta, "Trident of the seas", priced(
            new Ab(560, "Death rune", -2L, 150, -300L, Av.MANUAL_OVERRIDE),
            new Ab(562, "Chaos rune", -2L, 90, -180L, Av.GRAND_EXCHANGE),
            new Ab(554, "Fire rune", -10L, 5, -50L, Av.GRAND_EXCHANGE),
            new Ab(995, "Coins", -20L, 1, -20L, Av.FACE_VALUE)),
            Collections.emptySet());

        assertTrue("a manual override is a valid shared-policy valuation", result > 0L);
        assertEquals(550L, result);
    }

    @Test
    public void unpricedUnknownAndRetiredSourcesFailClosed()
    {
        Cm delta = tridentDelta();
        for (Av source : new Av[] {
            Av.UNPRICED, Av.UNKNOWN, Av.DEFERRED_CLAIM,
            Av.CURRENCY_PROXY, Av.HIGH_ALCHEMY, Av.OFFER_PRICE})
        {
            int unit = source == Av.UNPRICED ? 0 : 100;
            assertNoCost(tryBook(delta, "Trident of the seas", priced(
                new Ab(560, "Death rune", -2L, unit, -2L * unit, source),
                new Ab(562, "Chaos rune", -2L, 90, -180L, Av.GRAND_EXCHANGE),
                new Ab(554, "Fire rune", -10L, 5, -50L, Av.GRAND_EXCHANGE),
                new Ab(995, "Coins", -20L, 1, -20L, Av.FACE_VALUE)),
                Collections.emptySet()));
        }
    }

    @Test
    public void coinComponentsRequireFaceValueUnitOne()
    {
        Cm delta = tridentDelta();
        assertNoCost(tryBook(delta, "Trident of the seas", priced(
            new Ab(560, "Death rune", -2L, 100, -200L, Av.GRAND_EXCHANGE),
            new Ab(562, "Chaos rune", -2L, 90, -180L, Av.GRAND_EXCHANGE),
            new Ab(554, "Fire rune", -10L, 5, -50L, Av.GRAND_EXCHANGE),
            new Ab(995, "Coins", -20L, 1, -20L, Av.GRAND_EXCHANGE)),
            Collections.emptySet()));
        assertNoCost(tryBook(delta, "Trident of the seas", priced(
            new Ab(560, "Death rune", -2L, 100, -200L, Av.GRAND_EXCHANGE),
            new Ab(562, "Chaos rune", -2L, 90, -180L, Av.GRAND_EXCHANGE),
            new Ab(554, "Fire rune", -10L, 5, -50L, Av.GRAND_EXCHANGE),
            new Ab(995, "Coins", -20L, 2, -40L, Av.FACE_VALUE)),
            Collections.emptySet()));
        assertTrue(tryBook(delta, "Trident of the seas", priced(
            new Ab(560, "Death rune", -2L, 100, -200L, Av.GRAND_EXCHANGE),
            new Ab(562, "Chaos rune", -2L, 90, -180L, Av.GRAND_EXCHANGE),
            new Ab(554, "Fire rune", -10L, 5, -50L, Av.GRAND_EXCHANGE),
            new Ab(995, "Coins", -20L, 1, -20L, Av.FACE_VALUE)),
            Collections.emptySet()) > 0L);
    }

    private static Cm tridentDelta()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        observe(tracker, Ar.acs("Your Trident of the seas has 10 charges."));
        return observe(tracker,
            Ar.acs("Your Trident of the seas has 8 charges."));
    }

    private static Ar blowpipe(long scales, long darts)
    {
        return Ar.acs(
            "Darts: Adamant dart x " + darts + ". Scales: " + scales + " (1.0%).");
    }

    private static List<Ab> priced(Ab... flows)
    {
        List<Ab> list = new ArrayList<>();
        Collections.addAll(list, flows);
        return list;
    }

    private static void assertNoCost(long cost)
    {
        assertEquals(0L, cost);
    }

    private static long tryBook(Cm delta, String weapon, List<Ab> losses,
        java.util.Set<Integer> pendingLoadComponentIds)
    {
        return delta.aui(losses, pendingLoadComponentIds);
    }
}
