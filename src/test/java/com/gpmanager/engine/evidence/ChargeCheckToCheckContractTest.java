package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Hub v1 charge contract for every supported family: Check binds a target (never a quantity),
 * the first numeric read seeds, only a later same-target read books the measured delta as a
 * counted Supplies cost, and everything uncertain fails closed.
 */
public class ChargeCheckToCheckContractTest
{
    private static final String WEAPON_A = "149:5:0:22323:X";
    private static final String WEAPON_B = "149:6:0:22323:X";

    @Test
    public void blowpipeChecksBookOnlyTheMeasuredSameTargetDecrease()
    {
        Engine engine = engine();
        assertNull("first read seeds, never books",
            observe(engine, "Darts: Adamant dart x 1000. Scales: 100 (1.0%).", WEAPON_A, 1_000L));
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());

        ChargeDelta delta = observe(engine, "Darts: Adamant dart x 997. Scales: 96 (1.0%).", WEAPON_A, 2_000L);
        assertNotNull(delta);
        Transaction spend = book(engine, delta, "Toxic blowpipe", 2_000L);
        assertEquals(TransactionType.CONSUMPTION, spend.getType());
        assertTrue(spend.isCounted());
        assertSupplies(spend);
        assertEquals(-4L * 2L - 3L * 100L, spend.getNet());
    }

    @Test
    public void tridentFamiliesMeasureTheirRecipesIncludingSeasEnhanced()
    {
        for (String[] trident : new String[][] {
            {"Trident of the seas", "seas"}, {"Trident of the swamp", "swamp"}, {"Trident of the swamp (e)", "swamp (e)"}})
        {
            Engine engine = engine();
            assertNull(observe(engine, "Your " + trident[0] + " has 2,000 charges.", WEAPON_A, 1_000L));
            ChargeDelta delta = observe(engine, "Your " + trident[0] + " has 1,990 charges.", WEAPON_A, 2_000L);
            assertNotNull(trident[1], delta);
            Transaction spend = book(engine, delta, trident[0], 2_000L);
            assertEquals(trident[1], TransactionType.CONSUMPTION, spend.getType());
            assertTrue(spend.getNet() < 0L);
            assertSupplies(spend);
        }
        ChargeRead seasE = ChargeRead.parseCheckMessage("Your Trident of the seas (e) has 2,000 charges.");
        assertNotNull(seasE);
        assertTrue(seasE.bookable);
        Engine engine = engine();
        assertNull(engine.observeMeasuredChargeRead(seasE, WEAPON_A, 1_000L));
        ChargeDelta delta = engine.observeMeasuredChargeRead(
            ChargeRead.parseCheckMessage("Your Trident of the seas (e) has 1,990 charges."), WEAPON_A, 2_000L);
        assertNotNull(delta);
        assertEquals(-10L, quantityDelta(delta, 560));
        assertEquals(-10L, quantityDelta(delta, 562));
        assertEquals(-50L, quantityDelta(delta, 554));
        assertEquals(-100L, quantityDelta(delta, 995));
    }


    @Test
    public void aDifferentTargetSeedsInsteadOfComparing()
    {
        Engine engine = engine();
        assertNull(observe(engine, "Your Trident of the swamp has 2,000 charges.", WEAPON_A, 1_000L));
        assertNull("a second same-variant weapon is a fresh baseline, not consumption",
            observe(engine, "Your Trident of the swamp has 1,500 charges.", WEAPON_B, 2_000L));
        assertNull("the Check click binds identity, never a quantity: no target, no comparison",
            engine.observeMeasuredChargeRead(
                ChargeRead.parseCheckMessage("Your Trident of the swamp has 1,400 charges."), null, 3_000L));
    }

    @Test
    public void restartUnloadAndLoadIncreasesNeverBookConsumption()
    {
        Engine engine = engine();
        assertNull(observe(engine, "Your Trident of the seas has 2,000 charges.", WEAPON_A, 1_000L));
        engine.resetMeasuredChargeReads();
        assertNull("restart/unload/uncharge clears the transient baseline; the next read seeds",
            observe(engine, "Your Trident of the seas has 1,000 charges.", WEAPON_A, 2_000L));
        ChargeDelta increase = observe(engine, "Your Trident of the seas has 1,500 charges.", WEAPON_A, 3_000L);
        assertTrue("a higher read is a load, not a negative spend",
            increase == null || increase.getComponentDeltas().isEmpty());
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
    }

    @Test
    public void missingPriceFailsClosedForTheWholeRead()
    {
        Engine engine = engine();
        assertNull(observe(engine, "Your Trident of the swamp has 100 charges.", WEAPON_A, 1_000L));
        ChargeDelta delta = observe(engine, "Your Trident of the swamp has 90 charges.", WEAPON_A, 2_000L);
        assertNotNull(delta);
        List<Flow> partial = new ArrayList<>();
        for (ChargeDelta.ComponentDelta component : delta.getComponentDeltas())
        {
            if (component.getItemId() == 560)
            {
                partial.add(new Flow(560, "Death rune", component.getQuantityDelta(), 200,
                    component.getQuantityDelta() * 200L, PriceSource.GRAND_EXCHANGE));
            }
        }
        Transaction result = engine.bookChargeSpend(delta, "Trident of the swamp", partial, 2_000L);
        assertFalse("one unpriced component rejects the complete delta", result != null);
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
    }

    @Test
    public void runeFedStaffChecksMeasureTheirRecipes()
    {
        Engine engine = engine();
        assertNull(observe(engine, "Your Tumeken's shadow has 2,000 charges remaining.", "149:5:0:27275:S", 1_000L));
        ChargeDelta shadow = observe(engine,
            "Your Tumeken's shadow has 1,990 charges remaining.", "149:5:0:27275:S", 2_000L);
        assertNotNull(shadow);
        assertEquals(-20L, quantityDelta(shadow, ItemID.SOULRUNE));
        assertEquals(-50L, quantityDelta(shadow, ItemID.CHAOSRUNE));
        book(engine, shadow, "Tumeken's shadow", 2_000L);

        assertNull(observe(engine, "Your Sanguinesti staff has 100 charges remaining.", "149:5:0:22323:G", 1_000L));
        ChargeDelta sang = observe(engine,
            "Your Sanguinesti staff has 98 charges remaining.", "149:5:0:22323:G", 2_000L);
        assertNotNull(sang);
        assertEquals(-4L, quantityDelta(sang, ItemID.BLOODRUNE));
        book(engine, sang, "Sanguinesti staff", 2_000L);

        assertNull(observe(engine, "Your Holy sanguinesti staff has 10 charges remaining.", "149:5:0:26084:H", 1_000L));
        ChargeDelta holy = observe(engine,
            "Your Holy sanguinesti staff has 9 charges left.", "149:5:0:26084:H", 2_000L);
        assertNotNull(holy);
        assertEquals(-2L, quantityDelta(holy, ItemID.BLOODRUNE));

        assertNull(observe(engine, "Your warped sceptre only has 50 charges remaining.", "149:5:0:27634:W", 1_000L));
        ChargeDelta warped = observe(engine,
            "Your warped sceptre has 40 charges remaining.", "149:5:0:27634:W", 2_000L);
        assertNotNull(warped);
        assertEquals(-20L, quantityDelta(warped, ItemID.CHAOSRUNE));
        assertEquals(-50L, quantityDelta(warped, ItemID.EARTHRUNE));

        assertNull(observe(engine, "Your venator bow has 500 charges remaining.", "149:5:0:27612:V", 1_000L));
        ChargeDelta venator = observe(engine,
            "Your venator bow has 480 charges remaining.", "149:5:0:27612:V", 2_000L);
        assertNotNull(venator);
        assertEquals(-20L, quantityDelta(venator, ItemID.ANCIENT_ESSENCE));

        assertNull(observe(engine, "Your echo venator bow has 4,510 charges remaining.", "149:5:0:28962:E", 1_000L));
        ChargeDelta echo = observe(engine,
            "Your echo venator bow has 4,506 charges remaining.", "149:5:0:28962:E", 2_000L);
        assertNotNull(echo);
        assertEquals(-4L, quantityDelta(echo, ItemID.ANCIENT_ESSENCE));
    }

    @Test
    public void pendantOfAtesChecksMeasureOneTearPerCharge()
    {
        Engine engine = engine();
        assertNull(observe(engine, "The pendant has 250 charges.", "149:5:0:30786:P", 1_000L));
        ChargeDelta delta = observe(engine, "The pendant has 240 charges.", "149:5:0:30786:P", 2_000L);
        assertNotNull(delta);
        assertEquals(-10L, quantityDelta(delta, ItemID.FROZEN_TEAR));
        book(engine, delta, "Pendant of Ates", 2_000L);

        ChargeRead one = ChargeRead.parseCheckMessage("The pendant has 1 charge.");
        assertNotNull(one);
        assertEquals(1L, (long) one.componentCounts.get(ItemID.FROZEN_TEAR));
        ChargeRead none = ChargeRead.parseCheckMessage("The pendant has no charges.");
        assertNotNull(none);
        assertEquals(0L, (long) none.componentCounts.get(ItemID.FROZEN_TEAR));
    }

    @Test
    public void tomeAndCrystalChecksMeasurePagesAndShards()
    {
        Engine engine = engine();
        assertNull(observe(engine, "Your tome has been charged with Burnt Pages. It currently holds 1,000 charges.",
            "149:5:0:3006:T", 1_000L));
        ChargeDelta tome = observe(engine,
            "Your tome has been charged with Burnt Pages. It currently holds 960 charges.", "149:5:0:3006:T", 2_000L);
        assertNotNull(tome);
        assertEquals(-40L, quantityDelta(tome, ItemID.WINT_BURNT_PAGE));
        assertEquals(20, ChargeRead.unitsPerPricedItem(ChargeRead.Variant.V3, ItemID.WINT_BURNT_PAGE));

        assertNull(observe(engine, "Your crystal bow has 2,500 charges remaining.", "149:5:0:4212:C", 1_000L));
        ChargeDelta bow = observe(engine,
            "Your crystal bow has 2,400 charges remaining.", "149:5:0:4212:C", 2_000L);
        assertNotNull(bow);
        assertEquals(-100L, quantityDelta(bow, ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(100, ChargeRead.unitsPerPricedItem(ChargeRead.Variant.CRYSTAL_BOW, ItemID.PRIF_CRYSTAL_SHARD));

        assertNull(observe(engine, "Your bow of Faerdhinen has 1,000 charges remaining.", "149:5:0:25865:B", 1_000L));
        ChargeDelta bowfa = observe(engine,
            "Your bow of Faerdhinen has 950 charges remaining.", "149:5:0:25865:B", 2_000L);
        assertNotNull(bowfa);
        assertEquals(-50L, quantityDelta(bowfa, ItemID.PRIF_CRYSTAL_SHARD));
    }

    @Test
    public void atesChecksMeasureTearsOnePerCharge()
    {
        Engine engine = engine();
        assertNull(observe(engine, "The pendant has 10 charges.", "149:5:0:29893:A", 1_000L));
        ChargeDelta delta = observe(engine, "The pendant has 7 charges.", "149:5:0:29893:A", 2_000L);
        assertNotNull(delta);
        assertEquals(-3L, quantityDelta(delta, ItemID.FROZEN_TEAR));
    }

    @Test
    public void eyeChecksMeasureDemonTearsOnePerCharge()
    {
        Engine engine = engine();
        String target = "149:0:0:" + ItemID.EYE_OF_AYAK + ":EYE_OF_AYAK";
        assertNull(observe(engine,
            "The Eye of Ayak has been charged with demon tears. It currently has 1,996 charges.",
            target, 1_000L));
        ChargeDelta delta = observe(engine,
            "The Eye of Ayak has been charged with demon tears. It currently has 1,990 charges.",
            target, 2_000L);
        assertNotNull(delta);
        assertEquals("one tear is one charge", -6L, quantityDelta(delta, ItemID.DEMON_TEAR));
        assertEquals(1, ChargeRead.unitsPerPricedItem(ChargeRead.Variant.EYE_OF_AYAK, ItemID.DEMON_TEAR));
    }

    @Test
    public void eyeRuneChecksMeasureDeathAndChaosRunes()
    {
        Engine engine = engine();
        String target = "149:3:0:" + ItemID.EYE_OF_AYAK + ":EYE_OF_AYAK";
        assertNull(observe(engine,
            "The Eye of Ayak has been charged with runes. It currently has 100 charges.", target, 1_000L));
        ChargeDelta delta = observe(engine,
            "The Eye of Ayak has been charged with runes. It currently has 98 charges.", target, 2_000L);
        assertNotNull(delta);
        assertEquals("two charges' worth of runes", -4L, quantityDelta(delta, ItemID.DEATHRUNE));
        assertEquals(-2L, quantityDelta(delta, ItemID.CHAOSRUNE));
    }

    @Test
    public void bloodFuryHitsAreMeasuredByItsLineCount()
    {
        Engine engine = engine();
        String target = "149:2:0:" + ItemID.BLOOD_AMULET + ":BLOOD_FURY";
        assertNull(observe(engine, "Your Amulet of blood fury will work for 6,627 more hits.",
            target, 1_000L));
        ChargeDelta delta = observe(engine, "Your Amulet of blood fury will work for 6,620 more hits.",
            target, 2_000L);
        assertNotNull(delta);
        assertEquals("seven hits, healed or not, are seven charges", -7L,
            quantityDelta(delta, ItemID.BLOOD_SHARD));
        assertEquals(10_000, ChargeRead.unitsPerPricedItem(ChargeRead.Variant.BLOOD_FURY, ItemID.BLOOD_SHARD));
    }

    @Test
    public void everyImplementedFamilyIsMeasured()
    {
        List<String> implemented = new ArrayList<>();
        for (ChargeRead.Variant variant : ChargeRead.Variant.values())
        {
            if (variant.isImplemented())
            {
                implemented.add(variant.getDisplayName());
            }
        }
        Collections.sort(implemented);
        assertEquals(java.util.Arrays.asList("Amulet of blood fury", "Bow of Faerdhinen",
            "Crystal bow", "Crystal halberd", "Echo venator bow", "Eye of Ayak", "Holy sanguinesti staff",
            "Pendant of Ates", "Sanguinesti staff", "Tome of fire", "Toxic blowpipe",
            "Trident of the Seas", "Trident of the Seas (e)", "Trident of the Swamp",
            "Trident of the Swamp (e)", "Tumeken's shadow", "Venator bow", "Warped sceptre"),
            implemented);
    }

    private static ChargeDelta observe(Engine engine, String chat, String target, long now)
    {
        ChargeRead read = ChargeRead.parseCheckMessage(chat);
        assertNotNull(chat, read);
        ChargeDelta delta = engine.observeMeasuredChargeRead(read, target, now);
        return delta == null || delta.getComponentDeltas().isEmpty() ? null : delta;
    }

    /** Prices every measured component the way the intake does (coins at face value). */
    private static Transaction book(Engine engine, ChargeDelta delta, String label, long now)
    {
        List<Flow> losses = new ArrayList<>();
        for (ChargeDelta.ComponentDelta component : delta.getComponentDeltas())
        {
            int id = component.getItemId();
            int unitPrice = id == 995 ? 1 : id == 12934 ? 2 : 100;
            losses.add(new Flow(id, "Item " + id, component.getQuantityDelta(), unitPrice,
                component.getQuantityDelta() * unitPrice,
                id == 995 ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE, now));
        }
        Transaction result = engine.bookChargeSpend(delta, label, losses, now);
        assertTrue(label, result != null);
        return result;
    }

    private static Engine engine()
    {
        Engine engine = new Engine(
            deltas -> Collections.<Flow>emptyList(), new TransactionClassifier(), new GpManagerConfig() { });
        engine.ensureSession(500L);
        return engine;
    }

    private static void assertSupplies(Transaction spend)
    {
        for (Flow flow : spend.getFlows())
        {
            assertEquals("charge spend is Supplies, never Loss", CostKind.SUPPLIES, CostKind.of(spend, flow));
        }
    }

    private static long quantityDelta(ChargeDelta delta, int itemId)
    {
        for (ChargeDelta.ComponentDelta component : delta.getComponentDeltas())
        {
            if (component.getItemId() == itemId)
            {
                return component.getQuantityDelta();
            }
        }
        throw new AssertionError("Missing component delta for item " + itemId);
    }
}
