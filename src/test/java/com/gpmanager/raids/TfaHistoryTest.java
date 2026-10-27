package com.gpmanager;

import java.util.*;
import javax.swing.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** TFA synthetic probes; live native delivery remains a separate acceptance check. */
public class TfaHistoryTest {
    RaidHistoryTest f=new RaidHistoryTest();
    void enter() {
        f.instance=true;f.names.put(1573,"Player");f.bits.put(16395,0);f.widgets.add(974<<16);
    }
    void begin() {f.nativeRaids.pulse(f.now());f.tick++;enter();f.nativeRaids.pulse(f.now());}
    void complete() {f.vars.put(5894,1);f.tick++;f.nativeRaids.pulse(f.now());}
    void preview(String option) {
        f.bits.put(16416,1);f.widgets.add(973<<16);f.containers.put(1029,f.chest(50));
        f.nativeRaids.menu(RewardChestDispositionTest.menu(option,973),f.now());
    }
    Transaction claim(boolean bank,boolean receiptFirst) {
        preview(bank?"Bank-all":"Take-all");
        if(!bank)f.inv();
        Transaction row=f.receipt();
        if(receiptFirst)f.nativeRaids.booked(row,bank,bank?1029:0);
        f.containers.put(1029,f.chest(0));f.nativeRaids.container(1029,f.containers.get(1029));
        if(!receiptFirst)f.nativeRaids.booked(row,bank,bank?1029:0);
        return row;
    }
    @Test public void tfaRuleMatchesPinnedApi() {
        var rule=RaidHistory.RULES.get(3);
        assertEquals(net.runelite.api.gameval.InventoryID.TFA_CHESTS,Integer.parseInt(rule[2]));
        assertEquals(net.runelite.api.gameval.VarbitID.TFA_HAS_CLAIMABLE_LOOT,Integer.parseInt(rule[4]));
        assertEquals(net.runelite.api.gameval.VarPlayerID.TOTAL_COMPLETED_THEFRACTUREDARCHIVE,Integer.parseInt(rule[11]));
    }
    @Test public void oldThreeRaidArraysAreExpanded() {
        var saved=new RaidHistory.State();saved.clears=new long[]{4,5,6};
        f.engine.raids.restore(saved);assertArrayEquals(new long[]{4,5,6,0},f.engine.raids.state.clears);
    }
    @Test public void absentTfaDoesNotShowTab() {f.nativeRaids.pulse(f.now());assertEquals(0,f.engine.raids.state.seen);}
    @Test public void rosterAndHudWithoutInstanceCannotStart() {enter();f.instance=false;f.nativeRaids.pulse(f.now());assertEquals(0,f.engine.raids.state.seen);}
    @Test public void nativeTfaParticipantStartsOnce() {begin();assertEquals(8,f.engine.raids.state.seen);assertFalse(f.engine.raids.active.partial);}
    @Test public void spectatorCannotStartTfa() {enter();f.bits.put(16405,1);f.nativeRaids.pulse(f.now());assertEquals(0,f.engine.raids.state.seen);}
    @Test public void wrongRosterMemberCannotStartTfa() {enter();f.names.put(1573,"Other");f.nativeRaids.pulse(f.now());assertEquals(0,f.engine.raids.state.seen);}
    @Test public void tfaPersonalCounterConfirmsCompletion() {begin();complete();assertTrue(f.engine.raids.active.complete);assertEquals(0,f.net(f.id()));}
    @Test public void lobbyPartyStatusCannotStart() {f.bits.put(16386,2);f.names.put(1573,"Player");f.nativeRaids.pulse(f.now());assertEquals(0,f.engine.raids.state.seen);}
    @Test public void chatWithoutObservedParticipantDoesNothing() {f.nativeRaids.chat("You failed to survive the Fractured Archive.",f.now());assertEquals(0,f.engine.raids.state.seen);}
    @Test public void tradeNeverAcquiresRaidTag() {
        preview("Take-all");f.inv();f.containers.put(1029,f.chest(0));f.nativeRaids.container(1029,f.containers.get(1029));
        var row=f.row(565,50,100,TransactionType.TRADE);f.nativeRaids.booked(row,false,0);assertNull(row.flows.get(0).raid);
    }
    void syntheticParticipant() {
        f.engine.raids.observe(new RaidHistory.Sample(3,false,"Observed mode",new int[]{0}),f.now(),f.tick);
        f.tick++;f.engine.raids.observe(new RaidHistory.Sample(3,true,"Observed mode",new int[]{0}),f.now(),f.tick);
    }
    @Test public void observedTfaFailureEndsWithoutMoney() {
        syntheticParticipant();f.nativeRaids.chat("You failed to survive the Fractured Archive.",f.now());
        assertNull(f.engine.raids.active);assertTrue(f.engine.raids.state.runs.get(0).died);assertTrue(f.engine.raids.state.runs.get(0).failed);assertEquals(0,f.net(f.id()));
        assertTrue(f.engine.session().transactions().isEmpty());
    }
    @Test public void failedOutcomeSurvivesSaveReload() {
        syntheticParticipant();f.nativeRaids.chat("You failed to survive the Fractured Archive.",f.now());
        var gson=new com.google.gson.Gson();
        var restored=ReviewFixesTest.engine();restored.restore(gson.fromJson(gson.toJson(f.engine.savedState()),SavedState.class),f.now());
        assertTrue(restored.raids.state.runs.get(0).failed);
    }
    @Test public void oldAmbiguousFailureWithoutFlagStaysUnknown() {
        var saved=new SavedState();var raids=new RaidHistory.State();var run=new RaidHistory.Run();
        run.id="3:"+java.util.UUID.randomUUID();run.started=1;run.ended=2;run.died=true;raids.runs.add(run);raids.seen=1<<3;saved.raidHistory=raids;
        var restored=ReviewFixesTest.engine();restored.restore(saved,f.now());
        assertFalse(restored.raids.state.runs.get(0).failed);
    }
    @Test public void failureCannotReplaceKnownCompletion() {
        syntheticParticipant();var run=f.engine.raids.active;f.engine.raids.complete(run,f.now());
        f.nativeRaids.chat("You failed to survive the Fractured Archive.",f.now());assertFalse(run.died);assertFalse(run.failed);assertTrue(run.complete);
    }
    @Test public void normalizedFailureLineIsAccepted() {syntheticParticipant();f.nativeRaids.chat("you failed to survive the fractured archive.",f.now());assertNull(f.engine.raids.active);}
    @Test public void oldRewardIsNotReassignedToNewAttempt() {
        begin();complete();String old=f.id();f.instance=false;for(int i=0;i<3;i++){f.tick++;f.nativeRaids.pulse(f.now());}
        f.tick++;enter();f.nativeRaids.pulse(f.now());String next=f.id();assertNotEquals(old,next);
        var row=claim(false,true);assertEquals(old,row.flows.get(0).raid);assertEquals(0,f.net(next));
    }
    @Test public void wrongMessageDoesNotEndAttempt() {syntheticParticipant();f.nativeRaids.chat("Challenge complete: Bork",f.now());assertNotNull(f.engine.raids.active);}
    @Test public void deathFeeUsesExistingCanonicalPayment() {
        syntheticParticipant();f.engine.markPvmDeath(5,Map.of(4151,1L), Map.of(), f.engine.events.owner);f.nativeRaids.chat("You failed to survive the Fractured Archive.",f.now());
        var fee=f.engine.bookDeathFee(125000,null,f.now());assertEquals(-125000,f.net(f.id()));assertEquals(f.id(),fee.flows.get(0).raid);
    }
    @Test public void normalSupplyAndChargeCostsStayOutsideRaids() {
        syntheticParticipant();var supply=f.row(2434,-1,200,TransactionType.CONSUMPTION);var charge=f.row(565,-2,100,TransactionType.CONSUMPTION);
        assertNull(supply.flows.get(0).raid);assertNull(charge.flows.get(0).raid);assertEquals(-400,f.engine.session().metrics(f.now()).net);assertEquals(0,f.net(f.id()));
    }
    @Test public void delayedWheelbarrowClaimUsesSavedAttempt() {
        syntheticParticipant();String id=f.id();f.engine.raids.complete(f.engine.raids.active,f.now());f.engine.raids.logout(f.now());
        var row=claim(false,true);assertEquals(id,row.flows.get(0).raid);assertEquals(5000,f.net(id));
    }
    @Test public void bankAllMatchesBothCallbackOrders() {
        for(boolean first:new boolean[]{true,false}) {f=new RaidHistoryTest();var row=claim(true,first);assertNotNull(row.flows.get(0).raid);assertEquals(5000,f.net(f.id()));}
    }
    @Test public void takeMatchesBothCallbackOrders() {
        for(boolean first:new boolean[]{true,false}) {f=new RaidHistoryTest();var row=claim(false,first);assertNotNull(row.flows.get(0).raid);assertEquals(5000,f.net(f.id()));}
    }
    @Test public void noTradeOrSupplyPickerInRaidDetails() throws Exception {
        claim(false,true);SwingUtilities.invokeAndWait(()->{
            var panel=new RaidHistoryUiTest().panel(f.engine);panel.refreshNow();panel.shell.show(Shell.RAIDS);panel.refreshNow();panel.raids.open(3,f.id());panel.refreshNow();
            assertTrue(panel.raids.ledger.raid);assertFalse(Arrays.asList(panel.raids.ledger.body.getComponents()).contains(panel.raids.ledger.market));
            assertTrue("title="+panel.raids.ledger.income.title.getText()+" detail="+panel.raids.ledger.data.detail,panel.raids.ledger.income.title.getText().startsWith("LOOT"));
        });
    }
}
