package com.gpmanager.engine;

import com.google.gson.Gson;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.TransactionType;
import com.gpmanager.reward.ActionPresentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * B10 action wording through the real engine: the settled row carries an honest
 * {@link ActionKind} derived from matched intent plus flow shape, while type, valuation,
 * net and quantities are exactly what they were before B10.
 */
public class ActionWordingEngineTest
{
    private static final int PPOT4 = 2434;
    private static final int PPOT3 = 139;
    private static final int PPOT2 = 141;
    private static final int PPOT1 = 143;
    private static final int VIAL = 229;
    private static final int SHARK = 385;
    private static final int BONES = 526;
    private static final int RANARR = 257;
    private static final int SNAPE = 231;
    private static final int FIRE_RUNE = 554;
    private static final int RUNE_ARROW = 892;
    private static final int GRIMY = 199;
    private static final int RAW_SHARK = 383;
    private static final int BURNT_FISH = 387;

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 2;
        }

        @Override
        public boolean keepTransferAuditRows()
        {
            return true;
        }
    };

    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static final Map<Integer, Integer> PRICES = new HashMap<>();

    static
    {
        name(PPOT4, "Prayer potion(4)", 200);
        name(PPOT3, "Prayer potion(3)", 150);
        name(PPOT2, "Prayer potion(2)", 100);
        name(PPOT1, "Prayer potion(1)", 50);
        name(VIAL, "Vial", 1);
        name(SHARK, "Shark", 800);
        name(BONES, "Bones", 31);
        name(RANARR, "Ranarr weed", 6000);
        name(SNAPE, "Snape grass", 300);
        name(FIRE_RUNE, "Fire rune", 5);
        name(RUNE_ARROW, "Rune arrow", 60);
        name(GRIMY, "Grimy guam leaf", 20);
        name(RAW_SHARK, "Raw shark", 700);
        name(BURNT_FISH, "Burnt shark", 1);
    }

    private static void name(int id, String name, int price)
    {
        NAMES.put(id, name);
        PRICES.put(id, price);
    }

    private final FlowValuator valuator = deltas ->
    {
        List<ItemFlow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int id = entry.getKey();
            int price = PRICES.getOrDefault(id, 100);
            flows.add(new ItemFlow(
                id, NAMES.getOrDefault(id, "Item " + id), entry.getValue(), price, entry.getValue() * price));
        }
        return flows;
    };

    @Test
    public void drinkIntentWithFourToThreeIsDrankOneDoseAndNetUnchanged()
    {
        GpManagerEngine engine = started(inv(PPOT4, 1L));
        engine.noteConsumptionIntent(PPOT4, 6, false, ActionKind.DRINK);
        engine.markInventoryDirty();

        ProfitTransaction drink = settle(engine, inv(PPOT3, 1L), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, drink.getType());
        assertEquals(ActionKind.DRINK, drink.getActionKind());
        assertEquals("Drank · Prayer potion · 1 dose", ActionPresentation.receiptLine(drink));
        assertEquals(-50L, drink.getNet());
        assertQuantities(drink, PPOT4, -1L, PPOT3, 1L);
        SessionMetrics metrics = engine.getMetrics(4_000L);
        assertEquals(200L, metrics.getCosts());
        assertEquals(150L, metrics.getRevenue());
    }

    @Test
    public void lastDoseToEmptyVialIsDrank()
    {
        GpManagerEngine engine = started(inv(PPOT1, 1L));
        engine.noteConsumptionIntent(PPOT1, 6, false, ActionKind.DRINK);
        engine.markInventoryDirty();

        ProfitTransaction drink = settle(engine, inv(VIAL, 1L), 1_600L);

        assertEquals(ActionKind.DRINK, drink.getActionKind());
        assertEquals("Drank · Prayer potion · 1 dose", ActionPresentation.receiptLine(drink));
        assertEquals(-49L, drink.getNet());
        assertQuantities(drink, PPOT1, -1L, VIAL, 1L);
    }

    @Test
    public void autoVialDisposalWithoutLeftoverIsStillDrank()
    {
        GpManagerEngine engine = started(inv(PPOT1, 1L));
        engine.noteConsumptionIntent(PPOT1, 6, false, ActionKind.DRINK);
        engine.markInventoryDirty();

        ProfitTransaction drink = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, drink.getType());
        assertEquals(ActionKind.DRINK, drink.getActionKind());
        assertEquals("Drank · Prayer potion · 1 dose", ActionPresentation.receiptLine(drink));
        assertEquals(-50L, drink.getNet());
        assertQuantities(drink, PPOT1, -1L);
    }

    @Test
    public void twoDrinksInOneWindowReportTwoDoses()
    {
        GpManagerEngine engine = started(inv(PPOT4, 1L));
        engine.noteConsumptionIntent(PPOT4, 6, false, ActionKind.DRINK);
        engine.markInventoryDirty();

        ProfitTransaction drinks = settle(engine, inv(PPOT2, 1L), 1_600L);

        assertEquals(ActionKind.DRINK, drinks.getActionKind());
        assertEquals("Drank · Prayer potion · 2 doses", ActionPresentation.receiptLine(drinks));
        assertEquals(-100L, drinks.getNet());
        assertQuantities(drinks, PPOT4, -1L, PPOT2, 1L);
    }

    @Test
    public void eatAndDrinkSameTickNeverClaimsDrankForTheShark()
    {
        Map<Integer, Long> before = new HashMap<>();
        before.put(PPOT4, 1L);
        before.put(SHARK, 1L);
        GpManagerEngine engine = started(new ContainerSnapshot(before));
        engine.noteConsumptionIntent(PPOT4, 6, false, ActionKind.DRINK);
        engine.markInventoryDirty();

        ProfitTransaction both = settle(engine, inv(PPOT3, 1L), 1_600L);

        assertEquals(TransactionType.CONSUMPTION, both.getType());
        assertEquals(ActionKind.SUPPLIES, both.getActionKind());
        assertTrue(ActionPresentation.receiptLine(both).startsWith("Supplies used"));
        assertEquals(-850L, both.getNet());
        assertQuantities(both, PPOT4, -1L, SHARK, -1L, PPOT3, 1L);
    }

    @Test
    public void eatIntentWithFoodLossIsAte()
    {
        GpManagerEngine engine = started(inv(SHARK, 2L));
        engine.noteConsumptionIntent(SHARK, 6, false, ActionKind.EAT);
        engine.markInventoryDirty();

        ProfitTransaction eat = settle(engine, inv(SHARK, 1L), 1_600L);

        assertEquals(ActionKind.EAT, eat.getActionKind());
        assertEquals("Ate · Shark · 1", ActionPresentation.receiptLine(eat));
        assertEquals(-800L, eat.getNet());
        assertQuantities(eat, SHARK, -1L);
    }

    @Test
    public void decantThreePlusOneToFourIsDecantedNotDrank()
    {
        Map<Integer, Long> before = new HashMap<>();
        before.put(PPOT3, 1L);
        before.put(PPOT1, 1L);
        GpManagerEngine engine = started(new ContainerSnapshot(before));
        // A decant follows a Use click, not a Drink click: no consume intent.
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(PPOT4, 1L);
        after.put(VIAL, 1L);
        ProfitTransaction decant = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertNotNull(decant);
        assertEquals(TransactionType.UNCERTAIN, decant.getType());
        assertEquals(ActionKind.DECANT, decant.getActionKind());
        assertEquals("Decanted · Prayer potion", ActionPresentation.receiptLine(decant));
        // −150 −50 +200 +1: valuation untouched by wording.
        assertEquals(1L, decant.getNet());
        assertQuantities(decant, PPOT3, -1L, PPOT1, -1L, PPOT4, 1L, VIAL, 1L);
    }

    @Test
    public void herbloreProductionIsMixed()
    {
        Map<Integer, Long> before = new HashMap<>();
        before.put(RANARR, 1L);
        before.put(SNAPE, 1L);
        GpManagerEngine engine = started(new ContainerSnapshot(before));
        engine.markContext(com.gpmanager.model.TrackingContext.PRODUCTION, 6, "Herblore");
        engine.setDetectedActivity("Herblore", 1_000L);
        engine.markInventoryDirty();

        ProfitTransaction mix = settle(engine, inv(PPOT3, 1L), 1_600L);

        assertNotNull(mix);
        assertEquals(TransactionType.PROCESSING, mix.getType());
        assertEquals("Herblore", mix.getActivityName());
        assertEquals(ActionKind.MIX, mix.getActionKind());
        assertEquals("Mixed · Prayer potion(3) · 1", ActionPresentation.receiptLine(mix));
        assertEquals(150L - 6_300L, mix.getNet());
    }

    @Test
    public void cookingOutputIsCookedOrBurntFromTheActualResult()
    {
        GpManagerEngine cooked = started(inv(RAW_SHARK, 1L));
        cooked.markContext(com.gpmanager.model.TrackingContext.PRODUCTION, 6, "Cooking");
        cooked.setDetectedActivity("Cooking", 1_000L);
        cooked.markInventoryDirty();
        ProfitTransaction shark = settle(cooked, inv(SHARK, 1L), 1_600L);
        assertNotNull(shark);
        assertEquals(TransactionType.PROCESSING, shark.getType());
        assertEquals("Cooking", shark.getActivityName());
        assertEquals(ActionKind.COOK, shark.getActionKind());
        assertEquals("Cooked · Shark · 1", ActionPresentation.receiptLine(shark));
        assertEquals(800L - 700L, shark.getNet());

        GpManagerEngine burnt = started(inv(RAW_SHARK, 1L));
        burnt.markContext(com.gpmanager.model.TrackingContext.PRODUCTION, 6, "Cooking");
        burnt.setDetectedActivity("Cooking", 1_000L);
        burnt.markInventoryDirty();
        ProfitTransaction ash = settle(burnt, inv(BURNT_FISH, 1L), 1_600L);
        assertNotNull(ash);
        assertEquals(TransactionType.PROCESSING, ash.getType());
        assertEquals("Cooking", ash.getActivityName());
        assertEquals(ActionKind.BURN, ash.getActionKind());
        assertEquals("Burnt · Burnt shark · 1", ActionPresentation.receiptLine(ash));
        assertEquals(1L - 700L, ash.getNet());
    }

    @Test
    public void matchedActionFallsBackToSuppliesForMixedLossFamilies()
    {
        List<ItemFlow> mixedFoodAndRunes = Arrays.asList(
            evidenceFlow("Shark", -1L), evidenceFlow("Fire rune", -5L));
        List<ItemFlow> mixedBonesAndFood = Arrays.asList(
            evidenceFlow("Bones", -1L), evidenceFlow("Shark", -1L));

        assertEquals(ActionKind.SUPPLIES, ActionEvidence.resolve(
            ActionKind.EAT, mixedFoodAndRunes, TransactionType.CONSUMPTION, "General"));
        assertEquals(ActionKind.SUPPLIES, ActionEvidence.resolve(
            ActionKind.BURY, mixedBonesAndFood, TransactionType.CONSUMPTION, "General"));
        assertEquals(ActionKind.SUPPLIES, ActionEvidence.resolve(
            ActionKind.OFFER, mixedBonesAndFood, TransactionType.CONSUMPTION, "General"));
        assertEquals(ActionKind.SUPPLIES, ActionEvidence.resolve(
            ActionKind.SCATTER, mixedBonesAndFood, TransactionType.CONSUMPTION, "General"));
    }

    @Test
    public void unrelatedProductionFlowsDoNotGetCookOrMixVerbs()
    {
        List<ItemFlow> cookingWithUnrelatedGain = Arrays.asList(
            evidenceFlow("Raw shark", -1L),
            evidenceFlow("Shark", 1L),
            evidenceFlow("Bones", 1L));
        List<ItemFlow> herbloreWithUnrelatedGain = Arrays.asList(
            evidenceFlow("Ranarr weed", -1L),
            evidenceFlow("Snape grass", -1L),
            evidenceFlow("Prayer potion(3)", 1L),
            evidenceFlow("Shark", 1L));

        assertNull(ActionEvidence.resolve(
            null, cookingWithUnrelatedGain, TransactionType.PROCESSING, "Cooking"));
        assertNull(ActionEvidence.resolve(
            null, herbloreWithUnrelatedGain, TransactionType.PROCESSING, "Herblore"));
    }

    @Test
    public void unclassifiedDeathRuneAndAmmoLossesHaveNoSpendVerb()
    {
        GpManagerEngine runes = started(inv(FIRE_RUNE, 50L));
        runes.markUnclassifiedLocalDeath();
        runes.markInventoryDirty();

        ProfitTransaction runeLoss = settle(runes, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(runeLoss);
        assertEquals(TransactionType.CONSUMPTION, runeLoss.getType());
        assertTrue(runeLoss.isCounted());
        assertNull(runeLoss.getActionKind());

        GpManagerEngine ammo = started(inv(RUNE_ARROW, 50L));
        ammo.markUnclassifiedLocalDeath();
        ammo.markInventoryDirty();

        ProfitTransaction ammoLoss = settle(ammo, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(ammoLoss);
        assertEquals(TransactionType.CONSUMPTION, ammoLoss.getType());
        assertTrue(ammoLoss.isCounted());
        assertNull(ammoLoss.getActionKind());
    }

    @Test
    public void drinkPickAndBuryCoalescedFallsBackToSuppliesUsed()
    {
        Map<Integer, Long> before = new HashMap<>();
        before.put(PPOT4, 1L);
        before.put(BONES, 1L);
        GpManagerEngine engine = started(new ContainerSnapshot(before));
        // Last click was Bury; the same settle window also drank a dose and picked a herb.
        engine.noteConsumptionIntent(BONES, 6, false, ActionKind.BURY);
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(PPOT3, 1L);
        after.put(GRIMY, 1L);
        ProfitTransaction settled = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertNotNull(settled);
        assertEquals(ActionKind.SUPPLIES, settled.getActionKind());
        assertQuantities(settled, PPOT4, -1L, BONES, -1L, PPOT3, 1L, GRIMY, 1L);
        assertEquals(-200L - 31L + 150L + 20L, settled.getNet());
    }

    @Test
    public void bankDoseSwapCarriesNoActionVerb()
    {
        GpManagerEngine engine = started(inv(PPOT4, 1L));
        engine.markBankInterfaceOpen(6);
        engine.markContext(com.gpmanager.model.TrackingContext.TRANSFER, 6, "Bank container transfer");
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(PPOT3, 1L);
        after.put(PPOT1, 1L);
        ProfitTransaction swap = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertNotNull(swap);
        assertEquals(TransactionType.TRANSFER, swap.getType());
        assertNull(swap.getActionKind());
    }

    @Test
    public void weakBankOpenSignalCannotTurnDoseRedistributionIntoDecantWording()
    {
        Map<Integer, Long> before = new HashMap<>();
        before.put(PPOT3, 1L);
        before.put(PPOT1, 1L);
        GpManagerEngine engine = started(new ContainerSnapshot(before));
        engine.markBankInterfaceOpen(6);
        engine.markInventoryDirty();

        Map<Integer, Long> after = new HashMap<>();
        after.put(PPOT4, 1L);
        after.put(VIAL, 1L);
        ProfitTransaction swap = settle(engine, new ContainerSnapshot(after), 1_600L);

        assertNotNull(swap);
        assertEquals(TransactionType.UNCERTAIN, swap.getType());
        assertNull(swap.getActionKind());
    }

    @Test
    public void tradeDoseRedistributionIsNotDecanted()
    {
        List<ItemFlow> swap = Arrays.asList(
            evidenceFlow("Prayer potion(4)", -1L),
            evidenceFlow("Prayer potion(3)", 1L),
            evidenceFlow("Prayer potion(1)", 1L));

        assertNull(ActionEvidence.resolve(null, swap, TransactionType.TRADE, "Market"));
    }

    @Test
    public void staleDrinkIntentYieldsSuppliesUsedNotDrank()
    {
        GpManagerEngine engine = started(inv(PPOT4, 1L));
        engine.noteConsumptionIntent(PPOT4, 1, false, ActionKind.DRINK);
        // Let the one-tick intent expire on clean ticks before the inventory changes.
        for (int i = 0; i < 6; i++)
        {
            assertNull(engine.processIfDirty(inv(PPOT4, 1L), 1_000L + (i * 600L)));
        }
        engine.markInventoryDirty();

        ProfitTransaction late = settle(engine, inv(PPOT3, 1L), 6_000L);

        assertNotNull(late);
        assertEquals(TransactionType.CONSUMPTION, late.getType());
        assertEquals(ActionKind.SUPPLIES, late.getActionKind());
        assertEquals("Supplies used · Prayer potion · 1 dose", ActionPresentation.receiptLine(late));
        assertEquals(-50L, late.getNet());
    }

    @Test
    public void actionKindSurvivesSerializationForRelog()
    {
        GpManagerEngine engine = started(inv(PPOT4, 1L));
        engine.noteConsumptionIntent(PPOT4, 6, false, ActionKind.DRINK);
        engine.markInventoryDirty();
        ProfitTransaction drink = settle(engine, inv(PPOT3, 1L), 1_600L);
        assertEquals(ActionKind.DRINK, drink.getActionKind());

        Gson gson = new Gson();
        ProfitTransaction reloaded = gson.fromJson(gson.toJson(drink), ProfitTransaction.class);

        assertEquals(ActionKind.DRINK, reloaded.getActionKind());
        assertEquals(drink.getNet(), reloaded.getNet());
        assertEquals(drink.getType(), reloaded.getType());
        // Rows written before B10 have no field and stay generic rather than guessing.
        ProfitTransaction legacy = gson.fromJson(
            "{\"type\":\"CONSUMPTION\",\"counted\":true,\"flows\":[]}", ProfitTransaction.class);
        assertNull(legacy.getActionKind());
    }

    @Test
    public void runeOnlyLossIsRunesUsedWithoutAnyClick()
    {
        // Autocast produces no menu intent; the rune-only shape is the evidence.
        GpManagerEngine engine = started(inv(FIRE_RUNE, 100L));
        engine.markInventoryDirty();

        ProfitTransaction cast = settle(engine, inv(FIRE_RUNE, 95L), 1_600L);

        assertNotNull(cast);
        assertEquals(ActionKind.CAST, cast.getActionKind());
        assertTrue(cast.getActionKind().isRoutineRepetitive());
        assertEquals("Runes used · Fire rune · 5", ActionPresentation.receiptLine(cast));
        assertEquals(-25L, cast.getNet());
        assertQuantities(cast, FIRE_RUNE, -5L);
    }

    @Test
    public void bankDepositOfRunesIsATransferNotCasting()
    {
        GpManagerEngine engine = started(inv(FIRE_RUNE, 1_000L));
        engine.markBankInterfaceOpen(6);
        engine.markContext(com.gpmanager.model.TrackingContext.TRANSFER, 6, "Bank container transfer");
        engine.markInventoryDirty();

        ProfitTransaction deposit = settle(engine, ContainerSnapshot.empty(), 1_600L);

        assertNotNull(deposit);
        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertNull(deposit.getActionKind());
        assertTrue(ActionPresentation.showsOn(deposit, ActionPresentation.Surface.HUD_PLUS_TRAY));
    }

    @Test
    public void ammunitionLossIsFiringButGenericAmmoGainRemainsVisible()
    {
        GpManagerEngine engine = started(inv(RUNE_ARROW, 50L));
        engine.markInventoryDirty();
        ProfitTransaction fired = settle(engine, inv(RUNE_ARROW, 47L), 1_600L);
        assertNotNull(fired);
        assertEquals(ActionKind.FIRE, fired.getActionKind());
        assertEquals(-180L, fired.getNet());

        engine.markInventoryDirty();
        ProfitTransaction recovered = settle(engine, inv(RUNE_ARROW, 49L), 4_000L);
        assertNotNull(recovered);
        assertNull(recovered.getActionKind());
        for (ActionPresentation.Surface surface : ActionPresentation.Surface.values())
        {
            assertTrue("unknown-source ammo gain remains visible on " + surface,
                ActionPresentation.showsOn(recovered, surface));
        }
        assertEquals(120L, recovered.getNet());

        assertEquals(ActionKind.RECOVER_AMMO, ActionEvidence.resolve(
            ActionKind.RECOVER_AMMO,
            Arrays.asList(evidenceFlow("Rune arrow", 2L)),
            TransactionType.GAIN,
            "General"));

        // Accounting is untouched by the surface policy: both rows count toward net.
        SessionMetrics metrics = engine.getMetrics(8_000L);
        assertEquals(-60L, metrics.getNet());
    }

    @Test
    public void sustainedCastingKeepsExactTotalsWhileEveryRowStaysLedgerOnly()
    {
        GpManagerEngine engine = started(inv(FIRE_RUNE, 1_000L));
        long expectedNet = 0L;
        long now = 1_600L;
        for (int i = 1; i <= 20; i++)
        {
            engine.markInventoryDirty();
            ProfitTransaction cast = settle(engine, inv(FIRE_RUNE, 1_000L - (5L * i)), now);
            assertNotNull(cast);
            assertEquals(ActionKind.CAST, cast.getActionKind());
            for (ActionPresentation.Surface surface : ActionPresentation.Surface.values())
            {
                assertEquals(
                    "surface " + surface,
                    surface == ActionPresentation.Surface.LEDGER,
                    ActionPresentation.showsOn(cast, surface));
            }
            expectedNet += cast.getNet();
            now += 2_400L;
        }
        assertEquals(-500L, expectedNet);
        assertEquals(expectedNet, engine.getMetrics(now).getNet());
        assertEquals(20, engine.getActiveSession().getTransactions().size());
    }

    private GpManagerEngine started(ContainerSnapshot baseline)
    {
        GpManagerEngine engine = new GpManagerEngine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(baseline);
        return engine;
    }

    private static ProfitTransaction settle(GpManagerEngine engine, ContainerSnapshot snapshot, long firstTick)
    {
        assertNull(engine.processIfDirty(snapshot, firstTick));
        assertNull(engine.processIfDirty(snapshot, firstTick + 600L));
        return engine.processIfDirty(snapshot, firstTick + 1_200L);
    }

    private static ContainerSnapshot inv(int itemId, long quantity)
    {
        Map<Integer, Long> values = new HashMap<>();
        if (quantity > 0L)
        {
            values.put(itemId, quantity);
        }
        return new ContainerSnapshot(values);
    }

    private static ItemFlow evidenceFlow(String itemName, long quantityDelta)
    {
        return new ItemFlow(0, itemName, quantityDelta, 1, quantityDelta);
    }

    /** Asserts exact (itemId, delta) pairs — wording must never move a quantity. */
    private static void assertQuantities(ProfitTransaction transaction, long... idDeltaPairs)
    {
        Map<Integer, Long> expected = new HashMap<>();
        for (int i = 0; i < idDeltaPairs.length; i += 2)
        {
            expected.put((int) idDeltaPairs[i], idDeltaPairs[i + 1]);
        }
        Map<Integer, Long> actual = new HashMap<>();
        for (ItemFlow flow : transaction.getFlows())
        {
            actual.merge(flow.getItemId(), flow.getQuantityDelta(), Long::sum);
        }
        assertEquals(expected, actual);
    }
}
