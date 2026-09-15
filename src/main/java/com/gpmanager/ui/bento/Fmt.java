package com.gpmanager.ui.bento;

import java.text.NumberFormat;
import java.util.Locale;

/** Number, time and label formatting shared by the Bento kit (compact by default, exact on demand). */
public final class Fmt
{
    private static final NumberFormat EXACT = NumberFormat.getIntegerInstance(Locale.UK);

    private Fmt()
    {
    }

    /** {@code +1.2k}, {@code −36.0k}, {@code 0}. Uses a true minus sign. */
    public static String signed(long value)
    {
        if (value == 0L)
        {
            return "0";
        }
        return (value > 0L ? "+" : "−") + compact(value);
    }

    /**
     * Unsigned compact with fixed precision so columns line up:
     * {@code 812} · {@code 1.2k} · {@code 36.0k} · {@code 248k} · {@code 1.76M} · {@code 12.4M} · {@code 212M} · {@code 1.02B}.
     */
    public static String compact(long value)
    {
        long abs = value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
        if (abs < 1_000L)
        {
            return Long.toString(abs);
        }
        if (abs < 100_000L)
        {
            return String.format(Locale.ROOT, "%.1fk", abs / 1_000d);
        }
        if (abs < 1_000_000L)
        {
            return Math.round(abs / 1_000d) + "k";
        }
        if (abs < 10_000_000L)
        {
            return String.format(Locale.ROOT, "%.2fM", abs / 1_000_000d);
        }
        if (abs < 100_000_000L)
        {
            return String.format(Locale.ROOT, "%.1fM", abs / 1_000_000d);
        }
        if (abs < 1_000_000_000L)
        {
            return Math.round(abs / 1_000_000d) + "M";
        }
        if (abs < 10_000_000_000L)
        {
            return String.format(Locale.ROOT, "%.2fB", abs / 1_000_000_000d);
        }
        return String.format(Locale.ROOT, "%.1fB", abs / 1_000_000_000d);
    }

    /** {@code +124,318} with grouping and a true minus sign. */
    public static String exactSigned(long value)
    {
        if (value == 0L)
        {
            return "0";
        }
        return (value > 0L ? "+" : "−") + EXACT.format(Math.abs(value));
    }

    public static String exact(long value)
    {
        return EXACT.format(value);
    }

    /** Rate without unit: {@code 248k}. Callers add {@code /h}. */
    /** Rates read like figures: {@code 248k}, or {@code −13.0k} when the hour is losing. */
    public static String rate(long perHour)
    {
        return perHour < 0L ? "\u2212" + compact(perHour) : compact(perHour);
    }

    /** {@code h:mm:ss} for the session clock; {@code m:ss} under an hour. */
    public static String clock(long millis)
    {
        long total = Math.max(0L, millis) / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        return h > 0L
            ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
            : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    /** Short duration for labels: {@code 8m 04s}, {@code 1h 12m}, {@code 2d 3h}. */
    public static String duration(long millis)
    {
        long total = Math.max(0L, millis) / 1000L;
        long d = total / 86_400L;
        long h = (total % 86_400L) / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        if (d > 0L)
        {
            return d + "d " + h + "h";
        }
        if (h > 0L)
        {
            return h + "h " + String.format(Locale.ROOT, "%02d", m) + "m";
        }
        return m + "m " + String.format(Locale.ROOT, "%02d", s) + "s";
    }

    /** {@code Today}, {@code Yesterday}, {@code Mon 8 Sep} or {@code 8 Sep 2025} for a local date. */
    public static String dayLabel(java.time.LocalDate day, long now)
    {
        java.time.LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        long ago = java.time.temporal.ChronoUnit.DAYS.between(day, today);
        if (ago == 0L)
        {
            return "Today";
        }
        if (ago == 1L)
        {
            return "Yesterday";
        }
        String pattern = day.getYear() == today.getYear() ? "EEE d MMM" : "d MMM yyyy";
        return day.format(java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.ROOT));
    }

    /** Duration without seconds for tight cells: {@code 30m}, {@code 7h 40m}, {@code 2d 3h}. */
    public static String durationCompact(long millis)
    {
        long total = Math.max(0L, millis) / 1000L;
        long d = total / 86_400L;
        long h = (total % 86_400L) / 3600L;
        long m = (total % 3600L) / 60L;
        if (d > 0L)
        {
            return d + "d " + h + "h";
        }
        if (h > 0L)
        {
            return h + "h " + String.format(Locale.ROOT, "%02d", m) + "m";
        }
        return m + "m";
    }

    /** {@code ×27} quantity marker. */
    public static String times(long quantity)
    {
        return "×" + EXACT.format(Math.abs(quantity));
    }

    /** {@code 62%} clamped. */
    public static String percent(double fraction)
    {
        int pct = (int) Math.round(Math.max(0d, Math.min(1d, fraction)) * 100d);
        return pct + "%";
    }

    /** Day and clock for a session start: {@code Today 20:45}, {@code Yesterday 09:12}, {@code Mon 8 Sep 20:45}. */
    public static String when(long epochMillis, long now)
    {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate day = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate();
        java.time.LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        String clock = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
        long ago = java.time.temporal.ChronoUnit.DAYS.between(day, today);
        if (ago == 0L)
        {
            return "Today " + clock;
        }
        if (ago == 1L)
        {
            return "Yesterday " + clock;
        }
        String pattern = day.getYear() == today.getYear() ? "EEE d MMM" : "d MMM yyyy";
        return day.format(java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.ROOT)) + " " + clock;
    }

    /** Relative age: {@code 3m ago}, {@code 2h ago}, {@code just now}. */
    public static String age(long ageMillis)
    {
        long s = Math.max(0L, ageMillis) / 1000L;
        if (s < 45L)
        {
            return "just now";
        }
        if (s < 3600L)
        {
            return (s / 60L) + "m ago";
        }
        if (s < 86_400L)
        {
            return (s / 3600L) + "h ago";
        }
        return (s / 86_400L) + "d ago";
    }
}
