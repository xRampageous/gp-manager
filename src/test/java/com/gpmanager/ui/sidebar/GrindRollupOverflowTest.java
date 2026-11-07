package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrindRollupOverflowTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final long UNIT = 1_000_000_000_000_000_000L;

    private static List<Session> runs(long... values) {
        var engine = ReviewFixesTest.engine();
        for (int index = 0; index < values.length; index++) {
            long at = NOW + index * 60_000;
            engine.startCustom("Run " + index, at);
            long value = values[index];
            engine.session().addTransaction(Tx.of(at + 1,
                    value >= 0 ? TransactionType.GAIN : TransactionType.CONSUMPTION, Context.GENERIC, "", true,
                    List.of(new Flow(526, "Bones", value >= 0 ? 1 : -1, SafeMath.abs(value), value,
                            PriceSource.MANUAL_OVERRIDE))), 2000, false);
            engine.finishCustom(at + 60_000);
        }
        return new ArrayList<>(engine.history());
    }

    private static MoneySnapshot.Rollup rollup(List<Session> sessions) {
        var engine = ReviewFixesTest.engine();
        engine.history.addAll(sessions);
        return engine.summary().new Rollup(sessions);
    }

    @Test public void positiveAndNegativeTotalsNeverWrapTheirSigns() {
        var positive = rollup(runs(4 * UNIT, 4 * UNIT, 4 * UNIT));
        assertEquals(Long.MAX_VALUE, positive.total);
        assertTrue(Session.hourly(positive.total, positive.millis) > 0);
        var negative = rollup(runs(-4 * UNIT, -4 * UNIT, -4 * UNIT));
        assertEquals(-Long.MAX_VALUE, negative.total);
        assertTrue(Session.hourly(negative.total, negative.millis) < 0);
    }

    @Test public void mixedSignsUseOneBoundedFoldInBothRunOrders() {
        var sessions = runs(4 * UNIT, 4 * UNIT, 4 * UNIT, -4 * UNIT);
        assertEquals(Long.MAX_VALUE - 4 * UNIT, rollup(sessions).total);
        Collections.reverse(sessions);
        assertEquals(Long.MAX_VALUE - 4 * UNIT, rollup(sessions).total);
    }

    @Test public void durationAdditionSaturatesWithoutChangingRunSelection() {
        var sessions = runs(10, 20, 30);
        sessions.forEach(session -> session.savedActiveMillis = 4 * UNIT);
        var total = rollup(sessions);
        assertEquals(Long.MAX_VALUE, total.millis);
        assertEquals(60, total.total);
        assertEquals(3, total.count);
        assertEquals(30, total.last);
        assertEquals(30, total.best);
        assertEquals(NOW + 120_000, total.at);
    }

    @Test public void ordinaryAndEmptyRunsKeepTheirOriginalAverageAndFlags() {
        var ordinary = rollup(runs(100, -40, 30));
        assertEquals(90, ordinary.total);
        assertEquals(180_000, ordinary.millis);
        assertEquals(1800, Session.hourly(ordinary.total, ordinary.millis));
        assertEquals(100, ordinary.best);
        assertEquals(30, ordinary.last);
        assertTrue(ordinary.has);
        var empty = rollup(List.of());
        assertEquals(0, empty.total);
        assertEquals(0, empty.millis);
        assertEquals(0, empty.count);
        assertFalse(empty.has);
    }
}
