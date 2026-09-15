package com.gpmanager.ui;

import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.persistence.PersistenceCoordinator;
import javax.annotation.Nullable;

/**
 * Shared Live / Paused / AFK / Stopped / Recovery / Account hold labels.
 * Session idle-timeout pause is {@code AFK}; HUD character idle is a separate
 * snapshot flag that paints {@code Idle} only while the session is Live.
 */
public final class TrackingStatus
{
    private TrackingStatus()
    {
    }

    public static String resolve(
        GpManagerEngine engine,
        @Nullable PersistenceCoordinator persistence,
        SessionMetrics metrics,
        @Nullable ProfitSession active)
    {
        if (persistence != null && persistence.isIdentitySwitchHeld())
        {
            return "Account hold";
        }
        if (engine.isStopped())
        {
            return "Stopped";
        }
        if (active == null)
        {
            return "No session";
        }
        if (engine.isIdlePaused())
        {
            return "AFK";
        }
        if (metrics != null && metrics.isPaused())
        {
            return active.isRecoveredFromCrash() ? "Recovery" : "Paused";
        }
        return "Live";
    }

    public static String compactHeader(String status)
    {
        if (status == null || status.isEmpty())
        {
            return "IDLE";
        }
        switch (status)
        {
            case "No session":
                return "IDLE";
            case "Account hold":
                return "HOLD";
            case "AFK":
                return "AFK";
            default:
                return status.toUpperCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Short HUD+ status tokens for the overlay header. Empty when Live so the
     * activity/source name stands alone. Character idle is applied separately
     * via {@link #compactHudPlus(String, boolean)}.
     */
    public static String compactHudPlus(String status)
    {
        return compactHudPlus(status, false);
    }

    /**
     * @param characterIdle presentation-only; applies to Live and legacy Tracking
     *        labels (AFK / PAUSED / STOP / etc. always win).
     */
    public static String compactHudPlus(String status, boolean characterIdle)
    {
        String normalized = status == null ? "" : status.trim();
        if (normalized.isEmpty() || "Live".equalsIgnoreCase(normalized)
            || "Tracking".equalsIgnoreCase(normalized))
        {
            return characterIdle ? "Idle" : "";
        }
        switch (normalized)
        {
            case "Recovery":
                return "REC";
            case "Paused":
                return "PAUSED";
            case "Account hold":
                return "HOLD";
            case "AFK":
                return "AFK";
            case "Idle": // legacy session-idle string if any caller still passes it
                return "AFK";
            case "Stopped":
                return "STOP";
            case "No session":
                return "IDLE";
            default:
                return status.toUpperCase(java.util.Locale.ROOT);
        }
    }
}
