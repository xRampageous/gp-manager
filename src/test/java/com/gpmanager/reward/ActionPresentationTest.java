package com.gpmanager.reward;

import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.ui.HudTrayState;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * B10: one action-to-surface mapping. Wording comes from evidence; routine rune/ammo
 * events are Ledger-only on every other surface while accounting totals are unchanged.
 */
public class ActionPresentationTest
{
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(java.time.ZoneOffset.UTC);

    @Test
    public void routineKindsAreLedgerOnlyAndEverythingElseKeepsAllSurfaces()
    {
        assertEquals(EnumSet.of(ActionPresentation.Surface.LEDGER),
            ActionPresentation.surfacesFor(ActionKind.CAST));
        assertEquals(EnumSet.of(ActionPresentation.Surface.LEDGER),
            ActionPresentation.surfacesFor(ActionKind.FIRE));
        assertEquals(EnumSet.of(ActionPresentation.Surface.LEDGER),
            ActionPresentation.surfacesFor(ActionKind.RECOVER_AMMO));
        assertEquals(EnumSet.of(ActionPresentation.Surface.LEDGER),
            ActionPresentation.surfacesFor(ActionKind.DEFERRED_CLAIM));
        assertEquals(EnumSet.of(ActionPresentation.Surface.LEDGER),
            ActionPresentation.surfacesFor(ActionKind.CHARGE_LOAD_AMBIGUOUS));
        assertEquals(EnumSet.allOf(ActionPresentation.Surface.class),
            ActionPresentation.surfacesFor(ActionKind.DRINK));
        assertEquals(EnumSet.allOf(ActionPresentation.Surface.class),
            ActionPresentation.surfacesFor(null));
        assertFalse(ActionPresentation.showsOn(null, ActionPresentation.Surface.LEDGER));
    }

    @Test
    public void receiptLinesUseTheOwnerVocabulary()
    {
        assertEquals("Drank · Prayer potion · 1 dose",
            ActionPresentation.receiptLine(stamped(drink4to3(), ActionKind.DRINK)));
        assertEquals("Ate · Shark · 1",
            ActionPresentation.receiptLine(stamped(loss(385, "Shark", 1, 800), ActionKind.EAT)));
        assertEquals("Decanted · Prayer potion",
            ActionPresentation.receiptLine(stamped(decant(), ActionKind.DECANT)));
        assertEquals("Mixed · Prayer potion(3) · 1",
            ActionPresentation.receiptLine(stamped(mix(), ActionKind.MIX)));
        assertEquals("Runes used · Fire rune · 5",
            ActionPresentation.receiptLine(stamped(loss(554, "Fire rune", 5, 5), ActionKind.CAST)));
        assertEquals("Supplies used · Prayer potion · 1 dose",
            ActionPresentation.receiptLine(stamped(drink4to3(), ActionKind.SUPPLIES)));
        assertNull(ActionPresentation.receiptLine(drink4to3()));
        assertNull(ActionPresentation.receiptLine(stamped(new ProfitTransaction(
            1_000L, null, TransactionType.TRANSFER, TrackingContext.TRANSFER,
            "Key held · Brimstone chest", "Brimstone chest", false,
            Collections.singletonList(new ItemFlow(23083, "Brimstone key", 1L, 0, 0L))),
            ActionKind.DEFERRED_CLAIM)));
        assertNull(ActionPresentation.receiptLine(stamped(new ProfitTransaction(
            1_000L, null, TransactionType.TRANSFER, TrackingContext.TRANSFER,
            "Charge load: ambiguous quantity, not booked", "Charge load", false,
            Collections.emptyList()), ActionKind.CHARGE_LOAD_AMBIGUOUS)));
    }

    @Test
    public void chestClaimContentsUseCatalogueSourceAndChestLootTag()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction contents = new ProfitTransaction(
            1_000L, null, TransactionType.LOOT, TrackingContext.LOOT, "Brimstone chest",
            "Brimstone chest", true,
            Collections.singletonList(new ItemFlow(10_001, "Test reward", 1L, 25_000, 25_000L)));

        model.offerSkillingOrConfirmed(contents, 1_000L, false);

