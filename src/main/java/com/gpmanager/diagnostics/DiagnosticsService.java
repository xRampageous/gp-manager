package com.gpmanager.diagnostics;

import com.gpmanager.ChangeFeedbackPolicy;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.TrackingDisplay;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.PauseReason;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.TrackingInsightsSnapshot;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.party.PartyProfitTracker;
import com.gpmanager.persistence.PersistenceCoordinator;
import com.gpmanager.persistence.SaveStatus;
import com.gpmanager.persistence.SavedState;
import com.gpmanager.persistence.SessionRepository;
import com.gpmanager.persistence.TrackingIdentity;
import com.gpmanager.ui.LatestDropModel;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.JComponent;

/**
 * Builds a safe, copyable diagnostics report. Omits credentials, passphrases,
 * account identifiers, member names, and sensitive local paths by default.
 */
@Singleton
public class DiagnosticsService
{
    private final GpManagerEngine engine;
    private final SessionRepository repository;
    @Nullable
    private final PersistenceCoordinator persistence;
    private final PartyProfitTracker partyProfitTracker;
    private final DebugTrace debugTrace;
    private final GpManagerConfig config;
    private final LatestDropModel latestDropModel;

    @Inject
    public DiagnosticsService(
        GpManagerEngine engine,
        SessionRepository repository,
        PartyProfitTracker partyProfitTracker,
        DebugTrace debugTrace,
        @Nullable PersistenceCoordinator persistence,
        GpManagerConfig config,
        LatestDropModel latestDropModel)
    {
        this.engine = engine;
        this.repository = repository;
        this.partyProfitTracker = partyProfitTracker;
        this.debugTrace = debugTrace;
        this.persistence = persistence;
        this.config = config;
        this.latestDropModel = latestDropModel;
    }

    public String buildReport(@Nullable JComponent panel)
    {
        StringBuilder out = new StringBuilder(2_048);
        BuildFingerprint build = BuildFingerprint.get();
        out.append("GP Manager diagnostics\n");
        out.append("======================\n");
        out.append("Build fingerprint: ").append(build.getFingerprint()).append('\n');
        out.append("Version: ").append(build.getVersion()).append('\n');
        out.append("Resolved RuneLite dependency: ").append(build.getRuneLiteDependency()).append('\n');
        out.append("Built at (UTC resource): ").append(build.getBuiltAt()).append('\n');
        out.append("Schema: ").append(SavedState.CURRENT_SCHEMA_VERSION).append('\n');
        out.append("Java: ").append(System.getProperty("java.version", "?"))
            .append(" (").append(System.getProperty("java.vendor", "?")).append(")\n");
        out.append("OS: ").append(System.getProperty("os.name", "?"))
            .append(" ").append(System.getProperty("os.version", "?")).append('\n');

        appendUi(out, panel);
        appendTracker(out);
        appendSave(out);
        appendParty(out);
        appendTrace(out);
        out.append('\n');
        out.append("Notes\n");
        out.append("-----\n");
        out.append("- Ordinary clean restart/resume is not file corruption.\n");
        out.append("- Damaged-file recovery is reported only when a corrupt primary was preserved.\n");
        out.append("- Account identifiers, Party passphrases, member names, and local paths are omitted.\n");
        return out.toString();
    }

