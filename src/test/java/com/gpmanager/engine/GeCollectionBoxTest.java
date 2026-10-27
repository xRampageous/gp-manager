package com.gpmanager;

import static net.runelite.api.GrandExchangeOfferState.*;
import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.IntUnaryOperator;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** Native collection boxes identify sale proceeds without replacing custody's money rules. */
public class GeCollectionBoxTest {
    static final int RUNE = ItemID.NATURERUNE, COINS = ItemID.COINS;
    static final long START = 1_800_000_000_000L;

    static class Harness {
        final Engine engine;
        final OfferSlots slots = new OfferSlots();
        final Map<Integer, Long> inventory = new HashMap<>(Map.of(COINS, 100_000L));

        Harness() {
            JsonCodec.bind(new com.google.gson.Gson());
            engine = new Engine(deltas -> {
                var flows = new ArrayList<Flow>();
                deltas.forEach((id, quantity) -> flows.add(new Flow(id,
                        id == COINS ? "Coins" : "Nature rune", quantity, id == COINS ? 1 : 210,
                        quantity * (id == COINS ? 1 : 210),
                        id == COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE)));
                return flows;
            }, new TransactionClassifier(), new GpManagerConfig() {
                public int stabilizationTicks() { return 0; }
            });
            engine.ensureSession(START);
            engine.setBaseline(new ContainerSnapshot(inventory));
        }

        void offer(int slot, GrandExchangeOfferState state, int traded, int spent, long at) {
            slots.observe(new OfferSlots.Snapshot(slot, state, RUNE, 20, traded, 210, spent))
                    .ifPresent(change -> engine.noteGeOffer(change, "Nature rune", at));
        }

        void place(int slot, long at) {
            inventory.put(RUNE, 20L);
            engine.setBaseline(new ContainerSnapshot(inventory));
            offer(slot, SELLING, 0, 0, at);
            inventory.put(RUNE, 0L);
            settle(at + 1);
            offer(slot, SELLING, 10, 2100, at + 600);
        }

        void pair() { place(0, START + 1000); place(1, START + 3000); }

        void settle(long at) {
            engine.markDirty();
            var snapshot = new ContainerSnapshot(inventory);
            engine.processIfDirty(snapshot, at, null);
            engine.processIfDirty(snapshot, at + 1, null);
        }

        void cash(long at) { inventory.merge(COINS, 2060L, Long::sum); settle(at); }

        void box(int slot, long cash, long at) {
            engine.noteGeBox(slot, cash == 0 ? new Item[0] : new Item[] {new Item(COINS, (int) cash)},
                    IntUnaryOperator.identity(), at);
        }

        GeOffer record(int slot) {
            return engine.exchange.records().stream().filter(row -> row.slot == slot).findFirst().get();
        }

        long reviews() {
            return engine.session().transactions().stream().filter(ReviewRules::needsReview).count();
        }

        long trades() {
            return engine.session().transactions().stream().filter(row -> row.type() == TransactionType.TRADE).count();
        }

        void empty(int slot, long at) {
            slots.observe(new OfferSlots.Snapshot(slot, EMPTY, 0, 0, 0, 0, 0))
                    .ifPresent(change -> engine.noteGeOffer(change, "", at));
        }
    }

    @Test public void identicalLiveSalesResolveForBothCashBoxOrders() {
        for (boolean cashFirst : new boolean[] {false, true}) {
            var h = new Harness(); h.pair(); h.box(0, 2060, START + 5000);
            h.engine.noteGeCollect(START + 6000);
            if (cashFirst) h.cash(START + 6100);
            h.box(0, 0, START + 6200);
            if (!cashFirst) h.cash(START + 6300);
            h.engine.tickGe(START + 8500);
            assertEquals(10, h.record(0).settledQty);
            assertEquals(0, h.record(1).settledQty);
            assertEquals(0, h.reviews());
            assertEquals(-40, h.engine.metrics(START + 8600).net);
        }
    }

