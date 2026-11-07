package com.gpmanager;

import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Codex's overall 1.7.0 review (2026-11-07): its five reproduced findings and one control, kept as
 * regressions. Bond cleanup keeps a paid fee and respects an undone cleanup; the crystal cleanup needs
 * the tool's own dragon tool; a Hardcore death's deleted grave (looting-bag stock included) is lost,
 * never reclaimable.
 */
public class ReleaseReviewFixesTest {
    java.util.function.LongSupplier previous;

    @Before public void clock() {
        previous = SafeMath.clock;
        SafeMath.clock = () -> ProductionRecipeTest.T0;
    }
    @After public void restoreClock() { SafeMath.clock = previous; }

    @Test public void automaticBondCleanupMustPreserveCapturedCoinFees() {
        var engine = ProductionRecipeTest.engine();
        var coins = new Flow(995, "Coins", -500_000L, 1L, -500_000L, PriceSource.FACE_VALUE);
        var row = BondMigrationTest.review(BondMigrationTest.newBond(1), BondMigrationTest.oldBond(-1), coins);
        engine.session().addTransaction(row, 2000, false);
        assertEquals(500_000L, engine.metrics(ProductionRecipeTest.T0 + 1000).cost);
        engine.recheck();
        assertEquals("a captured conversion fee must not disappear", 500_000L,
                engine.metrics(ProductionRecipeTest.T0 + 1000).cost);
    }

    @Test public void undoOfBondCleanupMustSurviveJsonReloadAndRecheck() {
        var engine = ProductionRecipeTest.engine();
        var row = BondMigrationTest.review(BondMigrationTest.newBond(1), BondMigrationTest.oldBond(-1));
        engine.session().addTransaction(row, 2000, false);
        engine.recheck();
        assertTrue(engine.undoCorrection());
        assertEquals(Correction.AUTO, row.correction());
        var saved = JsonCodec.gson().fromJson(JsonCodec.gson().toJson(engine.savedState()), SavedState.class);
        var restored = ProductionRecipeTest.engine();
        restored.restore(saved, ProductionRecipeTest.T0 + 2000);
        restored.recheck();
        assertEquals("the owner undid this repair; a later load must respect that decision",
                Correction.AUTO, restored.session().findTransaction(row.id()).correction());
    }

    @Test public void crystalCleanupRequiresTheMatchingDragonTool() {
        var engine = CrystalRevertMigrationTest.engine();
        var row = CrystalRevertMigrationTest.review(CrystalRevertMigrationTest.AXE,
                CrystalRevertMigrationTest.SEED,
                CrystalRevertMigrationTest.flow(385, "Shark", 1, 900));
        engine.session().addTransaction(row, 2000, false);
        engine.recheck();
        assertEquals("a shark is not the returned dragon felling axe",
                Correction.AUTO, row.correction());
    }

    @Test public void hardcoreDeletedGearMustNotNeutralizeLaterReplacementLoot() {
        int whip = HardcoreWorldPricesTest.WHIP;
        var valuation = HardcoreWorldPricesTest.valuation(Valuation.Economy.HARDCORE);
        var fixture = new ProductionRecentTest.Fixture(java.util.Map.of(whip, 1L));
        fixture.engine.valuation = valuation;
        fixture.engine.markPvmDeath(8, java.util.Map.of(whip, 1L), java.util.Map.of(), fixture.engine.events.owner);
        fixture.become(java.util.Map.of());
        assertEquals("the deleted whip was booked as a loss", -72_000L, fixture.live().net);
        var saved = JsonCodec.gson().fromJson(JsonCodec.gson().toJson(fixture.engine.savedState()), SavedState.class);
        fixture.engine.restore(saved, fixture.at + 600);
        // A reload pauses for recovery until login resumes tracking, as the live client does.
        fixture.engine.resume(fixture.at + 600, PauseReason.IDLE, PauseReason.RECOVERY);
        fixture.engine.setBaseline(new ContainerSnapshot(java.util.Map.of()));
        valuation.setEconomy(Valuation.Economy.NORMAL);
        fixture.become(java.util.Map.of(whip, 1L));
        assertEquals("a later pickup after Transfer is new loot, not a return of the destroyed whip",
                2_000_000L - 72_000L, fixture.live().net);
    }

    @Test public void hardcoreDeathMustBookKnownLootingBagContents() {
        int bag = ItemID.LOOTING_BAG, whip = HardcoreWorldPricesTest.WHIP;
        var valuation = HardcoreWorldPricesTest.valuation(Valuation.Economy.HARDCORE);
        valuation.learn(whip, 2_500_000L, 1);
        var fixture = new ProductionRecentTest.Fixture(java.util.Map.of(bag, 1L));
        fixture.engine.valuation = valuation;
        fixture.engine.markPvmDeath(8, java.util.Map.of(bag, 1L), java.util.Map.of(whip, 1L), fixture.engine.events.owner);
        fixture.become(java.util.Map.of());
        assertEquals("the known contents were deleted with the bag", -2_500_000L, fixture.live().net);
    }

    @Test public void crystalCleanupUndoSurvivesJsonReloadWithoutAnotherShardRow() {
        var engine = CrystalRevertMigrationTest.engine();
        var row = CrystalRevertMigrationTest.review(CrystalRevertMigrationTest.AXE,
                CrystalRevertMigrationTest.SEED, CrystalRevertMigrationTest.DRAGON);
        engine.session().addTransaction(row, 2000, false);
        engine.recheck();
        assertTrue(engine.undoCorrection());
        var saved = JsonCodec.gson().fromJson(JsonCodec.gson().toJson(engine.savedState()), SavedState.class);
        var restored = CrystalRevertMigrationTest.engine();
        restored.restore(saved, ProductionRecipeTest.T0 + 2000);
        restored.recheck();
        assertEquals(Correction.AUTO, restored.session().findTransaction(row.id()).correction());
        assertEquals(1L, restored.session().transactions().stream()
                .filter(t -> t.flows().stream().anyMatch(f -> f.item == ItemID.PRIF_CRYSTAL_SHARD)).count());
    }
}

