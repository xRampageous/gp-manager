package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic accounting boundaries, not gameplay acceptance. */
public class RaidHistoryTest {
    final Engine engine;
    final GpManagerPlugin plugin=new GpManagerPlugin();
    final Map<Integer,Integer> bits=new HashMap<>(),vars=new HashMap<>();
    final Map<Integer,String> names=new HashMap<>();
    final Set<Integer> widgets=new HashSet<>();
    final Map<Integer,ItemContainer> containers=new HashMap<>();
    int tick=1;
    boolean clientThread=true, instance=false;
    GameState gameState=GameState.LOGGED_IN;
    final RaidHistory.Native nativeRaids;
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
    public RaidHistoryTest() { this(ReviewFixesTest.engine()); }
    RaidHistoryTest(Engine engine) {
        this.engine=engine;
        Player local=proxy(Player.class,(p,m,a)->m.getName().equals("getName")?"Player":null);
        plugin.engine=engine; plugin.config=engine.config;
        plugin.client=proxy(Client.class,(p,m,a)->{
            switch(m.getName()) {
                case "getGameState":return gameState;
                case "isClientThread":return clientThread;
                case "isInInstancedRegion":return instance;
                case "getTickCount":return tick;
                case "getLocalPlayer":return local;
                case "getVarbitValue":return bits.getOrDefault((int)a[0],0);
                case "getVarpValue":return vars.getOrDefault((int)a[0],0);
                case "getVarcStrValue":return names.get((int)a[0]);
                case "getItemContainer":return containers.get((int)a[0]);
                case "getWidget":return widgets.contains((int)a[0])?proxy(Widget.class,(q,n,b)->n.getName().equals("isHidden")?false:null):null;
                default:return null;
            }
        });
        plugin.containers=new ContainerSnapshotFactory(plugin.client,null);
        nativeRaids=new RaidHistory.Native(plugin); plugin.raidNative=nativeRaids;
    }
    long now(){return ReviewFixesTest.T0+tick*600;}
    void insideToa() {instance=true;bits.put(14345,1);bits.put(14354,1);bits.put(14319,1);names.put(1099,"Player");widgets.add(481<<16);}
    void start() {vars.put(3645,10);nativeRaids.pulse(now());tick++;insideToa();nativeRaids.pulse(now());}
    void complete(){tick++;vars.put(3645,11);nativeRaids.pulse(now());}
    ItemContainer chest(long amount) {return proxy(ItemContainer.class,(p,m,a)->m.getName().equals("getItems")?(amount>0?new Item[]{new Item(565,(int)amount)}:new Item[0]):null);}
    MenuOptionClicked click(String option) {return RewardChestDispositionTest.menu(option,771);}
    void take() {widgets.add(771<<16);containers.put(811,chest(50));nativeRaids.menu(click("Take-all"),now());}
    Transaction row(int id,long qty,int price,TransactionType type) {
        var row=new Transaction(now(),type,Context.GENERIC,"", "General",true,List.of(new Flow(id,"Item "+id,qty,price,qty*price)),Confidence.CONFIRMED,"",null);
        engine.add(row,false);return row;
    }
    Transaction receipt() {return row(565,50,100,TransactionType.LOOT);}
    void inv(){nativeRaids.container(net.runelite.api.gameval.InventoryID.INV,chest(0));}
    void deplete(){containers.put(811,chest(0));nativeRaids.container(811,containers.get(811));}
    String id(){return engine.raids.state.runs.get(0).id;}
    long net(String id) {engine.raids.read(id);long[] value=engine.raids.money(id);return value[0]-value[1];}
    Transaction claimed(){start();complete();take();inv();var row=receipt();nativeRaids.booked(row,false,0);deplete();return row;}
    void exit(){bits.put(14345,0);for(int i=0;i<3;i++){tick++;nativeRaids.pulse(now());}}

