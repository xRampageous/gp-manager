package com.gpmanager;

import java.util.*;
import java.math.BigInteger;
import net.runelite.api.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Confirmed bond payment and reservation boundaries; native evidence never opens a client. */
public class BondSettlementTest {
    java.util.function.LongSupplier previous;

    @Before public void clock() {previous=SafeMath.clock; SafeMath.clock=()->ProductionRecipeTest.T0;}
    @After public void restore() {SafeMath.clock=previous;}

    static void nativeResult(BondConversionTest.Fixture f,long coins) {
        f.inventory.clear(); f.inventory.put(BondConversionTest.NEW,1L);
        if(coins>0) f.inventory.put(995,coins);
    }

    @Test public void settlingAnOlderSnapshotMustNotSpendTheConfirmedPairProof() {
        var f=new BondConversionTest.Fixture(0); f.accept();
        var older=Map.of(BondConversionTest.OLD,1L,995,100L);
        f.f.engine.markDirty();
        assertNull(f.f.engine.processIfDirty(new ContainerSnapshot(older),f.f.at+=600,null));
        nativeResult(f,100);
        f.f.engine.noteCast(ActionLabel.of("Wind Strike"));
        // The existing spell boundary settles the older pickup before staging the newer snapshot.
        f.f.engine.processIfDirty(new ContainerSnapshot(f.inventory),f.f.at+=600,null);
        f.f.become(f.inventory);
        assertEquals(-357123+100,f.f.live().net);
        assertFalse("the older pickup consumed the bond proof and the later pair returned as Review",f.reviewed());
    }

    @Test public void bankOpeningAlsoMustNotSpendProofForAnAbsentPair() {
        var f=new BondConversionTest.Fixture(0); f.accept();
        var older=Map.of(BondConversionTest.OLD,1L,995,100L);
        f.f.engine.markDirty();
        assertNull(f.f.engine.processIfDirty(new ContainerSnapshot(older),f.f.at+=600,null));
        nativeResult(f,100);
        f.f.engine.bankContents(Map.of(),f.f.at+600,row->{});
        assertNotNull("the earlier pickup contains neither measured bond leg",
            f.f.engine.events.peek(Inbox.BOND_PAYMENT));
    }

    @Test public void coalescedNetPositiveGainStaysOrdinary() {
        var f=new BondConversionTest.Fixture(1_000_000); f.accept(); nativeResult(f,1_300_000);
        f.f.become(f.inventory);
        assertEquals(300000,f.f.live().net);
        assertFalse(f.reviewed());
    }

