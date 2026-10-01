package com.gpmanager;

import com.google.gson.Gson;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** GP/h is one number everywhere: Net over active time, live and after the Grind ends. */
public class LiveRateTest {
    private static final long START = 1_000L;
    private static final long NOW = START + 300_000L;

    private static Am engine() {
        JsonCodec.bind(new Gson());
        Am engine = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        engine.ajl("Rate", Cx.GENERAL, START);
        Ad session = engine.getActiveSession();
        for (long at : new long[] {START + 30_000L, START + 240_000L}) {
            session.kf(Tx.of(at, Ai.GAIN, Aj.GENERIC, "Gain", true,
                Collections.singletonList(new Ab(995, "Coins", 1_000L, 1, 1_000L))), 100);
        }
        return engine;
    }

    @Test public void liveGrindsAndClosedRunsShareTheWholeRunRate() {
        Am engine = engine();
        String id = engine.getActiveSession().getId();
        Ca live = Ca.capture(engine, NOW, null);
        As grinds = As.capture(engine, NOW, false, id);
        assertEquals(24_000L, live.gpPerHour);
        assertEquals(live.gpPerHour, grinds.activeGpPerHour);
        assertEquals(live.gpPerHour, grinds.detail.gpPerHour);
        engine.sx(NOW);
        As closed = As.capture(engine, NOW, false, id);
        assertTrue(closed.detail.closed);
        assertEquals(24_000L, closed.detail.gpPerHour);
    }
}
