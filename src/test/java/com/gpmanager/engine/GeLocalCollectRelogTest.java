package com.gpmanager;

import java.util.Map;
import org.junit.Test;
import static net.runelite.api.GrandExchangeOfferState.*;
import static org.junit.Assert.*;

/**
 * An offer collected to the bank here still books when the player hops or logs out before its
 * 30 s bank settlement; an offer first seen collected at a login stays an away claim even after a
 * second quick login.
 */
public class GeLocalCollectRelogTest {
    static void login(GeDeviceCollectionTest.H h) {
        h.now += 5_000;
        h.engine.exchange.clearPending();
        h.engine.seedGeSlots(Map.of(0, new OfferSlots.Snapshot(0, EMPTY, 0, 0, 0, 0, 0)), h.now);
    }

    static SavedState roundTrip(SavedState state) {
        var gson = new com.google.gson.Gson();
        return gson.fromJson(gson.toJson(state), SavedState.class);
    }

    /** A client restart: the saved profile comes back paused until gameplay resumes it. */
    static void restart(GeDeviceCollectionTest.H h) {
        h.engine.restoreProfile(null, roundTrip(h.engine.savedState()), h.now);
        h.engine.resume(h.now, PauseReason.IDLE, PauseReason.RECOVERY);
    }

    static void later(GeDeviceCollectionTest.H h) {
        h.now += GeCustodyLedger.UNOBSERVED_GRACE_MILLIS + GeCustodyLedger.LOGIN_REPLAY_MILLIS + 1000;
        h.engine.tickGe(h.now);
    }

    @Test public void aBankCollectHereBooksAfterAQuickHop() {
        for (boolean restart : new boolean[] {false, true}) {
            var h = new GeDeviceCollectionTest.H();
            h.listAndFill(10, 10);
            h.engine.noteGeCollect(h.now);
            h.event(EMPTY, 0, 0, 0, 0, 0);
            if (restart) restart(h);
            login(h);
            later(h);
            assertEquals("restart " + restart, 1, h.trades());
            assertEquals(0, h.reviews());
            assertEquals(-40, h.net());
        }
    }

    @Test public void everyReleasedSlotRecordStillSettlingSurvivesARestart() {
        var state = new SavedState();
        for (String id : new String[] {"first", "second"}) {
            var record = new GeOffer();
            record.offerId = id;
            record.slot = -1;
            record.placedAtEpochMillis = state.geCustody().size() + 1;
            state.geCustody().add(record);
        }
        var ledger = new GeCustodyLedger();
        ledger.restore(state, 0L);
        assertEquals("one slot reused twice keeps both earlier offers", 2, ledger.records().size());
    }

    @Test public void anAwayClaimStaysSilentAfterASecondQuickLogin() {
        for (boolean restart : new boolean[] {false, true}) {
            var h = new GeDeviceCollectionTest.H();
            h.listAndFill(10, 10);
            login(h);
            if (restart) restart(h);
            login(h);
            later(h);
            assertEquals("restart " + restart, 0, h.trades());
            assertEquals(0, h.reviews());
            assertEquals(0, h.net());
        }
    }

    @Test public void anOlderClearedOfferWithoutCollectionProvenanceNeverBecomesLocalIncome() {
        for (boolean explicitNull : new boolean[] {false, true}) {
            var h = new GeDeviceCollectionTest.H();
            h.listAndFill(10, 10);
            login(h);
            var gson = new com.google.gson.Gson();
            var wire = gson.toJsonTree(h.engine.savedState()).getAsJsonObject();
            for (var value : wire.getAsJsonArray("geCustody")) {
                var offer = value.getAsJsonObject();
                if (explicitNull) offer.add("collectedAway", com.google.gson.JsonNull.INSTANCE);
                else offer.remove("collectedAway");
            }
            h.engine.restoreProfile(null, gson.fromJson(wire, SavedState.class), h.now);
            h.engine.resume(h.now, PauseReason.IDLE, PauseReason.RECOVERY);
            login(h);
            later(h);
            assertEquals("unknown legacy provenance, null=" + explicitNull, 0, h.trades());
            assertEquals(0, h.net());
        }
    }
}
