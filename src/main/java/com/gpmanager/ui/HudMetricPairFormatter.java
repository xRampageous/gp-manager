package com.gpmanager.ui;

import com.gpmanager.diagnostics.RateAvailability;
import com.gpmanager.util.QuantityFormatter;
import java.awt.FontMetrics;

/**
 * Formats the HUD rate (left) / net (right) pair with a measured mid-gap.
 * Abbreviates rate first under pressure; stacks when a single row would become
 * ambiguous or when net alone exceeds the budget. Never hides net or clips signs.
 */
public final class HudMetricPairFormatter
{
    public static final int GAP_PX = 8;
    /** Minimum meaningful rate head length before preferring stack over ellipsis. */
    private static final int MIN_RATE_HEAD_CHARS = 2;

    private HudMetricPairFormatter()
    {
    }

    public static final class Pair
    {
        public final String rateText;
        public final String netText;
        /** True when rate and net should be painted on separate lines. */
        public final boolean stacked;

        public Pair(String rateText, String netText)
        {
            this(rateText, netText, false);
        }

        public Pair(String rateText, String netText, boolean stacked)
        {
            this.rateText = rateText == null ? "" : rateText;
            this.netText = netText == null ? "" : netText;
            this.stacked = stacked;
        }
    }

    /**
     * @param preferExact when true, start with full {@link QuantityFormatter#gp} strings;
     *                    under width pressure falls back to compact (rate first, then net)
     */
    public static Pair formatValues(
        FontMetrics metrics,
        int contentW,
        long net,
        long profitPerHour,
        boolean rateAvailable,
        boolean preferExact)
    {
        int width = Math.max(0, contentW);
        boolean exactNet = preferExact;
        boolean exactRate = preferExact;

        String netText = signedNet(net, exactNet);
        String rateText = rateAvailable
            ? rateString(profitPerHour, exactRate, true)
            : RateAvailability.unavailableLabel();

        if (fits(metrics, rateText, netText, width))
        {
            return new Pair(rateText, netText, false);
        }

        if (rateAvailable && exactRate)
        {
            exactRate = false;
            rateText = rateString(profitPerHour, false, true);
            if (fits(metrics, rateText, netText, width))
            {
                return new Pair(rateText, netText, false);
            }
        }

        if (rateAvailable)
        {
            rateText = rateString(profitPerHour, false, false);
            if (fits(metrics, rateText, netText, width))
            {
                return new Pair(rateText, netText, false);
            }
        }

        if (exactNet)
        {
            netText = signedNet(net, false);
            if (fits(metrics, rateText, netText, width))
            {
                return new Pair(rateText, netText, false);
            }
            if (rateAvailable)
            {
                rateText = rateString(profitPerHour, false, false);
                if (fits(metrics, rateText, netText, width))
                {
                    return new Pair(rateText, netText, false);
                }
            }
        }

        // Prefer stacking over an ambiguous truncated rate or an overflowing net.
        if (metrics.stringWidth(netText) + GAP_PX > width
            || wouldAmbiguousTruncate(metrics, rateText, netText, width))
        {
            return stackedPair(metrics, width, rateText, netText);
        }

        int netW = metrics.stringWidth(netText);
        int rateBudget = Math.max(0, width - GAP_PX - netW);
        String truncated = truncateRate(rateText, metrics, rateBudget);
        if (isAmbiguousRate(truncated))
        {
            return stackedPair(metrics, width, rateText, netText);
        }
        return new Pair(truncated, netText, false);
    }

    public static Pair formatLabels(FontMetrics metrics, int contentW)
    {
        int width = Math.max(0, contentW);
        String netLabel = "NET PROFIT";
        String rateLabel = "RATE";
        if (fits(metrics, rateLabel, netLabel, width))
        {
            return new Pair(rateLabel, netLabel, false);
        }
        if (metrics.stringWidth(netLabel) + GAP_PX > width)
        {
            return new Pair(rateLabel, netLabel, true);
        }
        int netW = metrics.stringWidth(netLabel);
        int rateBudget = Math.max(0, width - GAP_PX - netW);
        rateLabel = HudDropRowComponent.truncateToWidth(rateLabel, metrics, rateBudget);
        if (rateLabel.equals("…") || rateLabel.isEmpty())
        {
            return new Pair("RATE", netLabel, true);
        }
        return new Pair(rateLabel, netLabel, false);
    }

    private static Pair stackedPair(FontMetrics metrics, int width, String rateText, String netText)
    {
        String rate = rateText;
        String net = netText;
        if (metrics.stringWidth(net) > width)
        {
            net = HudDropRowComponent.truncateToWidth(net, metrics, width);
        }
        if (metrics.stringWidth(rate) > width)
        {
            rate = truncateRate(rate, metrics, width);
        }
        return new Pair(rate, net, true);
    }

