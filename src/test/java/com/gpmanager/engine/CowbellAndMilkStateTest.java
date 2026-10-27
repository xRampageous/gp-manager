package com.gpmanager;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.gpmanager.StoredChargeFixture.*;

/** Physical state swaps stay neutral; the cowbell's measured air runes are supplies. */
public class CowbellAndMilkStateTest {
    static final int EMPTY = 33103, CHARGED = 33104, COUNTER = 20097, AIR = 556;
    @Test public void cowbellCountsOnlyCarriedMeasuredAirRunesAndWatchesTheCounter() {
        assertEquals(7, snapshot(CHARGED, COUNTER, 7, Map.of()).quantity(AIR));
        assertEquals(0, snapshot(EMPTY, COUNTER, 7, Map.of()).quantity(AIR));
        assertEquals(0, snapshot(0, COUNTER, 7, Map.of()).quantity(AIR));
        assertTrue(ContainerSnapshotFactory.storedCharges(COUNTER));
    }
    @Test public void firstLastAndRefundBoundariesKeepTheOwnedAmulet() {
        for (int boundary = 0; boundary < 3; boundary++) {
            Engine engine = engine();
            var empty = snapshot(EMPTY, COUNTER, 0, Map.of(AIR, 1L));
            var charged = snapshot(CHARGED, COUNTER, 1, Map.of());
            engine.setBaseline(boundary == 0 ? empty : charged);
            settle(engine, boundary == 0 ? charged : boundary == 1
                    ? snapshot(EMPTY, COUNTER, 0, Map.of()) : empty, NOW + 1000);
            assertEquals(boundary == 1 ? 100 : 0, engine.metrics(NOW + 20_000).cost);
            assertEquals(0, engine.metrics(NOW + 20_000).income);
            assertEquals(0, reviews(engine));
        }
    }
    @Test public void bankCustodyDoesNotSpendOrCreateTheStoredAirRunes() {
        Engine engine = engine(); var held = snapshot(CHARGED, COUNTER, 7, Map.of());
        engine.setBaseline(held); engine.markContext(Context.TRANSFER, 50, "Bank");
        settle(engine, snapshot(0, COUNTER, 7, Map.of()), NOW + 1000);
        engine.markContext(Context.TRANSFER, 50, "Bank");
        settle(engine, held, NOW + 20_000);
        assertEquals(0, engine.metrics(NOW + 30_000).net);
        assertEquals(0, reviews(engine));
    }
    @Test public void bottomlessMilkBucketFillAndEmptyAreOnePhysicalItem() {
        for (boolean filling : new boolean[]{false, true}) {
            Engine engine = engine();
            engine.setBaseline(factory(Map.of(filling ? 33091 : 33089, 1L), Map.of()).capture(true, false));
            settle(engine, factory(Map.of(filling ? 33089 : 33091, 1L), Map.of()).capture(true, false), NOW + 1000);
            assertEquals(0, engine.metrics(NOW + 20_000).income);
            assertEquals(0, engine.metrics(NOW + 20_000).cost);
            assertEquals(0, reviews(engine));
        }
    }
}
