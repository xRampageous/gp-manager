package com.gpmanager.engine;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Contract tests for the offline/live boundary around GE settlement.
 *
 * <p>The offer ledger remains an observation source in both modes.  Until a live
 * comparison confirms sell-side {@code getSpent()} semantics, selecting OBSERVED
 * is deliberately an explicit opt-in and does not change the provenance stream.
 */
public class GeBookingModeTest
{
    @Test
    public void provenanceOnlyIsTheSafeDefaultAndNullRestoresIt()
    {
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), new com.gpmanager.GpManagerConfig() {});

        assertEquals(GeBookingMode.PROVENANCE_ONLY, engine.getGeBookingMode());
        engine.setGeBookingMode(GeBookingMode.OBSERVED);
        assertEquals(GeBookingMode.OBSERVED, engine.getGeBookingMode());
        engine.setGeBookingMode(null);
        assertEquals(GeBookingMode.PROVENANCE_ONLY, engine.getGeBookingMode());
    }
}