    private static boolean wouldAmbiguousTruncate(
        FontMetrics metrics, String rateText, String netText, int width)
    {
        int netW = metrics.stringWidth(netText);
        int rateBudget = Math.max(0, width - GAP_PX - netW);
        if (rateBudget <= 0)
        {
            return true;
        }
        return isAmbiguousRate(truncateRate(rateText, metrics, rateBudget));
    }

    static boolean isAmbiguousRate(String rateText)
    {
        if (rateText == null || rateText.isEmpty() || "…".equals(rateText))
        {
            return true;
        }
        if (RateAvailability.unavailableLabel().equals(rateText))
        {
            return false;
        }
        String head = rateText.endsWith("/h")
            ? rateText.substring(0, rateText.length() - 2)
            : rateText;
        // Strip ellipsis and signs for length check.
        String digits = head.replace("…", "").replace("-", "").replace("+", "").trim();
        return digits.length() < MIN_RATE_HEAD_CHARS || head.contains("…") && digits.length() <= 1;
    }

    private static boolean fits(FontMetrics metrics, String rate, String net, int contentW)
    {
        return metrics.stringWidth(rate) + GAP_PX + metrics.stringWidth(net) <= contentW;
    }

    /** True when GP/hr and Total can share one HUD+ metrics row. */
    public static boolean metricsFitSideBySide(
        FontMetrics metrics,
        String rateLine,
        String totalLine,
        int contentW)
    {
        return fits(metrics, rateLine == null ? "" : rateLine, totalLine == null ? "" : totalLine, contentW);
    }

    static String signedNet(long net, boolean exact)
    {
        if (exact)
        {
            String body = QuantityFormatter.gp(net);
            return net > 0L ? "+" + body : body;
        }
        String compact = QuantityFormatter.compactGp(net) + " gp";
        return net > 0L ? "+" + compact : compact;
    }

    /** Signed amount without a trailing {@code gp} unit — for HUD+ Total / GP/hr lines. */
    public static String signedAmount(long amount, boolean exact)
    {
        if (exact)
        {
            String formatted = QuantityFormatter.gp(amount);
            String body = formatted.endsWith(" gp")
                ? formatted.substring(0, formatted.length() - 3)
                : formatted;
            if (amount > 0L && !body.startsWith("+"))
            {
                return "+" + body;
            }
            return body;
        }
        String compact = QuantityFormatter.compactGp(amount);
        return amount > 0L ? "+" + compact : compact;
    }

    public static String formatRateLine(
        long profitPerHour,
        boolean rateAvailable,
        boolean exact,
        boolean withPrefix)
    {
        String value;
        if (!rateAvailable)
        {
            value = RateAvailability.unavailableLabel();
        }
        else
        {
            value = signedAmount(profitPerHour, exact);
        }
        return withPrefix ? "GP/hr: " + value : value;
    }

    public static String formatTotalLine(
        long net,
        @javax.annotation.Nullable ProfitTargetPresentation target,
        boolean exact,
        boolean withPrefix)
    {
        if (target != null && target.isPresent())
        {
            return target.totalLine(exact, withPrefix);
        }
        String amount = signedAmount(net, exact);
        return withPrefix ? "Total: " + amount : amount;
    }

    static String rateString(long profitPerHour, boolean exact, boolean includeGp)
    {
        if (exact)
        {
            return QuantityFormatter.gp(profitPerHour) + "/h";
        }
        if (includeGp)
        {
            return QuantityFormatter.compactGp(profitPerHour) + " gp/h";
        }
        return QuantityFormatter.compactGp(profitPerHour) + "/h";
    }

    /** Prefer keeping a trailing {@code /h} so truncated rates stay recognizable. */
    static String truncateRate(String rateText, FontMetrics metrics, int maxWidth)
    {
        if (rateText == null || rateText.isEmpty() || metrics.stringWidth(rateText) <= maxWidth)
        {
            return rateText == null ? "" : rateText;
        }
        if (!rateText.endsWith("/h"))
        {
            return HudDropRowComponent.truncateToWidth(rateText, metrics, maxWidth);
        }
        String suffix = "/h";
        int suffixWidth = metrics.stringWidth(suffix);
        int headBudget = maxWidth - suffixWidth;
        if (headBudget <= 0)
        {
            return HudDropRowComponent.truncateToWidth(rateText, metrics, maxWidth);
        }
        String head = rateText.substring(0, rateText.length() - suffix.length());
        return HudDropRowComponent.truncateToWidth(head, metrics, headBudget) + suffix;
    }
}
