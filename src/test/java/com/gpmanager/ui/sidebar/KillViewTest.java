package com.gpmanager;

import com.google.gson.Gson;
import java.util.*;
import net.runelite.api.gameval.ItemID;
import org.junit.*;
import static org.junit.Assert.*;
import static com.gpmanager.LiveCategoryLayoutTest.*;

/** KILLS is a derived history view: no booking, key value estimate or opponent information. */
public class KillViewTest {
    java.util.function.LongSupplier previous;
    @Before public void clock(){previous=SafeMath.clock;SafeMath.clock=()->NOW;}
    @After public void restoreClock(){SafeMath.clock=previous;}
    static Semantics.Table kills(){return Semantics.Table.valueOf("KILL");}
    static Transaction loot(Engine engine,String encounter,int item,long gp,long at){
        var row=Tx.of(at,TransactionType.PK_LOOT,Context.PK_LOOT,"PK loot",true,
                List.of(new Flow(item,"Loot "+item,1,gp,gp,PriceSource.GRAND_EXCHANGE)));
        engine.session().addTransaction(row,2000,false);engine.session().attachToEncounter(row.id(),encounter,false);
        return row;
    }
    static Transaction claim(Engine engine,String claimId,long gp,long at){
        var row=Tx.of(at,TransactionType.GAIN,Context.GENERIC,"Claimed loot",true,
                List.of(new Flow(995,"Coins",gp,1,gp,PriceSource.FACE_VALUE)));
        row.setSourceClaimId(claimId);engine.session().addTransaction(row,2000,false);return row;
    }
    static Transaction key(Engine engine,String encounter,int... items){
        var flows=new ArrayList<Flow>();for(int item:items)
            flows.add(new Flow(item,"Loot key",1,0,0,PriceSource.DEFERRED_CLAIM));
        return engine.recordKeyAudit(flows,encounter,3000);
    }
    static Engine populated(){
        var engine=StoredChargeFixture.engine();
        var first=engine.session().addEncounter(true,1000);
        loot(engine,first,526,100,1000);loot(engine,first,536,200,1100);
        var second=engine.session().addEncounter(true,2000);loot(engine,second,11212,400,2000);
        engine.session().addTransaction(Tx.of(2500,TransactionType.LOOT,Context.LOOT,"PvM loot",true,
                List.of(new Flow(4151,"Abyssal whip",1,9000,9000))),2000,false);
        var death=engine.session().addEncounter(false,1500);
        var deathRow=Tx.of(1500,TransactionType.PK_DEATH_LOSS,Context.PK_DEATH,"Death",true,
                List.of(new Flow(10004,"Lost",-1,500000,-500000,PriceSource.GRAND_EXCHANGE)));
        engine.session().addTransaction(deathRow,2000,false);engine.session().attachToEncounter(deathRow.id(),death,false);
        return engine;
    }
    @Test public void heroAddsClickableKillsAndLeavesBestKillNonClickable()throws Exception{
        MilestoneTest.onEdt(()->{
            var engine=populated();var panel=SavedSessionTest.panel(engine);
            panel.bindPvp(()->new PvpState(true,false,false,0));SidebarPanelProbe.refresh(panel);
            var cells=panel.live.strips(panel.snapshot).stream().flatMap(Collection::stream).toArray(Hero.Cell[]::new);
            var kill=Arrays.stream(cells).filter(cell->cell.label.equals("KILLS")).findFirst().orElse(null);
            assertNotNull("KILLS beside DEATHS",kill);assertNotNull(kill.open);assertEquals("2",kill.value);
            assertNull(Arrays.stream(cells).filter(cell->cell.label.equals("BEST")).findFirst().orElseThrow().open);
            kill.open.run();SidebarPanelProbe.refresh(panel);
            assertEquals(kills(),panel.category.table);assertEquals(Shell.LIVE,panel.shell.current);
            assertEquals(700,focused(panel).data.income.total);
            assertTrue(focused(panel).income.title.getText().startsWith("KILLS"));
            assertEquals(2,focused(panel).income.rows.size());kill.open.run();SidebarPanelProbe.refresh(panel);
            assertNull(panel.category);assertTrue(panel.live.recent.isVisible());return null;
        });
    }
    @Test public void oneKillKeepsSplitPickupsTogetherAndExcludesPvmSuppliesAndDeaths(){
        var engine=populated();String before=new Gson().toJson(engine.exportState());
        var data=LedgerData.capture(engine,NOW,category(kills()));
        assertEquals(2,data.income.groups.size());assertEquals(700,data.income.total);
        var first=data.income.groups.stream().filter(group->group.value==300).findFirst().orElseThrow();
        assertEquals(2,first.ids.size());assertEquals(2,first.entries.size());
        assertEquals(9700,engine.metrics(NOW).income);assertEquals(before,new Gson().toJson(engine.exportState()));
        var detail=LedgerData.capture(engine,NOW,category(kills()).withSelection(null,null,first.id));
        assertEquals(2,detail.detail.entries.size());
    }
    @Test public void aRecordedPvpDeathNeverAppearsInKills(){
        var engine=populated();
        var death=engine.session().transactions().stream().filter(t->t.original()==TransactionType.PK_DEATH_LOSS).findFirst().orElseThrow();
        var data=LedgerData.capture(engine,NOW,category(kills()));
        assertEquals(2,data.income.groups.size());assertEquals(700,data.income.total);
        assertTrue(data.income.groups.stream().flatMap(group->group.entries.stream())
                .noneMatch(entry->entry.transaction.equals(death.id())));
    }
    @Test public void transferCorrectionRemovesTheReceiptFromKillsButNotTheRestOfGains(){
        var engine=populated();
        var corrected=engine.session().transactions().stream().filter(t->t.original()==TransactionType.PK_LOOT).findFirst().orElseThrow();
        engine.correctTransaction(corrected.id(),Correction.TRANSFER,"Owner choice");
        var kills=LedgerData.capture(engine,NOW,category(kills()));
        assertEquals(2,kills.income.groups.size());assertEquals(600,kills.income.total);
        assertTrue(kills.income.groups.stream().flatMap(group->group.entries.stream())
                .noneMatch(entry->entry.transaction.equals(corrected.id())));
        assertEquals(9600,engine.metrics(NOW).income);
        assertEquals(9600,LedgerData.capture(engine,NOW,category(Semantics.Table.GAINS)).income.total);
    }
    @Test public void keyContentsJoinTheirKillAfterClaimWithoutCountingTheKeyAsIncome(){
        var engine=StoredChargeFixture.engine();var encounter=engine.session().addEncounter(true,1000);
        var audit=key(engine,encounter,ItemID.WILDY_LOOT_KEY0);loot(engine,encounter,526,100,3100);
        var pending=LedgerData.capture(engine,NOW,category(kills()));
        assertEquals(1,pending.income.groups.size());assertTrue(pending.income.groups.get(0).incomplete());
        assertEquals(100,pending.income.total);
        engine.claims.settle(audit.id(),1);claim(engine,audit.id(),700,5000);
        var collected=LedgerData.capture(engine,NOW,category(kills()));
        assertEquals(1,collected.income.groups.size());assertFalse(collected.income.groups.get(0).incomplete());
        assertEquals(800,collected.income.total);assertEquals(800,engine.metrics(NOW).income);
        assertEquals(1,engine.session().pk().kills);
        assertTrue(collected.income.groups.get(0).entries.stream().anyMatch(entry->entry.item==995&&entry.value==700));
    }
    @Test public void independentKeyClaimSuffixesAndUnrelatedClaimsCannotDuplicateAKill(){
        var engine=StoredChargeFixture.engine();var encounter=engine.session().addEncounter(true,1000);
        var audit=key(engine,encounter,ItemID.WILDY_LOOT_KEY0,ItemID.WILDY_LOOT_KEY1);
        engine.claims.settle(audit.id()+"#1",1);claim(engine,audit.id()+"#1",300,5000);
        assertTrue(LedgerData.capture(engine,NOW,category(kills())).income.groups.get(0).incomplete());
        engine.claims.settle(audit.id()+"#2",1);claim(engine,audit.id()+"#2",500,6000);
        claim(engine,"unrelated-non-pvp-key",9000,7000);
        var data=LedgerData.capture(engine,NOW,category(kills()));
        assertEquals(1,data.income.groups.size());assertEquals(800,data.income.total);
        assertFalse(data.income.groups.get(0).incomplete());assertEquals(9800,engine.metrics(NOW).income);
    }
    @Test public void fullyLinkedStackCompletesOnlyAfterEveryClaimReceipt(){
        var engine=StoredChargeFixture.engine();var encounter=engine.session().addEncounter(true,1000);
        var audit=key(engine,encounter,ItemID.WILDY_LOOT_KEY0,ItemID.WILDY_LOOT_KEY0);
        claim(engine,audit.id()+"#1",100,5000);
        assertTrue(LedgerData.capture(engine,NOW,category(kills())).income.groups.get(0).incomplete());
        claim(engine,audit.id()+"#2",100,6000);
        var data=LedgerData.capture(engine,NOW,category(kills()));
        assertFalse(data.income.groups.get(0).incomplete());assertEquals(200,data.income.total);
        assertEquals(200,engine.metrics(NOW).income);
    }
    @Test public void emptyAndCompactedKillsExplainTheRetainedCount()throws Exception{
        MilestoneTest.onEdt(()->{
            var engine=StoredChargeFixture.engine();var encounter=engine.session().addEncounter(true,1000);
            var panel=SavedSessionTest.panel(engine);panel.bindPvp(()->new PvpState(true,false,false,0));
            SidebarPanelProbe.refresh(panel);open(panel,"KILLS");SidebarPanelProbe.refresh(panel);
            var page=focused(panel);assertTrue(page.data.income.groups.isEmpty());
            assertEquals(0,engine.metrics(NOW).net);
            assertTrue(hasText(page.body,"Retained, linked loot only."));
            loot(engine,encounter,526,100,1100);engine.session().compactBefore(NOW,null);
            SidebarPanelProbe.refresh(panel);if(panel.category==null||panel.category.table!=kills())open(panel,"KILLS");
            SidebarPanelProbe.refresh(panel);var compacted=focused(panel);
            assertTrue(compacted.data.income.groups.isEmpty());assertTrue(hasText(compacted.body,"Retained, linked loot only."));
            assertEquals(1,engine.session().pk().kills);
            assertEquals(100,engine.metrics(NOW).net);return null;
        });
    }
    static boolean hasText(java.awt.Component component,String text){
        if(component instanceof javax.swing.JLabel&&((javax.swing.JLabel)component).getText().contains(text))return true;
        if(component instanceof java.awt.Container)for(var child:((java.awt.Container)component).getComponents())if(hasText(child,text))return true;
        return false;
    }
    @Test public void liveClockResetReloadAndOwnerChangePreserveTheGrindScope(){
        var engine=populated();engine.newLive(60000);
        var saved=new Gson().fromJson(new Gson().toJson(engine.savedState()),SavedState.class);
        var restored=StoredChargeFixture.engine();restored.restore(saved,NOW);
        var data=LedgerData.capture(restored,NOW,category(kills()));
        assertEquals(2,data.income.groups.size());assertEquals(700,data.income.total);
        restored.restoreProfile("rsprofile.other",null,NOW);
        assertTrue(LedgerData.capture(restored,NOW,category(kills())).income.groups.isEmpty());
    }
    @Test public void correctedAndUnpricedLootRetainTheirOriginalReceipts(){
        var engine=populated();var loot=engine.session().transactions().get(0);
        engine.correctTransaction(loot.id(),Correction.IGNORE,"Owner choice");
        var encounter=engine.session().addEncounter(true,8000);
        var unknown=Tx.of(8000,TransactionType.PK_LOOT,Context.PK_LOOT,"PK loot",true,
                List.of(new Flow(999999,"Unpriced loot",1,0,0,PriceSource.UNPRICED)));
        engine.session().addTransaction(unknown,2000,false);engine.session().attachToEncounter(unknown.id(),encounter,false);
        var data=LedgerData.capture(engine,NOW,category(kills()));
        assertEquals(3,data.income.groups.size());assertEquals(600,data.income.total);
        assertTrue(data.income.groups.stream().anyMatch(Semantics.Group::incomplete));
        assertTrue(data.income.groups.stream().flatMap(group->group.entries.stream()).anyMatch(entry->entry.corrected));
    }
    @Test public void keyOnlyKillStaysVisibleAndOpensItsExactNeutralReceipt()throws Exception{
        MilestoneTest.onEdt(()->{
            var engine=StoredChargeFixture.engine();var encounter=engine.session().addEncounter(true,1000);
            var audit=key(engine,encounter,ItemID.WILDY_LOOT_KEY0);
            var panel=SavedSessionTest.panel(engine);panel.bindPvp(()->new PvpState(true,false,false,0));
            SidebarPanelProbe.refresh(panel);open(panel,"KILLS");SidebarPanelProbe.refresh(panel);
            var page=focused(panel);assertEquals(1,page.income.rows.size());assertEquals(0,page.data.income.total);
            var group=page.data.income.groups.get(0);assertTrue(group.incomplete());
            page.income.rows.get(0).open.run();SidebarPanelProbe.refresh(panel);
            assertEquals(group.id,focused(panel).data.detail.group.id);
            assertEquals(1,focused(panel).detailItems.rows.size());
            assertEquals(audit.id(),focused(panel).data.detail.entries.get(0).transaction);
            assertFalse(audit.counts());assertEquals(0,engine.metrics(NOW).income);return null;
        });
    }
    @Test public void nativeStackedKeysKeepUnlinkedPartialCollectionsHonest(){
        for(int initial:new int[]{1,2}){
        var engine=nativeEngine();int key=ItemID.WILDY_LOOT_KEY0;
        engine.markPkLoot(Map.of(key,(long)initial),50,1100);settle(engine,Map.of(key,(long)initial),1600);
        var pending=LedgerData.capture(engine,NOW,category(kills()));
        assertEquals(1,pending.income.groups.size());assertTrue(pending.income.groups.get(0).incomplete());
        assertEquals(1,engine.session().pk().kills);
        for(int claimed=1;claimed<=initial;claimed++){
            engine.setChestVisible(true);engine.observeKeyContainer(key,Map.of(526,1L));
            engine.observeKeyContainer(key,Map.of());settle(engine,Map.of(key,(long)initial-claimed,526,(long)claimed),1600+1000*claimed);
            var data=LedgerData.capture(engine,NOW,category(kills()));
            // Production intentionally writes sourceClaimId only when the claim closes.
            // Never assign earlier ordinary GAINS by time, item or guessed encounter.
            assertEquals(1,data.income.groups.size());assertEquals(claimed==initial?100:0,data.income.total);
            assertEquals(initial>1||claimed<initial,data.income.groups.get(0).incomplete());
            assertEquals(100L*claimed,engine.metrics(NOW).income);
        }
        }
    }
    @Test public void aPartlyReceivedSingleKeyCannotStealEarlierUnlinkedGains(){
        var engine=nativeEngine();int key=ItemID.WILDY_LOOT_KEY0;
        engine.markPkLoot(Map.of(key,1L),50,1100);settle(engine,Map.of(key,1L),1600);
        engine.setChestVisible(true);engine.observeKeyContainer(key,Map.of(526,2L,554,1L));
        engine.observeKeyContainer(key,Map.of());settle(engine,Map.of(key,1L,526,1L),2600);
        assertTrue(LedgerData.capture(engine,NOW,category(kills())).income.groups.get(0).incomplete());
        assertEquals(0,LedgerData.capture(engine,NOW,category(kills())).income.total);
        assertEquals(100,engine.metrics(NOW).income);
        settle(engine,Map.of(526,2L,554,1L),3600);
        var data=LedgerData.capture(engine,NOW,category(kills()));
        assertEquals(1,data.income.groups.size());assertEquals(200,data.income.total);
        assertEquals(300,engine.metrics(NOW).income);assertTrue(engine.pendingClaims().isEmpty());
    }
    static Engine nativeEngine(){
        var config=new GpManagerConfig(){public int stabilizationTicks(){return 0;}};
        var engine=new Engine(deltas->{var flows=new ArrayList<Flow>();
            deltas.forEach((id,quantity)->flows.add(new Flow(id,"Item "+id,quantity,100,quantity*100)));
            return flows;},new TransactionClassifier(),config);
        engine.ensureSession(1000);engine.setBaseline(ContainerSnapshot.empty());
        return engine;
    }
    static void settle(Engine engine,Map<Integer,Long> items,long at){
        engine.markDirty();var snapshot=new ContainerSnapshot(items);
        if(engine.processIfDirty(snapshot,at,null)==null)engine.processIfDirty(snapshot,at+1,null);
    }
    static void previews(java.io.File out)throws java.io.IOException{
        var clock=SafeMath.clock;
        try{
        for(int width:new int[]{213,225,300}){
            var engine=populated();var encounter=engine.session().addEncounter(true,3000);
            key(engine,encounter,ItemID.WILDY_LOOT_KEY0);var panel=SavedSessionTest.panel(engine);
            panel.bindPvp(()->new PvpState(true,false,false,0));SidebarPanelProbe.refresh(panel);
            var frame=new javax.swing.JFrame("Offline KILLS view");
            frame.setUndecorated(true);frame.setFocusableWindowState(false);frame.setLocation(-10000,-10000);
            try{
                frame.add(panel);frame.pack();frame.setSize(width,760);frame.setVisible(true);open(panel,"KILLS");
                for(int i=0;i<2;i++){SidebarPanelProbe.refresh(panel);frame.validate();}
                shot(panel,out,"kills-view-"+width,width);
                focused(panel).income.rows.get(0).open.run();SidebarPanelProbe.refresh(panel);frame.validate();
                shot(panel,out,"kills-pending-"+width,width);
            }finally{frame.dispose();}
        }
        }finally{SafeMath.clock=clock;}
    }
    static void shot(SidebarPanel panel,java.io.File out,String name,int width)throws java.io.IOException{
        var image=new java.awt.image.BufferedImage(width,760,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();panel.printAll(graphics);graphics.dispose();
        javax.imageio.ImageIO.write(image,"png",new java.io.File(out,name+".png"));
    }
}
