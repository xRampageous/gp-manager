package com.gpmanager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static com.gpmanager.ReviewFixesTest.BONES;
import static com.gpmanager.ReviewFixesTest.T0;
import static com.gpmanager.ReviewFixesTest.engine;
import static com.gpmanager.ReviewFixesTest.plugin;
import static com.gpmanager.ReviewFixesTest.proxy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Codex research findings on 1.2 (2026-10-06), kept as regression tests without reflection. */
public class CodexResearchFixesTest
{
    /** Finding 1: another player's or a public pile vanishing is never our bag pickup. */
    @Test
    public void onlyOurOwnPileCanHaveGoneIntoOurBag()
    {
        assertEquals("a public pile", 0L, despawn(TileItem.OWNERSHIP_NONE));
        assertEquals("another player's pile", 0L, despawn(TileItem.OWNERSHIP_OTHER));
        assertEquals("our own pile is bounded by the two proven bones", 2L, despawn(TileItem.OWNERSHIP_SELF));
    }

    static long despawn(int ownership)
    {
        Engine engine = engine();
        engine.markLoot(Map.of(BONES, 2L), 50, "Loot from Guard", "Guard");
        ReviewFixesTest.Plugin p = plugin(engine, new int[]{11});
        ReviewFixesTest.take(p).item = BONES;
        ReviewFixesTest.take(p).at = 10;
        ReviewFixesTest.take(p).x = 10;
        ReviewFixesTest.take(p).y = 10;
        Map<String, Object> tileValues = new HashMap<>();
        tileValues.put("getSceneLocation", new Point(10, 10));
        tileValues.put("getWorldLocation", new WorldPoint(100, 100, 0));
        Map<String, Object> itemValues = new HashMap<>();
        itemValues.put("getId", BONES);
        itemValues.put("getQuantity", 3);
        itemValues.put("getOwnership", ownership);
        p.onItemDespawned(new ItemDespawned(proxy(Tile.class, tileValues), proxy(TileItem.class, itemValues)));
        return ReviewFixesTest.qty(p, Inbox.BAG_GAIN);
    }

    /** Finding 4: a reclaim fee still waiting its two ticks never reaches the next account. */
    @Test
    public void aWaitingBankFeeIsDroppedAtTheAccountFence()
    {
        ReviewFixesTest.Plugin p = plugin(engine(), new int[]{11});
        p.engine.inbox.put(Inbox.FEE, -1, 5_964L, 10, null);
        p.ready = false;
        p.onGameTick(new GameTick());
        assertEquals(0L, ReviewFixesTest.qty(p, Inbox.FEE));
    }

    /** Finding 5: a Check that reads the same as the last one is a compatible "nothing used". */
    @Test
    public void anUnchangedCheckRemovesTheOverestimate()
    {
        Engine e = engine();
        String target = "same-staff";
        e.observeCheck(ChargeCheck.parse("Your Trident of the seas has 100 charges."), target, T0);
        e.bookEstimatedCharge(target, ChargeCheck.ChargedItem.TRIDENT_SEAS.getDisplayName(), ActionKind.CAST,
            List.of(new Flow(ItemID.DEATHRUNE, "Death rune", -1, 100, -100, PriceSource.GRAND_EXCHANGE)), T0 + 1_000L);
        assertTrue(e.getMetrics(T0 + 1_500L).costs > 0L);
        ReviewFixesTest.Plugin p = plugin(e, new int[]{100});
        p.charges.startCheck(ChargeCheck.ChargedItem.TRIDENT_SEAS, target, 100, 2);
        assertTrue(p.charges.observeCheck("Your Trident of the seas has 100 charges.", T0 + 2_000L));
        assertEquals("the matching Check proves no charge was used", 0L, e.getMetrics(T0 + 5_000L).costs);
    }

    /** Finding 6: checking one staff keeps another same-kind staff's estimate credit. */
    @Test
    public void checkingOneStaffKeepsTheOtherStaffsEstimates()
    {
        Engine e = engine();
        String family = ChargeCheck.ChargedItem.TRIDENT_SEAS.getDisplayName();
        List<Flow> use = List.of(new Flow(ItemID.DEATHRUNE, "Death rune", -1, 100, -100, PriceSource.GRAND_EXCHANGE));
        e.bookEstimatedCharge("staff-a", family, ActionKind.CAST, use, T0 + 1_000L);
        e.bookEstimatedCharge("staff-b", family, ActionKind.CAST, use, T0 + 1_001L);
        e.clearPendingEstimates("staff-a", java.util.Collections.emptySet());
        String session = e.getActiveSession().getId();
        assertTrue(e.chargeEstimateJournal.receiptsFor(session, "staff-a").isEmpty());
        assertFalse("staff B keeps its own credit", e.chargeEstimateJournal.receiptsFor(session, "staff-b").isEmpty());
    }

    /** Conditional trace: a split catch books only the fish missing from the inventory. */
    @Test
    public void aSplitCatchBooksOnlyWhatIsMissingFromTheInventory()
    {
        Engine e = engine();
        ReviewFixesTest.Plugin p = plugin(e, new int[]{11});
        p.snapshotFactory = new ContainerSnapshotFactory(p.client, null)
        {
            @Override
            ContainerSnapshot capture(boolean equipment, boolean pouch)
            {
                return new ContainerSnapshot(Map.of(335, 1L));
            }
        };
        ReviewFixesTest.take(p).item = 335;
        ReviewFixesTest.take(p).held = 0;
        ReviewFixesTest.bag(p).at = 10;
        ReviewFixesTest.bag(p).qty = 2;
        p.bagSettle();
        assertEquals("one in the pack, one in the barrel", -1L, e.baseline.quantityOf(335));
    }
}
