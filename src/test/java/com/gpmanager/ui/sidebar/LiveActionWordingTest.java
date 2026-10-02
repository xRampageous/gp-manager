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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        Ac exact = cast(now + 1L, "Ice Burst",
            rune("Death rune", 560, -1L, -100L), rune("Chaos rune", 562, -1L, -50L),
            rune("Water rune", 555, -1L, -30L));
        exact.ahu(Bb.of("Ice Burst"));
        Ac generic = cast(now + 2L, "",
            rune("Death rune", 560, -1L, -100L), rune("Chaos rune", 562, -1L, -50L),
            rune("Water rune", 555, -1L, -30L));
        engine.getActiveSession().kf(exact, 2_000);
        engine.getActiveSession().kf(generic, 2_000);

        Ca snapshot = Ca.capture(engine, now + 10L, Dz.NONE);
        Ca.Recent exactRow = snapshot.recent.stream()
            .filter(row -> row.receiptId.equals(exact.getId())).findFirst().orElseThrow(AssertionError::new);
        Ca.Recent genericRow = snapshot.recent.stream()
            .filter(row -> row.receiptId.equals(generic.getId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals("Ice Burst", exactRow.name);
        assertEquals("Cast", LivePage.metaOf(exactRow));
        assertEquals("Cast", genericRow.name);
        assertEquals("3 rune types", LivePage.metaOf(genericRow));

        Ao.Entry deepLink = LivePage.yn(exactRow);
        assertEquals("deep link has no hidden action-name search", "", deepLink.search);
        assertEquals("an exact Cast links to Costs/Supplies",
            Ao.Bs.SUPPLIES, exactRow.ledgerCostView);
        Ao linked = Ao.capture(engine, now + 10L, deepLink);
        assertNotNull("the stable receipt opens its semantic group", linked.detail);
        assertTrue(linked.detail.group.containsContribution(exactRow.contributionId));

        Ao searched = Ao.capture(engine, now + 10L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.SUPPLIES, "Ice Burst", null, null, null, null));
        assertEquals("exact action text search keeps the whole group", 1, searched.costs.entries);
        assertEquals("Ice Burst", searched.costs.groups.get(0).primaryName);
    }

    @Test
    public void supplyVerbsAndMeasuredWeaponsUseProvenNames() throws Exception
    {
        Ca.Recent potion = recent(action(1L, Au.DRINK,
            new Ab(2434, "Prayer potion(4)", -1L, 200, -200L),
            new Ab(139, "Prayer potion(3)", 1L, 175, 175L)));
        assertEquals("Prayer potion", potion.name);
        assertEquals("Drank · 1 dose", LivePage.metaOf(potion));

        Ca.Recent food = recent(action(2L, Au.EAT,
            new Ab(385, "Shark", -1L, 950, -950L)));
        assertEquals("Shark", food.name);
        assertEquals("Ate", LivePage.metaOf(food));

        Ca.Recent partialFood = recent(action(3L, Au.EAT,
            new Ab(2309, "Whole pineapple pizza", -1L, 1_000, -1_000L),
            new Ab(2313, "Half pineapple pizza", 1L, 500, 500L)));
        assertEquals("pineapple pizza", partialFood.name);
        assertEquals("Ate · 1 portion", LivePage.metaOf(partialFood));

        Ca.Recent sapling = recent(action(30L, Au.SUPPLIES,
            new Ab(5373, "Yew sapling", -3L, 22_800, -68_400L),
            new Ab(5350, "Empty plant pot", 3L, 8, 24L)));
        assertEquals("Yew sapling", sapling.name);
        assertEquals("a planted sapling is not a portion", "×3", sapling.qty);

        Ca.Recent buried = recent(action(4L, Au.BURY,
            new Ab(536, "Dragon bones", -1L, 2_000, -2_000L)));
        assertEquals("Dragon bones", buried.name);
        assertEquals("Buried", LivePage.metaOf(buried));

        Ca.Recent offered = recent(action(5L, Au.OFFER,
            new Ab(536, "Dragon bones", -1L, 2_000, -2_000L)));
        assertEquals("Offered", LivePage.metaOf(offered));

        Ca.Recent ammo = recent(action(6L, Au.FIRE,
            new Ab(892, "Rune arrow", -2L, 100, -200L)));
        assertEquals("Rune arrow", ammo.name);
        assertEquals("Fired · ×2", LivePage.metaOf(ammo));

        Ca.Recent genericSupply = recent(action(7L, Au.SUPPLIES,
            new Ab(1511, "Willow logs", -1L, 50, -50L)));
        assertEquals("Used", LivePage.metaOf(genericSupply));

        Ac unsupported = transaction(8L,
            new Ab(385, "Shark", -1L, 950, -950L));
        Ca.Recent unsupportedRow = recent(unsupported);
        assertEquals("Shark", unsupportedRow.name);
        assertEquals("unsupported evidence stays generic", "Used",
            LivePage.metaOf(unsupportedRow));

        Ac blowpipe = action(9L, Au.FIRE,
            new Ab(11230, "Dragon dart", -2L, 100, -200L),
            new Ab(12934, "Zulrah's scales", -1L, 110, -110L));
        blowpipe.note = "Measured charge spend \u00b7 Toxic blowpipe";
        List<Ca.Recent> blowpipeRows = recents(blowpipe);
        assertEquals("owner 1.1: only the darts, as Supplies; the scales stay a charge", 1, blowpipeRows.size());
        assertEquals("Dragon dart", blowpipeRows.get(0).name);

        Ac trident = action(10L, Au.CAST,
            rune("Death rune", 560, -1L, -100L), rune("Chaos rune", 562, -1L, -50L),
            rune("Soul rune", 566, -1L, -100L));
        trident.note = "Measured charge spend \u00b7 Trident of the swamp";
        assertTrue("charge use stays out of Recent (owner 2026-09-29)", recents(trident).isEmpty());
    }

    private static List<Ca.Recent> recents(Ac transaction)
    {
        return LiveProbe.recent(Collections.singletonList(transaction), Dz.NONE);
    }

    private static Ca.Recent rowNamed(List<Ca.Recent> rows, String name)
    {
        for (Ca.Recent row : rows)
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
        List<Ac> casts = Arrays.asList(
            action(1L, Au.CAST, rune("Death rune", 560, -4L, -748L), rune("Blood rune", 565, -2L, -682L),
                rune("Water rune", 555, -6L, -30L)),
            action(2L, Au.CAST, rune("Death rune", 560, -4L, -748L), rune("Blood rune", 565, -2L, -682L),
                rune("Water rune", 555, -6L, -30L)));
        List<Ca.Recent> rows = LiveProbe.recent(casts, Dz.NONE);
        assertEquals("one row for the spell", 1, rows.size());
        assertEquals("Ice Barrage", rows.get(0).name);
        assertEquals("the spellbook icon", -328, rows.get(0).itemId);
    }

    /** Owner 2026-09-28: a whole potion drunk dose by dose is one Recent row, not one per sip. */
    @Test
    public void aWholePotionDrunkIsOneRecentRow()
    {
        List<Ac> sips = Arrays.asList(
            action(4L, Au.DRINK, new Ab(2442, "Super defence(4)", -1L, 3_364, -3_364L),
                new Ab(163, "Super defence(3)", 1L, 2_523, 2_523L)),
            action(3L, Au.DRINK, new Ab(163, "Super defence(3)", -1L, 2_523, -2_523L),
                new Ab(165, "Super defence(2)", 1L, 1_682, 1_682L)),
            action(2L, Au.DRINK, new Ab(165, "Super defence(2)", -1L, 1_682, -1_682L),
                new Ab(167, "Super defence(1)", 1L, 841, 841L)),
            action(1L, Au.DRINK, new Ab(167, "Super defence(1)", -1L, 841, -841L)));
        List<Ca.Recent> rows = LiveProbe.recent(sips, Dz.NONE);
        assertEquals("one row for the potion", 1, rows.size());
        assertEquals("Super defence", rows.get(0).name);
        assertEquals("every dose costs the same", -4L * 841L, rows.get(0).value);
        assertEquals("Drank · ×4 doses", LivePage.metaOf(rows.get(0)));
    }

    private static Ca.Recent recent(Ac transaction)
    {
        return LiveProbe.recent(Collections.singletonList(transaction), Dz.NONE).get(0);
    }

    private static Ac action(long at, Au kind, Ab... flows)
    {
        Ac transaction = transaction(at, flows);
        transaction.setActionKind(kind);
        return transaction;
    }

    private static Ac cast(long at, String name, Ab... flows)
    {
        Ac transaction = action(at, Au.CAST, flows);
        if (!name.isEmpty()) transaction.ahu(Bb.of(name));
        return transaction;
    }

    private static Ac transaction(long at, Ab... flows)
    {
        return new Ac(at, null, Ai.CONSUMPTION, Aj.GENERIC,
            "", "Vorkath", true, Arrays.asList(flows), Bd.CONFIRMED,
            "Positive action fixture", null);
    }

    private static Ab rune(String name, int id, long quantity, long value)
    {
        return new Ab(id, name, quantity, Math.toIntExact(Math.abs(value)), value);
    }
}
