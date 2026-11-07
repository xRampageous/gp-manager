package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.Map;
import net.runelite.api.ItemComposition;
import static net.runelite.api.gameval.ItemID.*;
import net.runelite.client.game.ItemMapping;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Wiki-pinned shop packs; current HA policy stays intact, optional Loot Tracker is absent. */
@RunWith(Parameterized.class)
public class RewardPackOpeningTest {
    @Parameterized.Parameters(name="{0}: {1}") public static Object[][] packs() {
        return new Object[][] {
            {PACK_SOFTCLAY,"Soft clay pack",6,12012,"Golden nugget",60,10,SOFTCLAY,"Soft clay",100},
            {PACK_SOFTCLAY_GUILD,"Soft clay pack",6,21341,"Unidentified minerals",30,10,SOFTCLAY,"Soft clay",100},
            {STAR_PACK_SOFTCLAY,"Soft clay pack",90,25527,"Stardust",30,150,SOFTCLAY,"Soft clay",100},
            {REWARD_GEM_BAG,"Bag full of gems",24,12012,"Golden nugget",60,40,UNCUT_SAPPHIRE,"Uncut sapphire",40},
            {REWARD_GEM_BAG_GUILD,"Bag full of gems",24,21341,"Unidentified minerals",30,20,UNCUT_SAPPHIRE,"Uncut sapphire",40},
            {STAR_REWARD_GEM_BAG,"Bag full of gems",180,25527,"Stardust",30,300,UNCUT_SAPPHIRE,"Uncut sapphire",40}
        };
    }
    @Parameterized.Parameter(0) public int pack;
    @Parameterized.Parameter(1) public String name;
    @Parameterized.Parameter(2) public int ha;
    @Parameterized.Parameter(3) public int currency;
    @Parameterized.Parameter(4) public String currencyName;
    @Parameterized.Parameter(5) public int currencyHa;
    @Parameterized.Parameter(6) public int cost;
    @Parameterized.Parameter(7) public int reward;
    @Parameterized.Parameter(8) public String rewardName;
    @Parameterized.Parameter(9) public int quantity;
    private long now = 1_000_000;

    private ItemComposition definition(int id) {
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
                new Class<?>[]{ItemComposition.class}, (proxy, method, args) -> {
            switch(method.getName()) {
                case "getName": return id == pack ? name : id == currency ? currencyName : rewardName;
                case "getId": return id;
                case "getHaPrice": return id == pack ? ha : id == currency ? currencyHa : 1;
                case "isTradeable": return id == reward;
                default: return method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? 0 : null;
            }
        });
    }
    private Valuation values() {
        return new Valuation(new GpManagerConfig(){}, this::definition,
                (id,active) -> id == reward ? 50 : id == 561 ? 100 : 0,
                id -> Valuation.hasUnsupportedMapping(ItemMapping.map(id)), Valuation.Economy.NORMAL);
    }
    private Engine engine() {
        JsonCodec.bind(new com.google.gson.Gson());
        var engine = new Engine(values(),new TransactionClassifier(),new GpManagerConfig(){
            @Override public int stabilizationTicks(){return 0;}
        });
        engine.startCustom("Reward pack",now);
        engine.setBaseline(new ContainerSnapshot(Map.of()));
        return engine;
    }
    private Transaction settle(Engine engine, Map<Integer,Long> held) {
        now += 3000;
        engine.processIfDirty(new ContainerSnapshot(held),now,null);
        engine.processIfDirty(new ContainerSnapshot(held),now + 600,null);
        var rows = engine.session().transactions();
        return rows.isEmpty() ? null : rows.get(rows.size()-1);
    }
    @Test public void openingAfterPurchaseBooksTheContentsWithoutOptionalLootTracker() {
        var engine = engine();
        settle(engine,Map.of(currency,(long)cost));
        assertEquals((long)cost*currencyHa,engine.metrics(now+1000).net);
        engine.events.put(Inbox.DIALOGUE,0,1,4,null);
        assertEquals(TransactionType.TRADE,settle(engine,Map.of(pack,1L)).type());
        assertEquals("held pack retains its current own HA",ha,engine.metrics(now+1000).net);
        engine.events.drop(Inbox.DIALOGUE);
        var opened = settle(engine,Map.of(reward,(long)quantity));
        System.out.println("pack="+pack+" before="+ha+" actualAfter="+engine.metrics(now+1000).net
                +" expectedAfter="+quantity*50L+" opening="+opened.type());
        assertFalse("opening a paid reward pack must not require a decision",ReviewRules.needsReview(opened));
        assertEquals(quantity*50L,engine.metrics(now+1000).net);
    }
    @Test public void theReviewMustNotChangePackOrCurrencyValuationPolicy() {
        var values = values();
        var flow = values.value(Map.of(pack,1L),now).get(0);
        assertEquals(ha,flow.unitPrice);
        assertEquals(PriceSource.HIGH_ALCHEMY,flow.source());
        assertEquals(currencyHa,values.value(Map.of(currency,1L),now).get(0).unitPrice);
    }

    @Test public void anExecutedShopSaleStillBooksATrade() {
        var engine=engine();engine.setBaseline(new ContainerSnapshot(Map.of(pack,1L)));
        engine.markContext(Context.MARKET,4,"sell 1 "+name);
        var sale=settle(engine,Map.of(currency,1L));
        assertEquals(TransactionType.TRADE,sale.type());
        assertEquals("Exchanged",Semantics.verbOf(sale));
        assertEquals(currencyHa-ha,engine.metrics(now+1000).net);
    }
}
