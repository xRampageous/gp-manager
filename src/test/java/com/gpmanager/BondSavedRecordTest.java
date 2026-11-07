package com.gpmanager;

import java.util.*;
import java.util.function.LongSupplier;
import org.junit.*;
import static org.junit.Assert.*;

/** Older whole snapshots and incomplete saved inverses must not create or erase coverage. */
public class BondSavedRecordTest {
    LongSupplier previous;

    @Before public void clock() {
        previous = SafeMath.clock;
        SafeMath.clock = () -> ProductionRecipeTest.T0;
    }

    @After public void restore() {
        SafeMath.clock = previous;
    }

    static Transaction counted(long at) {
        return new Transaction(at, TransactionType.UNCERTAIN, Context.GENERIC, "", "", true,
                List.of(new Flow(BondConversionTest.NEW, "Old school bond", 1, 12000000,
                                12000000, PriceSource.GRAND_EXCHANGE),
                        new Flow(995, "Coins", -357123, 1, -357123, PriceSource.FACE_VALUE)),
                Confidence.UNCERTAIN, "", null);
    }

    static BondConversionTest.Fixture repairCounted(boolean previousWholeSnapshot) {
        var fixture = new BondConversionTest.Fixture(0);
        var engine = fixture.f.engine;
        var row = counted(fixture.f.at + 100);
        engine.session().addTransaction(row, 2000, false);
        var beforeFinancial = engine.basis.snapshot();
        SafeMath.clock = () -> fixture.f.at + 1200;
        assertEquals(1, BondSettlementTest.repair(engine, List.of(row.id()), fixture.f.at + 1200));
        if (previousWholeSnapshot) engine.session().latestCorrection().bondBefore = beforeFinancial;
        return fixture;
    }

    static Engine reload(BondConversionTest.Fixture fixture) {
        var restored = ProductionRecipeTest.engine();
        // Exercise the persisted wire format as well as the detached in-memory save copy.
        var json = JsonCodec.gson().toJson(SaveCopies.copy(fixture.f.engine.savedState()));
        restored.restore(JsonCodec.gson().fromJson(json, SavedState.class), fixture.f.at + 1800);
        SafeMath.clock = () -> fixture.f.at + 2400;
        return restored;
    }

    static void assertCountedStock(Engine engine) {
        assertEquals(1, engine.basis.known(BondConversionTest.NEW).availableQty);
        assertEquals(12000000, engine.basis.known(BondConversionTest.NEW).availableBasisGp);
    }

    @Test public void currentPhysicalSnapshotCountedControlStillWorks() {
        var restored = reload(repairCounted(false));
        assertTrue(restored.undoCorrection());
        assertCountedStock(restored);
    }

    static BondConversionTest.Fixture mixedGroup(boolean previousWholeSnapshot) {
        var fixture = new BondConversionTest.Fixture(0);
        var engine = fixture.f.engine;
        engine.session().addTransaction(BondSettlementTest.acquired(fixture.f.at), 2000, false);
        engine.session().addTransaction(BondSettlementTest.acquired(fixture.f.at + 1), 2000, false);
        var counted = counted(fixture.f.at + 100);
        var uncounted = BondSettlementTest.newOnly(fixture.f.at + 101);
        engine.session().addTransaction(counted, 2000, false);
        engine.session().addTransaction(uncounted, 2000, false);
        var beforeFinancial = engine.basis.snapshot();
        SafeMath.clock = () -> fixture.f.at + 1200;
        assertEquals(2, BondSettlementTest.repair(engine,
                List.of(counted.id(), uncounted.id()), fixture.f.at + 1200));
        if (previousWholeSnapshot) engine.session().latestCorrection().bondBefore = beforeFinancial;
        return fixture;
    }

    static void assertMixedUndo(Engine engine) {
        assertTrue(engine.undoCorrection());
        assertEquals(2, engine.basis.known(BondConversionTest.OLD).availableQty);
        assertEquals(2400000, engine.basis.known(BondConversionTest.OLD).availableBasisGp);
        assertCountedStock(engine);
    }

    @Test public void mixedCountedAndUncountedGroupedControlStillWorks() {
        assertMixedUndo(reload(mixedGroup(false)));
    }

}
