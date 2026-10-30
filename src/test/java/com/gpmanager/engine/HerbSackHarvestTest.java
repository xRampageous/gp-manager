package com.gpmanager;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.gameval.ItemID;
import net.runelite.http.api.item.ItemPrice;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * An open herb sack takes herbs harvested from a patch or a herbiboar, and herbs picked up, without
 * the inventory changing. The game prints one SPAM line per herb (RuneLite Loot Tracker's herbiboar
 * handler and test, Dude Where's My Stuff, Farming Run Profit): that line books it, once, whichever
 * of it and a picked-up herb's despawn arrives first. An open seed box names the seeds it takes from a
 * drop or a pickpocket (Dude Where's My Stuff, GIM Hub, GP Per Hour), and an open coal bag takes mined
 * coal (OSRS Wiki: Coal bag), counted only when it did not reach the inventory.
 */
public class HerbSackHarvestTest
{
    private static final int RANARR = ItemID.UNIDENTIFIED_RANARR, SEED = ItemID.RANARR_SEED;
    private static final int FOX_FUR = ItemID.HUNTING_FENNECFOX_FUR, FOX_MEAT = ItemID.HUNTING_FENNECFOX_MEAT,
        LARUPIA_MEAT = ItemID.HUNTING_LARUPIA_MEAT, LARUPIA_FUR = ItemID.HUNTING_FUR_JAGUAR_PERFECT;
    private static final Map<Integer, String> NAMES = Map.of(RANARR, "Grimy ranarr weed", SEED, "Ranarr seed",
        ItemID.COAL, "Coal", ItemID.UNCUT_RUBY, "Uncut ruby", FOX_FUR, "Fox fur", FOX_MEAT, "Raw pyre fox",
        LARUPIA_MEAT, "Raw larupia", LARUPIA_FUR, "Larupia fur");
    private static final String LINE = "You put the Grimy ranarr weed herb into your herb sack.";

    private final int[] tick = {10};

    private ReviewFixesTest.Plugin plugin(int... carried)
    {
        Engine engine = ReviewFixesTest.engine();
        ReviewFixesTest.Plugin p = ReviewFixesTest.plugin(engine, tick);
        Item[] items = new Item[carried.length];
        for (int i = 0; i < carried.length; i++) items[i] = new Item(carried[i], 1);
        ItemContainer pack = ReviewFixesTest.proxy(ItemContainer.class, Map.of("getItems", items));
        Map<String, Object> client = new HashMap<>();
        client.put("getTickCount", (java.util.function.IntSupplier) () -> tick[0]);
        client.put("getGameState", GameState.LOGGED_IN);
        client.put("getItemContainer", pack);
        client.put("getLocalPlayer", ReviewFixesTest.proxy(Player.class,
            Collections.singletonMap("getWorldLocation", new WorldPoint(100, 100, 0))));
        p.client = ReviewFixesTest.proxy(Client.class, client);
        p.containers = new ContainerSnapshotFactory(p.client, null)
        {
            @Override ContainerSnapshot capture(boolean equipment, boolean pouch) { return FixtureReads.empty(); }
        };
        p.lookup = new ItemLookup(null)
        {
            @Override ItemComposition composition(int id)
            {
                return ReviewFixesTest.proxy(ItemComposition.class, Map.of("getName", NAMES.getOrDefault(id, "Item " + id)));
            }

            /** RuneLite's search: every priced item whose name contains the query. */
            @Override List<ItemPrice> find(String name)
            {
                List<ItemPrice> found = new java.util.ArrayList<>();
                NAMES.forEach((id, item) -> {
                    if (!item.toLowerCase().contains(name.toLowerCase())) return;
                    ItemPrice price = new ItemPrice();
                    price.setId(id);
                    price.setName(item);
                    found.add(price);
                });
                return found;
            }
        };
        return p;
    }

    private static void chat(ReviewFixesTest.Plugin p, String text)
    {
        ChatMessage event = new ChatMessage();
        event.setType(ChatMessageType.SPAM);
        event.setMessage(text);
        p.onChatMessage(event);
    }

    /** The player's own herb pile on the clicked tile disappears as their Take succeeds. */
    private void pickUp(ReviewFixesTest.Plugin p, int item)
    {
        p.engine.markLoot(Collections.singletonMap(item, 1L), 100, "Loot from Chaos druid", "Chaos druid");
        Observation click = ReviewFixesTest.take(p);
        click.item = item;
        click.at = tick[0];
        click.x = 10;
        click.y = 10;
        Map<String, Object> pile = new HashMap<>();
        pile.put("getId", item);
        pile.put("getQuantity", 1);
        pile.put("getOwnership", net.runelite.api.TileItem.OWNERSHIP_SELF);
        p.onItemDespawned(new ItemDespawned(ReviewFollowupTest.tile(10, 10),
            ReviewFixesTest.proxy(net.runelite.api.TileItem.class, pile)));
    }

    @Test public void bothWordingsNameTheHerb()
    {
        for (String line : new String[] {LINE, "You put the Grimy kwuarm into your herb sack.",
            "You put the Grimy torstol herb into your open herb sack."})
        {
            java.util.regex.Matcher m = GameData.pattern("bag").matcher(line);
            assertTrue(line, m.matches());
            assertTrue(line, m.group(2).startsWith("Grimy ") && !m.group(2).endsWith(" herb"));
        }
        for (String other : new String[] {"You put the Grimy guam leaf into the vial of water.",
            "You add the herbs to your sack.", "Your herb sack is too full to hold the Grimy ranarr weed herb.",
            "You look in your herb sack and see:"})
            assertTrue(other, !GameData.pattern("bag").matcher(other).matches());
    }

    @Test public void eachHarvestedHerbBooksOnce()
    {
        for (int sack : new int[] {ItemID.SLAYER_HERB_SACK_OPEN, ItemID.SLAYER_HERB_SACK_SILK_OPEN})
        {
            ReviewFixesTest.Plugin p = plugin(sack);
            chat(p, LINE);
            chat(p, LINE);
            assertEquals("two picks, two herbs", 2L, ReviewFixesTest.qty(p, Inbox.BAG_GAIN));
        }
    }

    @Test public void aClosedSackTakesNothing()
    {
        ReviewFixesTest.Plugin p = plugin(ItemID.SLAYER_HERB_SACK);
        p.containers = new ContainerSnapshotFactory(p.client, null);
        chat(p, LINE);
        assertEquals(0L, ReviewFixesTest.qty(p, Inbox.BAG_GAIN));
    }

    @Test public void aPickedUpHerbBooksOnceInEitherOrder()
    {
        ReviewFixesTest.Plugin lineFirst = plugin(ItemID.SLAYER_HERB_SACK_OPEN);
        chat(lineFirst, LINE);
        pickUp(lineFirst, RANARR);
        assertEquals(1L, ReviewFixesTest.qty(lineFirst, Inbox.BAG_GAIN));

        ReviewFixesTest.Plugin despawnFirst = plugin(ItemID.SLAYER_HERB_SACK_OPEN);
        pickUp(despawnFirst, RANARR);
        assertEquals("the sack's own line books it", 0L, ReviewFixesTest.qty(despawnFirst, Inbox.BAG_GAIN));
        chat(despawnFirst, LINE);
        assertEquals(1L, ReviewFixesTest.qty(despawnFirst, Inbox.BAG_GAIN));
    }

    @Test public void otherOpenBagsStillBookTheirPickupFromTheDespawn()
    {
        ReviewFixesTest.Plugin looting = plugin(ItemID.LOOTING_BAG_OPEN);
        pickUp(looting, RANARR);
        assertEquals("no herb sack: the looting bag took it, and no line names it", 1L,
            ReviewFixesTest.qty(looting, Inbox.BAG_GAIN));

        ReviewFixesTest.Plugin gems = plugin(ItemID.GEM_BAG_OPEN);
        pickUp(gems, ItemID.UNCUT_RUBY);
        assertEquals(1L, ReviewFixesTest.qty(gems, Inbox.BAG_GAIN));
    }

    @Test public void seedsStolenIntoAnOpenSeedBoxCount()
    {
        ReviewFixesTest.Plugin p = plugin(ItemID.SEED_BOX_OPEN);
        chat(p, "You put the stolen Ranarr seed into your seed box.");
        chat(p, "The following stolen loot gets added to your seed box: Ranarr seed x 2.");
        assertEquals("one single and one batch steal", -3L, p.engine.baseline.quantity(SEED));
    }

    @Test public void aPickedUpSeedCountsOnceInEitherOrder()
    {
        ReviewFixesTest.Plugin lineFirst = plugin(ItemID.SEED_BOX_OPEN);
        chat(lineFirst, "You put 1 x Ranarr seed straight into your open seed box.");
        pickUp(lineFirst, SEED);
        assertEquals(-1L, lineFirst.engine.baseline.quantity(SEED));
        assertEquals(0L, ReviewFixesTest.qty(lineFirst, Inbox.BAG_GAIN));

        ReviewFixesTest.Plugin despawnFirst = plugin(ItemID.SEED_BOX_OPEN);
        pickUp(despawnFirst, SEED);
        chat(despawnFirst, "You put 1 x Ranarr seed straight into your open seed box.");
        assertEquals(-1L, despawnFirst.engine.baseline.quantity(SEED));
        assertEquals(0L, ReviewFixesTest.qty(despawnFirst, Inbox.BAG_GAIN));
    }

    @Test public void minedCoalCountsOnlyWhenTheOpenBagTookIt()
    {
        ReviewFixesTest.Plugin bagged = plugin(ItemID.COAL_BAG_OPEN);
        chat(bagged, "You manage to mine some coal.");
        tick[0]++;
        bagged.bagSettle();
        assertEquals("the bag took it", -1L, bagged.engine.baseline.quantity(ItemID.COAL));

        ReviewFixesTest.Plugin carried = plugin(ItemID.COAL_BAG_OPEN, ItemID.COAL);
        chat(carried, "You manage to mine some coal.");
        tick[0]++;
        carried.bagSettle();
        assertEquals("the pack took it; its own settle books it", 0L, carried.engine.baseline.quantity(ItemID.COAL));
    }

    /**
     * An open shade coffin takes only shade remains the player picks up (OSRS Wiki: Bronze coffin,
     * Gold coffin), and prints no line for it: the pickup's despawn books it, for every coffin.
     */
    @Test public void anOpenCoffinBooksPickedUpRemainsFromTheDespawn()
    {
        for (int coffin : new int[] {ItemID.SHADES_COFFIN_BRONZE_OPEN, ItemID.SHADES_COFFIN_STEEL_OPEN,
            ItemID.SHADES_COFFIN_BLACK_OPEN, ItemID.SHADES_COFFIN_SILVER_OPEN, ItemID.SHADES_COFFIN_GOLD_OPEN})
        {
            ReviewFixesTest.Plugin p = plugin(coffin);
            p.containers = new ContainerSnapshotFactory(p.client, null)
            {
                @Override ContainerSnapshot capture(boolean equipment, boolean pouch) { return FixtureReads.empty(); }
            };
            pickUp(p, ItemID.SHADE_BONES5);
            assertEquals(1L, ReviewFixesTest.qty(p, Inbox.BAG_GAIN));
            tick[0]++;
            p.bagSettle();
            assertEquals("Fiyr remains the coffin took count once", -1L, p.engine.baseline.quantity(ItemID.SHADE_BONES5));
        }
    }

    /**
     * Open meat and fur pouches take a catch's meat and fur without the pack changing; the catch line
     * names the creature (TicTac7x Item Charges), the wiki its always-dropped items. A pitfall cat's
     * fur may be tatty, so only its meat counts.
     */
    @Test public void aCatchIntoOpenPouchesCountsItsFurAndMeatOnce()
    {
        ReviewFixesTest.Plugin both = plugin(ItemID.HG_MEATPOUCH_LARGE_OPEN, ItemID.HG_FURPOUCH_LARGE_OPEN);
        chat(both, "You've caught a pyre fox.");
        tick[0]++;
        both.bagSettle();
        assertEquals(-1L, both.engine.baseline.quantity(FOX_FUR));
        assertEquals(-1L, both.engine.baseline.quantity(FOX_MEAT));

        // A full meat pouch sent the meat to the pack; no fur pouch is open, so the fur is the pack's too.
        ReviewFixesTest.Plugin meatInPack = plugin(ItemID.HG_MEATPOUCH_SMALL_OPEN);
        chat(meatInPack, "You've caught a pyre fox.");
        tick[0]++;
        meatInPack.client = ReviewFixesTest.proxy(net.runelite.api.Client.class, Map.of(
            "getTickCount", (java.util.function.IntSupplier) () -> tick[0],
            "getItemContainer", ReviewFixesTest.proxy(ItemContainer.class, Map.of("getItems",
                new Item[] {new Item(ItemID.HG_MEATPOUCH_SMALL_OPEN, 1), new Item(FOX_MEAT, 1), new Item(FOX_FUR, 1)}))));
        meatInPack.bagSettle();
        assertEquals(0L, meatInPack.engine.baseline.quantity(FOX_MEAT));
        assertEquals(0L, meatInPack.engine.baseline.quantity(FOX_FUR));

        ReviewFixesTest.Plugin closed = plugin(ItemID.HG_MEATPOUCH_LARGE, ItemID.HG_FURPOUCH_LARGE);
        chat(closed, "You've caught a pyre fox.");
        assertEquals(0L, ReviewFixesTest.qty(closed, Inbox.BAG_GAIN));

        ReviewFixesTest.Plugin cat = plugin(ItemID.HG_MEATPOUCH_LARGE_OPEN, ItemID.HG_FURPOUCH_LARGE_OPEN);
        chat(cat, "You've caught a spined larupia!");
        tick[0]++;
        cat.bagSettle();
        assertEquals(-1L, cat.engine.baseline.quantity(LARUPIA_MEAT));
        assertEquals("its fur may be tatty", 0L, cat.engine.baseline.quantity(LARUPIA_FUR));
    }

    @Test public void gemLinesStillNameTheUncutGem()
    {
        ReviewFixesTest.Plugin p = plugin(ItemID.GEM_BAG_OPEN);
        chat(p, "You put the stolen Uncut ruby into your gem bag.");
        assertEquals(-1L, p.engine.baseline.quantity(ItemID.UNCUT_RUBY));
    }
    @Test public void aFullPouchMeatAlreadyInThePackCountsOnce()
    {
        // The inventory callback is visible before this catch message; the meat pouch is full.
        ReviewFixesTest.Plugin p = plugin(ItemID.HG_MEATPOUCH_SMALL_OPEN, FOX_MEAT);
        chat(p, "You've caught a pyre fox.");
        tick[0]++;
        p.bagSettle();
        p.engine.markDirty();
        ContainerSnapshot held = new ContainerSnapshot(Map.of(FOX_MEAT, 1L));
        p.engine.processIfDirty(held, ReviewFixesTest.T0 + 600, null);
        p.engine.processIfDirty(held, ReviewFixesTest.T0 + 1200, null);
        assertEquals("one physical meat at 100 GP must count once", 100L,
                p.engine.metrics(ReviewFixesTest.T0 + 1200).income);
    }

    @Test public void aMeatPickupBesideAHunterPouchStaysAMeasuredPickup()
    {
        ReviewFixesTest.Plugin p = plugin(ItemID.HG_MEATPOUCH_LARGE_OPEN, ItemID.LOOTING_BAG_OPEN);
        pickUp(p, FOX_MEAT);
        assertEquals("a ground pickup has no hunter-catch message", 1L,
                ReviewFixesTest.qty(p, Inbox.BAG_GAIN));
    }

    @Test public void aHunterCatchNeverBecomesLootingBagStock()
    {
        ReviewFixesTest.Plugin p = plugin(ItemID.HG_MEATPOUCH_LARGE_OPEN, ItemID.LOOTING_BAG_OPEN);
        ItemContainer pack = ReviewFixesTest.proxy(ItemContainer.class, Map.of("getItems",
                new Item[] {new Item(ItemID.HG_MEATPOUCH_LARGE_OPEN, 1), new Item(ItemID.LOOTING_BAG_OPEN, 1)},
                "contains", true));
        p.client = ReviewFixesTest.proxy(Client.class, Map.of(
                "getTickCount", (java.util.function.IntSupplier) () -> tick[0], "getItemContainer", pack));
        chat(p, "You've caught a pyre fox.");
        tick[0]++;
        p.bagSettle();
        assertTrue("hunter meat went into the meat pouch, not the looting bag", p.engine.bag.isEmpty());
    }

}