    @Test public void inventoryPaidRecentLedgerAndNeutralPricesReconcile() {
        var f=new BondConversionTest.Fixture(1_000_000); f.accept();
        f.f.engine.config=new GpManagerConfig() {
            @Override public int stabilizationTicks() { return 0; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        nativeResult(f,642877); f.f.become(f.inventory);
        var live=f.f.live();
        assertEquals(-357123,live.net);
        assertEquals(-357123,f.f.engine.metrics(f.f.at+1).net);
        var neutral=f.f.engine.session().transactions().stream()
            .filter(t->t.isTransfer()&&t.note().equals(GameData.msg("state.bond"))).findFirst().orElseThrow();
        String prices=JsonCodec.gson().toJson(neutral.flows());
        var restored=ProductionRecipeTest.engine();
        restored.restore(SaveCopies.copy(f.f.engine.savedState()),f.f.at);
        assertEquals(prices,JsonCodec.gson().toJson(restored.session().findTransaction(neutral.id()).flows()));
        var fee=live.recent.stream().filter(row->row.value==-357123).findFirst().orElseThrow();
        var ledger=LedgerData.capture(f.f.engine,f.f.at+1,LivePage.entryFor(fee));
        assertEquals(live.net,ledger.detail.group.value);
    }

    @Test public void saturatedInventoryShareKeepsClampedBucketsSeparate() {
        var f=new BondConversionTest.Fixture(0);
        f.inventory.put(995,Long.MAX_VALUE); f.quoted=Long.MAX_VALUE+" coins"; f.accept();
        nativeResult(f,0); f.f.become(f.inventory);
        assertEquals(-Long.MAX_VALUE,f.f.live().net);
        assertEquals(1,f.f.engine.session().transactions().stream().filter(Transaction::counts).count());
        assertFalse(f.reviewed());
    }

    static BondConversionTest.Fixture confirmed() {
        var f=new BondConversionTest.Fixture(1_000_000); f.accept(); nativeResult(f,642877); return f;
    }

    @Test public void consecutiveConversionsWithDifferingSharesUseTheirOwnLegs() {
        var f=new BondConversionTest.Fixture(0);
        f.inventory.put(BondConversionTest.OLD,2L);
        f.f.engine.setBaseline(new ContainerSnapshot(f.inventory));
        f.accept();
        f.inventory.put(BondConversionTest.OLD,1L); f.inventory.put(BondConversionTest.NEW,1L);
       
        f.inventory.put(995,1_000_000L); f.f.engine.markTransfer("Bank transfer",6);
        f.f.become(f.inventory); f.f.engine.bankClosed();
        f.accept();
        f.inventory.remove(BondConversionTest.OLD); f.inventory.put(BondConversionTest.NEW,2L);
        f.inventory.put(995,642877L); f.f.become(f.inventory);
        assertEquals(-714246,f.f.live().net);
        assertFalse(f.reviewed());
    }

    @Test public void aCompleteOrdinarySettlementStillBooksOneFee() {
        var f=confirmed(); f.f.become(f.inventory);
        assertEquals(-357123,f.f.live().net); assertFalse(f.reviewed());
    }

    @Test public void coinOnlyBankDepositCannotUseThePaymentCredit() {
        var f=new BondConversionTest.Fixture(1_000_000); f.accept();
        f.f.engine.markTransfer("Bank transfer",6); f.f.engine.markDirty();
        f.f.engine.processIfDirty(new ContainerSnapshot(Map.of(BondConversionTest.OLD,1L,995,900000L)),f.f.at+=600,null);
        nativeResult(f,542877);
        f.f.engine.noteCast(ActionLabel.of("Wind Strike"));
        f.f.engine.processIfDirty(new ContainerSnapshot(f.inventory),f.f.at+=600,null);
        f.f.become(f.inventory);
        assertEquals("a neutral bank deposit is not an inventory fee payment",-357123,f.f.live().net);
        assertEquals(357123,f.f.live().cost);
    }

    @Test public void ordinaryConversionStillKeepsOneFee() {
        var f=confirmed(); f.f.become(f.inventory); assertEquals(-357123,f.f.live().net);
    }

    static BondConversionTest.Fixture twice(long gained) {
        var f=new BondConversionTest.Fixture(1_000_000);
        f.inventory.put(BondConversionTest.OLD,2L);
        f.f.engine.setBaseline(new ContainerSnapshot(f.inventory)); f.accept();
        f.inventory.put(BondConversionTest.OLD,1L); f.inventory.put(BondConversionTest.NEW,1L);
        f.inventory.put(995,642877L);
        f.accept(); f.f.engine.markLoot(Map.of(995,gained),4,"Coin drop","Loot");
        f.inventory.remove(BondConversionTest.OLD); f.inventory.put(BondConversionTest.NEW,2L);
        f.inventory.put(995,285754L+gained); f.f.become(f.inventory);
        return f;
    }

    /** Owner 2026-11-07: redeeming is where a bond is spent; a cost GP Manager never saw uses a bond's GE price. */
    @Test public void redeemingABondWhoseCostWasNotSeenSpendsItsGePrice() {
        var f=new BondConversionTest.Fixture(0);
        long price=f.f.engine.valuation.value(Map.of(BondConversionTest.NEW,1L)).get(0).unitPrice;
        assertTrue(price>0);
        f.f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Redeem",MenuAction.CC_OP,null,
            "Old school bond (untradeable)"));
        f.inventory.clear(); f.f.become(f.inventory);
        assertEquals("the untradeable bond has no price of its own; a bond's GE price is spent",-price,f.f.live().net);
        assertFalse(f.reviewed());
    }