    @Test public void sameTieWithoutBoxProofStillFailsClosed() {
        var h = new Harness(); h.pair(); h.engine.noteGeCollect(START + 6000);
        h.cash(START + 6100); h.engine.tickGe(START + 8500);
        assertEquals(1, h.reviews()); assertEquals(0, h.trades());
    }

    @Test public void aBoxChangeWithoutCashDoesNotBook() {
        var h = new Harness(); h.pair(); h.box(0, 2060, START + 5000);
        h.engine.noteGeCollect(START + 6000); h.box(0, 0, START + 6100);
        h.engine.tickGe(START + 8500);
        assertEquals(0, h.trades()); assertEquals(0, h.reviews());
    }

    @Test public void noCollectCannotCreateSlotProof() {
        var h = new Harness(); h.pair(); h.box(0, 2060, START + 5000); h.box(0, 0, START + 6100);
        assertFalse(h.engine.exchange.clearAt.containsKey(h.record(0).offerId));
    }

    @Test public void firstSightingEmptyCannotCreateSlotProof() {
        var h = new Harness(); h.pair(); h.engine.noteGeCollect(START + 6000);
        h.box(0, 0, START + 6100); h.cash(START + 6200); h.engine.tickGe(START + 8500);
        assertEquals(1, h.reviews());
    }

    @Test public void malformedContentsInvalidateEarlierBox() {
        var h = new Harness(); h.pair(); h.box(0, 2060, START + 5000);
        h.engine.noteGeBox(0, new Item[] {new Item(ItemID.BRONZE_AXE, 1)}, IntUnaryOperator.identity(), START + 5500);
        h.engine.noteGeCollect(START + 6000); h.box(0, 0, START + 6100);
        assertFalse(h.engine.exchange.clearAt.containsKey(h.record(0).offerId));
    }

    @Test public void impossibleCashCannotConfirmAnotherSale() {
        var h = new Harness(); h.pair(); h.box(0, 3000, START + 5000);
        h.engine.noteGeCollect(START + 6000); h.box(0, 0, START + 6100);
        assertFalse(h.engine.exchange.clearAt.containsKey(h.record(0).offerId));
    }

    @Test public void fillGrowthBetweenSamplesCannotConfirmOldCash() {
        var h = new Harness(); h.pair(); h.box(0, 2060, START + 5000);
        h.offer(0, SELLING, 11, 2310, START + 5500);
        h.engine.noteGeCollect(START + 6000); h.box(0, 0, START + 6100);
        assertFalse(h.engine.exchange.clearAt.containsKey(h.record(0).offerId));
    }

    @Test public void loginSeedDiscardsBoxHistory() {
        var h = new Harness(); h.pair(); h.box(0, 2060, START + 5000);
        h.engine.seedGeSlots(Map.of(0, new OfferSlots.Snapshot(0, SELLING, RUNE, 20, 10, 210, 2100),
                1, new OfferSlots.Snapshot(1, SELLING, RUNE, 20, 10, 210, 2100)), START + 5500);
        h.engine.noteGeCollect(START + 6000); h.box(0, 0, START + 6100);
        assertFalse(h.engine.exchange.clearAt.containsKey(h.record(0).offerId));
    }

    @Test public void expiredSampleCannotConfirmAClaim() {
        var h = new Harness(); h.pair(); h.box(0, 2060, START + 5000);
        long at = START + GeCustodyLedger.COLLECTION_WINDOW_MILLIS + 6000;
        h.engine.noteGeCollect(at); h.box(0, 0, at + 100);
        assertFalse(h.engine.exchange.clearAt.containsKey(h.record(0).offerId));
    }

    @Test public void repeatedEmptyCallbackCannotBookAgain() {
        var h = new Harness(); h.pair(); h.box(0, 2060, START + 5000);
        h.engine.noteGeCollect(START + 6000); h.box(0, 0, START + 6100);
        h.cash(START + 6200); h.box(0, 0, START + 6300); h.engine.tickGe(START + 8500);
        assertEquals(1, h.trades()); assertEquals(0, h.reviews());
    }

