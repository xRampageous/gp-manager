package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.LongSupplier;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.*;
import static org.junit.Assert.*;

/** One source-backed collection matrix for all four raid interfaces and their normal modes. */
public class AllRaidChestMatrixTest {
    static final int[] GROUP={539,23,771,973},INV={581,612,811,1029};
    static final int[] ITEMS={InterfaceID.RaidsRewards.ITEMS,InterfaceID.TobChests.ITEMS,
            InterfaceID.ToaChests.ITEMS,InterfaceID.TfaChests.ITEMS};
    static final List<String[]> BUTTONS=GameData.rows("all_raid_chest_buttons");
    LongSupplier previous;
    @Before public void clock(){previous=SafeMath.clock;SafeMath.clock=()->FracturedArchiveReadinessTest.T0;}
    @After public void restoreClock(){SafeMath.clock=previous;}
    static String[] buttonRow(int kind,String field){return BUTTONS.stream().filter(r->Integer.parseInt(r[0])==kind&&r[4].equals(field)).findFirst().orElseThrow();}
    static MenuOptionClicked button(int kind,String field){var r=buttonRow(kind,field);return TfaLootButtonsTest.button(r[6],Integer.parseInt(r[5]));}

    static class Core extends FracturedArchiveReadinessTest.Fixture {
        final int kind;
        Core(int kind){
            this.kind=kind;
            plugin.client=(Client)Proxy.newProxyInstance(Client.class.getClassLoader(),new Class<?>[]{Client.class},(p,m,a)->{
                switch(m.getName()){
                    case "isClientThread":return true;
                    case "getGameState":return GameState.LOGGED_IN;
                    case "getVarbitValue":case "getVarpValue":return 0;
                    case "getTickCount":return tick;
                    case "getItemContainer":return (int)a[0]==INV[kind]?chest:null;
                    default:return null;
                }
            });
            plugin.containers=new ContainerSnapshotFactory(plugin.client,null);
            plugin.targets=GpManagerPluginProbe.tracker(plugin.client,engine,plugin.config);
            plugin.charges=new ChargeIntake(plugin.client,null,plugin.config,engine,null);
        }
        @Override void observe(){plugin.onLootReceived(new LootReceived(RaidHistory.RULES.get(kind)[1],0,
                LootRecordType.EVENT,List.of(new ItemStack(FracturedArchiveReadinessTest.SHARD,50)),1,null));}
        void click(String field){plugin.onMenuOptionClicked(button(kind,field));}
        @Override void shrink(int q){items=q==0?new Item[0]:new Item[]{new Item(FracturedArchiveReadinessTest.SHARD,q)};
            tick++;plugin.chestChanged(INV[kind],chest);}
        long net(){return engine.metrics(FracturedArchiveReadinessTest.T0+60000).net;}
    }

    static class Native {
        final RaidHistoryTest f=new RaidHistoryTest();
        final int kind,mode;
        final String[] rule;
        String run;
        Native(int kind,int mode){
            this.kind=kind;this.mode=mode;rule=RaidHistory.RULES.get(kind);
            f.nativeRaids.pulse(f.now());inside(true);f.tick++;f.nativeRaids.pulse(f.now());
            assertNotNull(f.engine.raids.active);assertEquals(kind,RaidHistory.kind(f.engine.raids.active.id));
            run=f.engine.raids.active.id;
            String[] counters=rule[11].split(",");int index=kind==0||kind==1?mode:kind==2?mode:0;
            f.vars.put(Integer.parseInt(counters[index]),1);f.tick++;f.nativeRaids.pulse(f.now());
            assertTrue(f.engine.raids.find(run).complete);
        }
        void inside(boolean yes){
            int slot=Integer.parseInt(rule[7]),hud=Integer.parseInt(rule[5]);
            f.bits.put(slot,kind==2?1:0);
            if(kind==0){f.bits.put(Integer.parseInt(rule[6]),yes?1:0);f.bits.put(Integer.parseInt(rule[8]),yes?1:0);f.bits.put(Integer.parseInt(rule[12]),mode);}
            else{
                f.names.put(Integer.parseInt(rule[9]),yes?"Player":"");
                if(yes)f.widgets.add(hud<<16);else f.widgets.remove(hud<<16);
                f.bits.put(Integer.parseInt(rule[8]),yes?(kind==2?1:2):0);f.instance=yes;
                if(kind==2)f.bits.put(Integer.parseInt(rule[12]),mode*150);
            }
        }
        void open(){int flag=Integer.parseInt(rule[4]);if(flag>0)f.bits.put(flag,1);
            f.widgets.add(GROUP[kind]<<16);f.containers.put(INV[kind],f.chest(50));}
        void click(String field){open();f.nativeRaids.menu(button(kind,field),f.now());assertNotNull(f.nativeRaids.request);}
        void shrink(int remaining){f.containers.put(INV[kind],f.chest(remaining));f.nativeRaids.container(INV[kind],f.containers.get(INV[kind]));}
        Transaction receipt(long q){return f.row(565,q,100,TransactionType.LOOT);}
        void booked(Transaction row,boolean bank){f.nativeRaids.booked(row,bank,bank?INV[kind]:0);}
        long all(){var view=f.engine.savedSession("raid:all");return view.view().scope(List.of(view.session)).net;}
    }

