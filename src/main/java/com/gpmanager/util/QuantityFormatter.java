package com.gpmanager.util;

import java.text.DecimalFormat;
import java.time.Duration;

public final class QuantityFormatter
{
    private static final DecimalFormat INTEGER = new DecimalFormat("#,##0");

    private QuantityFormatter()
    {
    }

    public static String gp(long value)
    {
        return INTEGER.format(value) + " gp";
    }

    public static String compactGp(long value)
    {
        long absolute = value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
        // Promote at the one-decimal rounding boundary so compact output never
        // reads "1000m" or "1000k".
        if (absolute >= 999_950_000L)
        {
            return formatCompact(value / 1_000_000_000.0d, "b");
        }
        if (absolute >= 999_950L)
        {
            return formatCompact(value / 1_000_000.0d, "m");
        }
        if (absolute >= 1_000L)
        {
            return formatCompact(value / 1_000.0d, "k");
        }
        return Long.toString(value);
    }


    public static String compactNumber(long value)
    {
        return compactGp(value);
    }

    private static String formatCompact(double value, String suffix)
    {
        return new DecimalFormat("0.#").format(value) + suffix;
    }

    public static String duration(long millis)
    {
        Duration duration = Duration.ofMillis(Math.max(0L, millis));
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        long seconds = duration.minusHours(hours).minusMinutes(minutes).getSeconds();
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * Compact HUD+ elapsed label: {@code 4h10m}, {@code 10m05s}, {@code 45s}.
     * No leading zeros on hours; omit zero higher units.
     */
    public static String compactDurationHud(long millis)
    {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long rem = seconds % 60L;
        if (hours > 0L)
        {
            return hours + "h" + String.format("%02dm", minutes);
        }
        if (minutes > 0L)
        {
            return minutes + "m" + String.format("%02ds", rem);
        }
        return rem + "s";
    }
}
