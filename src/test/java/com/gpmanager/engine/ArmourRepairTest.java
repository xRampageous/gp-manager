package com.gpmanager;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.LongSupplier;
import net.runelite.api.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.*;
import static org.junit.Assert.*;

/** A repair changes the owned item's state; only the observed fee is an expense. */
public class ArmourRepairTest {
    static final int COINS = ItemID.COINS;
    static final int[][] MOONS = {
        {29004,29031,29049},{29007,29033,29052},{29010,29035,29055},
        {29013,29037,29058},{29016,29039,29061},{29019,29041,29064},
        {29022,29043,29067},{29025,29045,29070},{29028,29047,29073}
    };
    LongSupplier previous;
    @Before public void clock() { previous = SafeMath.clock; SafeMath.clock = () -> 120000; }
    @After public void restoreClock() { SafeMath.clock = previous; }

    static class Fixture {
        final Map<Integer,Long> held = new HashMap<>();
        final Map<Integer,String> names = new HashMap<>();
        final Map<Integer,Integer> prices = new HashMap<>();
        final Engine engine;
        long now = 1000;
        Fixture(int... gear) {
            held.put(COINS,10000000L); names.put(COINS,"Coins"); prices.put(COINS,1);
            for (int id : gear) held.merge(id,1L,Long::sum);
            for (int[] family : MOONS) for (int id : family) {
                names.put(id,"Moon armour"); prices.put(id,1200000);
            }
            engine = new Engine(delta -> {
                var flows = new ArrayList<Flow>();
                delta.forEach((id,q) -> {
                    int price = prices.getOrDefault(id,1200000);
                    flows.add(new Flow(id,names.getOrDefault(id,"Equipment"),q,price,q*price,
                            id == COINS ? PriceSource.FACE_VALUE : price == 0 ? PriceSource.UNPRICED : PriceSource.GRAND_EXCHANGE));
                });
                return flows;
            },new TransactionClassifier(),new GpManagerConfig() {
                @Override public int stabilizationTicks() { return 0; }
            });
            engine.startCustom("Repairs",now);
            engine.setBaseline(new ContainerSnapshot(held));
        }
        void change(Map<Integer,Long> delta) {
            delta.forEach((id,q) -> held.merge(id,q,Long::sum));
            held.values().removeIf(q -> q == 0);
            settle(new ContainerSnapshot(held));
        }
        void settle(ContainerSnapshot snapshot) {
            now += 3000;
            engine.processIfDirty(snapshot,now,null);
            engine.processIfDirty(snapshot,now+600,null);
        }
        void swap(int from,int to,long fee) {
            var delta = new LinkedHashMap<Integer,Long>();
            delta.put(from,-1L);delta.put(to,1L);
            if (fee > 0) delta.put(COINS,-fee);
            change(delta);
        }
        Metrics money() { return engine.metrics(now+60000); }
        void feeOnly(long fee) {
            assertEquals(0,money().income);assertEquals(fee,money().cost);assertEquals(-fee,money().net);
            assertFalse(engine.session().transactions().stream().anyMatch(ReviewRules::needsReview));
            for (var row : engine.session().transactions()) if (row.counts()) {
                assertEquals(1,row.flows().size());assertEquals(COINS,row.flows().get(0).item);
            }
        }
    }

    @Test public void allNineMoonPiecesWearAndRepairBackToTheTradeableRawId() {
        for (int[] ids : MOONS) {
            var f = new Fixture(ids[0]);
            f.swap(ids[0],ids[1],0);f.feeOnly(0);
            f.swap(ids[1],ids[2],0);f.feeOnly(0);
            f.swap(ids[2],ids[0],1500000);f.feeOnly(1500000);
            f.swap(ids[0],ids[1],0);
            f.swap(ids[1],ids[0],757500);f.feeOnly(2257500);
        }
    }

    @Test public void brokenNamesAndMissingMarketQuotesStillBookOnlyTheMeasuredFee() {
        int[] ids = MOONS[0];var f = new Fixture(ids[2]);
        f.names.put(ids[0],"Eclipse moon chestplate");
        f.names.put(ids[2],"Eclipse moon chestplate (broken)");f.prices.put(ids[2],0);
        f.engine.events.put(Inbox.DIALOGUE,0,1L,4,null);
        f.swap(ids[2],ids[0],1500000);f.feeOnly(1500000);
    }

    @Test public void inventoryAndWornContainersKeepRawRepairIdsInBothDirections() {
        for (int[] ids : MOONS) {
            Item[][] inv = {new Item[]{new Item(COINS,10000000)}};
            Item[][] worn = {new Item[]{new Item(ids[1],1)}};
            Client client = (Client)Proxy.newProxyInstance(Client.class.getClassLoader(),new Class<?>[]{Client.class},
                    (p,m,a) -> m.getName().equals("getItemContainer") ? container((int)a[0] == InventoryID.INV ? inv[0] : worn[0])
                            : defaultValue(m.getReturnType()));
            var factory = new ContainerSnapshotFactory(client,null);
            var f = new Fixture(ids[1]);f.engine.setBaseline(factory.capture(true,false));
            worn[0] = new Item[]{new Item(ids[0],1)};inv[0] = new Item[]{new Item(COINS,8500000)};
            var snapshot = factory.capture(true,false);
            assertEquals(1,snapshot.items.get(ids[0]).longValue());assertFalse(snapshot.items.containsKey(ids[1]));
            f.settle(snapshot);f.feeOnly(1500000);
        }
    }

