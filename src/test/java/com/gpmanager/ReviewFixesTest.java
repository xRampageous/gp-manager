package com.gpmanager;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.http.api.item.ItemPrice;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The eight findings of the Codex review of GP Manager 1.2 (2026-10-05), kept as regression tests.
 * Adapted from Codex's probes; the catch lookup is tested through its helper, without reflection.
 */
public class ReviewFixesTest {
    static final long T0 = 1_700_000_000_000L;
    static final int BONES = 526;
    static final GpManagerConfig CONFIG = new GpManagerConfig() {
        @Override public int stabilizationTicks() { return 0; }
        @Override public boolean autoStartSession() { return false; }
    };

    static Engine engine() {
        JsonCodec.bind(new Gson());
        Engine e = new Engine(deltas -> {
            List<Flow> result = new ArrayList<>();
            deltas.forEach((id, q) -> result.add(new Flow(id, "Item " + id, q, 100, q * 100)));
            return result;
        }, new TransactionClassifier(), CONFIG);
        e.ensureSession(T0);
        e.setBaseline(ContainerSnapshot.empty());
        return e;
    }

    static final class Plugin extends GpManagerPlugin {
        boolean ready = true;
        @Override boolean canIngest() { return ready; }
        @Override void pulse() { }
    }

    static Plugin plugin(Engine engine, int[] tick) {
        Plugin p = new Plugin();
        p.engine = engine;
        p.config = CONFIG;
        p.client = proxy(Client.class, new HashMap<String, Object>() {{
            put("getTickCount", (java.util.function.IntSupplier) () -> tick[0]);
            put("getGameState", GameState.LOGIN_SCREEN);
            put("getLocalPlayer", proxy(Player.class, Collections.singletonMap("getWorldLocation", new WorldPoint(100, 100, 0))));
        }});
        p.containers = new ContainerSnapshotFactory(p.client, null) {
            @Override ContainerSnapshot capture(boolean equipment, boolean pouch) { return ContainerSnapshot.empty(); }
            @Override boolean bagOpen() { return true; }
        };
        p.storage = new Persistence(p.client, null, null, null, engine);
        p.charges = new ChargeIntake(p.client, null, CONFIG, engine, null);
        return p;
    }

    static Observation take(Plugin p) {
        Observation e = p.engine.events.peek(Inbox.GROUND_CLICK);
        return e == null ? p.engine.events.add(Inbox.GROUND_CLICK, -1, 1, 5, null) : e;
    }

    static Observation bag(Plugin p) {
        Observation e = p.engine.events.open.stream().filter(v -> v.kind == Inbox.BAG_GAIN).findFirst().orElse(null);
        if (e == null) {
            Observation click = take(p);
            e = p.engine.events.add(Inbox.BAG_GAIN, click.item, 0, 5, null);
            e.held = click.held;
        }
        return e;
    }

    static long qty(Plugin p, int kind) {
        Observation e = p.engine.events.peek(kind);
        return e == null ? 0L : e.qty;
    }

    @Test public void pendingBagGainMustRespectTheAccountReadinessFence() {
        Engine e = engine();
        Plugin p = plugin(e, new int[] { 11 });
        ReviewFixesTest.take(p).item = BONES;
        ReviewFixesTest.take(p).held = 0;
        ReviewFixesTest.bag(p).at = 10;
        ReviewFixesTest.bag(p).qty = 3;
        p.ready = false; // An account switch, restore or logout has fenced ingestion.
        p.onGameTick(new GameTick());
        assertEquals("a held account must not acquire a synthetic inventory delta", 0L, e.baseline.quantity(BONES));
    }

    @Test public void fencedBagSettlementMustNotWaitForTheEngineMonitor() throws Exception {
        Engine e = engine();
        Plugin p = plugin(e, new int[] { 11 });
        ReviewFixesTest.take(p).item = BONES;
        ReviewFixesTest.bag(p).at = 10;
        ReviewFixesTest.bag(p).qty = 3;
        p.ready = false;
        CountDownLatch tickReturned = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread tick = new Thread(() -> {
            try { p.onGameTick(new GameTick()); }
            catch (Throwable ex) { error.set(ex); }
            finally { tickReturned.countDown(); }
        });
        try {
            synchronized (e) { // A maintenance snapshot/restore owns this monitor.
                tick.start();
                assertTrue("fenced gameplay must return without waiting for maintenance", tickReturned.await(250, TimeUnit.MILLISECONDS));
            }
        } finally {
            tick.join(2_000);
            assertNull(error.get());
        }
    }

    @Test public void aNearbyDespawnIsNotProofThatTheClickedItemEnteredOurBag() {
        Plugin p = plugin(engine(), new int[] { 11 });
        ReviewFixesTest.take(p).item = BONES;
        ReviewFixesTest.take(p).at = 10;
        // The clicked pile was on (100,100); another player's pile on (101,100) expires.
        Tile tile = proxy(Tile.class, Collections.singletonMap("getWorldLocation", new WorldPoint(101, 100, 0)));
        TileItem item = proxy(TileItem.class, new HashMap<String, Object>() {{ put("getId", BONES); put("getQuantity", 3); }});
        p.onItemDespawned(new ItemDespawned(tile, item));
        assertEquals("an unrelated tile's despawn must not be promoted to counted loot", 0L, ReviewFixesTest.qty(p, Inbox.BAG_GAIN));
    }

