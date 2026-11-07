package com.gpmanager;

import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
import com.google.gson.*;

/** Repair-Undo reservation/completeness invariants and the valid controls. */
public class BondFourAccountingTest {
    java.util.function.LongSupplier previous;

    @Before public void clock() {previous=SafeMath.clock; SafeMath.clock=()->ProductionRecipeTest.T0;}
    @After public void restore() {SafeMath.clock=previous;}

    static void replace(BondConversionTest.Fixture f, JsonObject json) {
        var session=f.f.engine.session(); var last=session.latestCorrection();
        session.correctionHistory.set(session.correctionHistory.indexOf(last),
            JsonCodec.gson().fromJson(json,CorrectionRecord.class));
    }

    @Test public void aMissingWholeBeforeSnapshotMustNotEraseCoverage() {
        var f=BondSixFixAccountingTest.repaired();
        var json=JsonCodec.gson().toJsonTree(f.f.engine.session().latestCorrection()).getAsJsonObject();
        json.remove("bondBefore"); replace(f,json);
        var restored=ProductionRecipeTest.engine();
        restored.restore(SaveCopies.copy(f.f.engine.savedState()),f.f.at+2400);
        String before=JsonCodec.gson().toJson(restored.basis.stock);
        assertFalse("a missing before map is not proof that every previous pool was absent",
            restored.undoCorrection());
        assertEquals(before,JsonCodec.gson().toJson(restored.basis.stock));
    }

    @Test public void anOpenReservationMustFenceThePhysicalUndo() {
        var f=BondSixFixAccountingTest.repaired(); var e=f.f.engine;
        var offer=new GeOffer(); offer.offerId="unknown-sell"; offer.side=GeOffer.Side.SELL;
        offer.itemId=BondConversionTest.NEW; offer.offeredQty=offer.capturedQty=1;
        offer.listedPrice=12000000; offer.placedAtEpochMillis=f.f.at+1800;
        offer.confidence=GeOffer.CustodyConfidence.LEGACY_UNBASED; e.exchange.records.add(offer);
        assertTrue(e.exchange.latestReservationTime(BondConversionTest.NEW)>f.f.at);
        SafeMath.clock=()->f.f.at+2400;
        assertFalse("the existing basis validator rejects this reservation boundary",
            e.basis.canApply(BondConversionTest.NEW,-1,-1200000,f.f.at,null));
        assertFalse("repair Undo must not bypass a live reservation just because pool values match",
            e.undoCorrection());
        assertEquals(1,e.basis.known(BondConversionTest.NEW).availableQty);
    }

    @Test public void ordinaryRepairUndoControlStillWorks() {
        var f=BondSixFixAccountingTest.repaired();
        assertTrue(f.f.engine.undoCorrection());
        assertEquals(1,f.f.engine.basis.known(BondConversionTest.OLD).availableQty);
    }

}
