package com.gpmanager;

import com.google.gson.Gson;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** GP/h is one number everywhere: Net over active time, live and after the Grind ends. */
public class LiveRateTest {
    private static final long START = 1_000L;
    private static final long NOW = START + 300_000L;

    private static Engine engine() {
        JsonCodec.bind(new Gson());
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        engine.startCustom("Rate", START);
        Session session = engine.session();
        for (long at : new long[] {START + 30_000L, START + 240_000L}) {
            session.addTransaction(Tx.of(at, TransactionType.GAIN, Context.GENERIC, "Gain", true,
                Collections.singletonList(new Flow(995, "Coins", 1_000L, 1, 1_000L))), 100, false);
        }
        return engine;
    }

    @Test public void liveGrindsAndClosedRunsShareTheWholeRunRate() {
        Engine engine = engine();
        String id = engine.session().id();
        LiveSnapshot live = LiveSnapshot.capture(engine, NOW, null);
        GrindsData grinds = GrindsData.capture(engine, NOW, false, id);
        assertEquals(24_000L, live.rate);
        assertEquals(live.rate, grinds.rate);
        assertEquals(live.rate, grinds.detail.rate);
        engine.finishCustom(NOW);
        GrindsData closed = GrindsData.capture(engine, NOW, false, id);
        assertTrue(closed.detail.closed);
        assertEquals(24_000L, closed.detail.rate);
    }
}
