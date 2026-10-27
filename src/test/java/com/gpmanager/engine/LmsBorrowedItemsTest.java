package com.gpmanager;

import java.nio.charset.StandardCharsets;
import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.*;

/** The expected manifest is primary-source evidence, independent of the compact production table. */
public class LmsBorrowedItemsTest {
    static Map<Integer,String> manifest(String file) throws Exception {
        var result = new LinkedHashMap<Integer,String>();
        try (var input = LmsBorrowedItemsTest.class.getResourceAsStream("/com/gpmanager/"+file)) {
            assertNotNull(input);
            for (String row : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (row.isEmpty() || row.startsWith("#")) continue;
                var fields = row.split("\\t");
                assertEquals(fields[1], Integer.parseInt(fields[0]), ItemID.class.getField(fields[1]).getInt(null));
                result.put(Integer.valueOf(fields[0]), fields[1]);
            }
        }
        return result;
    }
    @Test public void catalogueKeepsAllPriorIdsAndAddsOnlyVerifiedLmsLoans() throws Exception {
        var expected = manifest("minigame-prior-20261024.tsv");
        expected.putAll(manifest("lms-loans-20261024.tsv"));
        expected.putAll(manifest("raid-loans-20261025.tsv"));
        for (var row : expected.entrySet())
            assertEquals(row.getValue(), row.getKey().intValue(), ItemID.class.getField(row.getValue()).getInt(null));
        assertEquals(expected.keySet(), MinigameItems.IDS);
        assertFalse(MinigameItems.isMinigameOnly(ItemID.BR_DEADMAN_BODY));
        assertFalse(MinigameItems.isMinigameOnly(ItemID.BR_CAPE_1000));
        assertFalse(MinigameItems.isMinigameOnly(ItemID.BLIGHTED_4DOSE2RESTORE));
        assertFalse(MinigameItems.isMinigameOnly(ItemID.AGS));
        for (var row : GameData.rows("minigame_items")) {
            var ids=row[0].split("-"); var names=row[1].split("\\.\\.");
            assertTrue(ids.length <= 2);
            assertEquals(ids.length,names.length);
            for (int i=0;i<ids.length;i++) assertEquals(Integer.parseInt(ids[i]),ItemID.class.getField(names[i]).getInt(null));
        }
    }
    @Test public void everyLoanedGrantSpendAndExitBooksNothingEvenWithAuditEnabled() throws Exception {
        var engine = engine();
        var full = new LinkedHashMap<Integer,Long>();
        manifest("lms-loans-20261024.tsv").keySet().forEach(id -> full.put(id, 2L));
        settle(engine, full, 2000);
        engine.noteConsume(ItemID.BR_4DOSE2RESTORE, 6, false, ActionKind.DRINK, null);
        var used = new LinkedHashMap<>(full);
        used.remove(ItemID.BR_4DOSE2RESTORE);
        used.put(ItemID.BR_3DOSE2RESTORE, 3L);
        settle(engine, used, 3000);
        settle(engine, Map.of(), 4000);
        assertTrue(engine.session().transactions().isEmpty());
        assertTrue(engine.getReviewRows(5000).isEmpty());
        assertEquals(0, engine.metrics(5000).net);
    }
    @Test public void lastingRewardsCountOnceBesideLoanedExitItems() {
        var engine = engine();
        engine.setBaseline(new ContainerSnapshot(Map.of(ItemID.BR_AGS, 1L)));
        var rewards = Map.of(ItemID.BLIGHTED_4DOSE2RESTORE, 4L, ItemID.BR_CAPE_1, 1L);
        settle(engine, rewards, 2000);
        settle(engine, rewards, 3000);
        assertEquals(500, engine.metrics(4000).income);
        assertEquals(0, engine.metrics(4000).cost);
        assertTrue(engine.session().transactions().stream().flatMap(row -> row.flows().stream())
                .noneMatch(flow -> flow.item == ItemID.BR_AGS));
    }
    @Test public void entryAndExitPreserveOwnGearWithoutHidingAClaimedReward() {
        var engine = engine();
        engine.setBaseline(new ContainerSnapshot(Map.of(ItemID.AGS,1L)));
        final boolean[] playing = {true};
        var plugin = new GpManagerPlugin(); plugin.engine=engine; plugin.config=engine.config;
        plugin.client=LootingBagMovementTest.proxy(Client.class, (m,a) ->
                m.equals("getVarbitValue") && (int)a[0] == VarbitID.BR_INGAME && playing[0] ? 1 : null);
        plugin.applyNeutralZone(12850); // Deliberately outside the old LMS region list.
        settle(engine, Map.of(ItemID.BR_AGS,1L), 2000);
        plugin.applyNeutralZone(12850);
        settle(engine, Map.of(ItemID.BR_SHARK,2L), 3000);
        playing[0]=false; plugin.applyNeutralZone(12850);
        settle(engine, Map.of(ItemID.AGS,1L,ItemID.BLIGHTED_4DOSE2RESTORE,4L), 4000);
        assertEquals(400, engine.metrics(5000).income);
        assertEquals(0, engine.metrics(5000).cost);
        assertTrue(engine.session().transactions().stream().filter(Transaction::counts)
                .flatMap(row -> row.flows().stream()).allMatch(flow -> flow.item == ItemID.BLIGHTED_4DOSE2RESTORE));
    }
    static Engine engine() {
        var engine = new Engine(deltas -> {
            var flows=new ArrayList<Flow>();
            deltas.forEach((id,qty) -> flows.add(new Flow(id,"Item "+id,qty,100,qty*100)));
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig() {
            @Override public int stabilizationTicks() { return 0; }
            @Override public boolean keepTransferAuditRows() { return true; }
        });
        engine.ensureSession(1000); engine.setBaseline(ContainerSnapshot.empty()); return engine;
    }
    static void settle(Engine engine, Map<Integer,Long> held, long at) {
        engine.markDirty();
        for(int i=0;i<3;i++) engine.processIfDirty(new ContainerSnapshot(held),at+i,null);
    }
}
