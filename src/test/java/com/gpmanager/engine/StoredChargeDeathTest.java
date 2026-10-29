package com.gpmanager;

import java.util.*;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.gpmanager.StoredChargeFixture.*;

/** Only observed losses book; stored components remain owned through a grave/retrieval service. */
public class StoredChargeDeathTest {
    static ContainerSnapshot owned(ContainerSnapshotFactory factory) {
        return factory.captureDeathOwned();
    }

    @Test public void graveAndRetrievalReturnNoIncomeForAnyStoredItem() {
        var cases = new ArrayList<>(StoredChargeBoundariesTest.FAMILIES);
        cases.add(new StoredChargeBoundariesTest.Family(ItemID.ASH_SANCTIFIER, ItemID.ASH_SANCTIFIER,
                VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY, 10, 0, Map.of(ItemID.DEATHRUNE, 1L)));
        for (var family : cases) for (String service : new String[]{"Grave", "Torfinn"}) {
            Engine engine = engine();
            var factory = factory(Map.of(family.charged, 1L), Map.of(family.varbit, family.full));
            var before = factory.capture(true, false);
            engine.setBaseline(before);
            engine.markPvmDeath(20, owned(factory).items, Map.of(), engine.events.owner);
            settle(engine, FixtureReads.empty(), NOW + 1000);
            assertEquals("death cost for " + family.charged, 0, engine.metrics(NOW + 10_000).cost);
            var retrieval = BossRetrievalCatalogue.serviceFor(service.equals("Grave") ? "Loot" : "Collect", service);
            assertNotNull(service, retrieval);
            assertTrue(engine.noteReclaimIntent(retrieval, 20));
            settle(engine, before, NOW + 20_000);
            assertEquals(0, engine.metrics(NOW + 30_000).income);
            assertEquals(0, engine.metrics(NOW + 30_000).net);
            assertEquals(0, reviews(engine));
            assertFalse(FixtureReads.deathStatus(engine).pending);
            assertNull(engine.savedState().pendingDeathReclaim);
        }
    }

    @Test public void deathProjectionIncludesStockButRiskKeepsPhysicalUnits() {
        for (var family : StoredChargeBoundariesTest.FAMILIES) {
            var factory = factory(Map.of(family.charged, 1L), Map.of(family.varbit, family.full));
            for (var component : family.recipe.entrySet())
                assertEquals(component.getValue().longValue(), owned(factory).quantity(component.getKey()));
            assertEquals(Map.of(family.charged, 1L), factory.captureDeathHeld(true).items);
        }
    }

    @Test public void pkDeathCountsOnlyTheObservedLostPortion() {
        for (var family : StoredChargeBoundariesTest.FAMILIES) for (int disposition = 0; disposition < 3; disposition++) {
            Engine engine = engine();
            var factory = factory(Map.of(family.charged, 1L), Map.of(family.varbit, family.full));
            var before = factory.capture(true, false);
            engine.setBaseline(before);
            engine.markPkDeath(NOW + 1000, Map.of(), owned(factory).items, engine.events.owner);
            ContainerSnapshot after = disposition == 0 ? before : disposition == 1
                    ? snapshot(family.empty, family.varbit, 0, Map.of()) : FixtureReads.empty();
            settle(engine, after, NOW + 2000);
            long contents = family.recipe.values().stream().mapToLong(Long::longValue).sum() * 100;
            long expected = disposition == 0 ? 0 : contents + (disposition == 2 ? 100 : 0);
            assertEquals("PK disposition " + disposition + " for " + family.charged,
                    expected, engine.metrics(NOW + 20_000).cost);
            assertEquals(0, engine.metrics(NOW + 20_000).income);
            assertEquals(0, reviews(engine));
        }
    }
}
