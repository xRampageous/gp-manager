package com.gpmanager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Recent Activity uses the item/action evidence that the settled receipt actually carries. */
public class LiveActionWordingTest
{
    @Test
    public void exactSpellNameAndGenericFallbackRenderWithoutSearchCoupling() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        Transaction exact = cast(now + 1L, "Ice Burst",
            rune("Death rune", 560, -1L, -100L), rune("Chaos rune", 562, -1L, -50L),
            rune("Water rune", 555, -1L, -30L));
        exact.setObservedActionLabel(ActionLabel.of("Ice Burst"));
        Transaction generic = cast(now + 2L, "",
            rune("Death rune", 560, -1L, -100L), rune("Chaos rune", 562, -1L, -50L),
            rune("Water rune", 555, -1L, -30L));
        engine.getActiveSession().addTransaction(exact, 2_000);
        engine.getActiveSession().addTransaction(generic, 2_000);

        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now + 10L, LiveContext.NONE);
        LiveSnapshot.Recent exactRow = snapshot.recent.stream()
            .filter(row -> row.receiptId.equals(exact.getId())).findFirst().orElseThrow(AssertionError::new);
        LiveSnapshot.Recent genericRow = snapshot.recent.stream()
            .filter(row -> row.receiptId.equals(generic.getId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals("Ice Burst", exactRow.name);
        assertEquals("Cast", LivePage.metaOf(exactRow));
        assertEquals("Cast", genericRow.name);
        assertEquals("3 rune types", LivePage.metaOf(genericRow));

        LedgerData.Entry deepLink = LivePage.ledgerEntryFor(exactRow);
        assertEquals("deep link has no hidden action-name search", "", deepLink.search);
        assertEquals("an exact Cast links to Costs/Supplies",
            LedgerData.CostView.SUPPLIES, exactRow.ledgerCostView);
        LedgerData linked = LedgerData.capture(engine, now + 10L, deepLink);
        assertNotNull("the stable receipt opens its semantic group", linked.detail);
        assertTrue(linked.detail.group.containsContribution(exactRow.contributionId));

        LedgerData searched = LedgerData.capture(engine, now + 10L,
            new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                LedgerData.CostView.SUPPLIES, "Ice Burst", null, null, null, null));
        assertEquals("exact action text search keeps the whole group", 1, searched.costs.entries);
        assertEquals("Ice Burst", searched.costs.groups.get(0).primaryName);
    }

    @Test
    public void supplyVerbsAndMeasuredWeaponsUseProvenNames() throws Exception
    {
        LiveSnapshot.Recent potion = recent(action(1L, ActionKind.DRINK,
            new Flow(2434, "Prayer potion(4)", -1L, 200, -200L),
            new Flow(139, "Prayer potion(3)", 1L, 175, 175L)));
        assertEquals("Prayer potion", potion.name);
        assertEquals("Drank · 1 dose", LivePage.metaOf(potion));

        LiveSnapshot.Recent food = recent(action(2L, ActionKind.EAT,
            new Flow(385, "Shark", -1L, 950, -950L)));
        assertEquals("Shark", food.name);
        assertEquals("Ate", LivePage.metaOf(food));

        LiveSnapshot.Recent partialFood = recent(action(3L, ActionKind.EAT,
            new Flow(2309, "Whole pineapple pizza", -1L, 1_000, -1_000L),
            new Flow(2313, "Half pineapple pizza", 1L, 500, 500L)));
        assertEquals("pineapple pizza", partialFood.name);
        assertEquals("Ate · 1 portion", LivePage.metaOf(partialFood));

        LiveSnapshot.Recent sapling = recent(action(30L, ActionKind.SUPPLIES,
            new Flow(5373, "Yew sapling", -3L, 22_800, -68_400L),
            new Flow(5350, "Empty plant pot", 3L, 8, 24L)));
        assertEquals("Yew sapling", sapling.name);
        assertEquals("a planted sapling is not a portion", "×3", sapling.qty);

        LiveSnapshot.Recent buried = recent(action(4L, ActionKind.BURY,
            new Flow(536, "Dragon bones", -1L, 2_000, -2_000L)));
        assertEquals("Dragon bones", buried.name);
        assertEquals("Buried", LivePage.metaOf(buried));

        LiveSnapshot.Recent offered = recent(action(5L, ActionKind.OFFER,
            new Flow(536, "Dragon bones", -1L, 2_000, -2_000L)));
        assertEquals("Offered", LivePage.metaOf(offered));

        LiveSnapshot.Recent ammo = recent(action(6L, ActionKind.FIRE,
            new Flow(892, "Rune arrow", -2L, 100, -200L)));
        assertEquals("Rune arrow", ammo.name);
        assertEquals("Fired · ×2", LivePage.metaOf(ammo));

        LiveSnapshot.Recent genericSupply = recent(action(7L, ActionKind.SUPPLIES,
            new Flow(1511, "Willow logs", -1L, 50, -50L)));
        assertEquals("Used", LivePage.metaOf(genericSupply));

        Transaction unsupported = transaction(8L,
            new Flow(385, "Shark", -1L, 950, -950L));
        LiveSnapshot.Recent unsupportedRow = recent(unsupported);
        assertEquals("Shark", unsupportedRow.name);
        assertEquals("unsupported evidence stays generic", "Used",
            LivePage.metaOf(unsupportedRow));

        Transaction blowpipe = action(9L, ActionKind.FIRE,
            new Flow(11230, "Dragon dart", -2L, 100, -200L),
            new Flow(12934, "Zulrah's scales", -1L, 110, -110L));
        blowpipe.note = "Measured charge spend \u00b7 Toxic blowpipe";
        assertTrue("charge use stays out of Recent (owner 2026-09-29)", recents(blowpipe).isEmpty());

        Transaction trident = action(10L, ActionKind.CAST,
            rune("Death rune", 560, -1L, -100L), rune("Chaos rune", 562, -1L, -50L),
            rune("Soul rune", 566, -1L, -100L));
        trident.note = "Measured charge spend \u00b7 Trident of the swamp";
        assertTrue("charge use stays out of Recent (owner 2026-09-29)", recents(trident).isEmpty());
    }

    private static List<LiveSnapshot.Recent> recents(Transaction transaction)
    {
        return LiveProbe.recent(Collections.singletonList(transaction), LiveContext.NONE);
    }

    private static LiveSnapshot.Recent rowNamed(List<LiveSnapshot.Recent> rows, String name)
    {
        for (LiveSnapshot.Recent row : rows)
        {
            if (name.equals(row.name))
            {
                return row;
            }
        }
        return null;
    }

    /** Owner 2026-09-28: autocast never clicks a spell, yet its row names the spell and shows its icon. */
    @Test
    public void anAutocastReadsAsItsSpellWithItsIcon()
    {
        List<Transaction> casts = Arrays.asList(
            action(1L, ActionKind.CAST, rune("Death rune", 560, -4L, -748L), rune("Blood rune", 565, -2L, -682L),
                rune("Water rune", 555, -6L, -30L)),
            action(2L, ActionKind.CAST, rune("Death rune", 560, -4L, -748L), rune("Blood rune", 565, -2L, -682L),
                rune("Water rune", 555, -6L, -30L)));
        List<LiveSnapshot.Recent> rows = LiveProbe.recent(casts, LiveContext.NONE);
        assertEquals("one row for the spell", 1, rows.size());
        assertEquals("Ice Barrage", rows.get(0).name);
        assertEquals("the spellbook icon", -328, rows.get(0).itemId);
    }

    /** Owner 2026-09-28: a whole potion drunk dose by dose is one Recent row, not one per sip. */
    @Test
    public void aWholePotionDrunkIsOneRecentRow()
    {
        List<Transaction> sips = Arrays.asList(
            action(4L, ActionKind.DRINK, new Flow(2442, "Super defence(4)", -1L, 3_364, -3_364L),
                new Flow(163, "Super defence(3)", 1L, 2_523, 2_523L)),
            action(3L, ActionKind.DRINK, new Flow(163, "Super defence(3)", -1L, 2_523, -2_523L),
                new Flow(165, "Super defence(2)", 1L, 1_682, 1_682L)),
            action(2L, ActionKind.DRINK, new Flow(165, "Super defence(2)", -1L, 1_682, -1_682L),
                new Flow(167, "Super defence(1)", 1L, 841, 841L)),
            action(1L, ActionKind.DRINK, new Flow(167, "Super defence(1)", -1L, 841, -841L)));
        List<LiveSnapshot.Recent> rows = LiveProbe.recent(sips, LiveContext.NONE);
        assertEquals("one row for the potion", 1, rows.size());
        assertEquals("Super defence", rows.get(0).name);
        assertEquals("every dose costs the same", -4L * 841L, rows.get(0).value);
        assertEquals("Drank · ×4 doses", LivePage.metaOf(rows.get(0)));
    }

    private static LiveSnapshot.Recent recent(Transaction transaction)
    {
        return LiveProbe.recent(Collections.singletonList(transaction), LiveContext.NONE).get(0);
    }

    private static Transaction action(long at, ActionKind kind, Flow... flows)
    {
        Transaction transaction = transaction(at, flows);
        transaction.setActionKind(kind);
        return transaction;
    }

    private static Transaction cast(long at, String name, Flow... flows)
    {
        Transaction transaction = action(at, ActionKind.CAST, flows);
        if (!name.isEmpty()) transaction.setObservedActionLabel(ActionLabel.of(name));
        return transaction;
    }

    private static Transaction transaction(long at, Flow... flows)
    {
        return new Transaction(at, null, TransactionType.CONSUMPTION, Context.GENERIC,
            "", "Vorkath", true, Arrays.asList(flows), ClassificationConfidence.CONFIRMED,
            "Positive action fixture", null);
    }

    private static Flow rune(String name, int id, long quantity, long value)
    {
        return new Flow(id, name, quantity, Math.toIntExact(Math.abs(value)), value);
    }
}
