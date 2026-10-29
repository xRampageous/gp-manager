package com.gpmanager;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** D4: known carried bag stock is lost once on a confirmed PK, without an invented container delta. */
public class LootingBagPkLossTest {
    static final int RUNE = 562, FOOD = 24589;
    static final long T0 = ReviewFixesTest.T0;
    LongSupplier previous;

    @Before public void clock() {
        previous = SafeMath.clock;
        SafeMath.clock = () -> T0 + 5_000;
        JsonCodec.bind(new Gson());
    }
    @After public void restoreClock() { SafeMath.clock = previous; }

    static class Probe extends GpManagerPlugin {
        boolean ready = true;
        final List<Transaction> published = new ArrayList<>();
        SavedState saved;
        @Override boolean canIngest() { return ready; }
        @Override void persist() { saved = engine.savedState(); }
        @Override void transactionBooked(Transaction row, long now) {
            super.transactionBooked(row, now);
            if (row != null) published.add(row);
        }
    }

    static class Fixture {
        Engine engine;
        Probe plugin = new Probe();
        boolean carried = true, dangerous = true, arena, pkEnabled = true;
        int bagId = ItemID.LOOTING_BAG;
        Map<Integer, Long> pack = Map.of();
        Player local = proxy(Player.class, (method, args) -> "getWorldLocation".equals(method)
            ? new WorldPoint(3200, 3600, 0) : null);
        ItemContainer inventory = proxy(ItemContainer.class, (method, args) -> {
            if ("contains".equals(method)) return carried && (int) args[0] == bagId;
            if ("getItems".equals(method)) {
                List<Item> items = new ArrayList<>();
                if (carried) items.add(new Item(bagId, 1));
                pack.forEach((id, quantity) -> items.add(new Item(id, quantity.intValue())));
                return items.toArray(new Item[0]);
            }
            return null;
        });
        Fixture() { this(ReviewFixesTest.engine()); }
        Fixture(Engine owner) {
            engine = owner;
            plugin.engine = engine;
            plugin.config = new GpManagerConfig() {
                @Override public boolean enablePkTracking() { return pkEnabled; }
            };
            plugin.client = proxy(Client.class, (method, args) -> {
                if ("getLocalPlayer".equals(method)) return local;
                if ("getItemContainer".equals(method)) return (int) args[0] == InventoryID.INV ? inventory : null;
                if ("getTickCount".equals(method)) return 100;
                if ("getVarbitValue".equals(method)) return (int) args[0] == VarbitID.INSIDE_WILDERNESS
                    ? (dangerous ? 1 : 0) : (int) args[0] == VarbitID.THIS_IS_A_PVP_ARENA_WORLD && arena ? 1 : 0;
                return null;
            });
            plugin.pvp = new PvpContextReader(plugin.client);
            plugin.targets = GpManagerPluginProbe.tracker(plugin.client, engine, plugin.config);
            plugin.idle = new CharacterIdleTracker(null, new CharacterIdleModel());
            plugin.activity = new ActivityDetector(plugin.config, engine);
            plugin.containers = new ContainerSnapshotFactory(plugin.client, null);
            plugin.hud = new HudBuilder(plugin.config, null);
            plugin.filter = new LootPresentationFilterService(GroundItemsPrefs.disabled());
            plugin.combat = 20;
            engine.rememberBag(Map.of(RUNE, 3L));
        }
        void die() { plugin.onActorDeath(new ActorDeath(local)); }
        Transaction loss() {
            return engine.session().transactions().stream()
                .filter(row -> row.type() == TransactionType.PK_DEATH_LOSS).findFirst().orElse(null);
        }
        void settle(Map<Integer, Long> items) {
            engine.markDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(items);
            engine.processIfDirty(snapshot, T0 + 5_600, row -> plugin.transactionBooked(row, T0 + 5_600));
            engine.processIfDirty(snapshot, T0 + 6_200, row -> plugin.transactionBooked(row, T0 + 6_200));
        }
    }
    interface Call { Object call(String method, Object[] args); }
    static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (object, method, args) -> call.call(method.getName(), args)));
    }
    static Map<Integer, Long> quantities(Transaction row) {
        Map<Integer, Long> result = new HashMap<>();
        row.flows().forEach(flow -> result.merge(flow.item, flow.quantity, Long::sum));
        return result;
    }

    @Test public void rememberedContentsAndConfirmedAdditionsBookWithoutAContainerDecrease() {
        for (int id : new int[] {ItemID.LOOTING_BAG, ItemID.LOOTING_BAG_OPEN}) {
            Fixture f = new Fixture(); f.bagId = id;
            f.engine.bag.put(RUNE, 2L); f.engine.bag.put(FOOD, 1L);
            f.die();
            Transaction row = f.loss();
            assertNotNull("both bag variants lose their contents", row);
            assertEquals(Map.of(RUNE, -5L, FOOD, -1L), quantities(row));
            assertEquals(-600L, row.net());
            assertEquals(Context.PK_DEATH, row.context());
            assertEquals("PKing", row.activityName());
            assertEquals(Confidence.LIKELY, row.confidence());
            assertTrue(row.explanation().toLowerCase().contains("remembered"));
            assertEquals(1, f.engine.session().pk().deaths);
            assertFalse(row.encounterId().isEmpty());
            assertTrue(f.engine.bagStock().isEmpty());
            assertEquals(-600L, FixtureReads.currentSession(f.plugin.saved).metrics(T0 + 5_000).net);
            assertNull(f.plugin.saved.lootingBag);
        }
    }

    @Test public void packAndBagLossesOfTheSameItemKeepTheirQuantitiesAndEncounter() {
        Fixture f = new Fixture(); f.pack = Map.of(RUNE, 1L);
        f.engine.setBaseline(new ContainerSnapshot(f.pack));
        f.die(); Transaction bag = f.loss();
        assertNotNull(bag);
        f.settle(Map.of());
        List<Transaction> rows = f.engine.session().transactions();
        assertEquals(2, rows.size());
        assertEquals(-400L, f.engine.metrics(T0 + 7_000).net);
        assertEquals(-4L, rows.stream().mapToLong(row -> quantities(row).getOrDefault(RUNE, 0L)).sum());
        assertTrue(rows.stream().allMatch(row -> bag.encounterId().equals(row.encounterId())));
        assertEquals(1, f.engine.session().pk().deaths);
    }

    @Test public void duplicateDeathAndLaterBagClearCannotChargeTheStockAgain() {
        Fixture f = new Fixture(); f.die();
        assertNotNull(f.loss());
        f.plugin.combat = 20; f.die();
        f.plugin.rememberBag(proxy(ItemContainer.class, (method, args) ->
            "getItems".equals(method) ? new Item[0] : null));
        f.settle(Map.of());
        assertEquals(1, f.engine.session().transactions().size());
        assertEquals(-300L, f.engine.metrics(T0 + 7_000).net);
    }

    @Test public void directBagRewardIncomeStaysCountedAndTheDeathOffsetsIt() {
        Fixture f = new Fixture(); f.engine.rememberBag(null);
        f.engine.noteBagReward(Map.of(FOOD, 2L), T0 + 1_000);
        f.engine.settle(new ContainerSnapshot(Map.of(29460, 1L)), T0 + 2_000, ignored -> {});
        assertEquals(300L, f.engine.metrics(T0 + 3_000).income);
        f.die();
        assertNotNull(f.loss());
        assertEquals(Map.of(FOOD, -2L), quantities(f.loss()));
        assertEquals(300L, f.engine.metrics(T0 + 6_000).income);
        assertEquals(200L, f.engine.metrics(T0 + 6_000).cost);
        assertEquals("the pack's ticket remains; the bag's reward is offset", 100L, f.engine.metrics(T0 + 6_000).net);
    }

    @Test public void absentOrBankedBagCannotLoseRememberedContents() {
        Fixture f = new Fixture(); f.carried = false; f.die();
        assertNull(f.loss());
        assertTrue(f.engine.bagStock().isEmpty());
    }

    @Test public void freshEmptyAndNeverObservedContentsDoNotInventALoss() {
        Fixture f = new Fixture(); f.engine.rememberBag(Map.of()); f.die();
        assertNull(f.loss()); assertEquals(0L, f.engine.metrics(T0 + 6_000).net);
    }

    @Test public void safePvmUnclassifiedDisabledAndPausedDeathsDoNotBookBagLoss() {
        for (int mode = 0; mode < 5; mode++) {
            Fixture f = new Fixture();
            if (mode == 0) f.arena = true;
            if (mode == 1) { f.dangerous = false; f.plugin.combat = 0; }
            if (mode == 2) f.dangerous = false;
            if (mode == 3) f.pkEnabled = false;
            if (mode == 4) f.engine.togglePause(T0 + 1_000);
            f.die();
            assertNull("disposition " + mode, f.loss());
            assertEquals(0L, f.engine.metrics(T0 + 6_000).net);
        }
    }

    @Test public void foreignActorAndHeldIngestionCannotConsumeThisOwnersStock() {
        Fixture f = new Fixture();
        f.plugin.onActorDeath(new ActorDeath(proxy(Player.class, (method, args) -> null)));
        assertEquals(Map.of(RUNE, 3L), f.engine.bagStock());
        f.plugin.ready = false; f.die();
        assertEquals(Map.of(RUNE, 3L), f.engine.bagStock());
        assertNull(f.loss());
    }

    @Test public void anotherAccountAndResetCannotInheritABagLoss() {
        for (boolean reset : new boolean[] {false, true}) {
            Fixture f = new Fixture();
            if (reset) f.engine.resetData(T0 + 1_000);
            else f.engine.restoreProfile("other", new SavedState(), T0 + 1_000);
            f.engine.ensureSession(T0 + 2_000); f.die();
            assertNull(f.loss());
        }
    }

    @Test public void anOwnerFenceDuringDeathClassificationCannotBookThePreviousBag() {
        Fixture f = new Fixture();
        f.plugin.pvp = new PvpContextReader(f.plugin.client) {
            @Override PvpContext sample(Player player) {
                f.engine.events.owner(f.engine.events.owner + 1);
                SavedState other = new SavedState(); other.lootingBag = Map.of(FOOD, 2L);
                f.engine.restoreProfile("other", other, T0 + 3_000);
                f.engine.ensureSession(T0 + 4_000);
                return PvpContext.DANGEROUS_PVP;
            }
        };
        f.die();
        assertNull(f.loss());
        assertEquals(Map.of(FOOD, 2L), f.engine.bagStock());
        assertTrue(f.plugin.published.isEmpty());
    }

    @Test public void unpricedKnownStockKeepsItsQuantityWithoutInventingGp() {
        Engine engine = new Engine(deltas -> {
            List<Flow> result = new ArrayList<>();
            deltas.forEach((id, quantity) -> result.add(new Flow(id, "Unknown item", quantity, 0, 0,
                PriceSource.UNPRICED)));
            return result;
        }, new TransactionClassifier(), ReviewFixesTest.CONFIG);
        engine.ensureSession(T0); engine.setBaseline(FixtureReads.empty());
        Fixture f = new Fixture(engine); f.die();
        assertNotNull(f.loss());
        assertEquals(Map.of(RUNE, -3L), quantities(f.loss()));
        assertEquals(PriceSource.UNPRICED, f.loss().flows().get(0).source());
        assertEquals(0L, f.engine.metrics(T0 + 6_000).net);
    }

    @Test public void relogRestoresKnownStockAndTheBookedLossSurvivesReloadWithoutRepeating() {
        Fixture f = new Fixture();
        Gson gson = new Gson();
        SavedState saved = gson.fromJson(gson.toJson(f.engine.savedState()), SavedState.class);
        f.engine.restoreProfile("same", saved, T0 + 1_000);
        f.engine.resume(T0 + 2_000, PauseReason.RECOVERY);
        f.die(); assertNotNull(f.loss());
        saved = gson.fromJson(gson.toJson(f.plugin.saved), SavedState.class);
        f.engine.restoreProfile("same", saved, T0 + 7_000);
        f.engine.resume(T0 + 8_000, PauseReason.RECOVERY);
        f.plugin.combat = 20; f.die();
        assertEquals(-300L, f.engine.metrics(T0 + 9_000).net);
        assertEquals(1, f.engine.session().transactions().size());
    }

    @Test public void lossPublishesToHudRecentAndLedgerAndRespectsCorrectionGuards() {
        Fixture f = new Fixture(); f.die();
        Transaction loss = f.loss(); assertNotNull(loss);
        assertEquals(List.of(loss), f.plugin.published);
        assertTrue(f.plugin.hud.tray.entries().stream().anyMatch(entry -> entry.state == HudTray.State.LOST));
        assertEquals(-300L, LiveSnapshot.capture(f.engine, T0 + 6_000, null).net);
        assertFalse(LiveSnapshot.capture(f.engine, T0 + 6_000, null).recent.isEmpty());
        assertEquals(-300L, LedgerData.capture(f.engine, T0 + 6_000, null).net);
        assertFalse("a physical sink retains the existing basis fence",
            f.engine.correctTransaction(loss.id(), Correction.IGNORE, "test"));
        assertTrue(f.engine.correctTransaction(loss.id(), Correction.COST, "test"));
        assertEquals(Correction.COST, loss.correction());
        f.engine.undoCorrection();
        assertEquals(Correction.AUTO, loss.correction());
        assertEquals(-300L, f.engine.metrics(T0 + 8_000).net);
        assertEquals(1, f.engine.session().pk().deaths);
    }
}
