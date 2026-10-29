package com.gpmanager;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import org.junit.Test;
import static org.junit.Assert.*;

/** Only source-confirmed borrowed quantities are free; brought supplies retain their original costs. */
public class RaidSupplyLoanTest {
    static final int FOOD=391, BREW=6685, THREE=6687, TWO=6689, ONE=6691;
    static Engine engine() {
        var e=new Engine(changes->{
            var flows=new ArrayList<Flow>();changes.forEach((id,q)->{
                int dose=id==BREW?4:id==THREE?3:id==TWO?2:id==ONE?1:0;
                flows.add(new Flow(id,dose>0?"Saradomin brew("+dose+")":id==FOOD?"Manta ray":"Item "+id,
                        q,dose>0?dose*100:100,q*(dose>0?dose*100:100)));
            });return flows;
        },new TransactionClassifier(),new GpManagerConfig(){
            @Override public int stabilizationTicks(){return 0;}
            @Override public boolean keepTransferAuditRows(){return true;}
        });
        e.ensureSession(ReviewFixesTest.T0);e.setBaseline(FixtureReads.empty());return e;
    }
    static RaidHistoryTest fixture(int kind) {
        var f=new RaidHistoryTest(engine());f.instance=true;
        f.bits.put(kind==1?6440:16386,2);f.names.put(kind==1?330:1573,"Player");
        f.widgets.add((kind==1?28:974)<<16);f.nativeRaids.pulse(f.now());
        assertEquals(kind,RaidHistory.kind(f.engine.raids.activeId()));
        if(kind==3){f.widgets.add(300<<16);f.containers.put(1030,RaidHistoryTest.proxy(ItemContainer.class,(p,m,a)->
                m.getName().equals("getItems")?new Item[]{new Item(FOOD,10),new Item(BREW,6)}:null));}
        return f;
    }
    static MenuOptionClicked click(int kind,int item,String option) {
        MenuEntry entry=RaidHistoryTest.proxy(MenuEntry.class,(p,m,a)->{
            switch(m.getName()){
                case "getOption":return option;
                case "getTarget":return "Supplies";
                case "getItemId":return item;
                case "getParam1":return (kind==1?405:300)<<16;
                case "getType":return MenuAction.CC_OP;
                case "getIdentifier":return 1;
                default:return m.getReturnType()==int.class?0:m.getReturnType()==boolean.class?false:null;
            }
        });return new MenuOptionClicked(entry);
    }
    static void settle(RaidHistoryTest f,Map<Integer,Long> items){LmsBorrowedItemsTest.settle(f.engine,items,f.now()+1000);}
    @Test public void bothSupplyShopsGrantAndUseOnlyTheirActualBorrowedFoodAtZero() {
        for(int kind:new int[]{1,3}){
            var f=fixture(kind);f.engine.setBaseline(new ContainerSnapshot(Map.of(FOOD,2L)));
            f.nativeRaids.menu(click(kind,FOOD,kind==1?"Take-1":"Buy-1"),f.now());
            settle(f,Map.of(FOOD,3L));assertEquals(0,f.engine.metrics(f.now()).income);
            f.engine.noteConsume(FOOD,6,false,ActionKind.EAT,null);settle(f,Map.of(FOOD,2L));
            assertEquals(0,f.engine.metrics(f.now()).cost);
            f.engine.noteConsume(FOOD,6,false,ActionKind.EAT,null);settle(f,Map.of(FOOD,1L));
            assertEquals(100,f.engine.metrics(f.now()).cost);assertTrue(f.engine.getReviewRows(f.now()).isEmpty());
        }
    }
    @Test public void borrowedPotionTracksEveryLeftoverWithoutHidingAnOwnedBottle() {
        for(int kind:new int[]{1,3}){
            var f=fixture(kind);f.engine.setBaseline(new ContainerSnapshot(Map.of(BREW,1L)));
            f.nativeRaids.menu(click(kind,BREW,kind==1?"Take-1":"Buy-1"),f.now());settle(f,Map.of(BREW,2L));
            f.engine.noteConsume(BREW,6,false,ActionKind.DRINK,null);settle(f,Map.of(BREW,1L,THREE,1L));
            f.engine.noteConsume(THREE,6,false,ActionKind.DRINK,null);settle(f,Map.of(BREW,1L,TWO,1L));
            f.engine.noteConsume(TWO,6,false,ActionKind.DRINK,null);settle(f,Map.of(BREW,1L,ONE,1L));
            f.engine.noteConsume(ONE,6,false,ActionKind.DRINK,null);settle(f,Map.of(BREW,1L,229,1L));
            assertEquals(0,f.engine.metrics(f.now()).net);assertTrue(f.engine.session().transactions().isEmpty());
            f.engine.noteConsume(BREW,6,false,ActionKind.DRINK,null);settle(f,Map.of(THREE,1L,229,1L));
            assertEquals(-100,f.engine.metrics(f.now()).net);assertTrue(f.engine.getReviewRows(f.now()).isEmpty());
        }
    }
    @Test public void mixedStockCannotWaiveMoreDosesThanTheRaidGranted() {
        var f=fixture(1);f.engine.setBaseline(new ContainerSnapshot(Map.of(BREW,1L)));
        f.nativeRaids.menu(click(1,BREW,"Take-1"),f.now());settle(f,Map.of(BREW,2L));
        f.engine.noteConsume(BREW,6,false,ActionKind.DRINK,null);settle(f,Map.of(THREE,2L));
        assertEquals(0,f.engine.metrics(f.now()).net);
        f.engine.noteConsume(THREE,6,false,ActionKind.DRINK,null);settle(f,Map.of(TWO,2L));
        assertEquals(0,f.engine.metrics(f.now()).net); // Four raid-provided doses exhausted.
        f.engine.noteConsume(TWO,6,false,ActionKind.DRINK,null);settle(f,Map.of(TWO,1L,ONE,1L));
        assertEquals(-100,f.engine.metrics(f.now()).net);assertTrue(f.engine.getReviewRows(f.now()).isEmpty());
    }
    @Test public void refusedConsumedExamineExpiredAndForeignClicksCannotWaiveAnOrdinaryGain() {
        for(int kind:new int[]{1,3})for(int scenario=0;scenario<5;scenario++){
            var f=fixture(kind);var e=click(kind,FOOD,scenario==1?"Examine":kind==1?"Take-1":"Buy-1");
            if(scenario==0)e.consume();f.nativeRaids.menu(e,f.now());
            if(scenario==2)f.engine.events.now+=20;
            if(scenario==3)f.engine.raids.restore(null);
            if(scenario==4)f.engine.events.owner++;
            settle(f,Map.of(FOOD,1L));assertEquals(100,f.engine.metrics(f.now()).income);
        }
    }
    @Test public void aOneItemRequestCannotHideAnotherItemOrAnExtraIdenticalGain() {
        var f=fixture(3);f.nativeRaids.menu(click(3,FOOD,"Buy-1"),f.now());
        settle(f,Map.of(FOOD,2L,565,4L));assertEquals(500,f.engine.metrics(f.now()).income);
    }
    @Test public void repeatSettlementAndDuplicateCallbacksCannotIncreaseTheFreeStock() {
        var f=fixture(1);f.nativeRaids.menu(click(1,FOOD,"Take-1"),f.now());settle(f,Map.of(FOOD,1L));
        settle(f,Map.of(FOOD,1L));f.engine.noteConsume(FOOD,6,false,ActionKind.EAT,null);settle(f,Map.of());
        settle(f,Map.of(FOOD,1L));f.engine.noteConsume(FOOD,6,false,ActionKind.EAT,null);settle(f,Map.of());
        assertEquals(100,f.engine.metrics(f.now()).income);assertEquals(100,f.engine.metrics(f.now()).cost);
    }
    @Test public void bankingBorrowedFoodPreservesTheFreeQuantity() {
        var f=fixture(1);f.nativeRaids.menu(click(1,FOOD,"Take-1"),f.now());settle(f,Map.of(FOOD,1L));
        f.engine.markContext(Context.TRANSFER,6,"Bank deposit");settle(f,Map.of());f.engine.clearTransfer();
        f.engine.markContext(Context.TRANSFER,6,"Bank withdraw");settle(f,Map.of(FOOD,1L));f.engine.clearTransfer();
        f.engine.noteConsume(FOOD,6,false,ActionKind.EAT,null);settle(f,Map.of());
        assertEquals(0,f.engine.metrics(f.now()).income);assertEquals(0,f.engine.metrics(f.now()).cost);
    }
    @Test public void decantingBorrowedPotionsConservesEveryFreeDose() {
        var f=fixture(1);f.nativeRaids.menu(click(1,BREW,"Take-1"),f.now());settle(f,Map.of(BREW,1L));
        f.engine.markContext(Context.TRANSFER,6,"Decanted");settle(f,Map.of(TWO,2L));f.engine.clearTransfer();
        f.engine.noteConsume(TWO,6,false,ActionKind.DRINK,null);settle(f,Map.of(TWO,1L,ONE,1L));
        f.engine.noteConsume(ONE,6,false,ActionKind.DRINK,null);settle(f,Map.of(TWO,1L,229,1L));
        f.engine.noteConsume(TWO,6,false,ActionKind.DRINK,null);settle(f,Map.of(ONE,1L,229,1L));
        f.engine.noteConsume(ONE,6,false,ActionKind.DRINK,null);settle(f,Map.of(229,2L));
        assertEquals(0,f.engine.metrics(f.now()).net);assertTrue(f.engine.getReviewRows(f.now()).isEmpty());
    }
    @Test public void partialAllowanceKeepsOriginalPricesAndOnlyBooksThePaidDose() {
        var f=fixture(1);f.nativeRaids.menu(click(1,BREW,"Take-1"),f.now());settle(f,Map.of(BREW,1L));
        f.engine.noteConsume(BREW,6,false,ActionKind.DRINK,null);settle(f,Map.of(THREE,1L));
        f.engine.noteConsume(THREE,6,false,ActionKind.DRINK,null);settle(f,Map.of(TWO,1L));
        f.engine.noteConsume(TWO,6,false,ActionKind.DRINK,null);settle(f,Map.of(ONE,1L));
        assertEquals("Remaining allowance after three free doses",1L,f.engine.raids.state.supplies.values().stream().mapToLong(Long::longValue).sum());
        f.engine.markContext(Context.TRANSFER,6,"Banked and decanted with paid stock");settle(f,Map.of(TWO,2L));f.engine.clearTransfer();
        f.engine.noteConsume(TWO,6,false,ActionKind.DRINK,null);settle(f,Map.of(ONE,2L));
        assertEquals(100,f.engine.metrics(f.now()).cost);assertTrue(f.engine.getReviewRows(f.now()).isEmpty());
        var row=f.engine.session().transactions().stream().filter(t->t.counts()).findFirst().orElseThrow();
        assertEquals(GameData.msg("raid-supply-credit"),row.note());
        assertEquals(200,row.flows().stream().filter(Flow::cost).findFirst().orElseThrow().unitPrice);
        assertEquals(-2,row.flows().stream().filter(Flow::cost).findFirst().orElseThrow().quantity);
    }
    @Test public void doseAllowancesSurviveCopyReloadAndStayDetachedFromTheOldOwner() {
        var f=fixture(1);f.nativeRaids.menu(click(1,BREW,"Take-1"),f.now());settle(f,Map.of(BREW,1L));
        var saved=f.engine.raids.snapshot();var original=new HashMap<>(saved.supplies);
        f.engine.raids.restore(saved);assertEquals(original,f.engine.raids.state.supplies);
        saved.supplies.clear();assertEquals(original,f.engine.raids.state.supplies);
        f.engine.noteConsume(BREW,6,false,ActionKind.DRINK,null);settle(f,Map.of(THREE,1L));
        assertEquals(0,f.engine.metrics(f.now()).cost);
        f.engine.raids.restore(new RaidHistory.State());f.engine.noteConsume(THREE,6,false,ActionKind.DRINK,null);settle(f,Map.of(TWO,1L));
        assertEquals(100,f.engine.metrics(f.now()).cost);
        var old=new RaidHistory.State();old.supplies=null;f.engine.raids.restore(old);assertTrue(f.engine.raids.state.supplies.isEmpty());
    }
    static RaidHistoryTest cox() {
        var f=new RaidHistoryTest(engine());f.bits.put(5432,1);f.bits.put(5424,1);f.nativeRaids.pulse(f.now());return f;
    }
    static MenuOptionClicked tool(int object,String option,boolean ground) {
        return new MenuOptionClicked(RaidHistoryTest.proxy(MenuEntry.class,(p,m,a)->{
            switch(m.getName()){
                case "getOption":return option;
                case "getTarget":return "Supplies";
                case "getIdentifier":return object;
                case "getItemId":return -1;
                case "getType":return ground?MenuAction.GROUND_ITEM_THIRD_OPTION:MenuAction.GAME_OBJECT_FIRST_OPTION;
                default:return m.getReturnType()==int.class?0:m.getReturnType()==boolean.class?false:null;
            }
        }));
    }
    @Test public void coxToolsAndTheFreeToaPickaxeNeedTheirOwnNativeSource() {
        int[][] sources={{29711,2347},{31634,590},{31862,1351},{41754,1267},{29771,5341},{29771,952},{29771,5343}};
        for(var source:sources){
            var f=cox();f.nativeRaids.menu(tool(source[0],"Take",false),f.now());settle(f,Map.of(source[1],1L));
            assertEquals("CoX object "+source[0],0,f.engine.metrics(f.now()).income);
        }
        var f=new RaidHistoryTest(engine());f.start();f.nativeRaids.menu(tool(45468,"Take-pickaxe",false),f.now());settle(f,Map.of(1265,1L));
        assertEquals(0,f.engine.metrics(f.now()).income);
        f.nativeRaids.menu(tool(45468,"Take-pickaxe",false),f.now());settle(f,Map.of(1265,1L,1275,1L));
        assertEquals(0,f.engine.metrics(f.now()).income);assertTrue("Stored pickaxes never mint a free allowance",f.engine.raids.state.supplies.isEmpty());
    }
    @Test public void loanedSuppliesLostInAPkDeathCannotWaiveLaterPaidStock() {
        var f=fixture(1);f.nativeRaids.menu(click(1,FOOD,"Take-1"),f.now());settle(f,Map.of(FOOD,1L));
        f.engine.add(new Transaction(f.now(),TransactionType.PK_DEATH_LOSS,Context.PK_DEATH,"Death","PKing",true,
                List.of(new Flow(FOOD,"Manta ray",-1,100,-100)),Confidence.CONFIRMED,"",null),false);
        f.engine.setBaseline(FixtureReads.empty());settle(f,Map.of(FOOD,1L));
        long before=f.engine.metrics(f.now()).cost;
        f.engine.noteConsume(FOOD,6,false,ActionKind.EAT,null);settle(f,Map.of());assertEquals(before+100,f.engine.metrics(f.now()).cost);
    }
    @Test public void everyLoanSourceMatchesPinnedGamevalConstants() throws Exception {
        assertEquals(11,GameData.rows("raid_supplies").size());
        for(var row:GameData.rows("raid_supplies")){
            int source=Integer.parseInt(row[2]),item=Integer.parseInt(row[3]),stock=Integer.parseInt(row[4]);
            if(row[1].equals("CC_OP")){
                var fields=Class.forName("net.runelite.api.gameval.InterfaceID$"+row[5]).getFields();
                assertTrue(Arrays.stream(fields).anyMatch(field->{try{return field.getInt(null)>>>16==source;}catch(Exception ex){throw new AssertionError(ex);}}));
            } else {
                var field=(row[1].equals("GROUND_ITEM_")?net.runelite.api.gameval.ItemID.class
                        :net.runelite.api.gameval.ObjectID.class).getField(row[5]);
                field.setAccessible(true);assertEquals(source,field.getInt(null));
            }
            if(item>0)assertEquals(item,net.runelite.api.gameval.ItemID.class.getField(row[6]).getInt(null));
            if(stock>0)assertEquals(stock,net.runelite.api.gameval.InventoryID.class.getField(row[7]).getInt(null));
        }
    }
}
