package com.gpmanager;

import java.util.*;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.gpmanager.StoredChargeFixture.*;

/** First load, depletion and returned-component state shapes never create owned-item income. */
public class StoredChargeBoundariesTest {
    static final class Family {
        final int charged, empty, varbit, full;
        final long lastCost;
        final Map<Integer, Long> recipe;
        Family(int charged, int empty, int varbit, int full, long lastCost, Map<Integer, Long> recipe) {
            this.charged = charged; this.empty = empty; this.varbit = varbit; this.full = full;
            this.lastCost = lastCost; this.recipe = recipe;
        }
    }
    static final List<Family> FAMILIES = List.of(
            new Family(ItemID.OBLIGATOR, ItemID.OBLIGATOR_UNCHARGED, VarbitID.CHARGES_OBLIGATOR_QUANTITY,
                    10, 400, Map.of(ItemID.ASCENSION_SHARDS, 1L, ItemID.NATURERUNE, 20L, ItemID.DEATHRUNE, 20L)),
            new Family(ItemID.ARCHIVISTS_FOLIO, ItemID.ARCHIVISTS_FOLIO_EMPTY,
                    VarbitID.CHARGES_ARCHIVISTS_FOLIO_QUANTITY, 5, 0, Map.of(ItemID.ASCENSION_SHARDS, 1L)),
            new Family(ItemID.ZORYAS_TOME, ItemID.ZORYAS_TOME_UNCHARGED, VarbitID.CHARGES_ZORYAS_TOME_QUANTITY,
                    1, 500, Map.of(ItemID.ASTRALRUNE, 3L, ItemID.NATURERUNE, 2L)));

    @Test public void exactOwnedFamiliesCoverAllSixStates() {
        for (Family family : FAMILIES) {
            String piece = Engine.piece(new Flow(family.charged, "Charged", 1, 100, 100));
            assertNotNull(String.valueOf(family.charged), piece);
            assertEquals(piece, Engine.piece(new Flow(family.empty, "Inert", 1, 100, 100)));
        }
    }

    @Test public void firstChargeIsNeutralEvenDuringAnExistingProductionWindow() {
        for (Family family : FAMILIES) for (Context context : new Context[]{Context.GENERIC, Context.PRODUCTION}) {
            Engine engine = engine();
            engine.setBaseline(snapshot(family.empty, family.varbit, 0, family.recipe));
            engine.markContext(context, 20, "Loading charges");
            settle(engine, snapshot(family.charged, family.varbit, family.full, Map.of()), NOW + 1000);
            assertNeutral(engine, family.charged);
        }
    }

    @Test public void finalChargeCountsOnlyItsActuallyRemovedComponents() {
        for (Family family : FAMILIES) for (Context context : new Context[]{Context.GENERIC, Context.PRODUCTION}) {
            Engine engine = engine();
            engine.setBaseline(snapshot(family.charged, family.varbit, 1, Map.of()));
            engine.markContext(context, 20, "Last charge");
            settle(engine, snapshot(family.empty, family.varbit, 0, Map.of()), NOW + 1000);
            assertEquals(String.valueOf(family.charged), 0, reviews(engine));
            assertEquals(0, engine.metrics(NOW + 10_000).income);
            assertEquals(family.lastCost, engine.metrics(NOW + 10_000).cost);
            assertEquals(-family.lastCost, engine.metrics(NOW + 10_000).net);
        }
    }

    @Test public void returnedComponentsAndTheEmptyParentStayOwned() {
        // The folio has no native Uncharge menu: its case is a refund-shaped ownership control,
        // not a claim that the player can invoke that action in game.
        for (Family family : FAMILIES) for (Context context : new Context[]{Context.GENERIC, Context.PRODUCTION}) {
            Engine engine = engine();
            engine.setBaseline(snapshot(family.charged, family.varbit, family.full, Map.of()));
            engine.markContext(context, 20, "Components returned");
            settle(engine, snapshot(family.empty, family.varbit, 0, family.recipe), NOW + 1000);
            assertNeutral(engine, family.charged);
        }
    }

    @Test public void sourceConfirmedLootCannotBeSwallowedByTheFamilyPair() {
        for (Family family : FAMILIES) {
            Engine engine = engine();
            engine.setBaseline(snapshot(family.empty, family.varbit, 0, Map.of()));
            engine.markLoot(Map.of(family.charged, 1L), 20, "Source loot", "Fixture NPC");
            settle(engine, snapshot(family.charged, family.varbit, 0, Map.of()), NOW + 1000);
            assertEquals(100, engine.metrics(NOW + 10_000).income);
        }
    }

    static void assertNeutral(Engine engine, int item) {
        assertEquals("Review for " + item, 0, reviews(engine));
        assertEquals(0, engine.metrics(NOW + 10_000).income);
        assertEquals(0, engine.metrics(NOW + 10_000).cost);
        assertEquals(0, engine.metrics(NOW + 10_000).net);
    }
}
