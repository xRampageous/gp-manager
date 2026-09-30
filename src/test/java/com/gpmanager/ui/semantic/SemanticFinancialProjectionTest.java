package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * PRE-R5C.2A semantic projection laws: living exact/generic Cast groups, measured-charge resource
 * truth, the mixed-action guard and unpriced-vs-Review routing.
 */
public class SemanticFinancialProjectionTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final int DEATH = 560;
    private static final int CHAOS = 562;
    private static final int WATER = 555;
    private static final int BLOOD = 565;
    private static final int NATURE = 561;

    @Test
    public void repeatedExactSpellUpdatesOneLivingGroupAndBubbles()
    {
        Ac first = exactCast(T0 + 1_000L, "Ice Barrage", 1, 1, 1);
        Ac second = exactCast(T0 + 2_000L, "Ice Barrage", 1, 1, 1);

        Br.Result result = capture(first, second);
        assertEquals("repeated compatible casts are one living group", 1, result.groups.size());
        Br.Group group = result.groups.get(0);
        assertEquals("Ice Barrage", group.primaryName);
        assertEquals(-360L, group.value);
        assertEquals(2, group.receiptCount);
        assertEquals(T0 + 2_000L, group.latestActivityAt);
        assertTrue(group.actionGroup());
        assertEquals("3 rune types", group.qe);
        assertEquals(1, Br.summarize(result.groups).entries);
        assertEquals(2, Br.summarize(result.groups).receipts);
        assertEquals(-360L, Br.summarize(result.groups).costs);

        Ac third = exactCast(T0 + 3_000L, "Blood Barrage", 1, 1, 1);
        Br.Result bubbled = capture(first, second, third);
        assertEquals("a newer compatible-free receipt bubbles to the top", 2, bubbled.groups.size());
        assertEquals("Blood Barrage", bubbled.groups.get(0).primaryName);
        assertEquals("Ice Barrage", bubbled.groups.get(1).primaryName);
    }

    @Test
    public void differentExactNamesNeverMergeDespiteSharedResources()
    {
        Br.Result result = capture(
            exactCast(T0 + 1_000L, "Ice Barrage", 1, 1, 1),
            exactCast(T0 + 2_000L, "Blood Barrage", 1, 1, 1));

        assertEquals(2, result.groups.size());
        assertTrue(result.groups.stream().anyMatch(group -> "Ice Barrage".equals(group.primaryName)));
        assertTrue(result.groups.stream().anyMatch(group -> "Blood Barrage".equals(group.primaryName)));
        assertFalse("different exact names never share a group id",
            result.groups.get(0).semanticGroupId.equals(result.groups.get(1).semanticGroupId));
    }

    @Test
    public void genericCastsShareOneGroupOnlyUnderTheSameNormalisedComposition()
    {
        Br.Result scaled = capture(
            genericCast(T0 + 1_000L, 2, 4, 6),
            genericCast(T0 + 2_000L, 4, 8, 12));

        assertEquals("2/4/6 and 4/8/12 share the same scale-normalised composition",
            1, scaled.groups.size());
        Br.Group group = scaled.groups.get(0);
        assertEquals("Cast", group.primaryName);
        assertEquals(2, group.receiptCount);
        assertEquals(-1_740L, group.value);

        Br.Result different = capture(
            genericCast(T0 + 1_000L, 1, 1, 1),
            bloodGenericCast(T0 + 2_000L));
        assertEquals("a different resource composition stays separate", 2, different.groups.size());
    }

    @Test
    public void genericCastsNeverReceiveAnInferredSpellName()
    {
        Br.Result result = capture(
            exactCast(T0 + 1_000L, "Ice Barrage", 1, 1, 1),
            genericCast(T0 + 2_000L, 1, 1, 1));

        Br.Group generic = result.groups.stream()
            .filter(group -> "Cast".equals(group.primaryName)).findFirst().orElseThrow(AssertionError::new);
        Br.Group exact = result.groups.stream()
            .filter(group -> "Ice Barrage".equals(group.primaryName)).findFirst().orElseThrow(AssertionError::new);

        assertFalse("the generic group must not carry the proven spell name",
            generic.searchTerms.contains("Ice Barrage"));
        assertFalse("the generic group must not match the exact spell name",
            generic.matchesText("Ice Barrage"));
        assertFalse("exact never merges with generic",
            exact.semanticGroupId.equals(generic.semanticGroupId));
        assertTrue("search metadata reaches the exact group", exact.matchesText("Ice Barrage"));
        assertTrue("search metadata reaches underlying resources of the exact group",
            exact.matchesText("Death rune"));
        assertEquals("a matched group keeps its full total", -180L, exact.value);
    }

    @Test
    public void mixedGainAndCostActionStaysAdditiveContributionTruth()
    {
        Ac alchemy = new Ac(T0 + 1_000L, null,
            Ai.CONSUMPTION, Aj.GENERIC, "", "Vorkath", true,
            Arrays.asList(
                new Ab(NATURE, "Nature rune", -1L, 100, -100L, Av.GRAND_EXCHANGE),
                new Ab(995, "Coins", 120L, 1, 120L, Av.FACE_VALUE)),
            Bd.CONFIRMED, "High alchemy", null);
        alchemy.setActionKind(Au.CAST);

        Br.Result result = capture(alchemy);
        assertEquals("a mixed action never collapses into one cost composite", 2, result.groups.size());
        Br.Group cost = result.groups.stream()
            .filter(group -> group.table == Br.Table.COSTS_SUPPLIES)
            .findFirst().orElseThrow(AssertionError::new);
        Br.Group gain = result.groups.stream()
            .filter(group -> group.table == Br.Table.GAINS)
            .findFirst().orElseThrow(AssertionError::new);
        assertEquals("Nature rune", cost.primaryName);
        assertEquals(-100L, cost.value);
        assertEquals("Coins", gain.primaryName);
        assertEquals(120L, gain.value);
        assertEquals("the additive parts reconcile to the canonical Net",
            alchemy.getNet(), cost.value + gain.value);
        assertFalse("no action composite was invented",
            result.groups.stream().anyMatch(Br.Group::actionGroup));
    }

    @Test
    public void estimatedChargeCastsAreChargeUseAndSpellsStaySupplies()
    {
        Ac estimate = estimatedCharge(T0 + 1_000L, "Trident of the Seas",
            new Ab(DEATH, "Death rune", -1L, 100, -100L, Av.GRAND_EXCHANGE),
            new Ab(CHAOS, "Chaos rune", -1L, 50, -50L, Av.GRAND_EXCHANGE));

        Br.Result result =
            capture(estimate, exactCast(T0 + 2_000L, "Ice Barrage", 1, 1, 1));
        Br.Group charge = named(result.groups, "Trident of the Seas");
        assertNotNull(charge);
        assertTrue("an estimated charge cast is charge use", charge.chargeUse);
        assertEquals(Br.Table.COSTS_SUPPLIES, charge.table);
        assertEquals("Cast", charge.actionLabel);

        Br.Group spell = named(result.groups, "Ice Barrage");
        assertNotNull(spell);
        assertFalse("an ordinary spell is a supply, never a charge", spell.chargeUse);

        Br.Group measured = named(capture(measuredCharge(
            T0 + 3_000L, Au.CAST, "Trident of the swamp",
            new Ab(DEATH, "Death rune", -1L, 100, -100L, Av.GRAND_EXCHANGE))).groups,
            "Death rune");
        assertNotNull(measured);
        assertTrue("a measured charge spend is charge use", measured.chargeUse);
    }

    @Test
    public void estimatedScytheChargeKeepsItsIconWithoutTheTransientHint()
    {
        Ac estimate = estimatedCharge(T0 + 1_000L, "Scythe of vitur",
            new Ab(BLOOD, "Blood rune", -2L, 100, -200L, Av.GRAND_EXCHANGE));

        Br.Group charge = named(capture(estimate).groups, "Scythe of vitur");
        assertNotNull(charge);
        assertTrue("a scythe estimate is charge use", charge.chargeUse);
        assertEquals("the weapon sprite must resolve after a restart",
            ItemID.SCYTHE_OF_VITUR, charge.itemId);
    }

    @Test
    public void eyeEstimateBooksTheDemonTearRowItsCheckWould()
    {
        Ac estimate = estimatedCharge(T0 + 1_000L, "Eye of Ayak",
            new Ab(ItemID.DEMON_TEAR, "Demon tear", -1L, 249, -249L, Av.GRAND_EXCHANGE));

        Br.Group tear = named(capture(estimate).groups, "Demon tear");
        assertNotNull(tear);
        assertEquals("Eye of Ayak", tear.usedBy);
        assertTrue("the eye's cast estimate rides the Charges chip", tear.chargeUse);
        for (Br.Group group : capture(estimate).groups)
        {
            assertFalse("the staff is never a row of its own",
                "Eye of Ayak".equals(group.primaryName));
        }
    }

    @Test
    public void bloodFuryEstimateBooksTheShardRowItsCheckWould()
    {
        Ac estimate = estimatedCharge(T0 + 1_000L, "Amulet of blood fury",
            new Ab(ItemID.BLOOD_SHARD, "Blood shard", -1L, 12, -12L, Av.GRAND_EXCHANGE));

        Br.Group shard = named(capture(estimate).groups, "Blood shard");
        assertNotNull(shard);
        assertEquals("Amulet of blood fury", shard.usedBy);
        assertTrue("the estimate rides the Charges chip", shard.chargeUse);
        for (Br.Group group : capture(estimate).groups)
        {
            assertFalse("the amulet is never a row of its own",
                "Amulet of blood fury".equals(group.primaryName));
        }
    }

    @Test
    public void coinPouchesMergeAndOpeningIsOneActionRowWithTheNet()
    {
        Ac first = pouch(T0 + 1_000L, 1L, 0L);
        Ac second = pouch(T0 + 2_000L, 1L, 0L);
        Ac opened = pouch(T0 + 3_000L, -1L, 300L);

        Br.Result result = capture(first, second, opened);
        assertEquals("the open composes; the pickups stay one neutral claim row",
            2, result.groups.size());
        Br.Group open = result.groups.get(0);
        assertEquals("Coin pouch", open.primaryName);
        assertEquals("the count is the claim spend", 1L, open.quantity);
        assertEquals(-1, open.claim);
        assertEquals("the net is the action's gain", 300L, open.value);
        assertFalse("the net prints; only a bare claim leg is neutral", open.neutral);
        Br.Group pickups = result.groups.get(1);
        assertEquals("pickups merge into a counted row", 2L, pickups.quantity);
        assertEquals("a claim pickup reads as +1", 1, pickups.claim);
        assertTrue(pickups.neutral);
        assertEquals(0L, pickups.value);
    }

    @Test
    public void anOpenedKeyIsOneRowWithItsLootNet()
    {
        Ac open = new Ac(T0 + 1_000L, null, Ai.GAIN, Aj.GENERIC, "", "Chest", true,
            Arrays.asList(
                new Ab(ItemID.CRYSTAL_KEY, "Crystal key", -1L, 0, 0L, Av.DEFERRED_CLAIM),
                new Ab(ItemID.DIAMOND, "Diamond", 1L, 2_000, 2_000L, Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "Exact fixture", null);

        Br.Result result = capture(open);
        assertEquals("the claim spend and its loot are one action row", 1,
            result.groups.size());
        Br.Group row = result.groups.get(0);
        assertEquals("Crystal key", row.primaryName);
        assertEquals(-1, row.claim);
        assertEquals(1L, row.quantity);
        assertEquals("the net is the loot", 2_000L, row.value);
        assertFalse(row.neutral);
    }

    @Test
    public void toxicBlowpipeIsContextAndTheResourcesAreTheFinancialRows()
    {
        Ac blowpipe = measuredCharge(T0 + 1_000L, Au.FIRE, "Toxic blowpipe",
            new Ab(11230, "Dragon dart", -2L, 100, -200L, Av.GRAND_EXCHANGE),
            new Ab(12934, "Zulrah's scales", -1L, 110, -110L, Av.GRAND_EXCHANGE));

        Br.Result result = capture(blowpipe);
        assertEquals(2, result.groups.size());
        assertFalse("the equipment is never a financial row",
            result.groups.stream().anyMatch(group -> "Toxic blowpipe".equals(group.primaryName)));

        Br.Group scales = named(result.groups, "Zulrah's scales");
        Br.Group darts = named(result.groups, "Dragon dart");
        assertNotNull(scales);
        assertNotNull(darts);
        assertEquals("Toxic blowpipe", scales.usedBy);
        assertEquals("Used by Toxic blowpipe", scales.qe);
        assertEquals(-110L, scales.value);
        assertEquals(1L, scales.quantity);
        assertEquals(-200L, darts.value);
        assertEquals(2L, darts.quantity);
        assertEquals("Toxic blowpipe", darts.usedBy);
        assertEquals("no component carries the whole parent Net",
            blowpipe.getNet(), scales.value + darts.value);
        assertEquals("the resources are supply costs",
            Br.Table.COSTS_SUPPLIES, scales.table);
    }

    @Test
    public void tridentMeasuredSpendUsesTheSameResourceLaw()
    {
        Ac trident = measuredCharge(T0 + 1_000L, Au.CAST, "Trident of the swamp",
            new Ab(DEATH, "Death rune", -1L, 100, -100L, Av.GRAND_EXCHANGE),
            new Ab(CHAOS, "Chaos rune", -1L, 50, -50L, Av.GRAND_EXCHANGE),
            new Ab(566, "Soul rune", -1L, 100, -100L, Av.GRAND_EXCHANGE));

        Br.Result result = capture(trident);
        assertEquals(3, result.groups.size());
        long total = 0L;
        for (Br.Group group : result.groups)
        {
            assertFalse("Trident of the swamp".equals(group.primaryName));
            assertEquals("Trident of the swamp", group.usedBy);
            assertEquals("Used by Trident of the swamp", group.qe);
            total += group.value;
        }
        assertEquals("the booked resources reconcile to the measured spend",
            trident.getNet(), total);
        assertEquals(3, Br.summarize(result.groups).entries);
        assertEquals(1, Br.summarize(result.groups).receipts);
    }

    @Test
    public void measuredResourceUseUpdatesOneLivingResourceGroup()
    {
        Ac first = measuredCharge(T0 + 1_000L, Au.FIRE, "Toxic blowpipe",
            new Ab(12934, "Zulrah's scales", -1L, 110, -110L, Av.GRAND_EXCHANGE));
        Ac second = measuredCharge(T0 + 2_000L, Au.FIRE, "Toxic blowpipe",
            new Ab(12934, "Zulrah's scales", -1L, 110, -110L, Av.GRAND_EXCHANGE));

        Br.Result result = capture(first, second);
        assertEquals("repeated matching resource use is one living row", 1, result.groups.size());
        Br.Group group = result.groups.get(0);
        assertEquals("Zulrah's scales", group.primaryName);
        assertEquals(-220L, group.value);
        assertEquals(2, group.receiptCount);
        assertEquals(T0 + 2_000L, group.latestActivityAt);
    }

    @Test
    public void unpricedSupplyStaysInCostsSuppliesIncompleteAndNotReview()
    {
        Ac unpriced = new Ac(T0 + 1_000L, null,
            Ai.CONSUMPTION, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(
                new Ab(385, "Shark", -1L, 0, 0L, Av.UNPRICED)),
            Bd.CONFIRMED, "Exact food fixture", null);
        unpriced.setActionKind(Au.EAT);

        Br.Result result = capture(unpriced);
        assertEquals(1, result.groups.size());
        Br.Group group = result.groups.get(0);
        assertEquals(Br.Table.COSTS_SUPPLIES, group.table);
        assertEquals(Br.Coverage.INCOMPLETE, group.coverage);
        assertFalse("an unpriced financial row is not a review decision",
            group.reviewRequired);
        assertEquals(0L, group.value);
        assertTrue(group.incomplete());
    }

    @Test
    public void reviewRequiredTransactionsStayTheirOwnGroups()
    {
        Ac review = new Ac(T0 + 1_000L, null,
            Ai.UNCERTAIN, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(777, "Unknown rune", -1L, 0, 0L)),
            Bd.UNCERTAIN, "Awaiting a decision", null);
        Ac normal = consumed(T0 + 2_000L, "Shark", 385, -1L, 950, -950L);

        Br.Result result = capture(review, normal);
        Br.Group reviewGroup = result.groups.stream()
            .filter(group -> group.reviewRequired).findFirst().orElseThrow(AssertionError::new);
        assertEquals("Unknown rune", reviewGroup.primaryName);
        assertEquals("the review row is not counted as a financial entry", 1, Br.summarize(result.groups).entries);
        assertEquals("review-required value never reaches the financial totals", -950L, Br.summarize(result.groups).costs);
        assertEquals("one counted financial receipt is represented", 1, Br.summarize(result.groups).receipts);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    @Test
    public void theActivityGuessNeverSplitsOneItemIntoTwoRows()
    {
        Ac general = activity(consumed(T0 + 1_000L, "Teleport to house", 8013, -1L, 553, -553L), "General");
        Ac farming = activity(consumed(T0 + 2_000L, "Teleport to house", 8013, -1L, 555, -555L), "Farming");

        Br.Result result = capture(general, farming);
        assertEquals(1, result.groups.size());
        assertEquals(2L, result.groups.get(0).quantity);
        assertEquals(-1_108L, result.groups.get(0).value);
    }

    @Test
    public void aPlantingIsPerItemRowsNotAnUnnamedUsedBundle()
    {
        Ac withPayment = planting(T0 + 1_000L, new Ab(3004, "White berries", -10L, 653, -6_530L,
            Av.GRAND_EXCHANGE));
        Ac alone = planting(T0 + 2_000L);

        Br.Result result = capture(withPayment, alone);
        assertEquals(2, result.groups.size());
        Br.Group sapling = named(result.groups, "Camphor sapling");
        assertEquals(2L, sapling.quantity);
        assertFalse(sapling.actionGroup());
        assertEquals(10L, named(result.groups, "White berries").quantity);
    }

    private static Ac activity(Ac transaction, String name)
    {
        return new Ac(transaction.timestampEpochMillis, null, transaction.getType(),
            Aj.GENERIC, "", name, true, transaction.getFlows(), Bd.CONFIRMED,
            "Exact fixture", null);
    }

    private static Ac planting(long at, Ab... extra)
    {
        List<Ab> flows = new ArrayList<>();
        flows.add(new Ab(31_481, "Camphor sapling", -1L, 7_507, -7_507L, Av.GRAND_EXCHANGE));
        flows.addAll(Arrays.asList(extra));
        flows.add(new Ab(5350, "Empty plant pot", 1L, 8, 8L, Av.GRAND_EXCHANGE));
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "General", true, flows, Bd.CONFIRMED,
            "Exact fixture", null);
        transaction.setActionKind(Au.SUPPLIES);
        return transaction;
    }

    private static Br.Result capture(Ac... transactions)
    {
        return Br.capture(Arrays.asList(transactions), Collections.emptyList(),
            "", null);
    }

    private static Br.Group named(List<Br.Group> groups,
        String name)
    {
        for (Br.Group group : groups)
        {
            if (name.equals(group.primaryName))
            {
                return group;
            }
        }
        return null;
    }

    private static Ac exactCast(long at, String name, int deathQty, int chaosQty, int waterQty)
    {
        Ac transaction = castFlows(at, deathQty, chaosQty, waterQty);
        transaction.ahu(Bb.of(name));
        return transaction;
    }

    private static Ac genericCast(long at, int deathQty, int chaosQty, int waterQty)
    {
        return castFlows(at, deathQty, chaosQty, waterQty);
    }

    private static Ac bloodGenericCast(long at)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Vorkath", true, Arrays.asList(
                rune(DEATH, "Death rune", -1L),
                rune(BLOOD, "Blood rune", -1L),
                rune(WATER, "Water rune", -1L)),
            Bd.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(Au.CAST);
        return transaction;
    }

    private static Ac castFlows(long at, int deathQty, int chaosQty, int waterQty)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Vorkath", true, Arrays.asList(
                rune(DEATH, "Death rune", -deathQty),
                rune(CHAOS, "Chaos rune", -chaosQty),
                rune(WATER, "Water rune", -waterQty)),
            Bd.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(Au.CAST);
        return transaction;
    }

    private static Ab rune(int id, String name, long quantity)
    {
        int unit = id == DEATH ? 100 : id == CHAOS ? 50 : id == BLOOD ? 200 : 30;
        return new Ab(id, name, quantity, unit, quantity * unit, Av.GRAND_EXCHANGE);
    }

    private static Ac measuredCharge(long at, Au kind, String weapon, Ab... flows)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "Measured charge spend \u00b7 " + weapon, "Vorkath", true,
            Arrays.asList(flows), Bd.CONFIRMED,
            "Measured Check difference for " + weapon + ".", null);
        transaction.setActionKind(kind);
        return transaction;
    }

    private static Ac estimatedCharge(long at, String weapon, Ab... flows)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "Estimated charge use \u00b7 " + weapon, "Vorkath", true,
            Arrays.asList(flows), Bd.LIKELY, "Estimated", null);
        transaction.setActionKind(Au.CAST);
        return transaction;
    }

    private static Ac pouch(long at, long quantity, long coins)
    {
        List<Ab> flows = new ArrayList<>();
        flows.add(new Ab(ItemID.PICKPOCKET_COIN_POUCH_ELF, "Coin pouch", quantity, 0, 0L,
            Av.DEFERRED_CLAIM));
        if (coins > 0L)
        {
            flows.add(new Ab(995, "Coins", coins, 1, coins, Av.FACE_VALUE));
        }
        return new Ac(at, null, Ai.GAIN, Aj.GENERIC, "", "Pickpocket", true, flows,
            Bd.CONFIRMED, "Exact fixture", null);
    }

    private static Ac consumed(long at, String name, int itemId, long quantity,
        int unitPrice, long value)
    {
        return new Ac(at, null, Ai.CONSUMPTION, Aj.GENERIC,
            "", "Vorkath", true, Collections.singletonList(
                new Ab(itemId, name, quantity, unitPrice, value, Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "Exact fixture", null);
    }
}