    @Test public void allNativeFooterIdsLabelsAndOperationTypesMatchRuneLite() throws Exception {
        Class<?>[] classes={net.runelite.api.gameval.InterfaceID.RaidsRewards.class,
                net.runelite.api.gameval.InterfaceID.TobChests.class,net.runelite.api.gameval.InterfaceID.ToaChests.class,
                net.runelite.api.gameval.InterfaceID.TfaChests.class};
        assertEquals(12,BUTTONS.size());
        for(var row:BUTTONS){int kind=Integer.parseInt(row[0]);assertEquals(GROUP[kind],Integer.parseInt(row[1]));
            assertEquals(Integer.valueOf(INV[kind]),GpManagerPlugin.CHESTS.get(GROUP[kind]));
            int widget=Integer.parseInt(row[5]);assertEquals(widget,classes[kind].getField(row[4]).getInt(null));
            var event=button(kind,row[4]);assertEquals(row[6],event.getMenuOption());
            assertEquals(Integer.parseInt(row[7]),event.getId());assertEquals(MenuAction.valueOf(row[8]),event.getMenuAction());}
    }
    @Test public void bankAndTakeCountOnceWithOrWithoutLootTrackerPreviews(){
        for(int kind=0;kind<4;kind++)for(boolean preview:new boolean[]{false,true})for(boolean bank:new boolean[]{false,true}){
            var c=new Core(kind);if(preview)c.observe();assertEquals(0,c.net());
            c.click(bank?"BANK":"INVENTORY");assertEquals(0,c.net());c.shrink(0);
            if(!bank){assertEquals(0,c.net());c.settle(Map.of(FracturedArchiveReadinessTest.SHARD,50L));}
            assertEquals(5000,c.net());c.shrink(0);assertEquals(5000,c.net());
            assertEquals(50,RewardChestBankAllTest.received(c.engine,FracturedArchiveReadinessTest.SHARD));}
    }
    @Test public void discardAfterRefusedOrPartialBankNeverCountsTheRemainder(){
        for(int kind=0;kind<4;kind++)for(int banked:new int[]{0,30}){
            var c=new Core(kind);c.observe();c.click("BANK");if(banked>0)c.shrink(50-banked);
            c.click("DISCARD");c.shrink(0);assertEquals(banked*100,c.net());
            assertEquals(banked,RewardChestBankAllTest.received(c.engine,FracturedArchiveReadinessTest.SHARD));}
    }
    @Test public void partialInventoryTakeThenBankCountsOnlyTheActualTwoPortions(){
        for(int kind=0;kind<4;kind++){
            var c=new Core(kind);c.observe();c.click("INVENTORY");c.shrink(30);
            c.settle(Map.of(FracturedArchiveReadinessTest.SHARD,20L));assertEquals(2000,c.net());
            c.click("BANK");c.shrink(0);assertEquals(5000,c.net());
            assertEquals(50,RewardChestBankAllTest.received(c.engine,FracturedArchiveReadinessTest.SHARD));}
    }
    @Test public void everyRaidModeAndBothCallbackOrdersKeepGlobalAndAllRaidTotalsAligned(){
        for(int kind=0;kind<4;kind++)for(int mode=0;mode<(kind==0?2:kind==3?1:3);mode++)
            for(boolean bank:new boolean[]{false,true})for(boolean first:new boolean[]{false,true}){
                var n=new Native(kind,mode);n.click(bank?"BANK":"INVENTORY");if(!bank)n.f.inv();var row=n.receipt(50);
                if(first)n.booked(row,bank);n.shrink(0);if(!first)n.booked(row,bank);
                assertEquals(n.run,row.flows().get(0).raid);assertEquals(5000,n.f.net(n.run));assertEquals(5000,n.all());
                assertEquals(5000,n.f.engine.session().metrics(n.f.now()).net);
                n.booked(row,bank);assertEquals(5000,n.all());}
    }
    @Test public void nativePartialTakeThenBankKeepsOneAttemptAndExactTotal(){
        for(int kind=0;kind<4;kind++){
            var n=new Native(kind,0);n.click("INVENTORY");n.f.inv();n.shrink(30);var take=n.receipt(20);n.booked(take,false);
            assertEquals(2000,n.all());
            n.f.nativeRaids.menu(button(kind,"BANK"),n.f.now());n.shrink(0);var bank=n.receipt(30);n.booked(bank,true);
            assertEquals(n.run,take.flows().get(0).raid);assertEquals(n.run,bank.flows().get(0).raid);
            assertEquals(5000,n.all());assertEquals(1,n.f.engine.raids.state.runs.size());}
    }
    @Test public void perItemTakeAmountsStayBoundedByTheirMeasuredChestRemoval(){
        for(int kind=0;kind<4;kind++)for(String option:List.of("Take","Take-1","Take-5","Take-X","Take-All")){
            var n=new Native(kind,0);n.open();
            n.f.nativeRaids.menu(TfaLootButtonsTest.button(option,ITEMS[kind]),n.f.now());n.f.inv();n.shrink(45);
            var row=n.receipt(8);n.booked(row,false);
            assertEquals(800,n.f.engine.session().metrics(n.f.now()).net);assertEquals(500,n.all());}
    }
    @Test public void consumedAndExamineClicksPreservePendingCollectionForEveryRaid(){
        for(int kind=0;kind<4;kind++)for(boolean consumed:new boolean[]{false,true}){
            var n=new Native(kind,0);n.click("BANK");var request=n.f.nativeRaids.request;
            var event=consumed?button(kind,"DISCARD"):TfaLootButtonsTest.button("Examine",GROUP[kind]<<16);
            if(consumed)event.consume();n.f.nativeRaids.menu(event,n.f.now());assertSame(request,n.f.nativeRaids.request);
            n.shrink(0);var row=n.receipt(50);n.booked(row,true);assertEquals(5000,n.all());}
    }
    @Test public void lobbyCollectionUsesTheOriginalClaimAndSurvivesAllowedReloads(){
        for(int kind=1;kind<4;kind++){
            var n=new Native(kind,0);n.inside(false);n.f.engine.raids.end(n.f.now());
            if(kind!=1)n.f.engine.raids.logout(n.f.now()); // ToB's unclaimed rewards are lost on logout.
            n.click("BANK");n.shrink(0);var row=n.receipt(50);n.booked(row,true);
            assertEquals(n.run,row.flows().get(0).raid);assertEquals(1,n.f.engine.raids.state.runs.size());assertEquals(5000,n.all());}
    }
    @Test public void anotherPlayersRewardAndExpiredOrForeignOwnerProofCannotAcquireRaidTags(){
        for(int kind=0;kind<4;kind++){
            var n=new Native(kind,0);n.open();int flag=Integer.parseInt(n.rule[4]);
            if(flag>0)n.f.bits.put(flag,0);else n.inside(false);
            n.f.nativeRaids.menu(button(kind,"BANK"),n.f.now());assertNull(n.f.nativeRaids.request);
            var row=n.receipt(50);n.booked(row,true);assertNull(row.flows().get(0).raid);
            for(boolean owner:new boolean[]{false,true}){
                n=new Native(kind,0);n.click("BANK");
                if(owner)n.f.engine.raids.restore(null);else n.f.tick+=11;
                n.shrink(0);row=n.receipt(50);n.booked(row,true);assertNull(row.flows().get(0).raid);}
        }
    }
    @Test public void zeroValueUntradeablesStillConfirmACollectionWithoutInventingMoney(){
        for(int kind=0;kind<4;kind++){
            var n=new Native(kind,0);n.click("INVENTORY");n.f.inv();n.shrink(0);
            var row=n.f.row(565,50,0,TransactionType.LOOT);n.booked(row,false);
            assertEquals(n.run,row.flows().get(0).raid);assertEquals(0,n.all());assertTrue(n.f.engine.raids.find(n.run).claimed);}
    }
    @Test public void midRaidSuppliesAndPrivateStorageAreNotRewardChestCollection(){
        for(int kind=0;kind<4;kind++)for(int group:new int[]{405,550,551}){
            assertFalse(GpManagerPlugin.CHESTS.containsKey(group));var n=new Native(kind,0);n.click("BANK");
            var request=n.f.nativeRaids.request;n.f.nativeRaids.menu(TfaLootButtonsTest.button("Take-all",group<<16),n.f.now());
            assertSame(request,n.f.nativeRaids.request);assertEquals(0,n.all());}
    }
}
