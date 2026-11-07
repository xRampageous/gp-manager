package com.gpmanager;

import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
import com.google.gson.*;

/** Shared inverse ownership, grouped owner identity and complete physical images. */
public class BondUndoValidationAccountingTest {
    java.util.function.LongSupplier previous;

    @Before public void clock() {previous=SafeMath.clock; SafeMath.clock=()->ProductionRecipeTest.T0;}
    @After public void restore() {SafeMath.clock=previous;}

    static BondConversionTest.Fixture repaired(int count) {
        var f=new BondConversionTest.Fixture(0); var e=f.f.engine; var ids=new ArrayList<String>();
        for (int i=0;i<count;i++) e.session().addTransaction(BondSettlementTest.acquired(f.f.at+i),2000,false);
        for (int i=0;i<count;i++) {
            var row=BondSettlementTest.newOnly(f.f.at+100+i);
            e.session().addTransaction(row,2000,false); ids.add(row.id());
        }
        SafeMath.clock=()->f.f.at+1200;
        assertEquals(count,BondSettlementTest.repair(e,ids,f.f.at+1200));
        assertEquals(count,e.basis.known(BondConversionTest.NEW).availableQty);
        return f;
    }

    @Test public void groupedCurrentUndoMustRecognizeItsActualOwnerFence() {
        var f=repaired(2); SafeMath.clock=()->f.f.at+1800;
        assertTrue("the grouped repair must recognize its oldest receipt's owner fence",
            f.f.engine.undoCorrection());
        assertEquals(2,f.f.engine.basis.known(BondConversionTest.OLD).availableQty);
        assertEquals(2400000,f.f.engine.basis.known(BondConversionTest.OLD).availableBasisGp);
    }

    @Test public void countedReviewUndoMustNotReplayItsBasisTwice() {
        var f=new BondConversionTest.Fixture(0); var e=f.f.engine;
        var row=new Transaction(f.f.at+100,TransactionType.UNCERTAIN,Context.GENERIC,"","",true,
            List.of(new Flow(BondConversionTest.NEW,"Old school bond",1,12000000,12000000,
                    PriceSource.GRAND_EXCHANGE),
                new Flow(995,"Coins",-357123,1,-357123,PriceSource.FACE_VALUE)),
            Confidence.UNCERTAIN,"",null);
        e.session().addTransaction(row,2000,false);
        assertTrue(ReviewRules.needsReview(row));
        assertEquals(1,e.basis.known(BondConversionTest.NEW).availableQty);
        assertEquals(12000000,e.basis.known(BondConversionTest.NEW).availableBasisGp);
        SafeMath.clock=()->f.f.at+1200;
        assertEquals(1,BondSettlementTest.repair(e,List.of(row.id()),f.f.at+1200));
        assertEquals(0,e.basis.known(BondConversionTest.NEW).availableQty);
        SafeMath.clock=()->f.f.at+1800;
        assertTrue(e.undoCorrection());
        assertEquals("the physical snapshot and financial inverse must not each add the same coverage",
            1,e.basis.known(BondConversionTest.NEW).availableQty);
        assertEquals(12000000,e.basis.known(BondConversionTest.NEW).availableBasisGp);
    }

    @Test public void singleUndoWithRealisticTimeControlStillWorks() {
        var f=repaired(1); SafeMath.clock=()->f.f.at+1800;
        assertTrue(f.f.engine.undoCorrection());
        assertEquals(1,f.f.engine.basis.known(BondConversionTest.OLD).availableQty);
    }
}
