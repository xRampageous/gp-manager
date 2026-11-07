package com.gpmanager;

import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
import com.google.gson.*;
import net.runelite.api.*;

/** Four remaining lifecycle/correction regressions, the fee-period display, and the control. */
public class BondSixFixAccountingTest {
    java.util.function.LongSupplier previous;

    @Before public void clock() {previous=SafeMath.clock; SafeMath.clock=()->ProductionRecipeTest.T0;}
    @After public void restore() {SafeMath.clock=previous;}

    static Transaction fee(BondConversionTest.Fixture f) {
        return f.f.engine.session().transactions().stream()
            .filter(t->GameData.msg("bond-fee").equals(t.note())).findFirst().orElseThrow();
    }

    static BondConversionTest.Fixture converted() {
        var f=BondSettlementTest.buy(); f.accept(); f.converted(442877); return f;
    }

    static Semantics.Result project(List<Transaction> rows) {
        return Semantics.capture(rows,List.of(),Set.of(),null);
    }

    static Transaction gain(int id,long at) {
        return new Transaction(at,TransactionType.GAIN,Context.GENERIC,"","",true,
            List.of(new Flow(id,"Whip",1,2_000_000,2_000_000,PriceSource.GRAND_EXCHANGE)),
            Confidence.CONFIRMED,"",null);
    }

    @Test public void correctedFeeMustKeepGainAndCostTablesHonest() {
        var f=BondSettlementTest.buy();
        var fee=f.f.engine.bookFee(357123,GameData.msg("bond-fee"),"Bonds",f.f.at+600);
        assertTrue(f.f.engine.correctTransaction(fee.id(),Correction.REVENUE,"owner gain"));
        var actual=Semantics.summarize(project(List.of(fee)).groups);
        assertEquals("the corrected fee is income only",357123,actual.income);
        assertEquals(0,actual.cost);
        assertEquals("the neutral purchase adds nothing to Net",357123,f.f.live().net);
    }

    @Test public void feeOnlyPeriodMustStillNameTheBond() {
        var f=converted(); var row=project(List.of(fee(f))).groups.get(0);
        assertTrue("a fee period must not turn the linked bond row into a Coins item: "+row.name,
            row.name.startsWith("Old school bond"));
    }

    @Test public void aTrackingFenceMustRetireRedeemEvidence() {
        var f=new BondConversionTest.Fixture(0);
        assertTrue(f.f.engine.live()); f.f.engine.markBondRedeem(); f.f.engine.resetTracking(false);
        f.f.engine.setBaseline(new ContainerSnapshot(f.inventory));
        f.inventory.clear(); f.f.become(f.inventory);
        assertTrue("a prior tracking period cannot prove this unexplained loss is redemption",f.reviewed());
    }

    @Test public void aProvenBankTransferMustNotDepleteRedeemCoverage() {
        var f=converted();
        assertTrue(f.f.engine.live()); f.f.engine.markBondRedeem(); f.f.engine.markTransfer("Bank transfer",10);
        f.inventory.remove(BondConversionTest.NEW); f.f.become(f.inventory);
        assertEquals("bank custody remains owned even after an abandoned Redeem input",
            1,f.f.engine.basis.known(BondConversionTest.NEW).availableQty);
    }

    /** Owner 2026-11-07: after buying and converting, only the fee counts until the bond is used. */
    @Test public void originalLifecycleSuiteControl() {
        var f=converted();
        assertEquals(-357123,f.f.live().net); assertFalse(f.reviewed());
    }
}
