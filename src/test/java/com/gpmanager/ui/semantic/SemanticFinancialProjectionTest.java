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
        Transaction first = exactCast(T0 + 1_000L, "Ice Barrage", 1, 1, 1);
        Transaction second = exactCast(T0 + 2_000L, "Ice Barrage", 1, 1, 1);

        SemanticFinancialProjection.Result result = capture(first, second);
        assertEquals("repeated compatible casts are one living group", 1, result.groups.size());
        SemanticFinancialProjection.Group group = result.groups.get(0);
        assertEquals("Ice Barrage", group.primaryName);
        assertEquals(-360L, group.value);
        assertEquals(2, group.receiptCount);
        assertEquals(T0 + 2_000L, group.latestActivityAt);
        assertTrue(group.actionGroup());
        assertEquals("3 rune types", group.contextLine);
        assertEquals(1, SemanticFinancialProjection.summarize(result.groups).entries);
        assertEquals(2, SemanticFinancialProjection.summarize(result.groups).receipts);
        assertEquals(-360L, SemanticFinancialProjection.summarize(result.groups).costs);

        Transaction third = exactCast(T0 + 3_000L, "Blood Barrage", 1, 1, 1);
        SemanticFinancialProjection.Result bubbled = capture(first, second, third);
        assertEquals("a newer compatible-free receipt bubbles to the top", 2, bubbled.groups.size());
        assertEquals("Blood Barrage", bubbled.groups.get(0).primaryName);
        assertEquals("Ice Barrage", bubbled.groups.get(1).primaryName);
    }

    @Test
    public void differentExactNamesNeverMergeDespiteSharedResources()
    {
        SemanticFinancialProjection.Result result = capture(
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
        SemanticFinancialProjection.Result scaled = capture(
            genericCast(T0 + 1_000L, 2, 4, 6),
            genericCast(T0 + 2_000L, 4, 8, 12));

        assertEquals("2/4/6 and 4/8/12 share the same scale-normalised composition",
            1, scaled.groups.size());
        SemanticFinancialProjection.Group group = scaled.groups.get(0);
        assertEquals("Cast", group.primaryName);
        assertEquals(2, group.receiptCount);
        assertEquals(-1_740L, group.value);

        SemanticFinancialProjection.Result different = capture(
            genericCast(T0 + 1_000L, 1, 1, 1),
            bloodGenericCast(T0 + 2_000L));
        assertEquals("a different resource composition stays separate", 2, different.groups.size());
    }

    @Test
    public void genericCastsNeverReceiveAnInferredSpellName()
    {
        SemanticFinancialProjection.Result result = capture(
            exactCast(T0 + 1_000L, "Ice Barrage", 1, 1, 1),
            genericCast(T0 + 2_000L, 1, 1, 1));

        SemanticFinancialProjection.Group generic = result.groups.stream()
            .filter(group -> "Cast".equals(group.primaryName)).findFirst().orElseThrow(AssertionError::new);
        SemanticFinancialProjection.Group exact = result.groups.stream()
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
        Transaction alchemy = new Transaction(T0 + 1_000L, null,
            TransactionType.CONSUMPTION, Context.GENERIC, "", "Vorkath", true,
            Arrays.asList(
                new Flow(NATURE, "Nature rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE),
                new Flow(995, "Coins", 120L, 1, 120L, PriceSource.FACE_VALUE)),
            ClassificationConfidence.CONFIRMED, "High alchemy", null);
        alchemy.setActionKind(ActionKind.CAST);

        SemanticFinancialProjection.Result result = capture(alchemy);
        assertEquals("a mixed action never collapses into one cost composite", 2, result.groups.size());
        SemanticFinancialProjection.Group cost = result.groups.stream()
            .filter(group -> group.table == SemanticFinancialProjection.Table.COSTS_SUPPLIES)
            .findFirst().orElseThrow(AssertionError::new);
        SemanticFinancialProjection.Group gain = result.groups.stream()
            .filter(group -> group.table == SemanticFinancialProjection.Table.GAINS)
            .findFirst().orElseThrow(AssertionError::new);
        assertEquals("Nature rune", cost.primaryName);
        assertEquals(-100L, cost.value);
        assertEquals("Coins", gain.primaryName);
        assertEquals(120L, gain.value);
        assertEquals("the additive parts reconcile to the canonical Net",
            alchemy.getNet(), cost.value + gain.value);
        assertFalse("no action composite was invented",
            result.groups.stream().anyMatch(SemanticFinancialProjection.Group::actionGroup));
    }

    @Test
    public void estimatedChargeCastsAreChargeUseAndSpellsStaySupplies()
    {
        Transaction estimate = estimatedCharge(T0 + 1_000L, "Trident of the Seas",
            new Flow(DEATH, "Death rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE),
            new Flow(CHAOS, "Chaos rune", -1L, 50, -50L, PriceSource.GRAND_EXCHANGE));

        SemanticFinancialProjection.Result result =
            capture(estimate, exactCast(T0 + 2_000L, "Ice Barrage", 1, 1, 1));
        SemanticFinancialProjection.Group charge = named(result.groups, "Trident of the Seas");
        assertNotNull(charge);
        assertTrue("an estimated charge cast is charge use", charge.chargeUse);
        assertEquals(SemanticFinancialProjection.Table.COSTS_SUPPLIES, charge.table);
        assertEquals("Cast", charge.actionLabel);

        SemanticFinancialProjection.Group spell = named(result.groups, "Ice Barrage");
        assertNotNull(spell);
        assertFalse("an ordinary spell is a supply, never a charge", spell.chargeUse);

        SemanticFinancialProjection.Group measured = named(capture(measuredCharge(
            T0 + 3_000L, ActionKind.CAST, "Trident of the swamp",
            new Flow(DEATH, "Death rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE))).groups,
            "Death rune");
        assertNotNull(measured);
        assertTrue("a measured charge spend is charge use", measured.chargeUse);
    }

    @Test
    public void estimatedScytheChargeKeepsItsIconWithoutTheTransientHint()
    {
        Transaction estimate = estimatedCharge(T0 + 1_000L, "Scythe of vitur",
            new Flow(BLOOD, "Blood rune", -2L, 100, -200L, PriceSource.GRAND_EXCHANGE));

        SemanticFinancialProjection.Group charge = named(capture(estimate).groups, "Scythe of vitur");
        assertNotNull(charge);
        assertTrue("a scythe estimate is charge use", charge.chargeUse);
        assertEquals("the weapon sprite must resolve after a restart",
            ItemID.SCYTHE_OF_VITUR, charge.itemId);
    }

    @Test
    public void eyeEstimateBooksTheDemonTearRowItsCheckWould()
    {
        Transaction estimate = estimatedCharge(T0 + 1_000L, "Eye of Ayak",
            new Flow(ItemID.DEMON_TEAR, "Demon tear", -1L, 249, -249L, PriceSource.GRAND_EXCHANGE));

        SemanticFinancialProjection.Group tear = named(capture(estimate).groups, "Demon tear");
        assertNotNull(tear);
        assertEquals("Eye of Ayak", tear.usedBy);
        assertTrue("the eye's cast estimate rides the Charges chip", tear.chargeUse);
        for (SemanticFinancialProjection.Group group : capture(estimate).groups)
        {
            assertFalse("the staff is never a row of its own",
                "Eye of Ayak".equals(group.primaryName));
        }
    }

    @Test
    public void bloodFuryEstimateBooksTheShardRowItsCheckWould()
    {
        Transaction estimate = estimatedCharge(T0 + 1_000L, "Amulet of blood fury",
            new Flow(ItemID.BLOOD_SHARD, "Blood shard", -1L, 12, -12L, PriceSource.GRAND_EXCHANGE));

        SemanticFinancialProjection.Group shard = named(capture(estimate).groups, "Blood shard");
        assertNotNull(shard);
        assertEquals("Amulet of blood fury", shard.usedBy);
        assertTrue("the estimate rides the Charges chip", shard.chargeUse);
        for (SemanticFinancialProjection.Group group : capture(estimate).groups)
        {
            assertFalse("the amulet is never a row of its own",
                "Amulet of blood fury".equals(group.primaryName));
        }
    }

    @Test
    public void coinPouchesMergeAndOpeningIsOneActionRowWithTheNet()
    {
        Transaction first = pouch(T0 + 1_000L, 1L, 0L);
        Transaction second = pouch(T0 + 2_000L, 1L, 0L);
        Transaction opened = pouch(T0 + 3_000L, -1L, 300L);

        SemanticFinancialProjection.Result result = capture(first, second, opened);
        assertEquals("the open composes; the pickups stay one neutral claim row",
            2, result.groups.size());
        SemanticFinancialProjection.Group open = result.groups.get(0);
        assertEquals("Coin pouch", open.primaryName);
        assertEquals("the count is the claim spend", 1L, open.quantity);
        assertEquals(-1, open.claim);
        assertEquals("the net is the action's gain", 300L, open.value);
        assertFalse("the net prints; only a bare claim leg is neutral", open.neutral);
        SemanticFinancialProjection.Group pickups = result.groups.get(1);
        assertEquals("pickups merge into a counted row", 2L, pickups.quantity);
        assertEquals("a claim pickup reads as +1", 1, pickups.claim);
        assertTrue(pickups.neutral);
        assertEquals(0L, pickups.value);
    }

    @Test
    public void anOpenedKeyIsOneRowWithItsLootNet()
    {
        Transaction open = new Transaction(T0 + 1_000L, null, TransactionType.GAIN, Context.GENERIC, "", "Chest", true,
            Arrays.asList(
                new Flow(ItemID.CRYSTAL_KEY, "Crystal key", -1L, 0, 0L, PriceSource.DEFERRED_CLAIM),
                new Flow(ItemID.DIAMOND, "Diamond", 1L, 2_000, 2_000L, PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);

        SemanticFinancialProjection.Result result = capture(open);
        assertEquals("the claim spend and its loot are one action row", 1,
            result.groups.size());
        SemanticFinancialProjection.Group row = result.groups.get(0);
        assertEquals("Crystal key", row.primaryName);
        assertEquals(-1, row.claim);
        assertEquals(1L, row.quantity);
        assertEquals("the net is the loot", 2_000L, row.value);
        assertFalse(row.neutral);
    }

    @Test
    public void toxicBlowpipeIsContextAndTheResourcesAreTheFinancialRows()
    {
        Transaction blowpipe = measuredCharge(T0 + 1_000L, ActionKind.FIRE, "Toxic blowpipe",
            new Flow(11230, "Dragon dart", -2L, 100, -200L, PriceSource.GRAND_EXCHANGE),
            new Flow(12934, "Zulrah's scales", -1L, 110, -110L, PriceSource.GRAND_EXCHANGE));

        SemanticFinancialProjection.Result result = capture(blowpipe);
        assertEquals(2, result.groups.size());
        assertFalse("the equipment is never a financial row",
            result.groups.stream().anyMatch(group -> "Toxic blowpipe".equals(group.primaryName)));

        SemanticFinancialProjection.Group scales = named(result.groups, "Zulrah's scales");
        SemanticFinancialProjection.Group darts = named(result.groups, "Dragon dart");
        assertNotNull(scales);
        assertNotNull(darts);
        assertEquals("Toxic blowpipe", scales.usedBy);
        assertEquals("Used by Toxic blowpipe", scales.contextLine);
        assertEquals(-110L, scales.value);
        assertEquals(1L, scales.quantity);
        assertEquals(-200L, darts.value);
        assertEquals(2L, darts.quantity);
        assertEquals("Toxic blowpipe", darts.usedBy);
        assertEquals("no component carries the whole parent Net",
            blowpipe.getNet(), scales.value + darts.value);
        assertEquals("the resources are supply costs",
            SemanticFinancialProjection.Table.COSTS_SUPPLIES, scales.table);
    }

    @Test
    public void tridentMeasuredSpendUsesTheSameResourceLaw()
    {
        Transaction trident = measuredCharge(T0 + 1_000L, ActionKind.CAST, "Trident of the swamp",
            new Flow(DEATH, "Death rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE),
            new Flow(CHAOS, "Chaos rune", -1L, 50, -50L, PriceSource.GRAND_EXCHANGE),
            new Flow(566, "Soul rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE));

        SemanticFinancialProjection.Result result = capture(trident);
        assertEquals(3, result.groups.size());
        long total = 0L;
        for (SemanticFinancialProjection.Group group : result.groups)
        {
            assertFalse("Trident of the swamp".equals(group.primaryName));
            assertEquals("Trident of the swamp", group.usedBy);
            assertEquals("Used by Trident of the swamp", group.contextLine);
            total += group.value;
        }
        assertEquals("the booked resources reconcile to the measured spend",
            trident.getNet(), total);
        assertEquals(3, SemanticFinancialProjection.summarize(result.groups).entries);
        assertEquals(1, SemanticFinancialProjection.summarize(result.groups).receipts);
    }

    @Test
    public void measuredResourceUseUpdatesOneLivingResourceGroup()
    {
        Transaction first = measuredCharge(T0 + 1_000L, ActionKind.FIRE, "Toxic blowpipe",
            new Flow(12934, "Zulrah's scales", -1L, 110, -110L, PriceSource.GRAND_EXCHANGE));
        Transaction second = measuredCharge(T0 + 2_000L, ActionKind.FIRE, "Toxic blowpipe",
            new Flow(12934, "Zulrah's scales", -1L, 110, -110L, PriceSource.GRAND_EXCHANGE));

        SemanticFinancialProjection.Result result = capture(first, second);
        assertEquals("repeated matching resource use is one living row", 1, result.groups.size());
        SemanticFinancialProjection.Group group = result.groups.get(0);
        assertEquals("Zulrah's scales", group.primaryName);
        assertEquals(-220L, group.value);
        assertEquals(2, group.receiptCount);
        assertEquals(T0 + 2_000L, group.latestActivityAt);
    }

    @Test
    public void unpricedSupplyStaysInCostsSuppliesIncompleteAndNotReview()
    {
        Transaction unpriced = new Transaction(T0 + 1_000L, null,
            TransactionType.CONSUMPTION, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(
                new Flow(385, "Shark", -1L, 0, 0L, PriceSource.UNPRICED)),
            ClassificationConfidence.CONFIRMED, "Exact food fixture", null);
        unpriced.setActionKind(ActionKind.EAT);

        SemanticFinancialProjection.Result result = capture(unpriced);
        assertEquals(1, result.groups.size());
        SemanticFinancialProjection.Group group = result.groups.get(0);
        assertEquals(SemanticFinancialProjection.Table.COSTS_SUPPLIES, group.table);
        assertEquals(SemanticFinancialProjection.Coverage.INCOMPLETE, group.coverage);
        assertFalse("an unpriced financial row is not a review decision",
            group.reviewRequired);
        assertEquals(0L, group.value);
        assertTrue(group.incomplete());
    }

    @Test
    public void reviewRequiredTransactionsStayTheirOwnGroups()
    {
        Transaction review = new Transaction(T0 + 1_000L, null,
            TransactionType.UNCERTAIN, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(777, "Unknown rune", -1L, 0, 0L)),
            ClassificationConfidence.UNCERTAIN, "Awaiting a decision", null);
        Transaction normal = consumed(T0 + 2_000L, "Shark", 385, -1L, 950, -950L);

        SemanticFinancialProjection.Result result = capture(review, normal);
        SemanticFinancialProjection.Group reviewGroup = result.groups.stream()
            .filter(group -> group.reviewRequired).findFirst().orElseThrow(AssertionError::new);
        assertEquals("Unknown rune", reviewGroup.primaryName);
        assertEquals("the review row is not counted as a financial entry", 1, SemanticFinancialProjection.summarize(result.groups).entries);
        assertEquals("review-required value never reaches the financial totals", -950L, SemanticFinancialProjection.summarize(result.groups).costs);
        assertEquals("one counted financial receipt is represented", 1, SemanticFinancialProjection.summarize(result.groups).receipts);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    @Test
    public void theActivityGuessNeverSplitsOneItemIntoTwoRows()
    {
        Transaction general = activity(consumed(T0 + 1_000L, "Teleport to house", 8013, -1L, 553, -553L), "General");
        Transaction farming = activity(consumed(T0 + 2_000L, "Teleport to house", 8013, -1L, 555, -555L), "Farming");

        SemanticFinancialProjection.Result result = capture(general, farming);
        assertEquals(1, result.groups.size());
        assertEquals(2L, result.groups.get(0).quantity);
        assertEquals(-1_108L, result.groups.get(0).value);
    }

    @Test
    public void aPlantingIsPerItemRowsNotAnUnnamedUsedBundle()
    {
        Transaction withPayment = planting(T0 + 1_000L, new Flow(3004, "White berries", -10L, 653, -6_530L,
            PriceSource.GRAND_EXCHANGE));
        Transaction alone = planting(T0 + 2_000L);

        SemanticFinancialProjection.Result result = capture(withPayment, alone);
        assertEquals(2, result.groups.size());
        SemanticFinancialProjection.Group sapling = named(result.groups, "Camphor sapling");
        assertEquals(2L, sapling.quantity);
        assertFalse(sapling.actionGroup());
        assertEquals(10L, named(result.groups, "White berries").quantity);
    }

    private static Transaction activity(Transaction transaction, String name)
    {
        return new Transaction(transaction.timestampEpochMillis, null, transaction.getType(),
            Context.GENERIC, "", name, true, transaction.getFlows(), ClassificationConfidence.CONFIRMED,
            "Exact fixture", null);
    }

    private static Transaction planting(long at, Flow... extra)
    {
        List<Flow> flows = new ArrayList<>();
        flows.add(new Flow(31_481, "Camphor sapling", -1L, 7_507, -7_507L, PriceSource.GRAND_EXCHANGE));
        flows.addAll(Arrays.asList(extra));
        flows.add(new Flow(5350, "Empty plant pot", 1L, 8, 8L, PriceSource.GRAND_EXCHANGE));
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "General", true, flows, ClassificationConfidence.CONFIRMED,
            "Exact fixture", null);
        transaction.setActionKind(ActionKind.SUPPLIES);
        return transaction;
    }

    private static SemanticFinancialProjection.Result capture(Transaction... transactions)
    {
        return SemanticFinancialProjection.capture(Arrays.asList(transactions), Collections.emptyList(),
            "", null);
    }

    private static SemanticFinancialProjection.Group named(List<SemanticFinancialProjection.Group> groups,
        String name)
    {
        for (SemanticFinancialProjection.Group group : groups)
        {
            if (name.equals(group.primaryName))
            {
                return group;
            }
        }
        return null;
    }

    private static Transaction exactCast(long at, String name, int deathQty, int chaosQty, int waterQty)
    {
        Transaction transaction = castFlows(at, deathQty, chaosQty, waterQty);
        transaction.setObservedActionLabel(ActionLabel.of(name));
        return transaction;
    }

    private static Transaction genericCast(long at, int deathQty, int chaosQty, int waterQty)
    {
        return castFlows(at, deathQty, chaosQty, waterQty);
    }

    private static Transaction bloodGenericCast(long at)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                rune(DEATH, "Death rune", -1L),
                rune(BLOOD, "Blood rune", -1L),
                rune(WATER, "Water rune", -1L)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(ActionKind.CAST);
        return transaction;
    }

    private static Transaction castFlows(long at, int deathQty, int chaosQty, int waterQty)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                rune(DEATH, "Death rune", -deathQty),
                rune(CHAOS, "Chaos rune", -chaosQty),
                rune(WATER, "Water rune", -waterQty)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(ActionKind.CAST);
        return transaction;
    }

    private static Flow rune(int id, String name, long quantity)
    {
        int unit = id == DEATH ? 100 : id == CHAOS ? 50 : id == BLOOD ? 200 : 30;
        return new Flow(id, name, quantity, unit, quantity * unit, PriceSource.GRAND_EXCHANGE);
    }

    private static Transaction measuredCharge(long at, ActionKind kind, String weapon, Flow... flows)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "Measured charge spend \u00b7 " + weapon, "Vorkath", true,
            Arrays.asList(flows), ClassificationConfidence.CONFIRMED,
            "Measured Check difference for " + weapon + ".", null);
        transaction.setActionKind(kind);
        return transaction;
    }

    private static Transaction estimatedCharge(long at, String weapon, Flow... flows)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "Estimated charge use \u00b7 " + weapon, "Vorkath", true,
            Arrays.asList(flows), ClassificationConfidence.LIKELY, "Estimated", null);
        transaction.setActionKind(ActionKind.CAST);
        return transaction;
    }

    private static Transaction pouch(long at, long quantity, long coins)
    {
        List<Flow> flows = new ArrayList<>();
        flows.add(new Flow(ItemID.PICKPOCKET_COIN_POUCH_ELF, "Coin pouch", quantity, 0, 0L,
            PriceSource.DEFERRED_CLAIM));
        if (coins > 0L)
        {
            flows.add(new Flow(995, "Coins", coins, 1, coins, PriceSource.FACE_VALUE));
        }
        return new Transaction(at, null, TransactionType.GAIN, Context.GENERIC, "", "Pickpocket", true, flows,
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
    }

    private static Transaction consumed(long at, String name, int itemId, long quantity,
        int unitPrice, long value)
    {
        return new Transaction(at, null, TransactionType.CONSUMPTION, Context.GENERIC,
            "", "Vorkath", true, Collections.singletonList(
                new Flow(itemId, name, quantity, unitPrice, value, PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
    }
}
