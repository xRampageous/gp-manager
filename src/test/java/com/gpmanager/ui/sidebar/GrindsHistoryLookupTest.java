package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.*;

/** History already captured by identity must not be searched again for every displayed row. */
public class GrindsHistoryLookupTest {
    private static final long NOW = 1_700_000_000_000L;

    static class Probe extends Engine {
        int lookups;

        Probe() {
            super(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        }

        @Override synchronized Session historySession(String id) {
            lookups++;
            return super.historySession(id);
        }
    }

    static Transaction gain(long at, long quantity) {
        return Tx.of(at, TransactionType.GAIN, Context.GENERIC, "", true,
                Collections.singletonList(new Flow(995, "Coins", quantity, 1, quantity)));
    }

    @Test public void aFullGrindsCaptureDoesNotRescanHistoryForEveryCanonicalSession() {
        var engine = new Probe();
        for (int index = 0; index < 2_000; index++) {
            var session = new Session("Run " + index, NOW - (index + 1L) * 100_000L, SessionMode.GENERAL);
            session.addTransaction(gain(session.startedAt, 500), 2_000, false);
            session.close(session.startedAt + 60_000L);
            engine.history.add(session);
        }
        engine.startCustom("Current", NOW);
        engine.session().addTransaction(gain(NOW, 1_000), 2_000, false);
        var view = GrindsData.capture(engine, NOW + 60_000L, false, null);
        assertEquals(2_000, view.recent.size());
        assertEquals(1_001_000L, view.overall.net);
        assertEquals(1_000L, view.activeNet);
        assertEquals("already captured canonical sessions need no history lookup", 0, engine.lookups);
    }

    @Test public void anAliasStillUsesTheRetainedHistoricalCopy() {
        var engine = new Probe();
        var retained = new Session("Retained", NOW, SessionMode.GENERAL);
        retained.addTransaction(gain(NOW, 500), 2_000, false);
        retained.close(NOW + 60_000L);
        var alias = SaveCopies.copy(retained);
        alias.addTransaction(gain(NOW + 1, 900), 2_000, false);
        engine.history.add(retained);
        var snapshot = engine.summary();
        assertEquals(500, snapshot.run(engine, alias, NOW + 120_000L).net());
        assertEquals("an uncaptured alias keeps the history fallback", 1, engine.lookups);
        assertEquals(500, snapshot.run(engine, retained, NOW + 120_000L).net());
        assertEquals("the canonical copy reuses its captured facts", 1, engine.lookups);
    }
}
