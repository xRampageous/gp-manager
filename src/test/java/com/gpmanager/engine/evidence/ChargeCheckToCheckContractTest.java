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
        ChargeOutcomeTest.none("first read seeds, never books",
            observe(engine, "Darts: Adamant dart x 1000. Scales: 100 (1.0%).", WEAPON_A, 1_000L));
        assertTrue(engine.session().transactions().isEmpty());

        ChargeDelta delta = observe(engine, "Darts: Adamant dart x 997. Scales: 96 (1.0%).", WEAPON_A, 2_000L);
        assertNotNull(delta);
        Transaction spend = book(engine, delta, "Toxic blowpipe", 2_000L);
        assertEquals(TransactionType.CONSUMPTION, spend.type());
        assertTrue(spend.counts());
        assertSupplies(spend);
        assertEquals(-4L * 2L - 3L * 100L, spend.net());
    }

    @Test
    public void tridentFamiliesMeasureTheirRecipesIncludingSeasEnhanced()
    {
        for (String[] trident : new String[][] {
            {"Trident of the seas", "seas"}, {"Trident of the swamp", "swamp"}, {"Trident of the swamp (e)", "swamp (e)"}})
        {
            Engine engine = engine();
            ChargeOutcomeTest.none(observe(engine, "Your " + trident[0] + " has 2,000 charges.", WEAPON_A, 1_000L));
            ChargeDelta delta = observe(engine, "Your " + trident[0] + " has 1,990 charges.", WEAPON_A, 2_000L);
            assertNotNull(trident[1], delta);
            Transaction spend = book(engine, delta, trident[0], 2_000L);
            assertEquals(trident[1], TransactionType.CONSUMPTION, spend.type());
            assertTrue(spend.net() < 0L);
            assertSupplies(spend);
        }
        ChargeCheck seasE = ChargeCheck.parse("Your Trident of the seas (e) has 2,000 charges.");
        assertNotNull(seasE);
        assertTrue(seasE.supported);
        Engine engine = engine();
        ChargeOutcomeTest.none(engine.observeCheck(seasE, WEAPON_A, 1_000L));
        ChargeDelta delta = engine.observeCheck(
            ChargeCheck.parse("Your Trident of the seas (e) has 1,990 charges."), WEAPON_A, 2_000L);
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
        ChargeOutcomeTest.none(observe(engine, "Your Trident of the swamp has 2,000 charges.", WEAPON_A, 1_000L));
        ChargeOutcomeTest.none("a second same-variant weapon is a fresh baseline, not consumption",
            observe(engine, "Your Trident of the swamp has 1,500 charges.", WEAPON_B, 2_000L));
        ChargeOutcomeTest.none("the Check click binds identity, never a quantity: no target, no comparison",
            engine.observeCheck(
                ChargeCheck.parse("Your Trident of the swamp has 1,400 charges."), null, 3_000L));
    }

    @Test
    public void restartUnloadAndLoadIncreasesNeverBookConsumption()
    {
        Engine engine = engine();
        ChargeOutcomeTest.none(observe(engine, "Your Trident of the seas has 2,000 charges.", WEAPON_A, 1_000L));
        engine.resetChecks(null);
        ChargeOutcomeTest.none("restart/unload/uncharge clears the transient baseline; the next read seeds",
            observe(engine, "Your Trident of the seas has 1,000 charges.", WEAPON_A, 2_000L));
        ChargeDelta increase = observe(engine, "Your Trident of the seas has 1,500 charges.", WEAPON_A, 3_000L);
        assertEquals(ChargeDelta.CheckResult.LOAD, increase.result);
        assertTrue("a higher read has no negative spend", increase.getComponentDeltas().isEmpty());
        assertTrue(engine.session().transactions().isEmpty());
    }

    @Test
    public void missingPriceFailsClosedForTheWholeRead()
    {
        Engine engine = engine();
        ChargeOutcomeTest.none(observe(engine, "Your Trident of the swamp has 100 charges.", WEAPON_A, 1_000L));
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
        Transaction result = engine.bookChargeSpend(delta, "Trident of the swamp", partial, 2_000L, null);
        assertFalse("one unpriced component rejects the complete delta", result != null);
        assertTrue(engine.session().transactions().isEmpty());
    }

    @Test
    public void runeFedStaffChecksMeasureTheirRecipes()
    {
        Engine engine = engine();
        ChargeOutcomeTest.none(observe(engine, "Your Tumeken's shadow has 2,000 charges remaining.", "149:5:0:27275:S", 1_000L));
        ChargeDelta shadow = observe(engine,
            "Your Tumeken's shadow has 1,990 charges remaining.", "149:5:0:27275:S", 2_000L);
        assertNotNull(shadow);
        assertEquals(-20L, quantityDelta(shadow, ItemID.SOULRUNE));
        assertEquals(-50L, quantityDelta(shadow, ItemID.CHAOSRUNE));
        book(engine, shadow, "Tumeken's shadow", 2_000L);

        ChargeOutcomeTest.none(observe(engine, "Your Sanguinesti staff has 100 charges remaining.", "149:5:0:22323:G", 1_000L));
        ChargeDelta sang = observe(engine,
            "Your Sanguinesti staff has 98 charges remaining.", "149:5:0:22323:G", 2_000L);
        assertNotNull(sang);
        assertEquals(-4L, quantityDelta(sang, ItemID.BLOODRUNE));
        book(engine, sang, "Sanguinesti staff", 2_000L);

        ChargeOutcomeTest.none(observe(engine, "Your Holy sanguinesti staff has 10 charges remaining.", "149:5:0:26084:H", 1_000L));
        ChargeDelta holy = observe(engine,
            "Your Holy sanguinesti staff has 9 charges left.", "149:5:0:26084:H", 2_000L);
        assertNotNull(holy);
        assertEquals(-2L, quantityDelta(holy, ItemID.BLOODRUNE));

        ChargeOutcomeTest.none(observe(engine, "Your warped sceptre only has 50 charges remaining.", "149:5:0:27634:W", 1_000L));
        ChargeDelta warped = observe(engine,
            "Your warped sceptre has 40 charges remaining.", "149:5:0:27634:W", 2_000L);
        assertNotNull(warped);
        assertEquals(-20L, quantityDelta(warped, ItemID.CHAOSRUNE));
        assertEquals(-50L, quantityDelta(warped, ItemID.EARTHRUNE));

        ChargeOutcomeTest.none(observe(engine, "Your venator bow has 500 charges remaining.", "149:5:0:27612:V", 1_000L));
        ChargeDelta venator = observe(engine,
            "Your venator bow has 480 charges remaining.", "149:5:0:27612:V", 2_000L);
        assertNotNull(venator);
        assertEquals(-20L, quantityDelta(venator, ItemID.ANCIENT_ESSENCE));

        ChargeOutcomeTest.none(observe(engine, "Your echo venator bow has 4,510 charges remaining.", "149:5:0:28962:E", 1_000L));
        ChargeDelta echo = observe(engine,
            "Your echo venator bow has 4,506 charges remaining.", "149:5:0:28962:E", 2_000L);
        assertNotNull(echo);
        assertEquals(-4L, quantityDelta(echo, ItemID.ANCIENT_ESSENCE));
    }

    @Test
    public void pendantOfAtesChecksMeasureOneTearPerCharge()
    {
        Engine engine = engine();
        ChargeOutcomeTest.none(observe(engine, "The pendant has 250 charges.", "149:5:0:30786:P", 1_000L));
        ChargeDelta delta = observe(engine, "The pendant has 240 charges.", "149:5:0:30786:P", 2_000L);
        assertNotNull(delta);
        assertEquals(-10L, quantityDelta(delta, ItemID.FROZEN_TEAR));
        book(engine, delta, "Pendant of Ates", 2_000L);

        ChargeCheck one = ChargeCheck.parse("The pendant has 1 charge.");
        assertNotNull(one);
        assertEquals(1L, (long) one.counts.get(ItemID.FROZEN_TEAR));
        ChargeCheck none = ChargeCheck.parse("The pendant has no charges.");
        assertNotNull(none);
        assertEquals(0L, (long) none.counts.get(ItemID.FROZEN_TEAR));
    }

    @Test
    public void tomeAndCrystalChecksMeasurePagesAndShards()
    {
        Engine engine = engine();
        ChargeOutcomeTest.none(observe(engine, "Your tome has been charged with Burnt Pages. It currently holds 1,000 charges.",
            "149:5:0:3006:T", 1_000L));
        ChargeDelta tome = observe(engine,
            "Your tome has been charged with Burnt Pages. It currently holds 960 charges.", "149:5:0:3006:T", 2_000L);
        assertNotNull(tome);
        assertEquals(-40L, quantityDelta(tome, ItemID.WINT_BURNT_PAGE));
        assertEquals(20, ChargeCheck.ChargedItem.TOME_OF_FIRE.units);

        ChargeOutcomeTest.none(observe(engine, "Your crystal bow has 2,500 charges remaining.", "149:5:0:4212:C", 1_000L));
        ChargeDelta bow = observe(engine,
            "Your crystal bow has 2,400 charges remaining.", "149:5:0:4212:C", 2_000L);
        assertNotNull(bow);
        assertEquals(-100L, quantityDelta(bow, ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(100, ChargeCheck.ChargedItem.CRYSTAL_BOW.units);

        ChargeOutcomeTest.none(observe(engine, "Your bow of Faerdhinen has 1,000 charges remaining.", "149:5:0:25865:B", 1_000L));
        ChargeDelta bowfa = observe(engine,
            "Your bow of Faerdhinen has 950 charges remaining.", "149:5:0:25865:B", 2_000L);
        assertNotNull(bowfa);
        assertEquals(-50L, quantityDelta(bowfa, ItemID.PRIF_CRYSTAL_SHARD));
    }

    @Test
    public void scytheChecksMeasureBloodRunes()
    {
        Engine engine = engine();
        String target = "149:0:0:" + ItemID.SCYTHE_OF_VITUR + ":SCYTHE";
        ChargeOutcomeTest.none(observe(engine, "Your Scythe of Vitur has 1,322 charges remaining.", target, 1_000L));
        ChargeDelta delta = observe(engine, "Your Scythe of Vitur has 1,315 charges remaining.", target, 2_000L);
        assertNotNull(delta);
        assertEquals(-14L, quantityDelta(delta, ItemID.BLOODRUNE));
    }

    @Test
    public void saeldorChecksMeasureShardsLikeItsCrystalFamily()
    {
        Engine engine = engine();
        String target = "149:5:0:" + ItemID.BLADE_OF_SAELDOR + ":S";
        ChargeOutcomeTest.none(observe(engine,
            "Your Blade of Saeldor has 1,000 charges remaining.", target, 1_000L));
        ChargeDelta delta = observe(engine,
            "Your Blade of Saeldor has 950 charges remaining.", target, 2_000L);
        assertNotNull(delta);
        assertEquals(-50L, quantityDelta(delta, ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(100, ChargeCheck.ChargedItem.SAELDOR.units);
    }

    @Test
    public void scytheAndSaeldorCheckGrammarsCoverObservedForms()
    {
        assertRead("Your Scythe of Vitur has 1,322 charges remaining.", ChargeCheck.ChargedItem.SCYTHE);
        assertRead("Your Holy Scythe of Vitur has 900 charges left.", ChargeCheck.ChargedItem.SCYTHE);
        assertRead("Your Blade of Saeldor has 10,000 charges remaining.", ChargeCheck.ChargedItem.SAELDOR);
        assertRead("<col=ff0000>Your Blade of Saeldor has 100 charges remaining, and 3% progress towards corruption.</col>",
            ChargeCheck.ChargedItem.SAELDOR);
    }

    @Test
    public void crystalArmourAndShieldChecksHaveNothingToCompare()
    {
        // A committed load books its shards on arrival; the piece's Check cannot measure anything
        // (owner 2026-10-12).
        String[][] pieces = {
            {"Crystal helm", "149:5:0:23971:CH", "CRYSTAL_HELM"},
            {"Crystal body", "149:5:0:23975:CB", "CRYSTAL_BODY"},
            {"Crystal legs", "149:5:0:23979:CL", "CRYSTAL_LEGS"},
            {"Crystal shield", "149:5:0:23991:CS", "CRYSTAL_SHIELD"}};
        for (String[] piece : pieces)
        {
            Engine engine = engine();
            String lower = piece[0].toLowerCase(java.util.Locale.ROOT);
            ChargeCheck read = ChargeCheck.parse("Your " + lower + " has 1,000 charges remaining.");
            assertNotNull(piece[0], read);
            assertEquals(piece[0], ChargeCheck.ChargedItem.valueOf(piece[2]), read.variant);
            assertFalse(piece[0], read.supported);
            ChargeOutcomeTest.none(engine.observeCheck(read, piece[1], 1_000L));
            ChargeOutcomeTest.none(engine.observeCheck(
                ChargeCheck.parse("Your " + lower + " has 950 charges remaining."), piece[1], 2_000L));
            assertTrue(piece[0], engine.session().transactions().isEmpty());
        }
    }

    private static void assertRead(String chat, ChargeCheck.ChargedItem expected)
    {
        ChargeCheck read = ChargeCheck.parse(chat);
        assertNotNull(chat, read);
        assertEquals(chat, expected, read.variant);
        assertTrue(chat, read.supported);
    }

    @Test
    public void atesChecksMeasureTearsOnePerCharge()
    {
        Engine engine = engine();
        ChargeOutcomeTest.none(observe(engine, "The pendant has 10 charges.", "149:5:0:29893:A", 1_000L));
        ChargeDelta delta = observe(engine, "The pendant has 7 charges.", "149:5:0:29893:A", 2_000L);
        assertNotNull(delta);
        assertEquals(-3L, quantityDelta(delta, ItemID.FROZEN_TEAR));
    }

    @Test
    public void eyeChecksMeasureDemonTearsOnePerCharge()
    {
        Engine engine = engine();
        String target = "149:0:0:" + ItemID.EYE_OF_AYAK + ":EYE_OF_AYAK";
        ChargeOutcomeTest.none(observe(engine,
            "The Eye of Ayak has been charged with demon tears. It currently has 1,996 charges.",
            target, 1_000L));
        ChargeDelta delta = observe(engine,
            "The Eye of Ayak has been charged with demon tears. It currently has 1,990 charges.",
            target, 2_000L);
        assertNotNull(delta);
        assertEquals("one tear is one charge", -6L, quantityDelta(delta, ItemID.DEMON_TEAR));
        assertEquals(1, ChargeCheck.ChargedItem.EYE_OF_AYAK.units);
    }

    @Test
    public void eyeRuneChecksMeasureDeathAndChaosRunes()
    {
        Engine engine = engine();
        String target = "149:3:0:" + ItemID.EYE_OF_AYAK + ":EYE_OF_AYAK";
        ChargeOutcomeTest.none(observe(engine,
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
        ChargeOutcomeTest.none(observe(engine, "Your Amulet of blood fury will work for 6,627 more hits.",
            target, 1_000L));
        ChargeDelta delta = observe(engine, "Your Amulet of blood fury will work for 6,620 more hits.",
            target, 2_000L);
        assertNotNull(delta);
        assertEquals("seven hits, healed or not, are seven charges", -7L,
            quantityDelta(delta, ItemID.BLOOD_SHARD));
        assertEquals(10_000, ChargeCheck.ChargedItem.BLOOD_FURY.units);
    }

    private static ChargeDelta observe(Engine engine, String chat, String target, long now)
    {
        ChargeCheck read = ChargeCheck.parse(chat);
        assertNotNull(chat, read);
        ChargeDelta delta = engine.observeCheck(read, target, now);
        return delta;
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
        Transaction result = engine.bookChargeSpend(delta, label, losses, now, null);
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
        for (Flow flow : spend.flows())
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