        assertNotNull(model.current());
        assertEquals("Brimstone chest", model.current().getSourceName());
        assertEquals("Chest loot", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void trayTagComesFromTheVerbNotTheStackShape()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction drink = stamped(drink4to3(), ActionKind.DRINK);
        model.offerSkillingOrConfirmed(drink, 1_000L, true);

        assertNotNull(model.current());
        assertEquals("Drank", HudTrayState.tagLine(model.current()));
        assertEquals(HudTrayState.USED, HudTrayState.resolve(model.current()));
        // Both stacks stay on the card so tray GP matches the row net.
        assertEquals(2, model.current().getItems().size());
        long trayNet = 0L;
        for (RewardItem item : model.current().getItems())
        {
            trayNet += item.getRecordedValue();
        }
        assertEquals(drink.getNet(), trayNet);
    }

    @Test
    public void decantCardSaysDecantedAndStaysNeutral()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerSkillingOrConfirmed(stamped(decant(), ActionKind.DECANT), 1_000L, true);

        assertNotNull(model.current());
        assertEquals("Decanted", HudTrayState.tagLine(model.current()));
        assertEquals(HudTrayState.MIXED, HudTrayState.resolve(model.current()));
    }

    @Test
    public void unevidencedDoseStepIsSuppliesUsedNeverMixed()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerSkillingOrConfirmed(stamped(drink4to3(), ActionKind.SUPPLIES), 1_000L, true);
        assertNotNull(model.current());
        assertEquals("Supplies used", HudTrayState.tagLine(model.current()));

        RewardPresentationModel legacy = new RewardPresentationModel();
        legacy.offerSkillingOrConfirmed(drink4to3(), 1_000L, true);
        assertNotNull(legacy.current());
        assertEquals("Supplies used", HudTrayState.tagLine(legacy.current()));
    }

    @Test
    public void twoDrinksMergeIntoOneDrankCard()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setActivityHold(true);
        model.offerSkillingOrConfirmed(stamped(drink4to3(), ActionKind.DRINK), 1_000L, true);
        ProfitTransaction second = stamped(new ProfitTransaction(
            1_600L, null, TransactionType.CONSUMPTION, TrackingContext.GENERIC, "", "General", true,
            Arrays.asList(
                new ItemFlow(139, "Prayer potion(3)", -1L, 150, -150L),
                new ItemFlow(141, "Prayer potion(2)", 1L, 100, 100L))), ActionKind.DRINK);
        model.offerSkillingOrConfirmed(second, 1_600L, true);

        assertNotNull(model.current());
        assertEquals("Drank", HudTrayState.tagLine(model.current()));
        long trayNet = 0L;
        for (RewardItem item : model.current().getItems())
        {
            trayNet += item.getRecordedValue();
        }
        assertEquals(-100L, trayNet);
    }

    @Test
    public void incidentalSipDuringCombatLeavesTheLootCardAndTitleAlone()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Zulrah",
            "npc:Zulrah:kill-1",
            Collections.singletonList(new RewardItem(12934, "Zulrah's scales", 100, 20_000L, true, com.gpmanager.model.ItemPriceSource.GRAND_EXCHANGE)),
            1_000L,
            true);
        assertNotNull(model.current());
        assertEquals("Zulrah", model.current().getSourceName());

        model.offerSkillingOrConfirmed(stamped(drink4to3(), ActionKind.DRINK), 1_200L, true);

        assertNotNull(model.current());
        assertEquals("Zulrah", model.current().getSourceName());
        assertTrue(model.current().getSourceKind().isObservedLoot());
    }

    @Test
    public void hudPlusCapsuleSkipsRoutineRowsButSessionNetIncludesThem()
    {
        ProfitSession session = new ProfitSession("s1", 1_000L);
        ProfitTransaction cast = stamped(loss(554, "Fire rune", 5, 5), ActionKind.CAST);
        ProfitTransaction fired = stamped(loss(892, "Rune arrow", 3, 60), ActionKind.FIRE);
        ProfitTransaction eat = stamped(loss(385, "Shark", 1, 800), ActionKind.EAT);
        session.addTransaction(cast, 100);
        session.addTransaction(fired, 100);
        session.addTransaction(eat, 100);

        SessionItemLedger capsule = new SessionItemLedger();
        capsule.bindToSession(session);
        SessionItemLedger.SessionLedgerSnapshot snapshot = capsule.snapshot(0L, 10, 10);

        assertEquals(1, snapshot.getLosses().size());
        assertEquals("Shark", snapshot.getLosses().get(0).getItemName());
        assertEquals(-800L, snapshot.getNet());
        // Accounting untouched: every row still counts toward the session.
        assertEquals(-25L - 180L - 800L, session.metrics(2_000L, 60_000L).getNet());

        SessionItemLedger live = new SessionItemLedger();
        live.record(cast);
        live.record(fired);
        assertTrue(live.snapshot(0L, 10, 10).isEmpty());
    }

    @Test
    public void liveTimelineOmitsRoutineRowsAndKeepsTheRest()
    {
        ProfitTransaction cast = stamped(loss(554, "Fire rune", 5, 5), ActionKind.CAST);
        ProfitTransaction recovered = stamped(new ProfitTransaction(
            1_100L, null, TransactionType.GAIN, TrackingContext.GENERIC, "", "General", true,
            Collections.singletonList(new ItemFlow(892, "Rune arrow", 2L, 60, 120L))), ActionKind.RECOVER_AMMO);
        ProfitTransaction eat = stamped(loss(385, "Shark", 1, 800), ActionKind.EAT);

        // The Live › Recent surface applies the same policy: routine rows never appear.
        assertNull(com.gpmanager.ui.bento.LiveSnapshot.toRecent(cast));
        assertNull(com.gpmanager.ui.bento.LiveSnapshot.toRecent(recovered));
        com.gpmanager.ui.bento.LiveSnapshot.Recent row = com.gpmanager.ui.bento.LiveSnapshot.toRecent(eat);
        assertNotNull(row);
        assertEquals(-800L, row.value);
        assertEquals("ate", row.verb);
    }

    @Test
    public void legacyProcessVerbsAreNotHijackedByTheActionLookup()
    {
        assertNull(HudTrayState.actionFromSource("Buried"));
        assertNull(HudTrayState.actionFromSource("Offered"));
        assertNull(HudTrayState.actionFromSource("Mixed"));
        assertEquals(ActionKind.DRINK, HudTrayState.actionFromSource("Drank"));
        assertEquals(ActionKind.SUPPLIES, HudTrayState.actionFromSource("Supplies used"));
        assertNull(HudTrayState.actionFromSource("Woodcutting"));
    }

    private static ProfitTransaction stamped(ProfitTransaction transaction, ActionKind kind)
    {
        transaction.setActionKind(kind);
        return transaction;
    }

    private static ProfitTransaction drink4to3()
    {
        return new ProfitTransaction(
            1_000L, null, TransactionType.CONSUMPTION, TrackingContext.GENERIC, "", "General", true,
            Arrays.asList(
                new ItemFlow(2434, "Prayer potion(4)", -1L, 200, -200L),
                new ItemFlow(139, "Prayer potion(3)", 1L, 150, 150L)));
    }

    private static ProfitTransaction decant()
    {
        return new ProfitTransaction(
            1_000L, null, TransactionType.PROCESSING, TrackingContext.PRODUCTION, "", "Herblore", true,
            Arrays.asList(
                new ItemFlow(139, "Prayer potion(3)", -1L, 150, -150L),
                new ItemFlow(143, "Prayer potion(1)", -1L, 50, -50L),
                new ItemFlow(2434, "Prayer potion(4)", 1L, 200, 200L),
                new ItemFlow(229, "Vial", 1L, 1, 1L)));
    }

    private static ProfitTransaction mix()
    {
        return new ProfitTransaction(
            1_000L, null, TransactionType.PROCESSING, TrackingContext.PRODUCTION, "", "Herblore", true,
            Arrays.asList(
                new ItemFlow(257, "Ranarr weed", -1L, 6000, -6000L),
                new ItemFlow(231, "Snape grass", -1L, 300, -300L),
                new ItemFlow(139, "Prayer potion(3)", 1L, 150, 150L)));
    }

    private static ProfitTransaction loss(int id, String name, long quantity, int price)
    {
        return new ProfitTransaction(
            1_000L, null, TransactionType.CONSUMPTION, TrackingContext.GENERIC, "", "General", true,
            Collections.singletonList(new ItemFlow(id, name, -quantity, price, -quantity * price)));
    }
}