    /**
     * Final review 2026-11-07: Redeem opens the bond window, where a redemption or a Jagex account
     * transfer is chosen; its last click proves the loss after the Redeem click itself has expired.
     */
    @Test public void aRedemptionChosenInTheBondWindowSpendsWhatWasPaid() {
        var f=buy();
        f.f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Redeem",MenuAction.CC_OP,null,
            "Old school bond (untradeable)"));
        f.f.engine.events.tick(f.f.engine.events.now+10);
        f.click("14 Days",net.runelite.api.gameval.InterfaceID.BondMain.PACKAGE_00);
        f.inventory.remove(BondConversionTest.OLD); f.f.become(f.inventory);
        assertEquals("the bond window's choice spends the 1200000 paid",-1_200_000,f.f.live().net);
        assertFalse(f.reviewed());
    }

    @Test public void aBondLossAfterTheRedeemClickExpiredWithoutABondWindowChoiceStaysReview() {
        var f=buy();
        f.f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Redeem",MenuAction.CC_OP,null,
            "Old school bond (untradeable)"));
        f.f.engine.events.tick(f.f.engine.events.now+10);
        f.inventory.remove(BondConversionTest.OLD); f.f.become(f.inventory);
        assertEquals("no live evidence: nothing is guessed",0,f.f.live().net);
        assertTrue(f.reviewed());
    }

    static BondConversionTest.Fixture buy() {
        var f=new BondConversionTest.Fixture(0);
        f.inventory.clear(); f.inventory.put(995,2_000_000L);
        f.f.engine.setBaseline(new ContainerSnapshot(f.inventory));
        var slots=new OfferSlots();
        for(var snap:List.of(
            new OfferSlots.Snapshot(0,GrandExchangeOfferState.BUYING,BondConversionTest.NEW,1,0,1_200_000,0),
            new OfferSlots.Snapshot(0,GrandExchangeOfferState.BOUGHT,BondConversionTest.NEW,1,1,1_200_000,1_200_000))) {
            slots.observe(snap).ifPresent(t->f.f.engine.noteGeOffer(t,"Old school bond",f.f.at));
            if(snap.state==GrandExchangeOfferState.BUYING) {
                f.inventory.put(995,800000L); f.f.become(f.inventory);
            } else {
                f.inventory.put(BondConversionTest.OLD,1L); f.f.become(f.inventory);
            }
        }
        return f;
    }

    /** Owner 2026-11-07: a bond is bought like any Grand Exchange item; what was paid stays its cost. */
    @Test public void aBondGePurchaseIsNeutralAndKeepsWhatWasPaid() {
        var f=buy();
        assertEquals("buying a bond is neutral, like any Grand Exchange purchase",0,f.f.live().net);
        var pool=f.f.engine.basis.known(BondConversionTest.OLD);
        assertEquals(1,pool.availableQty);
        assertEquals("the measured spend is the bond's cost",1_200_000,pool.availableBasisGp);
        assertFalse(f.reviewed());
    }

    @Test public void ordinaryLiveConversionControlStillBooksOnlyItsFee() {
        var f=confirmed(); f.f.become(f.inventory); assertEquals(-357123,f.f.live().net);
    }

    /** Owner 2026-11-07: the purchase is neutral, the fee counts when paid, and redemption spends what was paid. */
    @Test public void nativePurchaseConversionAndRedemptionSpendThePaidPriceAtRedemption() {
        var f=buy(); f.accept(); f.converted(442877);
        assertEquals("until the bond is used only the conversion fee counts",-357_123,f.f.live().net);
        assertEquals("what was paid moved to the tradeable bond",1_200_000,
            f.f.engine.basis.known(BondConversionTest.NEW).availableBasisGp);
        f.f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Redeem",MenuAction.CC_OP,null,
            "Old school bond"));
        f.inventory.remove(BondConversionTest.NEW); f.f.become(f.inventory);
        assertEquals("redemption spends the 1200000 paid; the fee already counted",-1_557_123,f.f.live().net);
        var spent=f.f.engine.session().transactions().stream()
            .filter(t->GameData.msg("bondSpent").equals(t.note())).findFirst().orElseThrow();
        assertEquals(-1_200_000,spent.net());
        assertFalse(f.reviewed());
        assertTrue("a whole-settle redemption books no empty adjustment",
            f.f.engine.session().transactions().stream().noneMatch(t->t.flows().isEmpty()));
    }

    /** Owner recording 20261104-220235: a bond gifted to another player leaves like a redeemed one. */
    @Test public void giftingTheConvertedBondSpendsWhatWasPaidWithoutReviewOrEmptyRow() {
        var f=buy(); f.accept(); f.converted(442877);
        assertEquals(-357_123,f.f.live().net);
        var gift=new net.runelite.api.events.ChatMessage(); gift.setType(ChatMessageType.MESBOX);
        gift.setMessage("Your bond has been gifted to RampageHC.");
        f.f.plugin.onChatMessage(gift);
        f.inventory.remove(BondConversionTest.NEW); f.f.become(f.inventory);
        assertEquals("a gift spends the 1200000 paid, like a redemption",-1_557_123,f.f.live().net);
        assertFalse("the gifted bond does not go to Review",f.reviewed());
        assertTrue(f.f.engine.session().transactions().stream().noneMatch(t->t.flows().isEmpty()));
    }

    /** Owner 2026-11-07: Recent shows the GE buy, the fee under the bond's name and the redemption; the state change stays in Ledger. */
    @Test public void aBondLifecycleReadsInRecentAsBuyFeeAndRedemption() {
        var f=buy();
        assertEquals("the Grand Exchange buy shows like any purchase",1,f.f.live().recent.size());
        f.accept(); f.converted(442877);
        var recent=f.f.live().recent;
        assertEquals("buy and fee; the neutral state change stays in Ledger",2,recent.size());
        assertTrue(recent.stream().anyMatch(r->r.name.startsWith("Old school bond")&&r.value==-357_123));
        f.f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Redeem",MenuAction.CC_OP,null,
            "Old school bond"));
        f.inventory.remove(BondConversionTest.NEW); f.f.become(f.inventory);
        assertEquals("the redemption is its own row",3,f.f.live().recent.size());
    }

    @Test public void aBondLossWithoutRedeemOrGiftEvidenceStillGoesToReview() {
        var f=buy(); f.accept(); f.converted(442877);
        f.inventory.remove(BondConversionTest.NEW); f.f.become(f.inventory);
        assertTrue(f.reviewed());
    }
}
