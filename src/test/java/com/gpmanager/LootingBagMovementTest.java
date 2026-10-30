package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native Oct 23: container 516 arrives on Check, but not on later pickups or Use deposits. */
public class LootingBagMovementTest {
    static final int FOOD = 24595, COINS = 995;
    static final long T0 = ReviewFixesTest.T0;
    static class Fixture {
        final Map<Integer, Long> pack = new HashMap<>();
        int bag = ItemID.LOOTING_BAG_OPEN, tick = 100;
        Widget selected;
        final Engine engine;
        final GpManagerPluginProbe plugin = new GpManagerPluginProbe();
        final ItemContainer inventory = proxy(ItemContainer.class, (method,args) -> {
            if (method.equals("contains")) return (int)args[0] == bag || pack.containsKey((int)args[0]);
            if (method.equals("getItems")) {
                List<Item> items = new ArrayList<>(); items.add(new Item(bag,1));
                pack.forEach((id,q)->items.add(new Item(id,q.intValue()))); return items.toArray(new Item[0]);
            }
            return null;
        });
        Fixture() { this(ReviewFixesTest.engine()); }
        Fixture(Engine engine) {
            this.engine = engine;
            plugin.client = proxy(Client.class, (method,args) -> {
                if (method.equals("getItemContainer")) return (int)args[0]==InventoryID.INV?inventory:null;
                if (method.equals("getTickCount")) return tick;
                if (method.equals("getSelectedWidget")) return selected;
                if (method.equals("getGameState")) return GameState.LOGGED_IN;
                return null;
            });
            plugin.engine=engine; plugin.config=ReviewFixesTest.CONFIG;
            plugin.containers=new ContainerSnapshotFactory(plugin.client,null);
            plugin.targets=GpManagerPluginProbe.tracker(plugin.client,engine,plugin.config);
            plugin.charges=new ChargeIntake(plugin.client,null,plugin.config,engine,null);
            plugin.activity=new ActivityDetector(plugin.config,engine);
            plugin.idle=new CharacterIdleTracker(null,new CharacterIdleModel());
            engine.setBaseline(capture());
        }
        ContainerSnapshot capture() { return plugin.containers.capture(false,false); }
        void baseline() { engine.setBaseline(capture()); }
        void use(int item, boolean reverse) {
            selected=widget(reverse?bag:item,149<<16);
            plugin.onMenuOptionClicked(menu("Use",MenuAction.WIDGET_TARGET_ON_WIDGET,
                widget(reverse?item:bag,149<<16),"Item -> Looting bag"));
        }
        void changed() { plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV,inventory)); }
        void settle() {
            engine.markDirty(); engine.processIfDirty(capture(),T0+600L*(++tick),null);
            engine.processIfDirty(capture(),T0+600L*(++tick),null);
        }
        void snapshot(Map<Integer,Long> contents) {
            ItemContainer box=proxy(ItemContainer.class,(m,a)->m.equals("getItems")?contents.entrySet().stream()
                .map(e->new Item(e.getKey(),e.getValue().intValue())).toArray(Item[]::new):null);
            plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.LOOTING_BAG,box));
        }
        void pickup(int item,long qty,long inventoryPart) {
            plugin.lastPack=new HashMap<>(pack);plugin.bagGain(item,qty,plugin.carries(ItemID.LOOTING_BAG_OPEN));
            if(inventoryPart>0)pack.merge(item,inventoryPart,Long::sum);
            tick++; plugin.bagSettle(); settle();
        }
        long risk() {
            plugin.pvp=new PvpContextReader(plugin.client);plugin.pvp.context=PvpContext.DANGEROUS_PVP;
            plugin.valuation=new Valuation(plugin.config,id->null,(id,active)->0, id -> false, Valuation.Economy.NORMAL) {
                @Override public List<Flow> value(Map<Integer,Long> items) {
                    Map<Integer,Long> priced=new HashMap<>(items);priced.remove(ItemID.LOOTING_BAG);
                    return engine.valuation.value(priced);
                }
            };
            if(tick%2!=0)tick++;
            plugin.samplePvpState();return plugin.currentPvp.risk;
        }
    }
    interface Call { Object value(String method,Object[] args); }
    static <T>T proxy(Class<T> type,Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)-> {
            Object value=call.value(m.getName(),a);
            if(value!=null)return value;
            if(m.getReturnType()==boolean.class)return false;
            if(m.getReturnType()==int.class)return 0;
            if(m.getReturnType()==long.class)return 0L;
            return null;
        }));
    }
    static Widget widget(int item,int parent) {
        return proxy(Widget.class,(m,a)-> {
            if(m.equals("getItemId"))return item;
            if(m.equals("getId"))return parent;
            if(m.equals("getName"))return "Item";
            return null;
        });
    }
    static MenuOptionClicked menu(String option,MenuAction action,Widget widget,String target) {
        return new MenuOptionClicked(proxy(MenuEntry.class,(m,a)-> {
            if(m.equals("getOption"))return option;
            if(m.equals("getTarget"))return target;
            if(m.equals("getType"))return action;
            if(m.equals("getWidget"))return widget;
            if(m.equals("getItemId"))return widget==null?-1:widget.getItemId();
            if(m.equals("getParam1"))return 149<<16;
            return null;
        }));
    }
    @Test public void nativeInventoryDepositsMoveStockWithoutChangingProfitInEitherUseOrder() {
        for(boolean reverse:new boolean[]{false,true}) for(int bag:new int[]{ItemID.LOOTING_BAG,ItemID.LOOTING_BAG_OPEN}) {
            Fixture f=new Fixture();f.bag=bag;f.pack.put(FOOD,2L);f.baseline();
            f.use(FOOD,reverse);f.pack.remove(FOOD);f.changed();f.changed();f.settle();
            assertEquals(Map.of(FOOD,2L),f.plugin.lootingBag());
            assertEquals("custody never spends the food",0,f.engine.metrics(T0+100_000).net);
        }
    }
    @Test public void nativeCoinsQuantityPromptAndPartialCallbacksCountOnlyMeasuredMovementOnce() {
        Fixture f=new Fixture();f.pack.put(COINS,111L);f.baseline();f.use(COINS,false);
        f.plugin.onMenuOptionClicked(menu("Continue",MenuAction.WIDGET_CONTINUE,null,""));
        f.pack.put(COINS,71L);f.changed();f.changed();
        assertEquals(Map.of(COINS,40L),f.engine.bagStock());
        f.pack.remove(COINS);f.changed();f.settle();
        assertEquals(Map.of(COINS,111L),f.engine.bagStock());assertEquals(0,f.engine.metrics(T0+100_000).net);
    }
    @Test public void aRejectedDepositAndLaterEatCannotInventBagStockOrHideItsCost() {
        Fixture f=new Fixture();f.pack.put(FOOD,1L);f.baseline();f.use(FOOD,false);f.changed();
        assertTrue(f.engine.bagStock().isEmpty());
        f.plugin.onMenuOptionClicked(menu("Eat",MenuAction.CC_OP,widget(FOOD,149<<16),"Food"));
        f.pack.remove(FOOD);f.changed();f.settle();
        assertTrue(f.engine.bagStock().isEmpty());assertEquals(-100,f.engine.metrics(T0+100_000).net);
    }
    @Test public void directAndSplitPickupsAddOnlyTheBagPortionAndRetainAllIncome() {
        for(int inventoryPart:new int[]{0,1,3}) {
            Fixture f=new Fixture();f.pickup(FOOD,3,inventoryPart);
            assertEquals(inventoryPart==3?Map.of():Map.of(FOOD,(long)(3-inventoryPart)),f.engine.bagStock());
            assertEquals(300,f.engine.metrics(T0+100_000).net);
            f.plugin.bagSettle();f.settle();assertEquals(300,f.engine.metrics(T0+100_000).net);
        }
    }
    @Test public void aDifferentOpenContainerNeverBecomesLootingBagStock() {
        Fixture f=new Fixture();f.bag=ItemID.FISH_BARREL_OPEN;f.baseline();f.pickup(FOOD,2,0);
        assertTrue(f.engine.bagStock().isEmpty());assertEquals(200,f.engine.metrics(T0+100_000).net);
    }
    @Test public void aFreshSnapshotBeforeOrAfterInventorySupersedesAnInferredDeposit() {
        for(boolean before:new boolean[]{false,true}) {
            Fixture f=new Fixture();f.pack.put(FOOD,2L);f.baseline();f.use(FOOD,false);
            if(before)f.snapshot(Map.of(FOOD,2L));
            f.pack.remove(FOOD);f.changed();
            if(!before)f.snapshot(Map.of(FOOD,2L));
            assertEquals(Map.of(FOOD,2L),f.engine.bagStock());f.settle();
            assertEquals(0,f.engine.metrics(T0+100_000).net);
        }
    }
    @Test public void aFreshSnapshotBetweenPickupAndSettleCannotDoubleTheStock() {
        Fixture f=new Fixture();f.plugin.bagGain(FOOD,3,true);f.snapshot(Map.of(FOOD,3L));
        f.tick++;f.plugin.bagSettle();f.settle();
        assertEquals(Map.of(FOOD,3L),f.engine.bagStock());assertEquals(300,f.engine.metrics(T0+100_000).net);
    }
    @Test public void storedNewLootRetainsTheIncomeThatHasNotSettledYet() {
        Fixture f=new Fixture();f.pack.put(FOOD,2L);f.use(FOOD,false);f.pack.remove(FOOD);f.changed();f.settle();
        assertEquals(Map.of(FOOD,2L),f.engine.bagStock());assertEquals(200,f.engine.metrics(T0+100_000).net);
    }
    @Test public void accountFenceAndExpiryDiscardAnUnconfirmedStore() {
        for(boolean fence:new boolean[]{true,false}) {
            Fixture f=new Fixture();f.pack.put(FOOD,1L);f.baseline();f.use(FOOD,false);
            if(fence)f.engine.events.owner(2);else f.engine.events.tick(f.engine.events.now+100);
            f.pack.remove(FOOD);f.changed();assertTrue(f.engine.bagStock().isEmpty());
        }
    }
    @Test public void foreignWidgetsAndConsumedClicksCannotAuthorizeInventoryCustody() {
        for(boolean consumed:new boolean[]{true,false}) {
            Fixture f=new Fixture();f.pack.put(FOOD,1L);f.baseline();
            f.selected=widget(FOOD,consumed?149<<16:12<<16);
            MenuOptionClicked event=menu("Use",MenuAction.WIDGET_TARGET_ON_WIDGET,widget(f.bag,149<<16),"Looting bag");
            if(consumed)event.consume();f.plugin.onMenuOptionClicked(event);
            f.pack.remove(FOOD);f.changed();assertTrue(f.engine.bagStock().isEmpty());
        }
    }
    @Test public void newStockSurvivesRelogAndTheD4LossOffsetsItsPickupIncome() {
        Fixture f=new Fixture();f.pickup(FOOD,3,0);long net=f.engine.metrics(T0+100_000).net;
        f.engine.restoreProfile("same",f.engine.savedState(),T0+101_000);
        assertEquals(Map.of(FOOD,3L),f.engine.bagStock());assertEquals(net,f.engine.metrics(T0+102_000).net);
        assertTrue(f.engine.resume(T0+102_000,PauseReason.RECOVERY));
        Transaction loss=f.engine.markPkDeath(T0+103_000,f.plugin.lootingBag(),null,f.engine.events.owner);
        assertNotNull(loss);assertEquals(-300,loss.net());assertEquals(0,f.engine.metrics(T0+104_000).net);
    }
    @Test public void theActualRiskSampleUpdatesWithoutOpeningCheck() {
        Fixture f=new Fixture();f.pack.put(FOOD,2L);f.baseline();
        assertEquals("these two inventory items are protected while unskulled",0,f.risk());
        f.use(FOOD,false);f.pack.remove(FOOD);f.changed();
        assertEquals("bag items cannot use protected slots",200,f.risk());
        f.pickup(COINS,3,0);assertEquals(500,f.risk());
    }
    @Test public void aClosedLootingBagBesideAnOpenFishBarrelDoesNotReceiveItsCatch() {
        Fixture f=new Fixture();f.bag=ItemID.LOOTING_BAG;f.pack.put(ItemID.FISH_BARREL_OPEN,1L);f.baseline();
        f.pickup(FOOD,2,0);assertTrue(f.engine.bagStock().isEmpty());
    }
    @Test public void otherSpendingInTheSameCallbackRetainsItsOwnCost() {
        Fixture f=new Fixture();f.pack.put(FOOD,2L);f.pack.put(COINS,20L);f.baseline();f.use(FOOD,false);
        f.pack.remove(FOOD);f.pack.put(COINS,10L);f.changed();f.settle();
        assertEquals(Map.of(FOOD,2L),f.engine.bagStock());
        assertTrue(f.engine.session().transactions().stream().flatMap(t->t.flows().stream())
            .anyMatch(flow->flow.item==COINS && flow.quantity==-10));
        assertFalse(f.engine.session().transactions().stream().flatMap(t->t.flows().stream())
            .anyMatch(flow->flow.item==FOOD && flow.quantity<0));
    }
    @Test public void aLocalDeathDiscardsPendingBagMovementsBeforeTheInventoryWipe() {
        for(int kind:new int[]{Inbox.BAG_GAIN,Inbox.BAG_STORE}) {
            LootingBagPkLossTest.Fixture f=new LootingBagPkLossTest.Fixture();
            f.engine.rememberBag(Map.of());f.pack=Map.of(FOOD,1L);
            f.engine.setBaseline(new ContainerSnapshot(Map.of(FOOD,1L,ItemID.LOOTING_BAG,1L)));
            Observation e=f.engine.events.add(kind,FOOD,1,30,null);e.flag=true;e.held=1;
            f.die();f.pack=Map.of();f.carried=false;
            f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV,f.inventory));
            f.plugin.bagSettle();
            assertTrue("pending movement cannot resurrect lost stock",f.engine.bagStock().isEmpty());
            assertNull(f.engine.events.peek(kind));
        }
    }
}