    @Test public void aCatchMustResolveToRawFishEvenWhenCookedFishComesFirst() {
        assertEquals("cooked first", 335, GpManagerPlugin.caughtItem("trout", List.of(price(333, "Trout"), price(335, "Raw trout"))));
        assertEquals("raw first", 335, GpManagerPlugin.caughtItem("trout", List.of(price(335, "Raw trout"), price(333, "Trout"))));
        assertEquals("logs have no raw form", 1515, GpManagerPlugin.caughtItem("yew logs", List.of(price(1515, "Yew logs"))));
        assertEquals("no match", -1, GpManagerPlugin.caughtItem("trout", List.of(price(5, "Trout pie"))));
    }

    static ItemPrice price(int id, String name) {
        ItemPrice price = new ItemPrice();
        price.setId(id);
        price.setName(name);
        return price;
    }

    @Test public void normalWorldStillPricesCrystalShardProxy() {
        Valuation values = proxyValuation(Valuation.Economy.NORMAL);
        Flow shard = values.value(Collections.singletonMap(23962, 1L), T0).get(0);
        assertEquals(100, shard.unitPrice);
        assertEquals(PriceSource.CURRENCY_PROXY, shard.source());
    }

    @Test public void specialWorldMustNotPriceCrystalShardsFromTheMainGameMarket() {
        Flow shard = proxyValuation(Valuation.Economy.UNSUPPORTED_SPECIAL).value(Collections.singletonMap(23962, 1L), T0).get(0);
        assertEquals(PriceSource.UNPRICED, shard.source());
        assertEquals(0, shard.unitPrice);
    }

    @Test public void unknownWorldMustNotPriceCrystalShardsBeforeLogin() {
        Flow shard = proxyValuation(Valuation.Economy.UNKNOWN).value(Collections.singletonMap(23962, 1L), T0).get(0);
        assertEquals(PriceSource.UNPRICED, shard.source());
    }

    @Test public void aHigherShardQuoteMustOnlyPriceTheNewCharge() {
        ChargeIntake intake = new ChargeIntake(null, null, CONFIG, engine(), null);
        assertEquals(10_000L, intake.carriedValue(ChargeCheck.ChargedItem.CRYSTAL_BOW, 23962, 100L, 10_000, 100));
        assertEquals("one new charge must not revalue the 100 already consumed", 200L,
            intake.carriedValue(ChargeCheck.ChargedItem.CRYSTAL_BOW, 23962, 1L, 20_000, 100));
    }

    @Test public void aLowerShardQuoteMustOnlyPriceTheNewCharge() {
        ChargeIntake intake = new ChargeIntake(null, null, CONFIG, engine(), null);
        assertEquals(10_000L, intake.carriedValue(ChargeCheck.ChargedItem.CRYSTAL_BOW, 23962, 100L, 10_000, 100));
        assertEquals("a cheaper quote must not subtract value booked at the previous quote", 90L,
            intake.carriedValue(ChargeCheck.ChargedItem.CRYSTAL_BOW, 23962, 1L, 9_000, 100));
    }

    @Test public void aSlowFactoryResetMustLeaveTheRuneLiteSwingThreadResponsive() throws Exception {
        CountDownLatch diskEntered = new CountDownLatch(1);
        CountDownLatch diskCanFinish = new CountDownLatch(1);
        CountDownLatch uiHeartbeat = new CountDownLatch(1);
        Persistence disk = new Persistence(null, null, null, null, null) {
            @Override synchronized ResetOutcome factoryReset(long now, boolean backup) {
                diskEntered.countDown();
                try { diskCanFinish.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { throw new AssertionError(ex); }
                return new ResetOutcome(false, "test reset refused");
            }
        };
        AtomicReference<GrindsController> controller = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> controller.set(new GrindsController(new SidebarPanel(engine(), CONFIG, null, null, disk))));
        SwingUtilities.invokeLater(() -> controller.get().resetNow(true));
        try {
            assertTrue("the reset was dispatched", diskEntered.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeLater(uiHeartbeat::countDown);
            assertTrue("disk work must not hold the entire RuneLite Swing thread", uiHeartbeat.await(250, TimeUnit.MILLISECONDS));
        } finally {
            diskCanFinish.countDown();
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    static Valuation proxyValuation(Valuation.Economy economy) {
        return new Valuation(CONFIG, id -> null, (id, active) -> 15_000, id -> false, economy);
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, method, args) -> {
            Object value = values.get(method.getName());
            if (value instanceof java.util.function.IntSupplier) return ((java.util.function.IntSupplier) value).getAsInt();
            if (value != null) return value;
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            if (r == byte.class) return (byte) 0;
            if (r == short.class) return (short) 0;
            if (r == float.class) return 0F;
            if (r == double.class) return 0D;
            if (r == char.class) return '\0';
            return null;
        });
    }
}
