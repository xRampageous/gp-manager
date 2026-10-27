package com.gpmanager;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class RaidRouteTest {
    RaidHistoryTest f=new RaidHistoryTest();
    void remaining(long qty) {f.containers.put(811,f.chest(qty));f.nativeRaids.container(811,f.containers.get(811));}
    Transaction row(long qty,boolean bank) {
        var row=f.row(565,qty,100,TransactionType.LOOT);f.nativeRaids.booked(row,bank,bank?811:0);return row;
    }
    void start() {f.start();f.complete();f.take();}
    @Test public void takeThenBankRetainsBothReceipts() {
        start();f.inv();remaining(30);f.nativeRaids.menu(f.click("Bank-all"),f.now());remaining(0);
        var bank=row(30,true);var take=row(20,false);
        assertEquals(f.id(),take.flows.get(0).raid);assertEquals(f.id(),bank.flows.get(0).raid);
        assertEquals(5000,f.net(f.id()));assertEquals(5000,f.engine.session().metrics(f.now()).net);
    }
    @Test public void bankThenTakeRetainsALateBankReceipt() {
        start();f.nativeRaids.menu(f.click("Bank-all"),f.now());remaining(30);
        f.nativeRaids.menu(f.click("Take-all"),f.now());f.inv();remaining(0);
        row(30,false);row(20,true);assertEquals(5000,f.net(f.id()));
    }
    @Test public void routesCannotSpendEachOthersProof() {
        start();f.inv();remaining(30);f.nativeRaids.menu(f.click("Bank-all"),f.now());remaining(0);
        var take=row(80,false);assertEquals(2000,f.net(f.id()));
        assertEquals(20,take.flows.stream().filter(flow->flow.raid!=null).mapToLong(flow->flow.quantity).sum());
        row(30,true);assertEquals(5000,f.net(f.id()));assertEquals(11000,f.engine.session().metrics(f.now()).net);
    }
    @Test public void alternatingRoutesKeepTheirSeparateTotals() {
        start();f.inv();remaining(40);f.nativeRaids.menu(f.click("Bank-all"),f.now());remaining(30);
        f.nativeRaids.menu(f.click("Take-all"),f.now());f.inv();remaining(0);
        row(10,true);row(40,false);assertEquals(5000,f.net(f.id()));
    }
    @Test public void aLateInventoryWitnessBelongsToTheEarlierTake() {
        start();remaining(30);f.nativeRaids.menu(f.click("Bank-all"),f.now());remaining(0);f.inv();
        row(30,true);row(20,false);assertEquals(5000,f.net(f.id()));
    }
    @Test public void aWaitingBankReceiptCannotBeRegisteredAsInventoryLoot() {
        start();f.inv();remaining(30);f.nativeRaids.menu(f.click("Bank-all"),f.now());
        var bank=row(30,true);f.nativeRaids.booked(bank,false,0);
        assertNull(bank.flows.get(0).raid);remaining(0);assertEquals(f.id(),bank.flows.get(0).raid);
        row(20,false);assertEquals(5000,f.net(f.id()));
    }
    @Test public void destroyKeepsReceivedLootAndNeverCountsDestroyedRemainder() {
        start();f.inv();remaining(30);f.nativeRaids.menu(f.click("Bank-all"),f.now());remaining(20);
        f.nativeRaids.menu(f.click("Destroy"),f.now());remaining(0);
        row(20,false);row(10,true);assertEquals(3000,f.net(f.id()));
        row(20,true);assertEquals(3000,f.net(f.id()));
    }
    @Test public void switchingRoutesDoesNotExtendTheEarlierDeadline() {
        start();f.inv();remaining(30);f.tick+=9;f.nativeRaids.menu(f.click("Bank-all"),f.now());
        f.tick+=2;remaining(0);row(20,false);assertEquals(0,f.net(f.id()));
        row(30,true);assertEquals(3000,f.net(f.id()));
    }
    @Test public void accountChangeDiscardsBothRoutes() {
        start();f.inv();remaining(30);f.nativeRaids.menu(f.click("Bank-all"),f.now());remaining(0);
        f.engine.raids.restore(null);var take=row(20,false);var bank=row(30,true);
        assertNull(take.flows.get(0).raid);assertNull(bank.flows.get(0).raid);
    }
    @Test public void allFourRaidsAndBothReceiptOrdersKeepBothRoutes() {
        for(int kind=0;kind<4;kind++)for(boolean bankFirst:new boolean[]{true,false}){
            f=new RaidHistoryTest();var rule=RaidHistory.RULES.get(kind);
            int chest=Integer.parseInt(rule[2]),group=Integer.parseInt(rule[3]),flag=Integer.parseInt(rule[4]);
            f.bits.put(flag,1);if(kind==0){f.bits.put(5432,1);f.bits.put(5424,1);}
            f.widgets.add(group<<16);f.containers.put(chest,f.chest(50));
            f.nativeRaids.menu(RewardChestDispositionTest.menu("Take-all",group),f.now());f.inv();
            // The next menu must checkpoint the visible remainder even if its callback was missed.
            f.containers.put(chest,f.chest(30));
            f.nativeRaids.menu(RewardChestDispositionTest.menu("Bank-all",group),f.now());
            var bank=f.row(565,30,100,TransactionType.LOOT);var take=f.row(565,20,100,TransactionType.LOOT);
            if(bankFirst)f.nativeRaids.booked(bank,true,chest);
            else f.nativeRaids.booked(take,false,0);
            f.containers.put(chest,f.chest(0));f.nativeRaids.container(chest,f.containers.get(chest));
            f.nativeRaids.booked(bank,true,chest);f.nativeRaids.booked(bank,false,0);f.nativeRaids.booked(take,false,0);
            assertEquals("kind "+kind+" first "+bankFirst,5000,f.net(f.id()));
            f.engine.raids.read("all");assertEquals(5000,f.engine.savedSession("raid:all").session.metrics(f.now()).net);
            assertEquals(5000,f.engine.session().metrics(f.now()).net);
        }
    }
    @Test public void actualBankCallbackAndStabilizedInventoryCountExactlyOnce() {
        var original=SafeMath.clock;SafeMath.clock=()->f.now();
        try{
            start();f.inv();remaining(30);f.nativeRaids.menu(f.click("Bank-all"),f.now());f.plugin.bankAll(771);
            remaining(0);f.plugin.chestChanged(811,f.containers.get(811));
            f.engine.markLoot(Map.of(565,20L),50,"Raid rewards","Tombs of Amascut");
            var held=new ContainerSnapshot(Map.of(565,20L));f.engine.processIfDirty(held,f.now(),null);f.tick+=4;
            var take=f.engine.processIfDirty(held,f.now(),null);assertNotNull(take);f.plugin.transactionBooked(take,f.now());
            assertEquals(5000,f.net(f.id()));assertEquals(5000,f.engine.session().metrics(f.now()).net);
            assertEquals(2,f.engine.session().transactions().size());
            for(var receipt:f.engine.session().transactions())assertFalse(ReviewRules.needsReview(receipt));
            f.plugin.chestChanged(811,f.containers.get(811));assertNull(f.engine.processIfDirty(held,f.now(),null));
            assertEquals(5000,f.net(f.id()));
        }finally{SafeMath.clock=original;}
    }
    @Test public void sharedPositiveCopyKeepsAllRaidInputGuardsAndIsDetached() {
        var source=new LinkedHashMap<Integer,Long>();source.put(null,1L);source.put(-1,5L);source.put(0,2L);
        source.put(565,50L);source.put(560,null);source.put(554,-2L);source.put(555,0L);
        var copy=RaidHistory.positive(source);assertEquals(Map.of(565,50L),copy);
        copy.put(565,20L);assertEquals(Long.valueOf(50),source.get(565));
        RaidHistory.positive(null).put(565,1L);
    }
}
