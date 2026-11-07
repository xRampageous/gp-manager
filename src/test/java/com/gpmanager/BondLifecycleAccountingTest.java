package com.gpmanager;

import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
import net.runelite.api.*;

/** Six lifecycle/repair regressions and the combined-row control. */
public class BondLifecycleAccountingTest {
    java.util.function.LongSupplier previous;

    @Before public void clock() {previous=SafeMath.clock; SafeMath.clock=()->ProductionRecipeTest.T0;}
    @After public void restore() {SafeMath.clock=previous;}

    static Transaction purchase(BondConversionTest.Fixture f) {
        return f.f.engine.session().transactions().stream().filter(t->t.flows().stream().anyMatch(fl->fl.item==BondConversionTest.OLD&&fl.quantity>0)).findFirst().orElseThrow();
    }

    @Test public void ignoredPurchaseMustNotHideALaterMeasuredFee() {
        var f=BondSettlementTest.buy(); var p=purchase(f);
        assertTrue(f.f.engine.correctEntries(List.of(p.id()),Correction.IGNORE));
        f.accept(); f.converted(442877);
        assertEquals("removing the neutral purchase keeps the later fee",-357123,f.f.live().net);
    }

    @Test public void feeMustRemainInThePeriodWhenItWasPaid() {
        var f=BondSettlementTest.buy(); long feeAt=f.f.at+86400000;
        SafeMath.clock=()->feeAt;
        f.f.engine.bookFee(357123,GameData.msg("bond-fee"),"Bonds",feeAt);
        long laterCosts=f.f.engine.session().transactions().stream()
            .filter(t->t.timestamp>=feeAt&&t.counts()).mapToLong(Transaction::costs).sum();
        assertEquals("combining a row must not backdate a new fee into the old purchase",357123,laterCosts);
    }

    @Test public void redemptionMustRetireItsKnownQuantity() {
        var f=BondSettlementTest.buy(); f.accept(); f.converted(442877);
        assertEquals(1,f.f.engine.basis.known(BondConversionTest.NEW).availableQty);
        f.f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Redeem",MenuAction.CC_OP,null,
            "Old school bond"));
        f.inventory.remove(BondConversionTest.NEW); f.f.become(f.inventory);
        assertEquals("redeemed physical stock must not remain known for a future sale",
            0,f.f.engine.basis.known(BondConversionTest.NEW).availableQty);
    }

    static void offer(BondConversionTest.Fixture f, OfferSlots slots, GrandExchangeOfferState state,
            int traded, int spent) {
        var snap=new OfferSlots.Snapshot(1,state,BondConversionTest.NEW,1,traded,1_500_000,spent);
        slots.observe(snap).ifPresent(t->f.f.engine.noteGeOffer(t,"Old school bond",f.f.at+=600));
    }

    @Test public void buyingConvertingAndSellingMustBookNetCashOnce() {
        var f=BondSettlementTest.buy(); f.accept(); f.converted(442877);
        var slots=new OfferSlots(); offer(f,slots,GrandExchangeOfferState.SELLING,0,0);
        f.inventory.remove(BondConversionTest.NEW); f.f.become(f.inventory);
        offer(f,slots,GrandExchangeOfferState.SOLD,1,1_500_000);
        f.f.engine.noteGeCollect(f.f.at);
        f.f.engine.markContext(Context.MARKET,10,GameData.msg("geCollect"));
        f.inventory.put(995,442877L+1_500_000); f.f.become(f.inventory);
        assertEquals("1500000 observed proceeds minus 1200000 purchase minus 357123 conversion",
            -57123,f.f.live().net);
        assertFalse(f.reviewed());
    }

    /** Owner 2026-11-07: purchase neutral, fee counted when paid; the bond's cost waits for its use. */
    @Test public void combinedPurchaseConversionControlStillReconciles() {
        var f=BondSettlementTest.buy(); f.accept(); f.converted(442877);
        assertEquals(-357123,f.f.live().net);
        assertEquals(1_200_000,f.f.engine.basis.known(BondConversionTest.NEW).availableBasisGp);
        assertFalse(f.reviewed());
    }
}