    @Test public void aPartialCashDepletionDoesNotInventQuantity() {
        var h = new Harness(); h.pair(); h.box(0, 2060, START + 5000);
        h.engine.noteGeCollect(START + 6000); h.box(0, 1000, START + 6100);
        assertFalse(h.engine.exchange.clearAt.containsKey(h.record(0).offerId));
        assertEquals(0, h.trades());
    }

    @Test public void notedContentsUseExistingCanonicalIdentity() {
        var h = new Harness(); h.pair();
        h.engine.noteGeBox(0, new Item[] {new Item(COINS, 2060), new Item(123456, 1)},
                id -> id == 123456 ? RUNE : id, START + 5000);
        h.engine.noteGeCollect(START + 6000); h.box(0, 0, START + 6100); h.cash(START + 6200);
        assertEquals(1, h.trades()); assertEquals(0, h.reviews());
    }

    @Test public void negativeQuantityInvalidatesSample() {
        var h = new Harness(); h.pair(); h.box(0, 2060, START + 5000);
        h.engine.noteGeBox(0, new Item[] {new Item(COINS, -1)}, IntUnaryOperator.identity(), START + 5500);
        h.engine.noteGeCollect(START + 6000); h.box(0, 0, START + 6100);
        assertFalse(h.engine.exchange.clearAt.containsKey(h.record(0).offerId));
    }

    @Test public void clearedOfferAndBoxProofDoNotDuplicateCash() {
        var h = new Harness(); h.pair(); h.box(0, 2060, START + 5000);
        h.engine.noteGeCollect(START + 6000); h.empty(0, START + 6100);
        h.box(0, 0, START + 6200); h.cash(START + 6300); h.engine.tickGe(START + 8500);
        assertEquals(1, h.trades()); assertEquals(0, h.reviews());
    }

    @Test public void allEightNativeContainerIdsMapToTheirOwnOffers() throws Exception {
        var h = new Harness();
        for (int slot = 0; slot < 8; slot++) h.place(slot, START + 1000 + slot * 2000);
        var plugin = new GpManagerPluginProbe(); GpManagerPluginProbe.set(plugin, "engine", h.engine);
        var clock = SafeMath.clock;
        try {
            SafeMath.clock = () -> START + 20000;
            for (int slot = 0; slot < 8; slot++) {
                plugin.geBox(slot < 6 ? 518 + slot : slot == 6 ? 539 : 540, container());
                assertEquals(h.record(slot).offerId, h.engine.exchange.boxes.get(slot).offer);
            }
            assertEquals(8, h.engine.exchange.boxes.size());
            assertEquals(0, h.trades());
        } finally { SafeMath.clock = clock; }
    }

    @Test public void loginAndUnrelatedContainersCannotSeedNativeProof() throws Exception {
        var h = new Harness(); h.pair();
        var plugin = new GpManagerPluginProbe(); GpManagerPluginProbe.set(plugin, "engine", h.engine);
        GpManagerPluginProbe.set(plugin, "seedTicks", 1); plugin.geBox(518, container());
        assertTrue(h.engine.exchange.boxes.isEmpty());
        GpManagerPluginProbe.set(plugin, "seedTicks", 0);
        plugin.geBox(93, container()); plugin.geBox(517, container()); plugin.geBox(524, container());
        plugin.geBox(538, container()); plugin.geBox(541, container()); plugin.geBox(518, null);
        assertTrue(h.engine.exchange.boxes.isEmpty());
    }

    @Test public void resetAndAdoptionDiscardNativeBoxHistory() {
        var h = new Harness(); h.pair(); h.box(0, 2060, START + 5000);
        var destination = new Harness(); destination.pair(); destination.box(1, 2060, START + 5000);
        destination.engine.exchange.adoptFrom(h.engine.exchange);
        assertTrue(destination.engine.exchange.boxes.isEmpty());
        h.engine.exchange.reset();
        assertTrue(h.engine.exchange.boxes.isEmpty());
    }

    private static ItemContainer container() {
        return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
                new Class<?>[] {ItemContainer.class}, (proxy, method, args) ->
                        method.getName().equals("getItems") ? new Item[] {new Item(COINS, 2060)} : null);
    }
}