    private void appendUi(StringBuilder out, @Nullable JComponent panel)
    {
        out.append('\n').append("UI\n").append("--\n");
        if (panel == null)
        {
            out.append("Panel size: unavailable\n");
            out.append("Usable content width: unavailable\n");
            return;
        }
        Dimension size = panel.getSize();
        out.append("Panel size: ").append(size.width).append('x').append(size.height).append('\n');
        out.append("Usable content width: ")
            .append(com.gpmanager.ui.bento.BentoTheme.CONTENT_WIDTH)
            .append(" (sidebar ").append(com.gpmanager.ui.bento.BentoTheme.OWNED_WIDTH)
            .append(" - scrollbar ").append(com.gpmanager.ui.bento.BentoTheme.SCROLLBAR_WIDTH).append(")\n");
        out.append("Density: ").append(com.gpmanager.ui.bento.BentoTheme.density())
            .append(" / accent ").append(com.gpmanager.ui.bento.BentoTheme.accent()).append('\n');
        Font font = panel.getFont();
        out.append("Font family: ").append(font == null ? "?" : font.getFamily()).append('\n');
        out.append("Font size: ").append(font == null ? "?" : font.getSize2D()).append('\n');
        out.append("UI scale hint: ").append(System.getProperty("sun.java2d.uiScale", "default")).append('\n');
        TrackingDisplay display = config == null ? TrackingDisplay.HUD_PLUS : config.trackingDisplay();
        out.append("Tracking display: ").append(display).append('\n');
        out.append("HUD+ tray: ").append(ChangeFeedbackPolicy.showHudPlusTray(config)).append('\n');
        out.append("Floating drops: ").append(ChangeFeedbackPolicy.showFloatingDrops(config)).append('\n');
        if (display == TrackingDisplay.HUD_PLUS && config != null)
        {
            out.append("HUD+ floating drops toggle: ").append(config.hudPlusFloatingDrops()).append('\n');
        }
        if (latestDropModel != null)
        {
            out.append("Latest-drop replacements: ").append(latestDropModel.getReplacementCount()).append('\n');
            out.append("Latest-drop arrival animations: ").append(latestDropModel.getAnimationStartCount()).append('\n');
            out.append("Latest-drop present: ").append(latestDropModel.highlight() != null).append('\n');
        }
        if (persistence != null)
        {
            out.append("Tracking identity ready: ").append(persistence.isTrackingReady()).append('\n');
            String block = persistence.identityBlockReason();
            if (block != null)
            {
                out.append("Identity hold: ").append(block).append('\n');
            }
            out.append("Scope generation: ").append(repository.getScopeGeneration()).append('\n');
        }
    }

    private void appendTracker(StringBuilder out)
    {
        out.append('\n').append("Tracker\n").append("-------\n");
        TrackingIdentity identity = persistence == null ? null : persistence.getActiveIdentity();
        out.append("Identity bound: ").append(identity != null).append('\n');
        out.append("Account-aware store: ").append(repository.isAccountAware()).append('\n');
        out.append("Unassigned legacy present: ").append(repository.hasUnassignedLegacyData()).append('\n');

        ProfitSession active = engine.getActiveSession();
        ProfitSession general = engine.getGeneralSession();
        out.append("Active owner: ").append(active == null ? "none"
            : (engine.isCustomSessionActive() ? "custom" : "general")).append('\n');
        out.append("Custom active: ").append(engine.isCustomSessionActive()).append('\n');
        if (active != null)
        {
            SessionMetrics metrics = engine.getMetrics(System.currentTimeMillis());
            out.append("Paused: ").append(active.isPaused()).append('\n');
            out.append("Stopped: ").append(active.isStopped()).append('\n');
            PauseReason reason = active.getPauseReason();
            out.append("Pause reason: ").append(reason == null ? "NONE" : reason.name()).append('\n');
            out.append("Recovered-from-crash marker: ").append(active.isRecoveredFromCrash()).append('\n');
            out.append("Baseline ready: ").append(engine.isBaselineReady()).append('\n');
            out.append("Baseline priming: ").append(engine.isBaselinePriming()).append('\n');
            out.append("Pending correlation: ").append(engine.hasPendingCorrelation()).append('\n');
            out.append("Pending loot expectations: ").append(engine.getPendingLootExpectationCount()).append('\n');
            out.append("Retained transactions: ").append(active.getTransactions().size()).append('\n');
            out.append("Compacted transactions: ").append(active.getCompactedTransactionCount()).append('\n');
            int unpriced = 0;
            int review = 0;
            for (ProfitTransaction tx : active.getTransactions())
            {
                if (tx == null)
                {
                    continue;
                }
                if (hasUnpricedFlow(tx))
                {
                    unpriced++;
                }
                if (needsReview(tx))
                {
                    review++;
                }
            }
            out.append("Unpriced retained: ").append(unpriced).append('\n');
            out.append("Review retained: ").append(review).append('\n');
            out.append("Active millis: ").append(metrics.getElapsedMillis()).append('\n');
            out.append("Rate established (>=60s): ").append(RateAvailability.isEstablished(metrics.getElapsedMillis())).append('\n');
        }
        if (general != null)
        {
            TrackingInsightsSnapshot insights = engine.getTrackingInsights(30, System.currentTimeMillis());
            out.append("Insights recorded days (30d window): ")
                .append(insights == null ? 0 : insights.getDayCount()).append('\n');
            out.append("Insights available since epoch ms: ")
                .append(insights == null ? 0L : insights.getAvailableSinceEpochMillis()).append('\n');
            out.append("Analytics started at: ").append(general.getAnalyticsStartedAtEpochMillis()).append('\n');
        }
        out.append("History sessions: ").append(engine.getHistory().size()).append('\n');
    }