    @Test public void nativeParticipantStartsOneAttempt(){start();assertNotNull(engine.raids.activeId());assertFalse(engine.raids.state.runs.get(0).partial);}
    @Test public void spectatorCannotStart(){insideToa();names.put(1099,"Other");nativeRaids.pulse(now());assertEquals(0,engine.raids.state.seen);}
    @Test public void firstLoginInRaidIsPartial(){insideToa();nativeRaids.pulse(now());assertTrue(engine.raids.state.runs.get(0).partial);}
    @Test public void existingClearCountIsNotHistory(){vars.put(3645,200);nativeRaids.pulse(now());assertTrue(engine.raids.state.runs.isEmpty());}
    @Test public void counterIncrementCompletesWithoutMoney(){start();complete();assertTrue(engine.raids.state.runs.get(0).complete);assertEquals(0,engine.session().metrics(now()).net);}
    @Test public void counterJumpCannotCreditClear(){start();vars.put(3645,12);nativeRaids.pulse(now());assertFalse(engine.raids.state.runs.get(0).complete);assertTrue(engine.raids.state.runs.get(0).partial);}
    @Test public void reopeningDoesNotAddClear(){claimed();take();assertEquals(1,engine.raids.state.runs.size());assertEquals(5000,net(id()));}
    @Test public void previewDoesNotBookMoney(){start();complete();take();assertEquals(0,engine.session().metrics(now()).net);assertFalse(engine.raids.state.runs.get(0).claimed);}
    @Test public void receiptBeforeDepletion(){claimed();assertEquals(5000,net(id()));assertTrue(engine.raids.state.runs.get(0).claimed);}
    @Test public void depletionBeforeReceipt(){start();complete();take();deplete();inv();var row=receipt();nativeRaids.booked(row,false,0);assertEquals(5000,net(id()));}
    @Test public void firstClaimCanConfirmCompletion(){start();take();deplete();inv();var row=receipt();nativeRaids.booked(row,false,0);assertTrue(engine.raids.state.runs.get(0).complete);}
    @Test public void counterBetweenClickAndReceiptPreservesScope(){start();take();complete();deplete();inv();var row=receipt();nativeRaids.booked(row,false,0);assertEquals(5000,net(id()));}
    @Test public void untradeableReceiptConfirmsClear(){start();take();deplete();inv();var row=row(565,50,0,TransactionType.LOOT);nativeRaids.booked(row,false,0);assertTrue(engine.raids.state.runs.get(0).complete);assertEquals(0,net(id()));}
    @Test public void rewardWithoutObservedStartIsPartial(){bits.put(14319,1);take();deplete();inv();var row=receipt();nativeRaids.booked(row,false,0);assertTrue(engine.raids.state.runs.get(0).partial);assertEquals(5000,net(id()));}
    @Test public void bankRouteRequiresBankClick(){start();complete();take();deplete();var row=receipt();nativeRaids.booked(row,true,811);assertNull(row.flows.get(0).raid);}
    @Test public void confirmedBankAllSharesSameOwner(){start();complete();take();nativeRaids.menu(click("Bank-all"),now());deplete();var row=receipt();nativeRaids.booked(row,true,811);assertEquals(5000,net(id()));}
    @Test public void wrongChestCannotAssignBankReceipt(){start();complete();take();nativeRaids.menu(click("Bank-all"),now());deplete();var row=receipt();nativeRaids.booked(row,true,581);assertNull(row.flows.get(0).raid);}
    @Test public void sameItemWithoutInventoryScopeCannotAssign(){start();complete();take();deplete();var row=receipt();nativeRaids.booked(row,false,0);assertNull(row.flows.get(0).raid);}
    @Test public void destroyCancelsRequest(){start();complete();take();nativeRaids.menu(click("Destroy"),now());deplete();inv();var row=receipt();nativeRaids.booked(row,false,0);assertNull(row.flows.get(0).raid);}
    @Test public void anotherClickBeforeTheChestUpdateKeepsTheRequest(){start();complete();take();nativeRaids.menu(RewardChestDispositionTest.menu("Walk here",161),now());inv();var row=receipt();nativeRaids.booked(row,false,0);deplete();assertEquals(5000,net(id()));assertTrue(engine.raids.state.runs.get(0).claimed);}
    @Test public void consumedTakeCannotArm(){start();complete();widgets.add(771<<16);containers.put(811,chest(50));var click=click("Take-all");click.consume();nativeRaids.menu(click,now());assertNull(nativeRaids.request);}
    @Test public void expiredRequestCannotAssign(){start();complete();take();tick+=11;deplete();inv();var row=receipt();nativeRaids.booked(row,false,0);assertNull(row.flows.get(0).raid);}
    @Test public void ownerChangeFencesCallbacks(){start();complete();take();inv();engine.raids.restore(null);var row=receipt();nativeRaids.booked(row,false,0);assertNull(row.flows.get(0).raid);}
    @Test public void counterEpochMismatchRefusesClaim(){start();complete();vars.put(3645,12);take();assertNull(nativeRaids.request);}
    @Test public void inventoryMoneyIsBookedOnce(){claimed();assertEquals(5000,engine.session().metrics(now()).net);engine.raids.read(id());assertEquals(5000,engine.summary().scope(List.of(engine.session())).net);}
    @Test public void normalSuppliesNeverAcquireRaidTag(){start();var supply=row(2434,-1,200,TransactionType.CONSUMPTION);assertNull(supply.flows.get(0).raid);assertEquals(-200,engine.session().metrics(now()).net);assertEquals(0,net(id()));}
    @Test public void normalChargesNeverAcquireRaidTag(){start();var charge=row(565,-2,100,TransactionType.CONSUMPTION);assertNull(charge.flows.get(0).raid);exit();assertEquals(-200,engine.session().metrics(now()).net);assertEquals(0,net(id()));}
    @Test public void leavingAddsNoTransaction(){start();int before=engine.session().transactions.size();exit();assertEquals(before,engine.session().transactions.size());assertNull(engine.raids.activeId());}
    @Test public void sameTickAbsenceCannotEndRaid(){start();bits.put(14345,0);for(int i=0;i<8;i++)nativeRaids.pulse(now());assertNotNull(engine.raids.activeId());}
    @Test public void paidDeathFeeOnly(){start();engine.markPvmDeath(5,Map.of(4151,1L), Map.of(), engine.events.owner);exit();var fee=engine.bookDeathFee(100000,null,now());assertEquals(id(),fee.flows.get(0).raid);assertEquals(-100000,net(id()));}
    @Test public void returnedGearCannotEnterRaidResult(){start();engine.markPvmDeath(5,Map.of(4151,1L), Map.of(), engine.events.owner);var returned=row(4151,1,2000000,TransactionType.TRANSFER);nativeRaids.booked(returned,false,0);assertNull(returned.flows.get(0).raid);assertEquals(0,net(id()));}
    @Test public void zeroFeeCreatesNoBooking(){start();engine.raids.death();assertNull(engine.bookDeathFee(0,null,now()));assertTrue(engine.session().transactions.isEmpty());}
    @Test public void laterNonRaidDeathClearsRaidFeeOwner(){start();exit();engine.markPvmDeath(5,Map.of(), Map.of(), engine.events.owner);var fee=engine.bookDeathFee(100000,null,now());assertNull(fee.flows.get(0).raid);}
    @Test public void laterUnknownDeathClearsRaidFeeOwner(){start();engine.raids.death();engine.markUnknownDeath();var fee=engine.bookDeathFee(100000,null,now());assertNull(fee.flows.get(0).raid);}
    @Test public void ledgerIgnoreChangesRaidResult(){var row=claimed();row.applyCorrection(Correction.IGNORE, "test");engine.session().transactionChanged(row);assertEquals(0,net(id()));}
    @Test public void retainedCompactedReceiptsKeepTotal(){var row=claimed();engine.session().transactions.remove(row);engine.session().compactTransaction(row);engine.session().notifyChanged();assertEquals(5000,net(id()));assertEquals(5000,engine.savedSession("raid:"+id()).view().scope(List.of(engine.historySession("raid:"+id()))).net);}
    @Test public void savedWrapperIdentitySurvivesTwoLookups(){claimed();assertSame(engine.savedSession("raid:"+id()),engine.savedSession("raid:"+id()));assertNotNull(LedgerData.capture(engine,now(),LedgerData.Entry.current().withScope(LedgerData.Scope.HISTORY,"raid:"+id(),"Raid")));}
    @Test public void capturedStateIsDetached(){start();var state=engine.savedState();var copy=SaveCopies.copy(state);copy.raidHistory.runs.clear();assertEquals(1,state.raidHistory.runs.size());assertEquals(1,engine.raids.state.runs.size());}
    @Test public void frozenWriteCopiesCompactedArrays(){var row=claimed();engine.session().compactTransaction(row);var copy=SaveCopies.copy(engine.session());copy.compactedRaids.get(id())[0]=7;assertEquals(5000,engine.session().compactedRaids.get(id())[0]);}
    @Test public void oldSavesNeedNoRaidMigration(){engine.raids.restore(null);assertEquals(0,engine.raids.state.seen);}
    @Test public void tfaHistoryIsEnabled(){engine.raids.observe(new RaidHistory.Sample(3,true,"Local",new int[]{1}),now(),tick);assertEquals(8,engine.raids.state.seen);}
    @Test public void restartRestoresPendingEpoch(){start();complete();var state=engine.raids.snapshot();engine.raids.restore(state);take();assertNotNull(nativeRaids.request);}
    @Test public void completeRestoreBeforeFirstPreviewDoesNotLoseClaim(){start();complete();engine.raids.restore(engine.raids.snapshot());take();deplete();inv();var row=receipt();nativeRaids.booked(row,false,0);assertEquals(5000,net(id()));}
    @Test public void laterPartialClaimCannotRefillConsumedProof(){claimed();containers.put(811,chest(50));nativeRaids.menu(click("Take-all"),now());assertNull(nativeRaids.request);}
    @Test public void attributionPreservesCapturedPrice(){var row=claimed();assertEquals(100,row.flows.get(0).unitPrice);assertEquals(5000,row.flows.get(0).value);}
    @Test public void partialSourceQuantityLeavesResidualUntagged(){start();complete();take();deplete();inv();var row=row(565,80,100,TransactionType.LOOT);nativeRaids.booked(row,false,0);assertEquals(8000,engine.session().metrics(now()).net);assertEquals(5000,net(id()));}
    @Test public void coxNativeMembershipAndCounter(){nativeRaids.pulse(now());bits.put(5432,1);bits.put(5415,0);bits.put(5424,1);tick++;nativeRaids.pulse(now());assertEquals(0,RaidHistory.kind(id()));vars.put(1532,1);tick++;nativeRaids.pulse(now());assertTrue(engine.raids.state.runs.get(0).complete);assertEquals(0,net(id()));}
    @Test public void tobNativeMembershipAndCounter(){nativeRaids.pulse(now());bits.put(6440,2);bits.put(6441,0);names.put(330,"Player");widgets.add(28<<16);tick++;nativeRaids.pulse(now());assertEquals(1,RaidHistory.kind(id()));vars.put(1748,1);tick++;nativeRaids.pulse(now());assertTrue(engine.raids.state.runs.get(0).complete);}
    @Test public void tobMidwayInterfaceCannotClaimRaidRewards(){nativeRaids.menu(RewardChestDispositionTest.menu("Take-all",405),now());assertNull(nativeRaids.request);}
    @Test public void missingPersonalClaimFlagRefuses(){start();complete();bits.put(14319,0);take();assertNull(nativeRaids.request);}
    @Test public void loadingDoesNotSplitMetadata(){start();String old=id();gameState=GameState.LOADING;for(int i=0;i<6;i++){tick++;nativeRaids.pulse(now());}assertEquals(old,engine.raids.activeId());}
    @Test public void offThreadCannotReadRaidState(){insideToa();clientThread=false;nativeRaids.pulse(now());assertTrue(engine.raids.state.runs.isEmpty());}
    @Test public void loggedOutCannotAssignLateReceipt(){start();complete();take();inv();deplete();gameState=GameState.LOGIN_SCREEN;var row=receipt();nativeRaids.booked(row,false,0);assertNull(row.flows.get(0).raid);}
    @Test public void toaDelayedClaimSurvivesLogout(){start();complete();String old=id();engine.raids.logout(now());bits.put(14345,0);take();deplete();inv();var row=receipt();nativeRaids.booked(row,false,0);assertEquals(old,id());assertEquals(5000,net(old));}
    @Test public void nextToaAttemptForfeitsOldPendingClaim(){start();complete();String old=id();exit();tick++;bits.put(14345,1);nativeRaids.pulse(now());assertNotEquals(old,id());assertTrue(engine.raids.state.claims.isEmpty());}
    @Test public void compactionCopyDoesNotShareArrays(){claimed();var row=engine.session().transactions.get(0);engine.session().compactTransaction(row);var map=RaidHistory.copyMoney(engine.session().compactedRaids);map.get(id())[0]=1;assertEquals(5000,engine.session().compactedRaids.get(id())[0]);}
    @Test public void missingCanonicalOwnerIsMarkedPartial(){claimed();engine.raids.state.runs.get(0).owners.add("missing-session");engine.changed();engine.raids.read(id());assertTrue(engine.raids.missing.contains(id()));}
    @Test public void moneyFoldIsRevisionCached(){claimed();var first=engine.raids.read(id());assertSame(first,engine.raids.read(id()));var fee=engine.bookDeathFee(10,null,now());assertNotNull(fee);assertNotSame(first,engine.raids.read(id()));}
    @Test public void unusedProfilesNeedNoRaidPayload(){assertNull(engine.savedState().raidHistory);assertNull(SaveCopies.copy(engine.savedState()).raidHistory);}
    @Test public void allThreeKindsStayVisibleAfterHistoryPruning(){for(int kind=0;kind<3;kind++)engine.raids.start(kind,"Normal",now(),false);assertEquals(7,engine.raids.state.seen);}
    @Test public void nativeStateReadsShareTheSidebarMonitor(){new RaidHistory.Native(plugin){RaidHistory.Sample sample(String[] rule){assertTrue(Thread.holdsLock(engine));return super.sample(rule);}}.pulse(now());}
    @Test public void loggedInRewardRoomDoesNotDuplicateTheKnownClear(){start();complete();String old=id();engine.raids.logout(now());tick++;nativeRaids.pulse(now());take();deplete();inv();var row=receipt();nativeRaids.booked(row,false,0);assertEquals(5000,net(old));assertEquals(1,engine.raids.state.runs.stream().filter(run->run.complete).count());}
    @Test public void newClearReplacesTheOldRewardEpoch(){start();complete();exit();tick++;bits.put(14345,1);nativeRaids.pulse(now());String current=id();tick++;vars.put(3645,12);nativeRaids.pulse(now());take();deplete();inv();var row=receipt();nativeRaids.booked(row,false,0);assertEquals(1,engine.raids.state.claims.size());assertEquals(current,row.flows.get(0).raid);assertEquals(5000,net(current));}
}
