package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.http.api.item.ItemPrice;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Deep sea trawling (2026-11-02; Deep Sea Trawling 5841a0ab, Trawling Plus 20e8b267, Wiki "Deep sea
 * trawling"): with a net lowered each catch line puts fish in the nets, never the pack, so it counts as
 * caught, as an open herb sack's catch does. Fish emptied into the cargo hold add nothing more, and fish
 * taken into the pack only move. Every item is worth 100 GP.
 */
public class TrawlNetTest
{
    private static final long T0 = ReviewFixesTest.T0;
    private static final int KRILL = ItemID.RAW_GIANT_KRILL, HADDOCK = ItemID.RAW_HADDOCK;
    private static final Map<Integer, String> NAMES = Map.of(KRILL, "Raw giant krill", HADDOCK, "Raw haddock",
        ItemID.GIANT_KRILL, "Giant krill");
    private static final String HOLD = "You empty the net into the cargo hold.";
    private static final String PARTIAL =
        "You empty the net into the cargo hold, but there was not enough space in there to do so entirely.";

    private LongSupplier previousClock;

    @Before public void fixedClock()
    {
        previousClock = SafeMath.clock;
        SafeMath.clock = () -> T0;
    }

    @After public void restoreClock()
    {
        SafeMath.clock = previousClock;
    }

    static final class Fixture
    {
        final Map<Integer, Long> pack = new HashMap<>();
        final List<Integer> carried = new ArrayList<>();
        final Map<Integer, Integer> varbits = new HashMap<>(Map.of(VarbitID.SAILING_PLAYER_IS_ON_PLAYER_BOAT, 1,
            VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_0_DEPTH, 2));
        int tick = 100;
        final Engine engine = HunterPouchReceiptTest.Fixture.engine();
        final ReviewFixesTest.Plugin plugin = ReviewFixesTest.plugin(engine, new int[] {100});
        final ItemContainer inventory = LootingBagMovementTest.proxy(ItemContainer.class, (method, args) -> {
            if (method.equals("contains")) return carried.contains((int) args[0]) || pack.containsKey((int) args[0]);
            if (method.equals("getItems")) {
                List<Item> items = new ArrayList<>();
                carried.forEach(id -> items.add(new Item(id, 1)));
                pack.forEach((id, quantity) -> items.add(new Item(id, quantity.intValue())));
                return items.toArray(new Item[0]);
            }
            return null;
        });

        Fixture(Integer... containers)
        {
            java.util.Collections.addAll(carried, containers);
            Player local = LootingBagMovementTest.proxy(Player.class,
                (method, args) -> method.equals("getWorldLocation") ? new WorldPoint(100, 100, 0) : null);
            plugin.client = LootingBagMovementTest.proxy(Client.class, (method, args) -> {
                switch (method) {
                    case "getItemContainer": return (int) args[0] == InventoryID.INV ? inventory : null;
                    case "getTickCount": return tick;
                    case "getGameState": return GameState.LOGGED_IN;
                    case "getLocalPlayer": return local;
                    case "getVarbitValue": return varbits.getOrDefault((int) args[0], 0);
                    default: return null;
                }
            });
            plugin.containers = new ContainerSnapshotFactory(plugin.client, null);
            plugin.targets = GpManagerPluginProbe.tracker(plugin.client, engine, plugin.config);
            plugin.charges = new ChargeIntake(plugin.client, null, plugin.config, engine, null);
            plugin.activity = new ActivityDetector(plugin.config, engine);
            plugin.idle = new CharacterIdleTracker(null, new CharacterIdleModel());
            plugin.lookup = new ItemLookup(null) {
                @Override ItemComposition composition(int id)
                {
                    return ReviewFixesTest.proxy(ItemComposition.class, Map.of("getName", NAMES.getOrDefault(id, "Item " + id)));
                }

                @Override List<ItemPrice> find(String query)
                {
                    List<ItemPrice> found = new ArrayList<>();
                    NAMES.forEach((id, name) -> {
                        if (!name.toLowerCase().contains(query.toLowerCase())) return;
                        ItemPrice price = new ItemPrice();
                        price.setId(id);
                        price.setName(name);
                        found.add(price);
                    });
                    return found;
                }
            };
            engine.setBaseline(capture());
            plugin.lastPack = plugin.counts(inventory);
        }

        ContainerSnapshot capture()
        {
            return plugin.containers.capture(false, false);
        }

        void chat(String line)
        {
            ChatMessage message = new ChatMessage();
            message.setType(ChatMessageType.GAMEMESSAGE);
            message.setMessage(line);
            plugin.onChatMessage(message);
        }

        void ticks(int count)
        {
            for (int i = 0; i < count; i++) {
                plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inventory));
                plugin.lastPack = plugin.counts(inventory);
                plugin.bagSettle();
                engine.markDirty();
                engine.processIfDirty(capture(), T0 + 600L * tick, null);
                engine.processIfDirty(capture(), T0 + 600L * tick + 1, null);
                tick++;
            }
        }

        long income()
        {
            return engine.metrics(T0 + 600L * (tick + 10)).income;
        }

        List<Transaction> rows()
        {
            return engine.session().transactions();
        }
    }

    @Test public void eachCatchCountsAsCaughtAndEmptyingTheNetsIntoTheHoldAddsNothing()
    {
        Fixture f = new Fixture();
        f.chat("You catch four giant krill!");
        f.chat("Trawler's trust: You catch an additional fish.");
        f.chat("Jolly Jim catches a giant krill!");
        f.ticks(3);
        assertEquals("five raw giant krill, the keg's fish already in the four", 500, f.income());
        f.chat(HOLD);
        f.chat("You empty the net into the cargo hold, but there was not enough space in there to do so entirely.");
        f.ticks(3);
        assertEquals("they were already counted, whatever the hold took", 500, f.income());
        assertTrue(f.rows().stream().allMatch(row -> row.type() == TransactionType.GAIN && row.counts()));
    }

    @Test public void fishTakenIntoThePackOnlyMove()
    {
        Fixture f = new Fixture();
        f.chat("You catch two haddock!");
        f.chat("You catch a haddock!");
        f.ticks(3);
        f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Collect-from", MenuAction.GAME_OBJECT_FIRST_OPTION,
            null, "<col=ffff>Trawling net"));
        f.pack.put(HADDOCK, 3L);
        f.chat("You take all of the fish from the net.");
        f.ticks(3);
        assertEquals("three haddock, counted once when caught", 300, f.income());
        assertTrue("never a Grand Exchange collection: " + f.rows(),
            f.rows().stream().noneMatch(row -> row.type() == TransactionType.TRADE));
    }

    @Test public void raisedNetsAndOrdinaryFishingAreNotTrawlCatches()
    {
        Fixture f = new Fixture();
        f.chat("You catch a shark.");
        f.varbits.put(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_0_DEPTH, 0);
        f.chat("You catch four giant krill!");
        f.varbits.put(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_1_DEPTH, 1);
        f.chat("Jolly Jim catches two giant krill!");
        f.ticks(3);
        assertEquals("only the catch with the second net lowered", 200, f.income());
    }

    @Test public void anOpenFishBarrelDoesNotAlsoTakeATrawlCatch()
    {
        Fixture f = new Fixture(ItemID.FISH_BARREL_OPEN);
        f.chat("You catch a haddock!");
        f.ticks(3);
        assertEquals("one haddock in the nets, not a second in the barrel", 100, f.income());
    }
    @Test public void aCatchStillSettlingIsNotSwallowedByTheCollection()
    {
        Fixture f = new Fixture();
        f.chat("You catch two haddock!");
        collect(f, 2);
        f.ticks(3);
        assertEquals("two fish, counted once", 200, f.income());
    }

    @Test public void aCrewCatchDuringTheCollectionStillCounts()
    {
        Fixture f = new Fixture();
        f.chat("You catch two haddock!");
        f.ticks(3);
        assertEquals(200, f.income());
        collect(f, 2);
        f.chat("Jolly Jim catches a haddock!");
        f.ticks(3);
        assertEquals("two collected, one new", 300, f.income());
        f.chat("You catch a haddock!");
        collect(f, 4);
        f.ticks(3);
        assertEquals("the rest taken later, each fish once", 400, f.income());
    }

    @Test public void onlyTheFishTheNetsHeldAreAHandover()
    {
        Fixture f = new Fixture();
        f.chat("You catch a haddock!");
        f.ticks(3);
        collect(f, 3);
        f.ticks(3);
        assertEquals("one fish was in the nets; the other two came from elsewhere", 300, f.income());
    }

    @Test public void theTakeLineCanArriveBeforeThePackInTheSameTick()
    {
        Fixture f = new Fixture();
        f.chat("You catch two haddock!");
        f.ticks(3);
        f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Collect-from", MenuAction.GAME_OBJECT_FIRST_OPTION,
            null, "<col=ffff>Trawling net"));
        f.chat("You take all of the fish from the net.");
        f.chat("Jolly Jim catches a haddock!");
        f.pack.put(HADDOCK, 2L);
        f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, f.inventory));
        f.ticks(3);
        assertEquals("the two taken only moved; the crew catch counts", 300, f.income());
    }

    @Test public void aNetEmptiedIntoTheHoldCannotExplainLaterGains()
    {
        Fixture f = new Fixture();
        f.chat("You catch three haddock!");
        f.ticks(3);
        f.chat(HOLD);
        f.ticks(3);
        f.chat("You catch a haddock!");
        f.ticks(3);
        collect(f, 3);
        f.ticks(3);
        assertEquals("three earlier catches, one new catch and two unrelated gains", 600, f.income());
    }

    @Test public void aPartialEmptyNamesNoCountSoTheTallyStays()
    {
        Fixture f = new Fixture();
        f.chat("You catch three haddock!");
        f.ticks(3);
        f.chat("You empty the net into the cargo hold, but there was not enough space in there to do so entirely.");
        f.ticks(3);
        collect(f, 3);
        f.ticks(3);
        assertEquals("the nets may still hold all three: never counted twice", 300, f.income());
    }

    @Test public void leavingTheBoatLoggingOutOrSwitchingOwnerDiscardsTheTally()
    {
        Fixture f = new Fixture();
        f.chat("You catch three haddock!");
        f.ticks(3);
        net.runelite.api.events.VarbitChanged off = new net.runelite.api.events.VarbitChanged();
        off.setVarbitId(VarbitID.SAILING_PLAYER_IS_ON_PLAYER_BOAT);
        off.setValue(0);
        f.plugin.onVarbitChanged(off);
        assertTrue("disembarking discards uncollected fish", f.plugin.net.isEmpty());

        f.plugin.offers = new OfferSlots();
        f.chat("You catch a haddock!");
        net.runelite.api.events.GameStateChanged out = new net.runelite.api.events.GameStateChanged();
        out.setGameState(GameState.LOGIN_SCREEN);
        f.plugin.onGameStateChanged(out);
        assertTrue("logging out moves net fish to the hold or discards them", f.plugin.net.isEmpty());

        f.chat("You catch a haddock!");
        f.plugin.beginOwnerSwitch();
        assertTrue("a tally never crosses owners", f.plugin.net.isEmpty());
        assertEquals(-1, f.plugin.takeAt);
    }

    @Test public void aTakeAllAfterAPartialEmptyRetiresTheUnknownRemainder()
    {
        Fixture f = new Fixture();
        f.chat("You catch three haddock!");
        f.ticks(3);
        f.chat(PARTIAL);
        f.ticks(3);
        collect(f, 1);
        f.ticks(3);
        assertEquals("two went to the hold, one was taken: all counted once", 300, f.income());
        f.chat("You catch a haddock!");
        f.ticks(3);
        collect(f, 4);
        f.ticks(3);
        assertEquals("the take-all proved the nets empty, so two of these came from elsewhere", 600, f.income());
    }

    @Test public void aTakeAllRetiresEachFishTypeAndRepeatedCallbacksMoveNothingTwice()
    {
        Fixture f = new Fixture();
        f.chat("You catch two haddock!");
        f.chat("You catch a giant krill!");
        f.ticks(3);
        f.chat(PARTIAL);
        f.ticks(3);
        collect(f, 1);
        f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, f.inventory));
        f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, f.inventory));
        f.ticks(3);
        assertEquals("three catches, the haddock taken only moved", 300, f.income());
        f.chat("You catch a haddock!");
        f.ticks(3);
        f.pack.put(KRILL, 1L);
        collect(f, 2);
        f.ticks(3);
        assertEquals("the new haddock only moved; no krill can still be in emptied nets", 500, f.income());
    }

    @Test public void anotherTakeLineKeepsWhatTheNetsMayStillHold()
    {
        Fixture f = new Fixture();
        f.chat("You catch three haddock!");
        f.ticks(3);
        f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Collect-from", MenuAction.GAME_OBJECT_FIRST_OPTION,
            null, "<col=ffff>Trawling net"));
        f.pack.put(HADDOCK, 1L);
        f.chat("You take some of the fish from the net.");
        f.ticks(3);
        collect(f, 3);
        f.ticks(3);
        assertEquals("not a take-all: the other two stay in the nets and move later", 300, f.income());
    }

    @Test public void aSeparateGroundTakeCannotUseTheCompletedCollectionsUpperBound()
    {
        Fixture f = new Fixture();
        f.chat("You catch three haddock!");
        f.ticks(3);
        f.chat(PARTIAL);
        f.ticks(3);
        collect(f, 1);
        f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, f.inventory));
        groundTake(f, HADDOCK);
        f.pack.put(HADDOCK, 3L);
        f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, f.inventory));
        f.ticks(3);
        assertEquals("three caught fish plus two independently picked up fish", 500, f.income());
    }

    @Test public void aGroundTakeEndsOnlyItsItemsHandoverWhileOtherFishCanStillArrive()
    {
        Fixture f = new Fixture();
        f.chat("You catch three haddock!");
        f.chat("You catch a giant krill!");
        f.ticks(3);
        f.chat(PARTIAL);
        f.ticks(3);
        collect(f, 1);
        f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, f.inventory));
        groundTake(f, HADDOCK);
        f.pack.put(HADDOCK, 3L);
        f.pack.put(KRILL, 1L);
        f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, f.inventory));
        f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, f.inventory));
        f.ticks(3);
        assertEquals("four catches plus two ground fish; the late krill only moved", 600, f.income());
    }

    @Test public void aGroundTakeSamplesFishThatArrivedBeforeTheirInventoryCallback()
    {
        Fixture f = new Fixture();
        f.chat("You catch three haddock!");
        f.ticks(3);
        f.chat(PARTIAL);
        f.ticks(3);
        f.chat("You take all of the fish from the net.");
        f.pack.put(HADDOCK, 1L);
        groundTake(f, HADDOCK);
        f.pack.put(HADDOCK, 3L);
        f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, f.inventory));
        f.ticks(3);
        assertEquals("the collected fish only moved, even with its callback delayed", 500, f.income());
    }

    @Test public void aCrewCatchDuringACargoDepositHasItsOwnIncomeReceipt()
    {
        Fixture f = new Fixture();
        f.pack.put(ItemID.LOGS, 1L);
        f.engine.setBaseline(f.capture());
        f.plugin.lastPack = new HashMap<>(f.pack);
        f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Deposit-held", MenuAction.GAME_OBJECT_SECOND_OPTION,
            null, "Cargo hold"));
        f.pack.remove(ItemID.LOGS);
        f.chat("Jolly Jim catches a haddock!");
        f.ticks(3);
        assertEquals("the crew catch counts beside the neutral log deposit", 100, f.income());
        assertEquals("a storage move is no cost", 100, f.engine.metrics(T0).net);
        collect(f, 1);
        f.ticks(3);
        assertEquals("collecting the already booked fish adds nothing", 100, f.income());
    }

    @Test public void aCatchDuringManualPauseIsNotBookedLaterOnCollection()
    {
        Fixture f = new Fixture();
        f.engine.session().pause(T0, PauseReason.MANUAL);
        f.chat("You catch a haddock!");
        f.ticks(3);
        assertEquals(0, f.income());
        f.engine.session().resume(T0 + 600);
        collect(f, 1);
        f.ticks(3);
        assertEquals("the pause-window catch is not catch-up income", 0, f.income());
    }

    @Test public void anExactCatchResumesIdleAndSchedulesItsReceiptForSaving()
    {
        Fixture f = new Fixture();
        f.engine.pauseIdle(T0);
        f.chat("Jolly Jim catches a haddock!");
        assertTrue("the catch wakes Idle", f.engine.live());
        assertEquals("the exact catch needs no inventory delta", 100, f.income());
        assertEquals("the actual pack baseline stays unchanged", 0, f.engine.baseline.quantity(HADDOCK));
        assertTrue("the receipt uses the coalesced save path",
            f.plugin.saveTicks >= GpManagerPlugin.SAVE_INTERVAL_TICKS - 10);
    }

    @Test public void aFencedOrPrimingCatchCannotCreateIncome()
    {
        Fixture fenced = new Fixture();
        fenced.plugin.ready = false;
        fenced.chat("Jolly Jim catches a haddock!");
        assertEquals(0, fenced.income());
        assertTrue("fenced chat does not become another owner's net tally", fenced.plugin.net.isEmpty());

        Fixture priming = new Fixture();
        priming.engine.warming = true;
        priming.chat("Jolly Jim catches a haddock!");
        assertEquals(0, priming.income());
        priming.engine.setBaseline(priming.capture());
        collect(priming, 1);
        priming.ticks(3);
        assertEquals("collecting a priming-window catch is no catch-up income", 0, priming.income());
    }

    private static void groundTake(Fixture f, int item)
    {
        MenuEntry entry = LootingBagMovementTest.proxy(MenuEntry.class, (method, args) -> {
            switch (method) {
                case "getOption": return "Take";
                case "getTarget": return NAMES.get(item);
                case "getType": return MenuAction.GROUND_ITEM_THIRD_OPTION;
                case "getIdentifier": return item;
                case "getItemId": return -1;
                case "getParam0": case "getParam1": return 0;
                case "isItemOp": return false;
                default: return null;
            }
        });
        f.plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
    }

    /** Collect-from on the net, then the pack shows {@code haddock} and the take line arrives. */
    private static void collect(Fixture f, long haddock)
    {
        f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Collect-from", MenuAction.GAME_OBJECT_FIRST_OPTION,
            null, "<col=ffff>Trawling net"));
        f.pack.put(HADDOCK, haddock);
        f.chat("You take all of the fish from the net.");
    }
}
