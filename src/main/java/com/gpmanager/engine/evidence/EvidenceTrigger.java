package com.gpmanager.engine.evidence;

/**
 * Marker for discrete gameplay evidence that should expire on its own clock
 * rather than sharing one multi-tick window with unrelated sources.
 *
 * <p>Full charge/container accounting will plug into this package later and
 * must surface calibration warnings (e.g. "Check X to calibrate") before
 * guessing unknown charge state. Burial vs bank-open already use distinct
 * lifetimes inside {@link com.gpmanager.engine.GpManagerEngine}.</p>
 */
public interface EvidenceTrigger
{
    /** Wall-clock or game-tick expiry for this evidence. */
    boolean isExpired(long nowEpochMillis);

    /** Short diagnostic label for logs/tests. */
    String label();
}
