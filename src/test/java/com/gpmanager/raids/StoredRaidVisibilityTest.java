package com.gpmanager;

import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

/** Retained typed raid receipts remain useful even when the old attempt summary is absent. */
public class StoredRaidVisibilityTest {
    static Engine restored(boolean compacted) {
        var engine = ReviewFixesTest.engine();
        var fee = engine.bookDeathFee(500000, null, ReviewFixesTest.T0 + 100);
        fee.flows().get(0).raid = "2:" + UUID.randomUUID();
        var saved = SaveCopies.copy(engine.savedState());
        saved.raidHistory = null;
        if (compacted) {
            var session = saved.customSession == null ? saved.generalSession : saved.customSession;
            session.transactions.clear();
            session.retainedCosts = 500000;
            session.compactedRaids = new HashMap<>(Map.of("2", new long[] {0, 500000}));
        }
        var restored = ReviewFixesTest.engine();
        restored.restore(saved, ReviewFixesTest.T0 + 200);
        return restored;
    }

    @Test public void anOlderTaggedToaFeeRevealsTheTabWithoutInventingAnAttempt() throws Exception {
        var engine = restored(false);
        assertTrue(engine.raids.unlocked());
        assertTrue(engine.raids.state.runs.isEmpty());
        assertEquals(0, engine.raids.state.clears[2]);
        assertEquals(-500000, engine.raids.read("all").session.metrics(0).net);
        SwingUtilities.invokeAndWait(() -> {
            var panel = RaidNavigationTest.panel(engine);
            panel.refreshNow();
            assertEquals(4, panel.shell.tabs.getComponentCount());
            assertEquals(Shell.LIVE, panel.shell.current);
            panel.shell.show(Shell.RAIDS); panel.refreshNow();
            assertTrue(panel.raids.history.rows.stream().anyMatch(row -> row.name.equals("Tombs of Amascut")));
        });
    }

    @Test public void olderCompactedRaidFeesStillRevealTheirRaid() {
        var engine = restored(true);
        assertTrue(engine.raids.unlocked());
        assertEquals(-500000, engine.raids.read("all").session.metrics(0).net);
        assertTrue(engine.raids.state.runs.isEmpty());
    }

    @Test public void anUntaggedRetrievalFeeDoesNotGuessTheRaidFromItsAmountOrLabel() {
        var engine = ReviewFixesTest.engine();
        engine.bookDeathFee(500000, "Tombs of Amascut", ReviewFixesTest.T0 + 100);
        var saved = SaveCopies.copy(engine.savedState()); saved.raidHistory = null;
        engine.restore(saved, ReviewFixesTest.T0 + 200);
        assertFalse(engine.raids.unlocked());
        assertEquals(-500000, engine.metrics(ReviewFixesTest.T0 + 300).net);
    }
    @Test public void resetAndReplacementProfileCannotRetainRecoveredVisibility() {
        var engine = restored(false);
        engine.resetData(ReviewFixesTest.T0 + 300);
        assertFalse(engine.raids.unlocked());
        var first = restored(false);
        first.restore(SaveCopies.copy(ReviewFixesTest.engine().savedState()), ReviewFixesTest.T0 + 400);
        assertFalse(first.raids.unlocked());
        assertTrue(first.raids.read("all").session.transactions().isEmpty());
    }
}
