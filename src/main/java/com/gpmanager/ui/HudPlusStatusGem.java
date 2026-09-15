package com.gpmanager.ui;

import java.awt.Color;
import javax.annotation.Nullable;

/**
 * Trip Beacon status-gem colour kind. Precedence: session tokens beat Banking;
 * Busy (coalesce / batch-lock / activity-hold) beats Idle so Pending/Ground Loot
 * keep a pulsing gem while the cassette is still open.
 *
 * <p>Factory HUD+ accent and label both default to {@code #8B93A7}, so Active/Busy
 * use a teal live accent when accent matches muted — otherwise Active and Idle
 * would be identical grey. Banking stays muted grey (neutral UI, not gain/loss).</p>
 */
public final class HudPlusStatusGem
{
    /** Fallback live gem when accent equals label grey (post–schema 26 factory). */
    static final Color LIVE_TEAL = new Color(0x2E, 0xE6, 0xD6);

    public enum Kind
    {
        /** AFK idle-timeout pause — coral soft pulse. */
        AFK,
        /** Manual pause or crash recovery — coral solid. */
        PAUSED,
        /** Stopped / Account hold / No session — dim muted solid. */
        MUTED_STOP,
        /** Bank UI open — muted grey (neutral, not profit/loss). */
        BANKING,
        /** Character idle while Live — muted solid. */
        IDLE,
        /** Loot coalesce, batch lock, or activity-hold — live accent soft pulse. */
        BUSY,
        /** Live, but no specific target or activity is available to name. */
        GENERIC_LIVE,
        /** No tracked activity yet — neutral solid, paired with the Waiting title. */
        WAITING,
        /** Live with a named activity — live accent solid. */
        ACTIVE
    }

    private HudPlusStatusGem()
    {
    }

    public static Kind resolve(@Nullable TrackingDisplaySnapshot snapshot, boolean activityHold)
    {
        if (snapshot == null)
        {
            return Kind.MUTED_STOP;
        }

        String status = snapshot.getStatusLabel();
        boolean live = status == null || status.isEmpty() || "Live".equalsIgnoreCase(status)
            || "Tracking".equalsIgnoreCase(status);

        if (!live)
        {
            String token = TrackingStatus.compactHudPlus(status, false);
            if ("AFK".equals(token))
            {
                return Kind.AFK;
            }
            if ("PAUSED".equals(token) || "REC".equals(token))
            {
                return Kind.PAUSED;
            }
            return Kind.MUTED_STOP;
        }

        if (snapshot.isBankingUiOpen())
        {
            return Kind.BANKING;
        }
        // Busy before Idle — Pending Rewards / Ground Loot lock must keep pulsing.
        if (snapshot.isLootTrayBusy() || activityHold)
        {
            return Kind.BUSY;
        }
        String body = HudPlusHeaderLabel.resolveBody(snapshot);
        if ("Waiting".equalsIgnoreCase(body))
        {
            return Kind.WAITING;
        }
        if (snapshot.isCharacterIdle())
        {
            return Kind.IDLE;
        }

        if (body == null || body.isEmpty()
            || "Tracking".equalsIgnoreCase(body))
        {
            return Kind.GENERIC_LIVE;
        }
        return Kind.ACTIVE;
    }

    /**
     * Solid or pulsed gem colour from HUD+ palette knobs.
     *
     * @param coral   typically {@code hudPlusLossColor}
     * @param muted   typically label grey
     * @param neutral typically {@code hudPlusNeutralColor}
     * @param accent  typically {@code hudPlusAccentColor}
     * @param profit  unused for gems today (kept for callers); Banking is muted grey
     */
    public static Color color(
        Kind kind,
        Color accent,
        Color coral,
        Color muted,
        Color neutral,
        Color profit,
        boolean reducedMotion,
        long nowEpochMillis)
    {
        Color safeAccent = accent == null ? TripBeaconPainter.TEAL : accent;
        Color safeCoral = coral == null ? TripBeaconPainter.CORAL : coral;
        Color safeMuted = muted == null ? TripBeaconPainter.LABEL : muted;
        Color safeNeutral = neutral == null ? TripBeaconPainter.VALUE : neutral;
        Color live = liveAccent(safeAccent, safeMuted);

        switch (kind)
        {
            case AFK:
                return withAlpha(safeCoral, pulse(reducedMotion, nowEpochMillis));
            case PAUSED:
                return opaque(safeCoral);
            case MUTED_STOP:
                return dim(safeMuted);
            case BANKING:
            case IDLE:
                return opaque(safeMuted);
            case WAITING:
                return opaque(safeNeutral);
            case BUSY:
                return withAlpha(live, pulse(reducedMotion, nowEpochMillis));
            case GENERIC_LIVE:
            case ACTIVE:
                return opaque(live);
            default:
                return opaque(live);
        }
    }

    /** @deprecated use {@link #color(Kind, Color, Color, Color, Color, Color, boolean, long)} */
    @Deprecated
    public static Color color(
        Kind kind,
        Color accent,
        Color coral,
        Color muted,
        Color neutral,
        boolean reducedMotion,
        long nowEpochMillis)
    {
        return color(kind, accent, coral, muted, neutral, TripBeaconPainter.MINT,
            reducedMotion, nowEpochMillis);
    }

    static Color liveAccent(Color accent, Color muted)
    {
        if (sameRgb(accent, muted))
        {
            return LIVE_TEAL;
        }
        return accent;
    }

    private static boolean sameRgb(Color a, Color b)
    {
        return a != null && b != null
            && a.getRed() == b.getRed()
            && a.getGreen() == b.getGreen()
            && a.getBlue() == b.getBlue();
    }

    private static Color dim(Color color)
    {
        return new Color(
            Math.max(0, color.getRed() * 2 / 3),
            Math.max(0, color.getGreen() * 2 / 3),
            Math.max(0, color.getBlue() * 2 / 3),
            255);
    }

    private static Color opaque(Color color)
    {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), 255);
    }

    private static float pulse(boolean reducedMotion, long nowEpochMillis)
    {
        if (reducedMotion)
        {
            return 1f;
        }
        return 0.40f + 0.60f * (float) Math.abs(Math.sin(nowEpochMillis / 420.0));
    }

    private static Color withAlpha(Color color, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(255 * alpha)));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
    }
}
