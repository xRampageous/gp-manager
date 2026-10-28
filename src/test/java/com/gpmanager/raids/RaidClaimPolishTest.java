package com.gpmanager;
import org.junit.Test;
import static org.junit.Assert.*;
public class RaidClaimPolishTest {
    @Test public void repeatedTakeBeforeSettlementKeepsEveryDepletedQuantity() {
        var f=new RaidHistoryTest();f.start();f.complete();f.take();f.inv();
        f.containers.put(811,f.chest(30));f.nativeRaids.container(811,f.containers.get(811));
        f.tick++;f.nativeRaids.menu(f.click("Take-all"),f.now());f.deplete();f.inv();
        var row=f.receipt();f.nativeRaids.booked(row,false,0);
        assertEquals(5000,f.net(f.id()));assertEquals(5000,f.engine.session().metrics(f.now()).net);
        f.nativeRaids.booked(row,false,0);f.deplete();assertEquals(5000,f.net(f.id()));
    }
    @Test public void delayedCanonicalReceiptWithinLiveClaimStillLinks() {
        var f=new RaidHistoryTest();f.start();f.complete();f.take();f.inv();f.deplete();f.tick+=4;
        var row=f.receipt();f.nativeRaids.booked(row,false,0);assertEquals(5000,f.net(f.id()));
        assertEquals(5000,f.engine.session().metrics(f.now()).net);
    }
    @Test public void retryDoesNotExtendTheOriginalClaimDeadline() {
        var f=new RaidHistoryTest();f.start();f.complete();f.take();f.inv();f.tick+=9;
        f.nativeRaids.menu(f.click("Take-all"),f.now());f.tick+=2;f.deplete();f.inv();
        var row=f.receipt();f.nativeRaids.booked(row,false,0);assertNull(row.flows.get(0).raid);
    }
    @Test public void repeatedTakeCannotCreateProofOrCaptureDifferentItems() {
        var f=new RaidHistoryTest();f.start();f.complete();f.take();f.inv();
        f.nativeRaids.menu(f.click("Take-all"),f.now());f.deplete();
        var other=f.row(560,50,100,TransactionType.LOOT);f.nativeRaids.booked(other,false,0);
        assertNull(other.flows.get(0).raid);assertEquals(0,f.net(f.id()));
        var row=f.receipt();f.nativeRaids.booked(row,false,0);f.nativeRaids.booked(f.receipt(),false,0);
        assertEquals(5000,f.net(f.id()));
    }
    @Test public void destroyAfterRetryKeepsOnlyLootAlreadyTaken() {
        var f=new RaidHistoryTest();f.start();f.complete();f.take();f.inv();
        f.containers.put(811,f.chest(30));f.nativeRaids.container(811,f.containers.get(811));
        f.nativeRaids.menu(f.click("Take-all"),f.now());f.nativeRaids.menu(f.click("Destroy"),f.now());f.deplete();
        var row=f.receipt();f.nativeRaids.booked(row,false,0);assertEquals(2000,f.net(f.id()));
    }
    @Test public void switchingToBankPreservesEachRoutesProvenQuantities() {
        var f=new RaidHistoryTest();f.start();f.complete();f.take();f.inv();
        f.containers.put(811,f.chest(30));f.nativeRaids.container(811,f.containers.get(811));
        f.nativeRaids.menu(f.click("Bank-all"),f.now());f.deplete();
        var old=f.row(565,20,100,TransactionType.LOOT);f.nativeRaids.booked(old,false,0);assertEquals(2000,f.net(f.id()));
        var bank=f.row(565,30,100,TransactionType.LOOT);f.nativeRaids.booked(bank,true,811);assertEquals(5000,f.net(f.id()));
    }
    @Test public void partialTakeRetryAndLateSettlementWorkForEveryRaid() {
        for(int kind=0;kind<4;kind++) {
            var f=new RaidHistoryTest();var rule=RaidHistory.RULES.get(kind);
            int chest=Integer.parseInt(rule[2]),group=Integer.parseInt(rule[3]),flag=Integer.parseInt(rule[4]);
            f.bits.put(flag,1);if(kind==0){f.bits.put(5432,1);f.bits.put(5424,1);}
            f.widgets.add(group<<16);f.containers.put(chest,f.chest(50));
            f.nativeRaids.menu(RewardChestDispositionTest.menu("Take-all",group),f.now());f.inv();
            f.containers.put(chest,f.chest(30));f.nativeRaids.container(chest,f.containers.get(chest));
            f.tick++;f.nativeRaids.menu(RewardChestDispositionTest.menu("Take-all",group),f.now());
            f.containers.put(chest,f.chest(0));f.nativeRaids.container(chest,f.containers.get(chest));f.inv();f.tick+=4;
            var row=f.receipt();f.nativeRaids.booked(row,false,0);
            assertEquals("kind "+kind,5000,f.net(f.id()));assertEquals(5000,f.engine.session().metrics(f.now()).net);
            assertTrue(row.counts());assertEquals(Confidence.CONFIRMED,row.confidence);
        }
    }
    @Test public void sourceBackedDelayedSettleNeedsNoReviewOrSecondBooking() {
        var f=new RaidHistoryTest();f.start();f.complete();f.take();f.inv();f.deplete();
        f.engine.markLoot(java.util.Map.of(565,50L),50,"Raid rewards","Tombs of Amascut");
        var snapshot=new ContainerSnapshot(java.util.Map.of(565,50L));
        assertNull(f.engine.processIfDirty(snapshot,f.now(),null));f.tick+=4;
        var row=f.engine.processIfDirty(snapshot,f.now(),null);assertNotNull(row);
        f.nativeRaids.booked(row,false,0);assertEquals(5000,f.net(f.id()));
        assertFalse(ReviewRules.needsReview(row));assertEquals(1,f.engine.session().transactions().size());
        assertEquals(5000,f.engine.session().metrics(f.now()).net);
        assertNull(f.engine.processIfDirty(snapshot,f.now(),null));
    }
    @Test public void receiptWaitingForDepletionSurvivesAnotherTakeClick() {
        var f=new RaidHistoryTest();f.start();f.complete();f.take();f.inv();
        var first=f.row(565,20,100,TransactionType.LOOT);f.nativeRaids.booked(first,false,0);
        f.containers.put(811,f.chest(30));f.nativeRaids.menu(f.click("Take-all"),f.now());f.deplete();
        var second=f.row(565,30,100,TransactionType.LOOT);f.nativeRaids.booked(second,false,0);
        assertEquals(5000,f.net(f.id()));assertEquals(5000,f.engine.session().metrics(f.now()).net);
    }
    @Test public void netTotalIsShownOnOverviewAllLedgerAndAttempt() throws Exception {
        var f=new RaidHistoryTest();f.claimed();
        javax.swing.SwingUtilities.invokeAndWait(()->{
            var panel=new RaidHistoryUiTest().panel(f.engine);panel.refreshNow();panel.shell.show(Shell.RAIDS);
            for(int page=0;page<3;page++){
                panel.raids.open(page==2?2:null,page==0?null:page==1?"all":f.id());panel.refreshNow();
                var labels=new java.util.ArrayList<String>();RaidHistoryUiTest.labels(panel.raids.body,labels);
                // The prominent value and compact breakdown appear on every Raids page.
                assertFalse("only the literal Net wording is removed",
                        labels.stream().anyMatch(label->label.startsWith("Net ")));
                assertTrue(labels.stream().anyMatch(label->label.startsWith("LOOT ·")));
                assertNull(RaidHistoryUiTest.button(panel.raids.body,"Loot "));
                assertTrue(labels.stream().anyMatch(label->label.equals("Death fees:")));
                assertFalse(labels.stream().anyMatch(label->label.startsWith("Loot ")&&label.contains("Death fees")));
                assertFalse(labels.stream().anyMatch(label->label.contains("Loot − fees")));
                // Owner 2026-10-23: the "Confirmed loot and paid death fees" line is removed from every page.
                assertFalse(labels.stream().anyMatch(label->label.contains("Confirmed loot and paid death fees")));
            }
        });
    }
}
