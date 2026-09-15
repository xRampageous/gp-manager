package com.gpmanager;

import com.gpmanager.diagnostics.RateAvailability;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.ui.HudPlusHeaderLabel;
import com.gpmanager.ui.ProfitTargetPresentation;
import com.gpmanager.ui.TrackingDisplayModel;
import com.gpmanager.ui.TrackingDisplaySnapshot;
import com.gpmanager.util.QuantityFormatter;
import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;
import net.runelite.api.MenuAction;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

/**
 * Compact RuneLite infobox — small GP/hr (or Total) chip; hover for activity,
 * status, GP/hr, Total, and target. Floating drops carry live change feedback.
 * Lesser cousin of HUD+: same metric language, no tray.
 */
public class GpManagerTrackingInfoBox extends InfoBox
{
    private final GpManagerEngine engine;
    private final GpManagerConfig config;
    private final InfoBoxManager infoBoxManager;
    private final TrackingDisplayModel displayModel;
    private final BufferedImage coinImage;
    private boolean registered;

    public GpManagerTrackingInfoBox(
        GpManagerEngine engine,
        GpManagerConfig config,
        InfoBoxManager infoBoxManager,
        @Nullable ItemManager itemManager,
        com.gpmanager.ui.LatestDropModel latestDropModel,
        TrackingDisplayModel displayModel,
        Plugin plugin)
    {
        super(coinImage(itemManager), plugin);
        this.engine = engine;
        this.config = config;
        this.infoBoxManager = infoBoxManager;
        // retained for call-site compatibility; Infobox stays on the coin chip
        @SuppressWarnings("unused")
        com.gpmanager.ui.LatestDropModel ignoredDropModel = latestDropModel;
        this.displayModel = displayModel;
        this.coinImage = coinImage(itemManager);
        setImage(coinImage);
        setTooltip("GP Manager");
        refreshMenuEntries();
    }

    /** Keeps Open panel + Pause/Resume in sync with the active session's pause state. */
    private void refreshMenuEntries()
    {
        ProfitSession active = engine.getActiveSession();
        String pauseLabel = active != null && active.isPaused()
            ? GpManagerOverlay.MENU_RESUME_TRACKING
            : GpManagerOverlay.MENU_PAUSE_TRACKING;
        java.util.List<OverlayMenuEntry> entries = getMenuEntries();
        entries.clear();
        entries.add(new OverlayMenuEntry(MenuAction.RUNELITE_INFOBOX, GpManagerOverlay.MENU_OPEN_PANEL, "GP Manager"));
        entries.add(new OverlayMenuEntry(MenuAction.RUNELITE_INFOBOX, pauseLabel, "GP Manager"));
    }

    private static BufferedImage coinImage(@Nullable ItemManager itemManager)
    {
        if (itemManager != null)
        {
            try
            {
                BufferedImage image = itemManager.getImage(995, 1, false);
                if (image != null)
                {
                    return image;
                }
            }
            catch (RuntimeException ignored)
            {
                // Fall through to placeholder.
            }
        }
        BufferedImage placeholder = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 4; y < 12; y++)
        {
            for (int x = 4; x < 12; x++)
            {
                placeholder.setRGB(x, y, 0xFFE2B84B);
            }
        }
        return placeholder;
    }

    public void sync()
    {
        boolean want = config.trackingDisplay() == TrackingDisplay.INFOBOX
            && engine.getActiveSession() != null;
        if (want && !registered)
        {
            infoBoxManager.addInfoBox(this);
            registered = true;
        }
        else if (!want && registered)
        {
            infoBoxManager.removeInfoBox(this);
            registered = false;
        }
        setImage(coinImage);
        refreshMenuEntries();
    }

    public void unregister()
    {
        if (registered)
        {
            infoBoxManager.removeInfoBox(this);
            registered = false;
        }
    }

    @Override
    public String getText()
    {
        TrackingDisplaySnapshot snapshot = displayModel.snapshot(System.currentTimeMillis());
        // GP/hr-style: prefer hourly rate when established; otherwise show net.
        long amount = snapshot.isRateAvailable() ? snapshot.getProfitPerHour() : snapshot.getNet();
        String compact = QuantityFormatter.compactGp(Math.abs(amount));
        return (amount > 0L ? "+" : amount < 0L ? "-" : "") + compact;
    }

    @Override
    public Color getTextColor()
    {
        TrackingDisplaySnapshot snapshot = displayModel.snapshot(System.currentTimeMillis());
        long amount = snapshot.isRateAvailable() ? snapshot.getProfitPerHour() : snapshot.getNet();
        if (amount > 0L)
        {
            return new Color(0x70, 0xC4, 0x70);
        }
        if (amount < 0L)
        {
            return new Color(0xE0, 0x70, 0x70);
        }
        return Color.WHITE;
    }

    @Override
    public String getTooltip()
    {
        ProfitSession active = engine.getActiveSession();
        if (active == null)
        {
            return "GP Manager — no active tracker";
        }
        TrackingDisplaySnapshot snapshot = displayModel.snapshot(System.currentTimeMillis());
        // RuneLite TooltipComponent supports <br> only — not <html>/<b>/<i>.
        StringBuilder tip = new StringBuilder();
        if (snapshot.isCustomSessionActive())
        {
            String name = snapshot.getSessionName();
            tip.append("Current · ").append(safe(name.isEmpty() ? "custom" : name)).append("<br>");
            tip.append(safe(HudPlusHeaderLabel.resolveFull(snapshot, false))).append("<br>");
        }
        else
        {
            tip.append("Overall").append("<br>");
            tip.append(safe(snapshot.getDisplayActivity())).append("<br>");
        }
        tip.append(safe(snapshot.getStatusLabel())).append(" · ")
            .append(QuantityFormatter.duration(snapshot.getElapsedMillis())).append("<br>");
        tip.append("GP/hr: ").append(snapshot.isRateAvailable()
            ? QuantityFormatter.gp(snapshot.getProfitPerHour()) + "/h"
            : RateAvailability.unavailableLabel()).append("<br>");
        tip.append("Total: ").append(QuantityFormatter.gp(snapshot.getNet()));
        if (snapshot.isOverallPeekPresent())
        {
            tip.append("<br>Overall Total: ").append(QuantityFormatter.gp(snapshot.getOverallNet()));
            tip.append(" · ").append(snapshot.isOverallRateAvailable()
                ? QuantityFormatter.gp(snapshot.getOverallProfitPerHour()) + "/h"
                : RateAvailability.unavailableLabel());
            tip.append(" (paused)");
        }
        ProfitTargetPresentation target = snapshot.getProfitTarget();
        if (target != null && target.isPresent())
        {
            tip.append("<br>").append(safe(target.tooltipLine()));
        }
        return tip.toString();
    }

    private static String safe(String text)
    {
        if (text == null)
        {
            return "";
        }
        return text.replace("<", "").replace(">", "");
    }
}
