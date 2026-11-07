package com.gpmanager;

import java.util.*;
import net.runelite.api.gameval.ItemID;
import org.junit.*;
import static org.junit.Assert.*;

/** 1.7.0-only load repair of crystal tool Reverts left in Review; delete with the migration in the next release. */
public class CrystalRevertMigrationTest {
    static final long T0 = ProductionRecipeTest.T0;
    java.util.function.LongSupplier previous;
    @Before public void clock() { previous = SafeMath.clock; SafeMath.clock = () -> T0; }
    @After public void restore() { SafeMath.clock = previous; }

    static Flow flow(int id, String name, long qty, long price) {
        return new Flow(id, name, qty, price, qty * price, PriceSource.GRAND_EXCHANGE);
    }
    static final Flow AXE = flow(ItemID.CRYSTAL_AXE_2H, "Crystal felling axe", -1, 17_599_599),
        SEED = flow(ItemID.PRIF_TOOL_SEED, "Crystal tool seed", 1, 15_850_000),
        DRAGON = flow(ItemID.DRAGON_AXE_2H, "Dragon felling axe", 1, 1_749_599);

    /** How the owner's 7 Nov Revert was saved (recording 20261107-071643): a counted mixed Review. */
    static Transaction review(Flow... flows) {
        return new Transaction(T0 + 100, TransactionType.UNCERTAIN, Context.GENERIC, "", "Woodcutting", true, List.of(flows),
                Confidence.UNCERTAIN, "Mixed gains and costs had no confirmed context; counted by configuration.", null);
    }

    static Engine engine() {
        var engine = ProductionRecipeTest.engine();
        engine.valuation = deltas -> {
            var flows = new ArrayList<Flow>();
            deltas.forEach((id, qty) -> flows.add(flow(id, id == ItemID.PRIF_CRYSTAL_SHARD ? "Crystal shard" : "Other", qty, 2_000)));
            return flows;
        };
        return engine;
    }

    static List<Transaction> shardRows(Engine engine) {
        var rows = new ArrayList<Transaction>();
        for (var row : engine.session().transactions())
            if ("Dismantled Crystal felling axe".equals(row.note())) rows.add(row);
        return rows;
    }

    @Test public void aRevertLeftInReviewBooksOnlyItsShardsOnLoadOnce() {
        var engine = engine(); var row = review(AXE, SEED, DRAGON);
        engine.session().addTransaction(row, 2000, false);
        long before = engine.metrics(T0 + 1000).net;
        engine.recheck();
        assertEquals("the Ledger's money-only removal", Correction.IGNORE, row.correction());
        assertEquals("its physical history stays as booked", Correction.AUTO, row.ownershipCorrection);
        assertFalse(ReviewRules.needsReview(row));
        var shards = shardRows(engine);
        assertEquals(1, shards.size());
        assertEquals(TransactionType.CONSUMPTION, shards.get(0).original());
        assertEquals(Confidence.CONFIRMED, shards.get(0).confidence());
        assertEquals("the live Revert's row: 120 crystal shards", ItemID.PRIF_CRYSTAL_SHARD, shards.get(0).flows().get(0).item);
        assertEquals(-120L, shards.get(0).flows().get(0).quantity);
        assertEquals(row.timestamp, shards.get(0).timestamp);
        assertEquals("only the shards leave Net", before - 240_000L, engine.metrics(T0 + 1000).net);
        engine.recheck();
        assertEquals("a second load changes nothing", 1, shardRows(engine).size());
        assertEquals(1, engine.session().correctionHistory.size());
    }

    @Test public void afterUndoTheReviewStaysAndTheShardsNeverBookTwice() {
        var engine = engine(); var row = review(AXE, SEED, DRAGON);
        engine.session().addTransaction(row, 2000, false);
        engine.recheck();
        assertTrue(engine.undoCorrection());
        assertTrue(ReviewRules.needsReview(row));
        engine.recheck();
        assertEquals(Correction.AUTO, row.correction());
        assertEquals(1, shardRows(engine).size());
    }

    @Test public void otherShapesStayForTheOwner() {
        var engine = engine();
        var noSeed = review(AXE, DRAGON);
        var twoAxes = review(flow(ItemID.CRYSTAL_AXE_2H, "Crystal felling axe", -2, 17_599_599), SEED, DRAGON);
        var extraLoss = review(AXE, SEED, flow(385, "Shark", -1, 900));
        var notACrystalTool = review(flow(ItemID.DRAGON_AXE_2H, "Dragon felling axe", -1, 1_749_599), SEED, flow(385, "Shark", 1, 900));
        var stays = List.of(noSeed, twoAxes, extraLoss, notACrystalTool);
        for (var row : stays) engine.session().addTransaction(row, 2000, false);
        long before = engine.metrics(T0 + 1000).net;
        engine.recheck();
        for (var row : stays) {
            assertEquals(Correction.AUTO, row.correction());
            assertTrue(ReviewRules.needsReview(row));
        }
        assertEquals(before, engine.metrics(T0 + 1000).net);
        assertTrue(shardRows(engine).isEmpty());
    }
}
