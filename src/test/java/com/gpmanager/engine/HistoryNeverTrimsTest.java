package com.gpmanager;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Receipt detail may compact; reaching a session count never discards canonical money or Review. */
public class HistoryNeverTrimsTest {
    static final long NOW = StoredChargeFixture.NOW;

    @Test public void restoreAboveTheOldShippingCapKeepsMoneyAndUndecidedReview() {
        Engine engine = StoredChargeFixture.engine();
        for (int index = 0; index < 2001; index++) {
            long at = NOW - 400L * 86_400_000 - index * 1000L;
            var session = new Session("History " + index, at, SessionMode.GENERAL);
            session.addTransaction(gain(at), 2000, false);
            if (index == 0) {
                var review = Tx.of(at + 1, TransactionType.UNCERTAIN, Context.GENERIC, "Undecided", false,
                        List.of(new Flow(536, "Dragon bones", 1, 100, 100)));
                review.confidence = Confidence.UNCERTAIN;
                assertTrue(ReviewRules.needsReview(review));
                session.addTransaction(review, 2000, false);
            }
            session.close(at + 100);
            engine.history.add(0, session);
        }
        assertEquals(200_100, GrindsData.allTime(engine, NOW).net);
        Engine restored = StoredChargeFixture.engine();
        restored.restore(engine.savedState(), NOW + 1000);
        assertEquals(2001, restored.history.size());
        assertEquals(200_100, GrindsData.allTime(restored, NOW + 1000).net);
        assertEquals(1, restored.sessions().stream().flatMap(session -> session.transactions().stream())
                .filter(ReviewRules::needsReview).count());
        assertEquals("ordinary old details still compact", 1,
                restored.history.stream().mapToLong(session -> session.transactions().size()).sum());
    }

    @Test public void finishingGrindsCannotEvictOlderCanonicalSessions() {
        Engine engine = new Engine(StoredChargeFixture.engine().valuation, new TransactionClassifier(),
                new GpManagerConfig() { @Override public int maxHistorySessions() { return 1; } });
        engine.ensureSession(NOW);
        for (int index = 0; index < 3; index++) {
            engine.startCustom("Trip " + index, NOW + index * 1000);
            engine.session().addTransaction(gain(NOW + index * 1000 + 100), 2000, false);
            engine.finishCustom(NOW + index * 1000 + 500);
        }
        assertEquals(3, engine.history.size());
        assertEquals(300, GrindsData.allTime(engine, NOW + 10_000).net);
    }

    static Transaction gain(long at) {
        return Tx.of(at, TransactionType.GAIN, Context.GENERIC, "Owned history", true,
                List.of(new Flow(995, "Coins", 100, 1, 100, PriceSource.FACE_VALUE)));
    }
}