    @Test public void everyCatalogueFamilyPairsWithoutUsingAmbiguousDisplayNames() throws Exception {
        var families = new LinkedHashMap<String,List<Integer>>();var seen = new HashSet<Integer>();
        for (String[] row : GameData.rows("wear_gear")) {
            assertEquals(3,row.length);int id = Integer.parseInt(row[0]);
            assertEquals(id,ItemID.class.getField(row[2]).getInt(null));assertTrue(seen.add(id));
            families.computeIfAbsent(row[1],key -> new ArrayList<>()).add(id);
        }
        assertEquals(381,seen.size());assertEquals(130,families.size());
        for (var family : families.values()) for (int i = 1; i < family.size(); i++) {
            int a = family.get(0), b = family.get(i);
            var f = new Fixture(a);f.swap(a,b,0);f.feeOnly(0);
            f.swap(b,a,12345);f.feeOnly(12345);
        }
    }

    @Test public void perduCanRepairSeveralBrokenItemsForOneObservedPayment() {
        var f = new Fixture(ItemID.TZHAAR_CAPE_FIRE_BROKEN,ItemID.BARBASSAULT_PENANCE_FIGHTER_TORSO_BROKEN);
        f.change(Map.of(ItemID.TZHAAR_CAPE_FIRE_BROKEN,-1L,ItemID.TZHAAR_CAPE_FIRE,1L,
                ItemID.BARBASSAULT_PENANCE_FIGHTER_TORSO_BROKEN,-1L,ItemID.BARBASSAULT_PENANCE_FIGHTER_TORSO,1L,COINS,-300000L));
        f.feeOnly(300000);
    }

    @Test public void aFeeCanArriveBeforeOrAfterTheStatePairWithoutDoubleCounting() {
        for (boolean first : new boolean[]{true,false}) {
            var f = new Fixture(MOONS[0][2]);
            if (first) f.change(Map.of(COINS,-1500000L));
            f.swap(MOONS[0][2],MOONS[0][0],0);
            if (!first) f.change(Map.of(COINS,-1500000L));
            f.feeOnly(1500000);
        }
    }

    @Test public void samePieceLootDuringWearSurvivesAndOrdinaryPurchasesAreNotNeutralized() {
        int[] ids = MOONS[0];var f = new Fixture(ids[1]);
        f.engine.markLoot(Map.of(ids[0],1L),30,"Lunar Chest","Lunar Chest");
        f.change(Map.of(ids[1],-1L,ids[0],2L));
        assertEquals(1200000,f.money().income);assertEquals(0,f.money().cost);
        assertFalse(f.engine.session().transactions().stream().anyMatch(ReviewRules::needsReview));
        var bought = new Fixture();bought.engine.events.put(Inbox.DIALOGUE,0,1L,4,null);
        bought.change(Map.of(ids[0],1L,COINS,-1200000L));
        assertTrue(bought.engine.session().transactions().stream().anyMatch(t -> t.counts() && t.flows().stream().anyMatch(x -> x.item == ids[0])));
    }

    @Test public void aTrueDropAndANewSamePieceGainCannotPretendToBeARepair() {
        int[] ids = MOONS[0];var f = new Fixture(ids[1]);
        f.engine.noteDrop(ids[1],8);
        f.swap(ids[1],ids[0],0);
        assertTrue("the new item must remain visible",f.engine.session().transactions().stream()
                .filter(Transaction::counts).flatMap(t -> t.flows().stream()).anyMatch(x -> x.item == ids[0] && x.gain()));
    }

    @Test public void unknownSameNameVariantsAndDifferentPiecesNeverGetPaired() {
        var f = new Fixture(999998);f.swap(999998,999999,0);
        assertFalse(f.engine.session().transactions().stream().anyMatch(t -> t.type() == TransactionType.TRANSFER));
        assertNull(Engine.piece(new Flow(999999,"Dharok's helm 75",1,10,10)));
        var different = new Fixture(MOONS[0][1]);different.swap(MOONS[0][1],MOONS[1][0],1500000);
        assertFalse(different.engine.session().transactions().stream().anyMatch(t -> t.note().equals(GameData.msg("barrows-wear"))));
    }

    @Test public void feeOnlyProjectionAndReloadKeepRecentLedgerAndMoneyAligned() {
        var f = new Fixture(MOONS[0][2]);f.swap(MOONS[0][2],MOONS[0][0],1500000);f.feeOnly(1500000);
        var snapshot = LiveSnapshot.capture(f.engine,f.now+60000,null);
        assertEquals(0,snapshot.income);assertEquals(-1500000,snapshot.net);
        var ledger = LedgerData.capture(f.engine,f.now+60000,LedgerData.Entry.current());
        assertTrue(ledger.income.groups.isEmpty());assertEquals(-1500000,ledger.cost.total);
        assertFalse(snapshot.recent.stream().anyMatch(row -> row.item == MOONS[0][0]));
        var hud = new HudBuilder(HudBuilderTest.config(false,8),(id,q) -> null);
        hud.update(snapshot,flow -> true,f.now);
        for (var row : f.engine.session().transactions()) hud.tray.booked(row,f.now,false,f.now);
        hud.update(snapshot,flow -> true,f.now);
        assertEquals(-1500000,hud.lastNet);
        assertTrue(hud.tray.entries().stream().noneMatch(row -> row.item != COINS));
        var copy = new Fixture();copy.engine.restore(new Gson().fromJson(new Gson().toJson(f.engine.exportState()),SavedState.class),f.now+60000);
        assertEquals(f.money().net,copy.engine.metrics(f.now+60000).net);
        assertEquals(f.engine.session().transactions().size(),copy.engine.session().transactions().size());
    }

    static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
    static ItemContainer container(Item[] items) {
        return (ItemContainer)Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),new Class<?>[]{ItemContainer.class},
                (p,m,a) -> m.getName().equals("getItems") ? items : defaultValue(m.getReturnType()));
    }
}
