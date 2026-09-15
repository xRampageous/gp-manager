package com.gpmanager.ui;

import com.gpmanager.util.QuantityFormatter;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * Immutable shared profit-target view model for sidebar, HUD+, legacy HUD, and
 * Infobox. Surfaces share calculations; each renderer owns layout only.
 */
public final class ProfitTargetPresentation
{
    private final boolean present;
    private final long targetGp;
    private final long netGp;
    private final long remainingGp;
    private final long surplusGp;
    private final double rawPercent;
    /** Fractional fill in {@code [0, 1]} — never round an unreached target to full. */
    private final float fillRatio;
    private final boolean reached;
    private final boolean crossedThisUpdate;

    private ProfitTargetPresentation(
        boolean present,
        long targetGp,
        long netGp,
        long remainingGp,
        long surplusGp,
        double rawPercent,
        float fillRatio,
        boolean reached,
        boolean crossedThisUpdate)
    {
        this.present = present;
        this.targetGp = targetGp;
        this.netGp = netGp;
        this.remainingGp = remainingGp;
        this.surplusGp = surplusGp;
        this.rawPercent = rawPercent;
        this.fillRatio = fillRatio;
        this.reached = reached;
        this.crossedThisUpdate = crossedThisUpdate;
    }

    public static ProfitTargetPresentation unset()
    {
        return new ProfitTargetPresentation(false, 0L, 0L, 0L, 0L, 0d, 0f, false, false);
    }

    /**
     * @param previousReached prior reached state for the same target identity;
     *                        used so restore/session switch does not re-fire
     *                        celebration when already at/above target
     */
    public static ProfitTargetPresentation of(@Nullable Long targetGp, long netGp, boolean previousReached)
    {
        if (targetGp == null || targetGp <= 0L)
        {
            return unset();
        }
        long target = targetGp;
        long remaining;
        try
        {
            remaining = Math.subtractExact(target, netGp);
        }
        catch (ArithmeticException ex)
        {
            remaining = netGp < target ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        long surplus = remaining < 0L
            ? (remaining == Long.MIN_VALUE ? Long.MAX_VALUE : -remaining)
            : 0L;
        double raw = target == 0L ? 0d : (netGp * 100.0d) / target;
        if (Double.isNaN(raw) || Double.isInfinite(raw))
        {
            raw = netGp >= target ? 100d : 0d;
        }
        boolean reached = netGp >= target;
        float fill = clampFillRatio(raw, reached);
        boolean crossed = reached && !previousReached;
        return new ProfitTargetPresentation(
            true, target, netGp, Math.max(0L, remaining), surplus, raw, fill, reached, crossed);
    }

    private static float clampFillRatio(double rawPercent, boolean reached)
    {
        if (rawPercent <= 0d)
        {
            return 0f;
        }
        if (reached || rawPercent >= 100d)
        {
            return 1f;
        }
        // Keep unreached fills strictly below 1 so 99.999% never paints as complete.
        return (float) Math.min(0.999d, Math.max(0d, rawPercent / 100.0d));
    }

    public boolean isPresent()
    {
        return present;
    }

    public long getTargetGp()
    {
        return targetGp;
    }

    public long getNetGp()
    {
        return netGp;
    }

    public long getRemainingGp()
    {
        return remainingGp;
    }

    public long getSurplusGp()
    {
        return surplusGp;
    }

    public double getRawPercent()
    {
        return rawPercent;
    }

    /** Visual bar fill ratio clamped to {@code [0, 1]}. */
    public float getFillRatio()
    {
        return fillRatio;
    }

    /** Integer percent for legacy callers; floors so 99.9 does not become 100. */
    public int getFillPercent()
    {
        if (!present)
        {
            return 0;
        }
        if (reached)
        {
            return 100;
        }
        return (int) Math.max(0, Math.min(99, Math.floor(fillRatio * 100.0d)));
    }

    public boolean isReached()
    {
        return reached;
    }

    /** True once when net first crosses the current target from below. */
    public boolean isCrossedThisUpdate()
    {
        return crossedThisUpdate;
    }

    /** Compact percent for HUD/Infobox; tiny positives become {@code <0.1%}. */
    public String percentLabel()
    {
        if (!present)
        {
            return "";
        }
        if (rawPercent <= 0d)
        {
            return "0%";
        }
        if (rawPercent < 0.1d)
        {
            return "<0.1%";
        }
        if (rawPercent >= 100d)
        {
            return reached && surplusGp > 0L ? "100%+" : "100%";
        }
        // Floor to one decimal so 99.999% never displays as 100.0% before reached.
        double display = Math.floor(rawPercent * 10.0d) / 10.0d;
        if (display >= 100.0d)
        {
            display = 99.9d;
        }
        return String.format(Locale.ROOT, "%.1f%%", display);
    }

    public String remainingOrSurplusLabel()
    {
        if (!present)
        {
            return "";
        }
        if (reached)
        {
            return surplusGp > 0L
                ? "+" + QuantityFormatter.compactGp(surplusGp) + " over"
                : "reached";
        }
        return QuantityFormatter.compactGp(remainingGp) + " gp left";
    }

    /** Live progress caption row: percent only (left of bar). */
    public String percentOnlyLabel()
    {
        return percentLabel();
    }

    /** Explicit current/goal caption, e.g. {@code Target 5.1k / 100k}. */
    public String captionLabel()
    {
        if (!present)
        {
            return "";
        }
        long shownNet = Math.max(0L, netGp);
        return "Target "
            + QuantityFormatter.compactGp(shownNet)
            + " / "
            + QuantityFormatter.compactGp(targetGp);
    }

    public String compactHudLine()
    {
        if (!present)
        {
            return "";
        }
        return percentLabel() + " · " + remainingOrSurplusLabel();
    }

    /**
     * HUD+ progress under the target bar — percent and remaining/surplus only
     * (no "Target …" caption). Callers centre this under the fill bar.
     */
    public String centeredProgressLine()
    {
        return compactHudLine();
    }

    /** Compact {@code /target} suffix; Exact uses full digits without k/m/b. */
    public String netOverTargetSuffix()
    {
        return netOverTargetSuffix(false);
    }

    public String netOverTargetSuffix(boolean exact)
    {
        if (!present)
        {
            return "";
        }
        if (exact)
        {
            String body = QuantityFormatter.gp(targetGp);
            if (body.endsWith(" gp"))
            {
                body = body.substring(0, body.length() - 3);
            }
            return "/" + body;
        }
        return "/" + QuantityFormatter.compactGp(targetGp);
    }

    /** {@code Total: +1.8k/200k} or value-only {@code +1.8k/200k}. */
    public String totalLine(boolean exact, boolean withPrefix)
    {
        String amount = HudMetricPairFormatter.signedAmount(netGp, exact);
        String text = present ? amount + netOverTargetSuffix(exact) : amount;
        return withPrefix ? "Total: " + text : text;
    }

    public String tooltipLine()
    {
        if (!present)
        {
            return "";
        }
        StringBuilder tip = new StringBuilder();
        tip.append(captionLabel());
        tip.append(" · ").append(percentLabel());
        if (reached)
        {
            tip.append(" · surplus ").append(QuantityFormatter.gp(surplusGp));
        }
        else
        {
            tip.append(" · remaining ").append(QuantityFormatter.gp(remainingGp));
        }
        tip.append(" (tracked net)");
        return tip.toString();
    }
}
