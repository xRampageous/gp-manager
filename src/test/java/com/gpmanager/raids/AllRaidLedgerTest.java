package com.gpmanager;
import java.util.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AllRaidLedgerTest {
    RaidHistoryTest f=new RaidHistoryTest();
    Transaction loot(int kind,long qty) {
        var run=f.engine.raids.start(kind,"Observed mode",f.now(),false);
        var row=f.row(565,qty,100,TransactionType.LOOT);row.flows.get(0).raid=run.id;
        f.engine.raids.received(run.id,f.engine.session(),f.now());return row;
    }
    long net() {var view=f.engine.savedSession("raid:all");return view.view().scope(List.of(view.session)).net;}
    @Test public void allFourRaidReceiptsAreIncludedOnce() {
        for(int kind=0;kind<4;kind++)loot(kind,10);
        assertEquals(4000,net());assertEquals(4000,f.engine.session().metrics(f.now()).net);
        assertEquals(4,f.engine.savedSession("raid:all").session.transactions().size());
    }
    @Test public void deathFeeFromFailedAttemptAppearsBesideSuccessfulLoot() {
        loot(0,10);f.engine.raids.active=f.engine.raids.start(1,"Observed",f.now(),false);
        f.engine.markPvmDeath(5,Map.of(4151,1L), Map.of(), f.engine.events.owner);f.engine.bookDeathFee(100000,null,f.now());
        assertEquals(-99000,net());
        assertEquals(2,f.engine.savedSession("raid:all").session.transactions().size());
    }
    @Test public void suppliesTradesAndReturnedEquipmentAreNotAdded() {
        loot(2,10);f.row(2434,-1,200,TransactionType.CONSUMPTION);
        f.row(4151,1,2000000,TransactionType.TRANSFER);f.row(5503,1,1000,TransactionType.TRADE);
        assertEquals(1000,net());assertEquals(1,f.engine.savedSession("raid:all").session.transactions().size());
    }
    @Test public void allViewFollowsLedgerCorrections() {
        var row=loot(3,10);assertEquals(1000,net());row.applyCorrection(Correction.IGNORE, "test");
        f.engine.session().transactionChanged(row);assertEquals(0,net());
    }
    @Test public void compactedMoneyIsIncludedWithoutDoubleCountingRaidTotals() {
        var first=loot(0,10);loot(1,20);var owner=f.engine.session();
        owner.transactions.remove(first);owner.compactTransaction(first);owner.notifyChanged();
        assertEquals(3000,net());assertEquals(1,f.engine.savedSession("raid:all").session.transactions().size());
    }
    @Test public void prunedPerKindAggregateIsIncluded() {
        f.engine.session().compactedRaids=new HashMap<>();
        f.engine.session().compactedRaids.put("0",new long[]{9000,1000});
        f.engine.changed();assertEquals(8000,net());
    }
    @Test public void allViewIsReadOnlyAndCached() {
        loot(2,10);assertSame(f.engine.savedSession("raid:all"),f.engine.savedSession("raid:all"));
        var entry=LedgerData.Entry.current().withScope(LedgerData.Scope.HISTORY,"raid:all","All raids");
        assertTrue(LedgerData.readOnly(entry));assertNotNull(LedgerData.capture(f.engine,f.now(),entry));
    }
    @Test public void allViewDoesNotBecomeAnotherCanonicalOrSavedSession() {
        loot(2,10);int before=f.engine.sessions().size();net();
        assertEquals(before,f.engine.sessions().size());assertTrue(f.engine.savedState().savedSessions==null || f.engine.savedState().savedSessions.isEmpty());
    }
    @Test public void allLedgerSelectionSurvivesRefreshAndKeepsTradeHidden() throws Exception {
        loot(3,10);SwingUtilities.invokeAndWait(()->{
            var panel=new RaidHistoryUiTest().panel(f.engine);panel.refreshNow();panel.shell.show(Shell.RAIDS);
            panel.raids.open(null,"all");panel.refreshNow();panel.refreshNow();
            assertEquals("all",panel.raids.selected);assertNotNull(panel.raids.ledger.data);
            var labels=new ArrayList<String>();RaidHistoryUiTest.labels(panel.raids.overview,labels);
            assertTrue(labels.contains("‹ Raids"));assertFalse(labels.contains("‹ Attempts"));
            assertFalse(Arrays.asList(panel.raids.ledger.body.getComponents()).contains(panel.raids.ledger.market));
            assertEquals(1,panel.raids.ledger.income.rows.size());
        });
    }
    @Test public void clearingProfileClearsCombinedMoney() {loot(2,10);assertEquals(1000,net());f.engine.restore(null,f.now());assertEquals(0,net());}
}
