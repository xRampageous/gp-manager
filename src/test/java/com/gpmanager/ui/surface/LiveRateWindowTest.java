package com.gpmanager;

import com.google.gson.Gson;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** The configured live window changes estimates, never booked money or completed-run rates. */
public class LiveRateWindowTest {
    private static final long START = 1_000L;
    private static final long NOW = START + 300_000L;

    private static final class WindowConfig implements GpManagerConfig {
        int minutes = 1;
        @Override public int rollingRateMinutes() { return minutes; }
    }

    private static Am engine(WindowConfig config) {
        JsonCodec.bind(new Gson());
        Am engine = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ajl("Window", Cx.GENERAL, START);
        Ad session = engine.getActiveSession();
        for (long at : new long[] {START + 30_000L, START + 240_000L}) {
            session.kf(Tx.of(at, Ai.GAIN, Aj.GENERIC, "Gain", true,
                Collections.singletonList(new Ab(995, "Coins", 1_000L, 1, 1_000L))), 100);
        }
        engine.ahq(5_000L, null, NOW);
        return engine;
    }

    @Test public void windowChangesLiveAndActiveGrindRateAndEta() {
        WindowConfig config = new WindowConfig();
        Am engine = engine(config);
        String id = engine.getActiveSession().getId();
        Ca live = Ca.capture(engine, NOW, null);
        As grinds = As.capture(engine, NOW, false, id);
        assertEquals(60_000L, live.gpPerHour);
        assertEquals("~3m", live.goal.eta);
        assertEquals(live.gpPerHour, grinds.activeGpPerHour);
        assertEquals(live.gpPerHour, grinds.detail.gpPerHour);
        assertEquals("~3m remaining", grinds.activePace.line);
        config.minutes = 10;
        Ca longer = Ca.capture(engine, NOW, null);
        assertEquals(26_667L, longer.gpPerHour);
        assertEquals("~7m", longer.goal.eta);
        As longerGrinds = As.capture(engine, NOW, false, id);
        assertEquals(longer.gpPerHour, longerGrinds.activeGpPerHour);
        assertEquals(longer.gpPerHour, longerGrinds.detail.gpPerHour);
        assertEquals(live.net, longer.net);
        assertEquals(2_000L, longer.net);
    }

    @Test public void completedRunRateKeepsItsFullSessionBasis() {
        WindowConfig config = new WindowConfig();
        Am engine = engine(config);
        String id = engine.getActiveSession().getId();
        engine.sx(NOW);
        As closed = As.capture(engine, NOW, false, id);
        assertTrue(closed.detail.closed);
        assertEquals(24_000L, closed.detail.gpPerHour);
        assertEquals(2_000L, closed.detail.net);
        config.minutes = 10;
        assertEquals(closed.detail.gpPerHour, As.capture(engine, NOW, false, id).detail.gpPerHour);
    }
}
