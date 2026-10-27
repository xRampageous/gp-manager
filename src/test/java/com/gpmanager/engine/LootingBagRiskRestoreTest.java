package com.gpmanager;

import com.google.gson.Gson;
import java.lang.reflect.*;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

public class LootingBagRiskRestoreTest {
    @org.junit.Before public void bindJson() {JsonCodec.bind(new Gson());}
    @Test public void rememberedContentsAndUnseenRewardsSurviveLoginWithoutBookingAgain() throws Exception {
        Engine engine = TargetFreshStartTest.engine();
        engine.contents.put(562,100L); engine.bag.put(562,20L);
        long net=engine.metrics(20L).net;
        Gson gson=new Gson();
        SavedState saved=gson.fromJson(gson.toJson(engine.savedState()),SavedState.class);
        engine.restoreProfile("same",saved,30L);
        assertEquals(Map.of(562,120L),bag(engine,true,null));
        assertEquals("bag stocks never create an income receipt",net,engine.metrics(40L).net);
        assertTrue("not carried means not at risk",bag(engine,false,null).isEmpty());
        assertEquals(Map.of(562,120L),bag(engine,true,null));
        assertEquals("an observed empty bag supersedes old contents",Map.of(),bag(engine,true,new Item[0]));
    }
    @Test public void anotherAccountAndAResetCannotInheritTheBag() throws Exception {
        Engine engine=TargetFreshStartTest.engine();
        engine.contents.put(562,100L);
        SavedState saved=engine.savedState();
        engine.restoreProfile("other",new SavedState(),30L);
        assertTrue(bag(engine,true,null).isEmpty());
        engine.restoreProfile("same",saved,40L);
        assertEquals(Map.of(562,100L),bag(engine,true,null));
        engine.resetData(50L);
        assertTrue(bag(engine,true,null).isEmpty());
    }
    @Test public void aFreshContainerAndRewardEachContributeOnce() throws Exception {
        Engine engine=TargetFreshStartTest.engine();
        engine.contents.put(562,100L);engine.bag.put(562,20L);
        assertEquals(Map.of(562,120L),bag(engine,true,new Item[]{new Item(562,100)}));
        engine.bag.clear();
        assertEquals(Map.of(562,120L),bag(engine,true,new Item[]{new Item(562,120)}));
        engine.restoreProfile("same",engine.savedState(),50L);
        assertEquals(Map.of(562,120L),bag(engine,true,null));
    }
    static Map<Integer,Long> bag(Engine engine,boolean carried,Item[] contents) throws Exception {
        GpManagerPlugin plugin=new GpManagerPlugin();
        ItemContainer pack=proxy(ItemContainer.class,(method,args)-> {
            if(method.equals("contains"))return carried && (int)args[0]==ItemID.LOOTING_BAG;
            return null;
        });
        ItemContainer bag=contents==null?null:proxy(ItemContainer.class,(method,args)->method.equals("getItems")?contents:null);
        Client client=proxy(Client.class,(method,args)->method.equals("getItemContainer")
                ? (int)args[0]==InventoryID.INV?pack:bag :null);
        for(Object[] binding:new Object[][]{{"client",client},{"engine",engine}}) {
            Field f=GpManagerPlugin.class.getDeclaredField((String)binding[0]);f.setAccessible(true);f.set(plugin,binding[1]);
        }
        if(bag!=null) plugin.rememberBag(bag);
        return plugin.lootingBag();
    }
    interface Call {Object call(String method,Object[] args);}
    static <T>T proxy(Class<T>type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->call.call(m.getName(),a)));}
}
