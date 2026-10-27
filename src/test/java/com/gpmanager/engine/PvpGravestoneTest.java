package com.gpmanager;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native PvP preview plus recorded death/retrieval prove these two items remain owned. */
public class PvpGravestoneTest {
    static final Map<Integer,Long> HELD = Map.of(10499,1L,19564,1L,861,1L,11941,1L);
    static Engine engine() {
        return engine(true);
    }
    static Engine engine(boolean audit) {
        var engine = new Engine(deltas -> {
            var flows = new ArrayList<Flow>();
            deltas.forEach((id,qty) -> {
                int price = id == 10499 ? 600 : id == 19564 ? 12 : id == 861 ? 715 : id == 11941 ? 6 : 100;
                flows.add(new Flow(id, "Item " + id, qty, price, qty * price));
            });
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig() {
            @Override public int stabilizationTicks() { return 0; }
            @Override public boolean keepTransferAuditRows() { return audit; }
        });
        engine.ensureSession(1000);
        engine.setBaseline(new ContainerSnapshot(HELD));
        return engine;
    }
    static void settle(Engine engine, Map<Integer,Long> items, long at) {
        engine.markDirty();
        for (int i = 0; i < 4; i++) engine.processIfDirty(new ContainerSnapshot(items), at+i, null);
    }
    static void death(Engine engine) {
        engine.markPkDeath(2000, Map.of(1079,2L), HELD, engine.events.owner);
        settle(engine, Map.of(), 3000);
    }

    @Test public void retainedUntradeablesAreNeutralButRealGearAndBagStayLosses() {
        var engine = engine(); death(engine);
        assertEquals(-921, engine.metrics(4000).net);
        assertEquals(0, engine.metrics(4000).income);
        assertEquals(1, engine.session().pk().deaths);
        var rows = engine.session().transactions();
        assertEquals(2, rows.stream().filter(row -> row.original() == TransactionType.PK_DEATH_LOSS).count());
        var loss = rows.stream().filter(row -> row.flows().stream().anyMatch(flow -> flow.item == 861)).findFirst().orElseThrow();
        assertTrue(loss.encounterId().startsWith("death:"));
        assertEquals(TransactionType.PK_DEATH_LOSS, loss.original());
        assertTrue(engine.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Loot", "Grave"), 20));
        settle(engine, Map.of(10499,1L,19564,1L), 5000);
        assertEquals(-921, engine.metrics(6000).net);
        assertEquals(0, engine.metrics(6000).income);
        assertNull(engine.savedState().pendingDeathReclaim);
    }
    @Test public void whitelistMatchesPinnedNamedGameItems() throws Exception {
        for (var row : GameData.rows("pvp_gravestone"))
            assertEquals(Integer.parseInt(row[0]),net.runelite.api.gameval.ItemID.class.getField(row[1]).getInt(null));
        assertEquals(Set.of(10499,19564),Engine.PVP_GRAVE);
    }

    @Test public void pendingWhitelistSurvivesReloadBeforeTheWipe() {
        var first = engine();
        first.markPkDeath(2000, Map.of(), HELD, first.events.owner);
        var gson = new com.google.gson.Gson();
        var state = gson.fromJson(gson.toJson(first.savedState()), SavedState.class);
        var next = engine(); next.restore(state, 2500);
        next.resume(2600, PauseReason.IDLE, PauseReason.RECOVERY);
        next.setBaseline(new ContainerSnapshot(HELD));
        settle(next, Map.of(), 3000);
        assertEquals(-721, next.metrics(4000).net);
        assertTrue(next.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Loot", "Grave"), 20));
        settle(next, Map.of(10499,1L,19564,1L), 5000);
        assertEquals(-721, next.metrics(6000).net);
    }

    @Test public void missingSnapshotAndStaleOwnerCannotGrantOwnership() {
        var engine = engine();
        assertNull(engine.markPkDeath(2000, Map.of(1079,2L), HELD, engine.events.owner-1));
        assertEquals(0, engine.session().pk().deaths);
        assertNull(engine.savedState().pendingDeathReclaim);
        engine.markPkDeath(2000, Map.of(), null, engine.events.owner);
        settle(engine, Map.of(), 3000);
        assertEquals(-1333, engine.metrics(4000).net);
        assertFalse(engine.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Loot", "Grave"), 20));
    }

    @Test public void aBankTransferCannotMasqueradeAsTheDeathWipe() {
        var engine = engine();
        engine.markPkDeath(2000, Map.of(), HELD, engine.events.owner);
        engine.markTransfer("Bank", 20);
        settle(engine, Map.of(), 3000);
        assertNull(engine.savedState().pendingDeathReclaim);
    }

    @Test public void gravestoneRowsRemainVisibleWithoutUnrelatedTransferAudit() {
        var engine = engine(false); death(engine);
        assertTrue(engine.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Loot", "Grave"), 20));
        settle(engine, Map.of(10499,1L,19564,1L), 5000);
        assertEquals(2, engine.session().transactions().stream().filter(Transaction::isTransfer).count());
        assertEquals(-921, engine.metrics(6000).net);
    }
}
