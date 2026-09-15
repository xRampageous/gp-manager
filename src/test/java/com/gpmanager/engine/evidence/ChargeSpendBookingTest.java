package com.gpmanager.engine.evidence;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TransactionType;
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
    @Test
    public void booksOnlyCompleteExactBlowpipeMeasurement()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertEquals(null, tracker.observe(blowpipe(100, 1_000)));
        MeasuredChargeDelta delta = tracker.observe(blowpipe(96, 997));

        ChargeSpendBooking.Result result = ChargeSpendBooking.tryBook(
            delta, "Toxic blowpipe", priced(
                new ItemFlow(12934, "Zulrah's scales", -4L, 2, -8L, ItemPriceSource.GRAND_EXCHANGE),
                new ItemFlow(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -300L,
                    ItemPriceSource.GRAND_EXCHANGE)));
        assertTrue(result.booked);
        assertEquals(308L, result.costGp);
        assertTrue(result.explanation.toLowerCase().contains("measured check difference"));
    }

    @Test
    public void missingExtraOrMismatchedPriceFailsClosedAsWholeRead()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(blowpipe(100, 1_000));
        MeasuredChargeDelta delta = tracker.observe(blowpipe(96, 997));

        assertNoCost(ChargeSpendBooking.tryBook(delta, "Toxic blowpipe", Collections.singletonList(
            new ItemFlow(12934, "Zulrah's scales", -4L, 2, -8L, ItemPriceSource.GRAND_EXCHANGE))));
        assertNoCost(ChargeSpendBooking.tryBook(delta, "Toxic blowpipe", priced(
            new ItemFlow(12934, "Zulrah's scales", -5L, 2, -10L, ItemPriceSource.GRAND_EXCHANGE),
            new ItemFlow(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -300L,
                ItemPriceSource.GRAND_EXCHANGE))));
        assertNoCost(ChargeSpendBooking.tryBook(delta, "Toxic blowpipe", priced(
            new ItemFlow(12934, "Zulrah's scales", -4L, 2, -8L, ItemPriceSource.GRAND_EXCHANGE),
            new ItemFlow(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -299L,
                ItemPriceSource.GRAND_EXCHANGE))));
        assertNoCost(ChargeSpendBooking.tryBook(delta, "Toxic blowpipe", priced(
            new ItemFlow(12934, "Zulrah's scales", -4L, 2, -8L, ItemPriceSource.GRAND_EXCHANGE),
            new ItemFlow(ItemID.ADAMANT_DART, "Adamant dart", -3L, 0, 0L,
                ItemPriceSource.UNPRICED))));
    }

    @Test
    public void pendingLoadReviewBlocksMatchingMeasuredSpendUntilResolved()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(blowpipe(100, 1_000));
        MeasuredChargeDelta delta = tracker.observe(blowpipe(96, 997));
        List<ItemFlow> flows = priced(
            new ItemFlow(12934, "Zulrah's scales", -4L, 2, -8L, ItemPriceSource.GRAND_EXCHANGE),
            new ItemFlow(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -300L,
                ItemPriceSource.GRAND_EXCHANGE));

        ChargeSpendBooking.Result pending = ChargeSpendBooking.tryBook(
            delta, "Toxic blowpipe", flows, Collections.singleton(12934));
        assertNoCost(pending);
        ChargeSpendBooking.Result resolved = ChargeSpendBooking.tryBook(
            delta, "Toxic blowpipe", flows, Collections.emptySet());
        assertTrue(resolved.booked);
    }

    @Test
    public void aggregateCostOverflowFailsClosedAtBookingBoundary()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(MeasuredChargeRead.parseCheckMessage(
            "Darts: Adamant dart x 4,000,000,000,000,000,000. "
                + "Scales: 4,000,000,000,000,000,000 (1.0%)."));
        MeasuredChargeDelta delta = tracker.observe(MeasuredChargeRead.parseCheckMessage(
            "Darts: Adamant dart x 0. Scales: 0 (0.0%)."));

        ChargeSpendBooking.Result result = ChargeSpendBooking.tryBook(delta, "Toxic blowpipe", priced(
            new ItemFlow(12934, "Zulrah's scales", -4_000_000_000_000_000_000L,
                2, -8_000_000_000_000_000_000L, ItemPriceSource.GRAND_EXCHANGE),
            new ItemFlow(ItemID.ADAMANT_DART, "Adamant dart", -4_000_000_000_000_000_000L,
                2, -8_000_000_000_000_000_000L, ItemPriceSource.GRAND_EXCHANGE)));

        assertNoCost(result);
    }

    @Test
    public void tridentRecipeComesFromMeasuredChargeDifferenceAndCoinsUseFaceValue()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(MeasuredChargeRead.parseCheckMessage("Your Trident of the seas has 10 charges."));
        MeasuredChargeDelta delta = tracker.observe(
            MeasuredChargeRead.parseCheckMessage("Your Trident of the seas has 8 charges."));

        ChargeSpendBooking.Result result = ChargeSpendBooking.tryBook(delta, "Trident of the seas", priced(
            new ItemFlow(560, "Death rune", -2L, 100, -200L, ItemPriceSource.GRAND_EXCHANGE),
            new ItemFlow(562, "Chaos rune", -2L, 90, -180L, ItemPriceSource.GRAND_EXCHANGE),
            new ItemFlow(554, "Fire rune", -10L, 5, -50L, ItemPriceSource.GRAND_EXCHANGE),
            new ItemFlow(995, "Coins", -20L, 1, -20L, ItemPriceSource.FACE_VALUE)));
        assertTrue(result.booked);
        assertEquals(450L, result.costGp);
    }

    @Test
    public void engineAddsMeasuredChargeSpendAsCountedLedgerConsumption()
    {
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        engine.ensureSession(1_000L);
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(blowpipe(100, 1_000));
        MeasuredChargeDelta delta = tracker.observe(blowpipe(96, 997));

        ChargeSpendBooking.Result result = engine.bookChargeSpend(
            delta,
            "Toxic blowpipe",
            priced(
                new ItemFlow(12934, "Zulrah's scales", -4L, 2, -8L, ItemPriceSource.GRAND_EXCHANGE),
                new ItemFlow(ItemID.ADAMANT_DART, "Adamant dart", -3L, 100, -300L,
                    ItemPriceSource.GRAND_EXCHANGE)),
            1_200L);

        assertTrue(result.booked);
        ProfitTransaction transaction = result.transaction;
        assertNotNull(transaction);
        assertEquals(TransactionType.CONSUMPTION, transaction.getType());
        assertTrue(transaction.isCounted());
        assertEquals(ActionKind.FIRE, transaction.getActionKind());
        assertEquals(-308L, transaction.getNet());
    }

    @Test
    public void catalogueMarksOnlyMeasuredFamiliesImplemented()
    {
        assertTrue(ChargeRecipeCatalogue.recipeFor(ChargeFamilyIds.BLOWPIPE).implemented);
        assertTrue(ChargeRecipeCatalogue.recipeFor(ChargeFamilyIds.TRIDENT).implemented);
        assertTrue(ChargeRecipeCatalogue.isKnownFamily(ChargeFamilyIds.IBAN_STAFF));
        assertFalse(ChargeRecipeCatalogue.recipeFor(ChargeFamilyIds.IBAN_STAFF).implemented);
        for (ChargeRecipeCatalogue.Recipe recipe : ChargeRecipeCatalogue.all().values())
        {
            if (!ChargeFamilyIds.BLOWPIPE.equals(recipe.familyId)
                && !ChargeFamilyIds.TRIDENT.equals(recipe.familyId))
            {
                assertFalse(recipe.familyId + " must remain catalogue-only", recipe.implemented);
            }
        }
    }

    private static MeasuredChargeRead blowpipe(long scales, long darts)
    {
        return MeasuredChargeRead.parseCheckMessage(
            "Darts: Adamant dart x " + darts + ". Scales: " + scales + " (1.0%).");
    }

    private static List<ItemFlow> priced(ItemFlow... flows)
    {
        List<ItemFlow> list = new ArrayList<>();
        Collections.addAll(list, flows);
        return list;
    }

    private static void assertNoCost(ChargeSpendBooking.Result result)
    {
        assertFalse(result.booked);
        assertEquals(0L, result.costGp);
        assertTrue(result.flows.isEmpty());
    }
}