    private void appendSave(StringBuilder out)
    {
        out.append('\n').append("Save\n").append("----\n");
        SaveStatus status = persistence == null ? SaveStatus.neverSaved() : persistence.getSaveStatus();
        out.append("State: ").append(status.getState()).append('\n');
        out.append("Revision: ").append(status.getRevision()).append('\n');
        out.append("Last success epoch ms: ").append(status.getLastSuccessEpochMillis()).append('\n');
        out.append("Last duration ms: ").append(status.getLastDurationMillis()).append('\n');
        out.append("Detail: ").append(status.getDetail().isEmpty() ? "(none)" : status.getDetail()).append('\n');
        out.append("Retry available: ").append(status.isRetryAvailable()).append('\n');
        out.append("Disk revision: ").append(repository.getLastKnownDiskRevision()).append('\n');
    }

    private void appendParty(StringBuilder out)
    {
        out.append('\n').append("Party\n").append("-----\n");
        if (partyProfitTracker == null)
        {
            out.append("Unavailable\n");
            return;
        }
        try
        {
            var summary = partyProfitTracker.getSummary(true);
            out.append("Members: ").append(summary.getMembers().size()).append('\n');
            out.append("Reporting: ").append(summary.getReportingCount()).append('\n');
            out.append("Partial combined rate: ").append(summary.hasPartialRate()).append('\n');
        }
        catch (RuntimeException ex)
        {
            out.append("Party summary unavailable in this environment\n");
        }
        out.append("Member display names omitted\n");
    }

    private void appendTrace(StringBuilder out)
    {
        out.append('\n').append("Debug trace\n").append("-----------\n");
        out.append("Enabled: ").append(debugTrace.isEnabled()).append('\n');
        List<String> events = debugTrace.snapshot();
        out.append("Retained events: ").append(events.size()).append('/').append(DebugTrace.CAPACITY).append('\n');
        int start = Math.max(0, events.size() - 40);
        for (int i = start; i < events.size(); i++)
        {
            out.append(events.get(i)).append('\n');
        }
    }

    private static boolean hasUnpricedFlow(ProfitTransaction transaction)
    {
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow != null
                && (flow.getPriceSource() == ItemPriceSource.UNKNOWN
                    || flow.getPriceSource() == ItemPriceSource.UNPRICED))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean needsReview(ProfitTransaction transaction)
    {
        if (transaction.getCorrection() == TransactionCorrection.AUTO
            && transaction.getConfidence() == com.gpmanager.model.ClassificationConfidence.UNCERTAIN)
        {
            return true;
        }
        return hasUnpricedFlow(transaction);
    }
}
