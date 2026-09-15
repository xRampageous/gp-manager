package com.gpmanager.ui.bento;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.SessionMode;
import com.gpmanager.ui.InteractionContextModel;
import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The Bento sidebar as a RuneLite PluginPanel. Owns the shell and pages; reads engine
 * state only inside {@link #refresh()} on the EDT, through {@link LiveSnapshot}.
 */
public final class BentoPanel extends PluginPanel
{
    private final GpManagerEngine engine;
    private final GpManagerConfig config;
    @Nullable
    private final ItemManager itemManager;
    @Nullable
    private final InteractionContextModel interactionContext;
    private final BentoShell shell = new BentoShell();
    private final LivePage live;
    private final LedgerPage ledger;
    private final ItemSheet itemSheet;
    private final SessionsPage runs;
    private final ComparePage compare;
    private final InsightsPage insights;
    private final ToolsPage tools;
    private final SearchPage search;
    private String searchReturnPage = BentoShell.SESSIONS;
    private final BoundaryLog boundaries = new BoundaryLog();
    /** Inventory quantities summed by lower-case base name, from the plugin's container events. */
    private java.util.function.Supplier<com.gpmanager.reward.RewardPresentationModel.EncounterSummary> encounterSource = () -> null;
    @Nullable
    private volatile String autoStartedSessionId;
    @Nullable
    private final com.gpmanager.persistence.CsvExporter csvExporter;
    @Nullable
    private final com.gpmanager.persistence.SessionRepository repository;
    @Nullable
    private final com.gpmanager.persistence.PersistenceCoordinator persistence;
    @Nullable
    private final com.gpmanager.diagnostics.DiagnosticsService diagnostics;
    @Nullable
    private final com.gpmanager.diagnostics.DebugTrace debugTrace;
    @Nullable
    private final net.runelite.client.game.SpriteManager spriteManager;
    @Nullable
    private final net.runelite.client.config.ConfigManager configManager;
    private final java.util.Map<Integer, BufferedImage> gameSprites = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Integer> gameSpritesRequested = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private java.util.function.Supplier<com.gpmanager.model.PartyProfitSummary> partySource = () -> null;
    private volatile PvpState pvpState = PvpState.NONE;
    @Nullable
    private java.time.LocalDate lastSeenDate;
    /** Live wealth locations from the plugin (GE offers, collection box); null until bound. */
    private java.util.function.Supplier<com.gpmanager.model.WealthLocationsSnapshot> wealthSource = () -> null;
    private final ItemRules rules;
    private String ledgerSearch = "";
    private int sheetItemId = -1;
    private String sheetItemName = "";
    /** Wealth locate observations from the plugin (coffers, raid bags, GIM); painted by Tools. */
    private final com.gpmanager.ui.WealthLocateModel wealthLocate = new com.gpmanager.ui.WealthLocateModel();
    private BooleanSupplier neutralZone = () -> false;
    private BooleanSupplier accountHold = () -> false;
    private Runnable configurationOpener = () -> { };
    private boolean active;

    public BentoPanel(
        GpManagerEngine engine,
        GpManagerConfig config,
        @Nullable ItemManager itemManager,
        @Nullable InteractionContextModel interactionContext,
        @Nullable net.runelite.client.config.ConfigManager configManager)
    {
        this(engine, config, itemManager, interactionContext, configManager, null, null, null, null, null, null);
    }

    @Inject
    public BentoPanel(
        GpManagerEngine engine,
        GpManagerConfig config,
        @Nullable ItemManager itemManager,
        @Nullable InteractionContextModel interactionContext,
        @Nullable net.runelite.client.config.ConfigManager configManager,
        @Nullable com.gpmanager.persistence.CsvExporter csvExporter,
        @Nullable com.gpmanager.persistence.SessionRepository repository,
        @Nullable com.gpmanager.persistence.PersistenceCoordinator persistence,
        @Nullable com.gpmanager.diagnostics.DiagnosticsService diagnostics,
        @Nullable com.gpmanager.diagnostics.DebugTrace debugTrace,
        @Nullable net.runelite.client.game.SpriteManager spriteManager)
    {
        super(false);
        this.engine = engine;
        this.config = config;
        this.itemManager = itemManager;
        this.interactionContext = interactionContext;
        this.configManager = configManager;
        this.csvExporter = csvExporter;
        this.repository = repository;
        this.persistence = persistence;
        this.diagnostics = diagnostics;
        this.debugTrace = debugTrace;
        this.spriteManager = spriteManager;
        this.rules = new ItemRules(config, configManager);
        applyAppearance();
        Icon.bindGameArt(this::gameSprite);
        engine.addAlertListener(this::onAlert);
        setLayout(new BorderLayout());
        setBackground(BentoTheme.BG);
        live = new LivePage(new LiveActions(), this::sprite);
        shell.addPage(live);
        ledger = new LedgerPage(new LedgerActions(), this::sprite);
        shell.addPage(ledger);
        itemSheet = new ItemSheet(new SheetActions(), this::sprite);
        shell.addPage(itemSheet);
        runs = new SessionsPage(new SessionsActions(), this::sprite);
        shell.addPage(runs);
        compare = new ComparePage(new CompareActions());
        shell.addPage(compare);
        insights = new InsightsPage(new InsightsActions(), this::sprite);
        shell.addPage(insights);
        tools = new ToolsPage(new ToolsActions(), this::sprite);
        shell.addPage(tools);
        search = new SearchPage(new SearchSource());
        shell.addPage(search);
        installBackKeys();
        add(shell, BorderLayout.CENTER);
        shell.show(BentoShell.LIVE);
    }

    /** Plugin-owned state the engine does not expose. */
    public void bindState(BooleanSupplier neutralZoneActive, BooleanSupplier identitySwitchHeld)
    {
        neutralZone = neutralZoneActive == null ? () -> false : neutralZoneActive;
        accountHold = identitySwitchHeld == null ? () -> false : identitySwitchHeld;
    }

    public void bindConfigurationOpener(@Nullable Runnable opener)
    {
        configurationOpener = opener == null ? () -> { } : opener;
    }

    /** Party figures are read by the plugin; the panel only paints them. */
    public void bindParty(java.util.function.Supplier<com.gpmanager.model.PartyProfitSummary> source)
    {
        partySource = source == null ? () -> null : source;
    }

    /** Client-thread PvP facts (Wilderness / PvP world, skull, Protect Item, risk); safe from any thread. */
    public void observePvp(boolean pvpPossible, boolean skulled, boolean highRisk, boolean protectItem,
        boolean riskKnown, long riskValue)
    {
        PvpState next = new PvpState(pvpPossible, skulled, highRisk, protectItem, riskKnown, riskValue);
        PvpState previous = pvpState;
        pvpState = next;
        if (previous.pvpPossible != next.pvpPossible || previous.skulled != next.skulled
            || previous.protectItem != next.protectItem || previous.highRisk != next.highRisk)
        {
            refresh();
        }
    }

    /** The live loot source's session totals for the Encounter row (read by the plugin from the reward model). */
    public void bindEncounter(java.util.function.Supplier<com.gpmanager.reward.RewardPresentationModel.EncounterSummary> source)
    {
        encounterSource = source == null ? () -> null : source;
    }

    /** An automatic boundary set to Mark: a dot on the ribbon for the live owner. Safe from any thread. */
    public void noteBoundaryMark(Ribbon.MarkKind kind, String label)
    {
        ProfitSession active = engine.getActiveSession();
        boundaries.note(active == null ? null : active.getId(), kind, label, System.currentTimeMillis());
        refresh();
    }

    /** Sidebar appearance follows config; re-read on every refresh so the Tools toggles apply at once. */
    private void applyAppearance()
    {
        BentoTheme.setAccent(config.sidebarAccent());
        BentoTheme.setDensity(BentoTheme.Density.COMPACT);
        engine.setAlertPolicy(new com.gpmanager.model.AlertPolicy()
            .withEnabled(com.gpmanager.model.AlertKind.GOAL_REACHED, config.alertGoalReached()));
        engine.setSessionIdleAutoEnd(config.sessionIdleAutoEndMinutes());
        engine.setGeBookingMode(config.geBookingObserved()
            ? com.gpmanager.engine.GeBookingMode.OBSERVED : com.gpmanager.engine.GeBookingMode.PROVENANCE_ONLY);
        engine.setGeSellSpentIsNet(config.geSellSpentIsNet());
    }

    /** The engine's transient alert stream (pass 9 step 37): one listener, one toast per kind. */
    private void onAlert(com.gpmanager.model.AlertEvent event)
    {
        if (event == null || event.getKind() == null)
        {
            return;
        }
        // Alerts arrive from the client's tick thread; every toast and refresh must reach the EDT.
        javax.swing.SwingUtilities.invokeLater(() -> dispatchAlert(event));
    }

    private void dispatchAlert(com.gpmanager.model.AlertEvent event)
    {
        switch (event.getKind())
        {
            case NOTABLE_DROP:
            {
                String detail = event.getDetail();
                BufferedImage img = event.getItemId() > 0 ? sprite(event.getItemId()) : null;
                shell.toasts().info(img, "✦", event.getTitle(), detail, "Copy", BentoTheme.accentColor(),
                    () -> PopoutWindow.copyText(detail));
                break;
            }
            case GOAL_REACHED:
                shell.toasts().info("◎", event.getTitle(), event.getDetail(), null, BentoTheme.accentColor(), null);
                break;
            case WEALTH_MILESTONE:
                shell.toasts().info("◉", event.getTitle(), event.getDetail(), "Insights", BentoTheme.INFO, () ->
                {
                    shell.show(BentoShell.INSIGHTS);
                    insights.showMode(InsightsPage.Mode.WEALTH);
                    refresh();
                });
                break;
            case RECLAIM_EXPIRED:
                shell.toasts().info("☠", event.getTitle(), event.getDetail(), null, BentoTheme.WARN, null);
                break;
            case SESSION_AUTO_ENDED:
            {
                String id = event.getSessionId();
                if (id != null)
                {
                    announceSessionEnded(id, System.currentTimeMillis(), "idle " + event.getValue() + "m");
                    refresh();
                }
                break;
            }
            default:
                // SUPPLIES_LOW and any future kind: no sidebar presentation.
                break;
        }
    }

    @Nullable
    private BufferedImage gameSprite(int spriteId)
    {
        BufferedImage cached = gameSprites.get(spriteId);
        if (cached != null || spriteManager == null)
        {
            return cached;
        }
        if (gameSpritesRequested.add(spriteId))
        {
            try
            {
                spriteManager.getSpriteAsync(spriteId, 0, image ->
                {
                    if (image != null)
                    {
                        gameSprites.put(spriteId, image);
                        SwingUtilities.invokeLater(this::refresh);
                    }
                });
            }
            catch (RuntimeException ex)
            {
                // sprite cache unavailable (headless); the glyph fallback paints instead
            }
        }
        return null;
    }

    public void onActivate()
    {
        active = true;
        refresh();
    }

    public void onDeactivate()
    {
        active = false;
    }

    public BentoShell shell()
    {
        return shell;
    }

    public com.gpmanager.ui.WealthLocateModel wealthLocate()
    {
        return wealthLocate;
    }

    public void observeWealthLocation(String slotId, String detail)
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(() -> observeWealthLocation(slotId, detail));
            return;
        }
        wealthLocate.observe(slotId, detail);
    }

    public void clearWealthObservations()
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(this::clearWealthObservations);
            return;
        }
        wealthLocate.clearObservations();
    }

    public void refresh()
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(this::refresh);
            return;
        }
        if (!active && !isShowing())
        {
            return;
        }
        long now = System.currentTimeMillis();
        applyAppearance();
        BentoTheme.setExactFigures(config.sidebarExactFigures());
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, now, neutralZone.getAsBoolean(), accountHold.getAsBoolean(),
            new LiveContext(config.notableDropThresholdGp(), encounterSource.get(), boundaries));
        live.setActivity(resolveActivity(now));
        live.setPvp(config.pvpLayoutMode(), pvpState, this::gameSprite);
        live.setNetGraph(config.sidebarNetGraph());
        live.setHiddenTiles(engine.getTileLayout() == null ? java.util.Collections.emptySet()
            : engine.getTileLayout().getHiddenTileIds(ToolsSnapshot.LIVE_PAGE));
        live.setTileOrder(ToolsSnapshot.liveTileOrder(engine.getTileLayout()));
        live.apply(snapshot);
        noteDayRollover();
        live.applyParty(partySource.get(), now);
        shell.rail().setDot(BentoShell.TOOLS, snapshot.reviewCount > 0);
        String page = shell.currentPage();
        if (BentoShell.LEDGER.equals(page))
        {
            ledger.apply(ledger.historySessionId() != null
                ? LedgerSnapshot.captureSession(engine, ledger.historySessionId(), ledgerSearch)
                : LedgerSnapshot.capture(engine, ledger.scope(), now, ledgerSearch));
        }
        else if (BentoShell.SESSIONS.equals(page))
        {
            runs.apply(SessionsSnapshot.capture(engine, now, runs.favoritesOnly()));
        }
        else if (BentoShell.INSIGHTS.equals(page))
        {
            insights.apply(InsightsSnapshot.capture(engine, insights.range(), now, insights.partyOnly(), wealthSource.get()));
        }
        else if (BentoShell.TOOLS.equals(page))
        {
            tools.apply(ToolsSnapshot.capture(engine, now, wealthSource.get(), wealthLocate, partySource.get(), rules), toolsSettings());
        }
        else if (ItemSheet.ID.equals(page) && sheetItemId > 0)
        {
            itemSheet.apply(ItemSheet.Snapshot.capture(engine, sheetItemId, sheetItemName,
                rules.override(sheetItemId), rules.isExcluded(sheetItemId)));
        }
    }

    private void showLedger()
    {
        shell.show(BentoShell.LEDGER);
        refresh();
    }

    /** Test/preview seam. */
    public LedgerPage ledgerForPreview()
    {
        return ledger;
    }

    /** Wealth locations are read by the plugin (client thread); the panel only paints them. */
    public void bindWealth(java.util.function.Supplier<com.gpmanager.model.WealthLocationsSnapshot> source)
    {
        wealthSource = source == null ? () -> null : source;
    }

    /** Test/preview seam. */
    public InsightsPage insightsForPreview()
    {
        return insights;
    }

    /** Test/preview seam. */
    public ToolsPage toolsForPreview()
    {
        return tools;
    }

    public SearchPage searchForPreview()
    {
        return search;
    }

    public SessionsPage sessionsForPreview()
    {
        return runs;
    }

    /** Test/preview seam. */
    public void compareForPreview(SessionsSnapshot.Statement left, SessionsSnapshot.Statement right)
    {
        new SessionsActions().compare(left, right);
    }

    /** Test/preview seam. */
    public void openItemForPreview(int itemId, String name)
    {
        showItem(itemId, name);
    }

    private void showItem(int itemId, String name)
    {
        sheetItemId = itemId;
        sheetItemName = name;
        shell.show(ItemSheet.ID);
        refresh();
    }

    private String resolveActivity(long now)
    {
        ProfitSession session = engine.getActiveSession();
        if (session != null && interactionContext != null && config.autoActivityDetection() && !engine.isStopped())
        {
            InteractionContextModel.View view = interactionContext.snapshot(session.getId(), now);
            if (view.isPresent())
            {
                return view.getCategory().getDisplayName();
            }
        }
        SessionMetrics metrics = engine.getMetrics(now);
        String hint = metrics.getActivityHint();
        return hint == null ? "" : hint;
    }

    @Nullable
    private BufferedImage sprite(int itemId)
    {
        if (itemManager == null || itemId <= 0)
        {
            return null;
        }
        try
        {
            AsyncBufferedImage image = itemManager.getImage(itemId);
            if (image != null)
            {
                image.onLoaded(() -> SwingUtilities.invokeLater(this::repaint));
            }
            return image;
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    private final class LiveActions implements LivePage.Actions
    {
        @Override
        public void togglePause()
        {
            engine.togglePause(System.currentTimeMillis());
            refresh();
        }

        @Override
        public void openSessionMenu(JComponent anchor)
        {
            long now = System.currentTimeMillis();
            ProfitSession session = engine.getActiveSession();
            boolean custom = engine.isCustomSessionActive();
            JPopupMenu menu = new JPopupMenu();
            menu.setBackground(BentoTheme.ALT);
            menu.setBorder(javax.swing.BorderFactory.createLineBorder(BentoTheme.BORDER));

            JMenuItem rename = item("✎  Rename session");
            rename.setEnabled(session != null && custom);
            rename.addActionListener(e ->
            {
                String value = (String) JOptionPane.showInputDialog(BentoPanel.this, "Session name", "Rename session",
                    JOptionPane.PLAIN_MESSAGE, null, null, session == null ? "" : session.getName());
                if (value != null && !value.trim().isEmpty())
                {
                    engine.renameActiveSession(value.trim());
                    refresh();
                }
            });
            menu.add(rename);

            JMenuItem goal = item("◎  Goal…");
            goal.setEnabled(session != null);
            goal.addActionListener(e -> openGoalEditor());
            menu.add(goal);
            menu.addSeparator();

            JMenuItem undoChange = item("↶  Undo last change");
            undoChange.setEnabled(session != null);
            undoChange.addActionListener(e ->
            {
                if (engine.undoLastTransaction(System.currentTimeMillis()) != null)
                {
                    shell.toasts().undo("Change removed", "", () -> { engine.restoreLastUndo(System.currentTimeMillis()); refresh(); }, 8);
                }
                refresh();
            });
            menu.add(undoChange);
            JMenuItem undoCorrection = item("↶  Undo last correction");
            undoCorrection.addActionListener(e -> { engine.undoLastCorrection(System.currentTimeMillis()); refresh(); });
            menu.add(undoCorrection);
            JMenuItem restore = item("↷  Restore last undo");
            restore.addActionListener(e -> { engine.restoreLastUndo(System.currentTimeMillis()); refresh(); });
            menu.add(restore);
            menu.addSeparator();

            if (custom)
            {
                JMenuItem end = item("■  End session · back to free play");
                end.setForeground(BentoTheme.NEGATIVE);
                end.addActionListener(e ->
                {
                    int choice = JOptionPane.showConfirmDialog(BentoPanel.this,
                        "End \"" + (session == null ? "this session" : session.getName()) + "\"? Tracking continues as free play.",
                        "End session", JOptionPane.OK_CANCEL_OPTION);
                    if (choice == JOptionPane.OK_OPTION)
                    {
                        engine.finishCustomSession(System.currentTimeMillis());
                        refresh();
                    }
                });
                menu.add(end);
            }
            else
            {
                JMenuItem start = item("＋  Start a session…");
                start.addActionListener(e -> startCustomSession());
                menu.add(start);
            }
            menu.addSeparator();
            JMenuItem settings = item("⚙  Plugin settings");
            settings.addActionListener(e -> configurationOpener.run());
            menu.add(settings);
            menu.show(anchor, 0, anchor.getHeight());
        }

        @Override
        public void openGoalEditor()
        {
            ProfitSession session = engine.getActiveSession();
            if (session == null)
            {
                return;
            }
            com.gpmanager.model.GoalDefinition existing = LiveSnapshot.sessionGoalDefinition(engine);
            com.gpmanager.model.GoalDefinition.Kind currentKind = existing == null ? com.gpmanager.model.GoalDefinition.Kind.NET : existing.getKind();
            long currentTarget = existing != null ? existing.getTargetValue()
                : session.getProfitTargetGp() == null ? 0L : session.getProfitTargetGp();

            javax.swing.JComboBox<String> kindBox = new javax.swing.JComboBox<>(new String[] {"Net", "GP/h", "Kills"});
            kindBox.setSelectedIndex(currentKind == com.gpmanager.model.GoalDefinition.Kind.GP_PER_HOUR ? 1
                : currentKind == com.gpmanager.model.GoalDefinition.Kind.KILLS ? 2 : 0);
            javax.swing.JTextField valueField = new javax.swing.JTextField(currentTarget > 0L ? Long.toString(currentTarget) : "", 12);
            JPanel form = new JPanel(new java.awt.GridBagLayout());
            java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.anchor = java.awt.GridBagConstraints.WEST;
            c.insets = new java.awt.Insets(2, 0, 2, 8);
            form.add(new javax.swing.JLabel("Goal"), c);
            c.gridx = 1;
            c.fill = java.awt.GridBagConstraints.HORIZONTAL;
            c.weightx = 1d;
            form.add(kindBox, c);
            c.gridx = 0;
            c.gridy = 1;
            c.fill = java.awt.GridBagConstraints.NONE;
            c.weightx = 0d;
            form.add(new javax.swing.JLabel("Target"), c);
            c.gridx = 1;
            c.fill = java.awt.GridBagConstraints.HORIZONTAL;
            c.weightx = 1d;
            form.add(valueField, c);
            c.gridx = 0;
            c.gridy = 2;
            c.gridwidth = 2;
            c.insets = new java.awt.Insets(6, 0, 0, 0);
            javax.swing.JLabel hint = new javax.swing.JLabel("200k, 1.5m or plain digits · kills count PvM encounters and PKs · empty removes the goal");
            hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
            form.add(hint, c);
            int choice = JOptionPane.showConfirmDialog(BentoPanel.this, form, "Session goal", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION)
            {
                return;
            }
            com.gpmanager.model.GoalDefinition.Kind kind = kindBox.getSelectedIndex() == 1 ? com.gpmanager.model.GoalDefinition.Kind.GP_PER_HOUR
                : kindBox.getSelectedIndex() == 2 ? com.gpmanager.model.GoalDefinition.Kind.KILLS : com.gpmanager.model.GoalDefinition.Kind.NET;
            String trimmed = valueField.getText() == null ? "" : valueField.getText().trim().replace(",", "").replace("_", "")
                .replaceAll("(?i)\\s*kills?$", "").toLowerCase();
            long target = 0L;
            if (!trimmed.isEmpty())
            {
                try
                {
                    target = parseGp(trimmed);
                }
                catch (NumberFormatException ex)
                {
                    shell.toasts().info("!", "Not a number", "use 200k, 1.5m or plain digits", null, BentoTheme.WARN, null);
                    return;
                }
            }
            setSessionGoal(kind, target);
            refresh();
        }

        @Override
        public void openLedger()
        {
            showLedger();
        }

        @Override
        public void openReview()
        {
            shell.show(BentoShell.TOOLS);
        }
    }

    private final class LedgerActions implements LedgerPage.Actions
    {
        @Override
        public void correct(LedgerSnapshot.Row row, com.gpmanager.model.TransactionCorrection correction)
        {
            long now = System.currentTimeMillis();
            int applied = 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (LedgerSnapshot.Receipt r : row.receipts)
            {
                if (seen.add(r.transactionId) && engine.correctTransaction(r.transactionId, correction, now, "Sidebar correction"))
                {
                    applied++;
                }
            }
            if (applied > 0)
            {
                final int count = applied;
                shell.toasts().undo("Correction applied",
                    row.name + " → " + correction.name().toLowerCase() + (count > 1 ? " · " + count + " receipts" : ""),
                    () ->
                    {
                        for (int i = 0; i < count; i++)
                        {
                            engine.undoLastCorrection(System.currentTimeMillis());
                        }
                        refresh();
                    }, 8);
            }
            refresh();
        }

        @Override
        public void excludeItem(int itemId, String name)
        {
            if (!rules.canWrite())
            {
                shell.toasts().info("!", "Settings unavailable", "cannot write exclusions here", null, BentoTheme.WARN, null);
                return;
            }
            rules.setExcluded(itemId, true);
            shell.toasts().undo("Excluded " + name, "never counted from now on", () ->
            {
                rules.setExcluded(itemId, false);
                refresh();
            }, 8);
            refresh();
        }

        @Override
        public void openItemSheet(int itemId, String name)
        {
            showItem(itemId, name);
        }

        @Override
        public void scopeChanged(LedgerSnapshot.Scope scope)
        {
            refresh();
        }

        @Override
        public void searchChanged(String text)
        {
            ledgerSearch = text == null ? "" : text;
            refresh();
        }
    }

    /** Starts a named session now; used by Start a session…, ⟲ Again and automatic boundaries. */
    public void startSessionNamed(String name, boolean auto)
    {
        startSessionNamed(name, auto, null);
    }

    /**
     * Starts a named session of a category (§13.2). PvP turns PK accounting on; every kind is
     * written as a tag so the Sessions page and Insights read it back without the detector.
     */
    public void startSessionNamed(String name, boolean auto, @Nullable SessionKind kind)
    {
        if (name == null || name.trim().isEmpty())
        {
            return;
        }
        long now = System.currentTimeMillis();
        if (engine.isCustomSessionActive())
        {
            ProfitSession current = engine.getActiveSession();
            if (current != null && current.getName().equalsIgnoreCase(name.trim()))
            {
                return;
            }
            endSessionNow(now, auto ? com.gpmanager.model.SessionEndReason.BOUNDARY : com.gpmanager.model.SessionEndReason.MANUAL, false);
        }
        engine.startCustomSession(name.trim(), kind == null ? SessionMode.AUTO : kind.mode, now);
        ProfitSession started = engine.getActiveSession();
        if (started != null && engine.isCustomSessionActive() && kind != null)
        {
            engine.setActiveSessionCategory(kind.category);
        }
        if (auto)
        {
            autoStartedSessionId = started == null ? null : started.getId();
            engine.renameActiveSession(name.trim());
        }
        else
        {
            autoStartedSessionId = null;
        }
        refresh();
    }

    /** The category the picker pre-selects: the danger facts, then the detector, then the name. */
    SessionKind suggestedKind()
    {
        String category = "";
        ProfitSession session = engine.getActiveSession();
        if (session != null && interactionContext != null && config.autoActivityDetection())
        {
            InteractionContextModel.View view = interactionContext.snapshot(session.getId(), System.currentTimeMillis());
            if (view.isPresent())
            {
                category = view.getCategory().name();
            }
        }
        return SessionKind.suggest(live.activity(), category, pvpState.pvpPossible);
    }

    public boolean isAutoSessionActive()
    {
        ProfitSession current = engine.getActiveSession();
        return current != null && engine.isCustomSessionActive() && current.getId().equals(autoStartedSessionId);
    }

    /** Bank visit boundary set to New session: the live session ends and one with the same name begins. */
    public void restartSessionAfterBank(long now)
    {
        ProfitSession current = engine.getActiveSession();
        if (current == null || !engine.isCustomSessionActive())
        {
            return;
        }
        String name = current.getName();
        boolean auto = current.getId().equals(autoStartedSessionId);
        SessionKind kind = SessionKind.of(current);
        if (current.getElapsedMillis(now) < 60_000L)
        {
            // Two bank closes in a minute (deposit box, wrong tab) are not two trips.
            return;
        }
        endSessionNow(now, com.gpmanager.model.SessionEndReason.BOUNDARY, false);
        engine.startCustomSession(name, kind == null ? SessionMode.AUTO : kind.mode, now);
        ProfitSession next = engine.getActiveSession();
        if (next != null && kind != null)
        {
            engine.setActiveSessionCategory(kind.category);
        }
        autoStartedSessionId = auto && next != null ? next.getId() : null;
        refresh();
    }

    /** Ends the live session (if any) as a manual close; an automatic one is tagged so history shows ⚙. */
    public boolean endSessionNow(long now)
    {
        return endSessionNow(now, com.gpmanager.model.SessionEndReason.MANUAL);
    }

    /** As {@link #endSessionNow(long)}, recording why the session actually closed. */
    public boolean endSessionNow(long now, com.gpmanager.model.SessionEndReason reason)
    {
        return endSessionNow(now, reason, true);
    }

    private boolean endSessionNow(long now, com.gpmanager.model.SessionEndReason reason, boolean announce)
    {
        ProfitSession current = engine.getActiveSession();
        boolean wasAuto = current != null && current.getId().equals(autoStartedSessionId);
        String endedId = current == null ? null : current.getId();
        if (current != null)
        {
            engine.setActiveSessionEndReason(reason);
        }
        if (!engine.finishCustomSession(now))
        {
            return false;
        }
        if (wasAuto && endedId != null)
        {
            ProfitSession ended = engine.getHistorySession(endedId);
            String tags = ended == null ? "" : ended.getTagsDisplay();
            engine.setHistorySessionTags(endedId, tags == null || tags.isEmpty() ? SessionsSnapshot.AUTO_TAG : tags + ", " + SessionsSnapshot.AUTO_TAG);
        }
        autoStartedSessionId = null;
        SessionNetCache.invalidate();
        refresh();
        if (announce && endedId != null)
        {
            announceSessionEnded(endedId, now, null);
        }
        return true;
    }

    /** The just-ended session's one-line summary, with Copy — the moment it ends is when it is shared. */
    private void announceSessionEnded(String endedId, long now, @Nullable String note)
    {
        SessionsSnapshot snapshot = SessionsSnapshot.capture(engine, now, false);
        for (SessionsSnapshot.Group g : snapshot.groups)
        {
            for (SessionsSnapshot.Fold f : g.folds)
            {
                for (SessionsSnapshot.SessionRow r : f.sessions)
                {
                    if (r.id.equals(endedId))
                    {
                        String line = Fmt.signed(r.net) + " · " + Fmt.duration(r.durationMillis)
                            + (r.rateAvailable ? " · " + Fmt.rate(r.gpPerHour) + "/h" : "") + (r.keyStat.isEmpty() ? "" : " · " + r.keyStat);
                        shell.toasts().info("■", r.name + " ended" + (note == null ? "" : " · " + note), line, "Copy", BentoTheme.accentColor(),
                            () -> new SessionsActions().copySummary(r));
                        return;
                    }
                }
            }
        }
    }

    /** Start a session: a name and a category (§13.2), both pre-filled from what the detector sees. */
    private void startCustomSession()
    {
        String defaultName = live.activity() == null || live.activity().isEmpty() || "General".equalsIgnoreCase(live.activity())
            ? "" : live.activity();
        javax.swing.JTextField nameField = new javax.swing.JTextField(defaultName, 18);
        javax.swing.JComboBox<SessionKind> kindBox = new javax.swing.JComboBox<>(SessionKind.values());
        kindBox.setSelectedItem(suggestedKind());
        JPanel form = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.insets = new java.awt.Insets(2, 0, 2, 8);
        form.add(new javax.swing.JLabel("Name"), c);
        c.gridx = 1;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.weightx = 1d;
        form.add(nameField, c);
        c.gridx = 0;
        c.gridy = 1;
        c.fill = java.awt.GridBagConstraints.NONE;
        c.weightx = 0d;
        form.add(new javax.swing.JLabel("Category"), c);
        c.gridx = 1;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.weightx = 1d;
        form.add(kindBox, c);
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.insets = new java.awt.Insets(6, 0, 0, 0);
        javax.swing.JLabel hint = new javax.swing.JLabel("Vorkath, Wildy slayer, CoX… PvP turns kill and death accounting on.");
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        form.add(hint, c);
        nameField.addAncestorListener(new javax.swing.event.AncestorListener()
        {
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent event)
            {
                nameField.requestFocusInWindow();
                nameField.selectAll();
            }

            @Override
            public void ancestorRemoved(javax.swing.event.AncestorEvent event)
            {
            }

            @Override
            public void ancestorMoved(javax.swing.event.AncestorEvent event)
            {
            }
        });
        int choice = JOptionPane.showConfirmDialog(BentoPanel.this, form, "Start a session", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION)
        {
            return;
        }
        String value = nameField.getText();
        SessionKind kind = (SessionKind) kindBox.getSelectedItem();
        if (value == null || value.trim().isEmpty())
        {
            value = kind == null ? "" : kind.label;
        }
        if (!value.trim().isEmpty())
        {
            startSessionNamed(value.trim(), false, kind);
        }
    }

    private final class SessionsActions implements SessionsPage.Actions
    {
        @Override
        public void startSession()
        {
            if (engine.isCustomSessionActive())
            {
                shell.toasts().info("!", "A session is already going", "end it from Live › Session ▾ first", null, BentoTheme.WARN, null);
                return;
            }
            startCustomSession();
        }

        @Override
        public void endSession()
        {
            if (engine.isCustomSessionActive())
            {
                endSessionNow(System.currentTimeMillis());
            }
        }

        @Override
        public void startAgain(String name)
        {
            // Same name, same category as the last session of that name.
            SessionKind kind = null;
            for (ProfitSession s : engine.getHistory())
            {
                if (s.getName().equalsIgnoreCase(name) && SessionKind.of(s) != null)
                {
                    kind = SessionKind.of(s);
                }
            }
            startSessionNamed(name, false, kind);
            shell.show(BentoShell.LIVE);
            refresh();
        }

        @Override
        public void toggleFavorite(SessionsSnapshot.SessionRow session)
        {
            engine.setHistorySessionFavorite(session.id, !session.favorite);
            refresh();
        }

        @Override
        public void openSearch()
        {
            openSearchPalette();
        }

        /** Merge this session with the previous one of the same name (pass 10 step 41); the toast offers Undo. */
        public void mergeWithPrevious(SessionsSnapshot.SessionRow session)
        {
            long now = System.currentTimeMillis();
            ProfitSession target = engine.getHistorySession(session.id);
            ProfitSession previous = null;
            for (ProfitSession s : engine.getHistory())
            {
                if (s == null || s.getId().equals(session.id) || !s.isClosed() || !s.getName().equalsIgnoreCase(session.name)) continue;
                if (s.getStartedAtEpochMillis() < session.startedAt && (previous == null || s.getStartedAtEpochMillis() > previous.getStartedAtEpochMillis()))
                {
                    previous = s;
                }
            }
            if (target == null || previous == null)
            {
                shell.toasts().info("!", "Nothing to merge", "no earlier " + session.name, null, BentoTheme.WARN, null);
                return;
            }
            GpManagerEngine.MergeOutcome outcome = engine.mergeHistorySessions(java.util.Arrays.asList(previous.getId(), session.id), now);
            if (!outcome.isMerged())
            {
                String why = "NOT_CONSECUTIVE".equals(outcome.getReason()) ? "another session sits between them"
                    : "MIXED_OWNER".equals(outcome.getReason()) ? "one of them is Free play" : outcome.getReason().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
                shell.toasts().info("!", "Not merged", why, null, BentoTheme.WARN, null);
                return;
            }
            SessionNetCache.invalidate();
            refresh();
            shell.toasts().info("", "Merged into one " + session.name, "the two now read as one session", "Undo", BentoTheme.accentColor(), () ->
            {
                if (engine.undoLastMerge())
                {
                    SessionNetCache.invalidate();
                    refresh();
                }
            });
        }

        @Override
        public void compareWithPrevious(SessionsSnapshot.SessionRow session)
        {
            SessionsSnapshot snapshot = SessionsSnapshot.capture(engine, System.currentTimeMillis(), false);
            SessionsSnapshot.SessionRow previous = null;
            for (SessionsSnapshot.Group g : snapshot.groups)
            {
                for (SessionsSnapshot.Fold f : g.folds)
                {
                    for (SessionsSnapshot.SessionRow r : f.sessions)
                    {
                        if (!r.id.equals(session.id) && r.name.equalsIgnoreCase(session.name) && r.startedAt < session.startedAt
                            && (previous == null || r.startedAt > previous.startedAt))
                        {
                            previous = r;
                        }
                    }
                }
            }
            if (previous == null)
            {
                shell.toasts().info("!", "No earlier " + session.name, "this is the first one", null, BentoTheme.WARN, null);
                return;
            }
            compare(previous.asStatement, session.asStatement);
        }

        @Override
        public void compareWith(SessionsSnapshot.SessionRow session)
        {
            SessionsSnapshot snapshot = SessionsSnapshot.capture(engine, System.currentTimeMillis(), false);
            java.util.List<SessionsSnapshot.SessionRow> others = new java.util.ArrayList<>();
            for (SessionsSnapshot.Group g : snapshot.groups)
            {
                for (SessionsSnapshot.Fold f : g.folds)
                {
                    for (SessionsSnapshot.SessionRow r : f.sessions)
                    {
                        if (!r.id.equals(session.id))
                        {
                            others.add(r);
                        }
                    }
                }
            }
            if (others.isEmpty())
            {
                shell.toasts().info("!", "Nothing to compare with", null, null, BentoTheme.WARN, null);
                return;
            }
            String[] labels = new String[others.size()];
            for (int i = 0; i < labels.length; i++)
            {
                SessionsSnapshot.SessionRow r = others.get(i);
                labels[i] = r.name + " · " + Fmt.when(r.startedAt, System.currentTimeMillis()) + " · " + Fmt.signed(r.net);
            }
            String picked = (String) JOptionPane.showInputDialog(BentoPanel.this, "Compare " + session.name + " with", "Compare with",
                JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
            if (picked == null)
            {
                return;
            }
            SessionsSnapshot.SessionRow other = others.get(java.util.Arrays.asList(labels).indexOf(picked));
            compare(other.startedAt < session.startedAt ? other.asStatement : session.asStatement,
                other.startedAt < session.startedAt ? session.asStatement : other.asStatement);
        }

        @Override
        public void copySummary(SessionsSnapshot.SessionRow session)
        {
            StringBuilder sb = new StringBuilder();
            sb.append(session.name);
            if (!session.kind.isEmpty())
            {
                sb.append(" (").append(session.kind).append(')');
            }
            sb.append(" · ").append(Fmt.when(session.startedAt, System.currentTimeMillis()))
                .append(" · ").append(Fmt.duration(session.durationMillis))
                .append(" · net ").append(Fmt.signed(session.net));
            if (session.rateAvailable)
            {
                sb.append(" · ").append(Fmt.rate(session.gpPerHour)).append("/h");
            }
            if (!session.keyStat.isEmpty())
            {
                sb.append(" · ").append(session.keyStat);
            }
            if (session.deaths > 0)
            {
                sb.append(" · ").append(session.deaths).append(session.deaths == 1 ? " death" : " deaths");
            }
            PopoutWindow.copyText(sb.toString());
            shell.toasts().info("⎘", "Summary copied", sb.toString(), null, BentoTheme.accentColor(), null);
        }

        @Override
        public void openLedgerFor(String sessionId, String name)
        {
            ProfitSession active = engine.getActiveSession();
            if (active != null && active.getId().equals(sessionId))
            {
                ledger.setScope(LedgerSnapshot.Scope.SESSION);
            }
            else
            {
                ledger.setHistorySession(sessionId, name);
            }
            showLedger();
        }

        @Override
        public void compare(SessionsSnapshot.Statement left, SessionsSnapshot.Statement right)
        {
            long now = System.currentTimeMillis();
            String activity = left.title;
            ComparePage.Average average = SessionsSnapshot.activityAverage(engine, activity, now, null);
            com.gpmanager.model.SessionComparison model = left.sessionId == null || right.sessionId == null ? null
                : engine.compareSessions(left.sessionId, right.sessionId, now);
            compare.apply(left, right, average, model == null || !model.isTopItemDeltasAvailable()
                ? java.util.Collections.emptyList() : model.getTopItemDeltas());
            shell.show(ComparePage.ID);
        }

        @Override
        public void sessionMenu(JComponent anchor, SessionsSnapshot.SessionRow session)
        {
            JPopupMenu menu = new JPopupMenu();
            menu.setBackground(BentoTheme.ALT);
            menu.setBorder(javax.swing.BorderFactory.createLineBorder(BentoTheme.BORDER));
            JMenuItem rename = item("✎  Rename…");
            rename.addActionListener(e ->
            {
                String value = (String) JOptionPane.showInputDialog(BentoPanel.this, "Session name", "Rename",
                    JOptionPane.PLAIN_MESSAGE, null, null, session.name);
                if (value != null && !value.trim().isEmpty())
                {
                    engine.renameHistorySession(session.id, value.trim());
                    refresh();
                }
            });
            menu.add(rename);
            JMenuItem compareWith = item("⚖  Compare with…");
            compareWith.addActionListener(e -> new SessionsActions().compareWith(session));
            menu.add(compareWith);
            JMenuItem comparePrev = item("⚖  Compare with previous " + session.name);
            comparePrev.addActionListener(e -> new SessionsActions().compareWithPrevious(session));
            menu.add(comparePrev);
            JMenuItem copy = item("⎘  Copy summary");
            copy.addActionListener(e -> new SessionsActions().copySummary(session));
            menu.add(copy);
            if (!session.freePlay)
            {
                JMenuItem merge = item("⊕  Merge with previous " + session.name);
                merge.addActionListener(e -> new SessionsActions().mergeWithPrevious(session));
                menu.add(merge);
            }
            menu.addSeparator();
            JMenuItem category = item("◈  Category…");
            category.addActionListener(e ->
            {
                ProfitSession s = engine.getHistorySession(session.id);
                SessionKind current = s == null ? null : SessionKind.of(s);
                SessionKind pick = (SessionKind) JOptionPane.showInputDialog(BentoPanel.this, "Category for \"" + session.name + "\"",
                    "Category", JOptionPane.PLAIN_MESSAGE, null, SessionKind.values(), current == null ? SessionKind.OTHER : current);
                if (pick != null && s != null)
                {
                    if (!engine.setHistorySessionCategory(session.id, pick.category))
                    {
                        shell.toasts().info("!", "Category not changed", "a PvP session keeps PvP accounting", null, BentoTheme.WARN, null);
                    }
                    refresh();
                }
            });
            menu.add(category);
            JMenuItem tags = item("#  Tags…");
            tags.addActionListener(e ->
            {
                ProfitSession s = engine.getHistorySession(session.id);
                String value = (String) JOptionPane.showInputDialog(BentoPanel.this, "Comma-separated tags", "Tags",
                    JOptionPane.PLAIN_MESSAGE, null, null, s == null ? "" : s.getTagsDisplay());
                if (value != null)
                {
                    engine.setHistorySessionTags(session.id, value.trim());
                    refresh();
                }
            });
            menu.add(tags);
            JMenuItem notes = item("✎  Notes…");
            notes.addActionListener(e ->
            {
                ProfitSession s = engine.getHistorySession(session.id);
                String value = (String) JOptionPane.showInputDialog(BentoPanel.this, "Notes", "Notes",
                    JOptionPane.PLAIN_MESSAGE, null, null, s == null ? "" : s.getNotes());
                if (value != null)
                {
                    engine.setHistorySessionNotes(session.id, value.trim());
                    refresh();
                }
            });
            menu.add(notes);
            menu.addSeparator();
            JMenuItem fav = item(session.favorite ? "☆  Remove favourite" : "★  Add favourite");
            fav.addActionListener(e -> { engine.setHistorySessionFavorite(session.id, !session.favorite); refresh(); });
            menu.add(fav);
            JMenuItem avg = item(session.excludedFromAverages ? "∅  Include in averages" : "∅  Exclude from averages");
            avg.addActionListener(e -> { engine.setHistorySessionExcluded(session.id, !session.excludedFromAverages); refresh(); });
            menu.add(avg);
            menu.addSeparator();
            JMenuItem delete = item("✕  Delete session…");
            delete.setForeground(BentoTheme.NEGATIVE);
            delete.addActionListener(e ->
            {
                int first = JOptionPane.showConfirmDialog(BentoPanel.this,
                    "Delete \"" + session.name + "\" and its receipts?", "Delete session", JOptionPane.OK_CANCEL_OPTION);
                if (first != JOptionPane.OK_OPTION)
                {
                    return;
                }
                int second = JOptionPane.showConfirmDialog(BentoPanel.this,
                    "This cannot be undone. Delete for good?", "Confirm delete", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (second == JOptionPane.OK_OPTION)
                {
                    engine.deleteHistorySession(session.id);
                    runs.clearSelection();
                    refresh();
                }
            });
            menu.add(delete);
            menu.show(anchor, 0, anchor.getHeight());
        }

        @Override
        public void favoritesToggled(boolean favoritesOnly)
        {
            refresh();
        }
    }

    private final class InsightsActions implements InsightsPage.Actions
    {
        @Override
        public void rangeChanged(InsightsSnapshot.Range range)
        {
            refresh();
        }

        @Override
        public void modeChanged(InsightsPage.Mode mode)
        {
            // The capture already covers every mode; the page re-renders itself.
        }

        @Override
        public void partyFilterChanged(boolean partyOnly)
        {
            refresh();
        }

        @Override
        public void openItem(int itemId, String name)
        {
            showItem(itemId, name);
        }

        @Override
        public void openSession(String sessionId)
        {
            shell.show(BentoShell.SESSIONS);
            refresh();
        }

        @Override
        public void markCoinStoreUnused(com.gpmanager.model.CoinStore store, boolean unused)
        {
            engine.markCoinStoreUnused(store, unused);
            refresh();
        }

        @Override
        public void exportCsv(InsightsSnapshot snapshot, InsightsPage.Mode mode)
        {
            PopoutWindow.copyText(InsightsPage.csv(snapshot, mode));
            shell.toasts().info("⇪", "Insights copied as CSV", mode.label + " · " + snapshot.range.label, null, BentoTheme.accentColor(), null);
        }
    }

    private ToolsPage.Settings toolsSettings()
    {
        String profile = "";
        if (persistence != null)
        {
            com.gpmanager.persistence.TrackingIdentity identity = persistence.getActiveIdentity();
            profile = identity == null ? "" : identity.toString();
        }
        com.gpmanager.diagnostics.BuildFingerprint build = com.gpmanager.diagnostics.BuildFingerprint.get();
        String about = "v" + build.getVersion() + " · schema " + com.gpmanager.persistence.SavedState.CURRENT_SCHEMA_VERSION
            + " · RuneLite " + build.getRuneLiteDependency();
        return new ToolsPage.Settings(profile, config.enablePartyTracking(), config.showPartyMetricsInOverlay(),
            config.notableDropThresholdGp(), config.sidebarAccent(), BentoTheme.Density.COMPACT, config.pvpLayoutMode(),
            config.sidebarNetGraph(), config.enableDebugTrace(), configManager != null,
            config.boundarySlayer(), config.boundaryRaid(), config.boundaryBank(), config.receiptRetentionDays(),
            config.alertGoalReached(), config.wealthMilestoneGp(), config.reducedMotion(), about, config.sidebarExactFigures(),
            config.sessionIdleAutoEndMinutes()).withGe(config.geBookingObserved(), config.geSellSpentIsNet());
    }

    /**
     * The profile's one session-scope goal (§13.5): a definition the engine scores through
     * getGoalProgress; a net goal is mirrored onto the session's legacy target so the HUD keeps it.
     */
    void setSessionGoal(com.gpmanager.model.GoalDefinition.Kind kind, long target)
    {
        java.util.List<com.gpmanager.model.GoalDefinition> kept = new java.util.ArrayList<>();
        for (com.gpmanager.model.GoalDefinition d : engine.getGoalDefinitions())
        {
            if (d != null && d.getScope() != com.gpmanager.model.GoalDefinition.Scope.SESSION)
            {
                kept.add(d);
            }
        }
        if (target > 0L)
        {
            kept.add(new com.gpmanager.model.GoalDefinition(kind, com.gpmanager.model.GoalDefinition.Scope.SESSION, target,
                java.util.Arrays.asList(25, 50, 75)));
        }
        engine.setGoalDefinitions(kept);
        ProfitSession session = engine.getActiveSession();
        if (session != null)
        {
            session.setProfitTargetGp(kind == com.gpmanager.model.GoalDefinition.Kind.NET && target > 0L ? target : null);
        }
    }

    /** At local midnight: yesterday's total as a toast, with a jump to Insights. */
    private void noteDayRollover()
    {
        java.time.LocalDate today = java.time.LocalDate.now();
        if (lastSeenDate == null)
        {
            lastSeenDate = today;
            return;
        }
        if (today.equals(lastSeenDate))
        {
            return;
        }
        java.time.LocalDate yesterday = lastSeenDate;
        lastSeenDate = today;
        for (com.gpmanager.model.DailyRollup day : engine.getDailyRollups(yesterday, yesterday))
        {
            int sessions = day.getNamedSessionStarts();
            shell.toasts().info("◷", "Yesterday " + Fmt.signed(day.getNetGp()), sessions + (sessions == 1 ? " session" : " sessions"),
                "Insights", BentoTheme.INFO, () -> shell.show(BentoShell.INSIGHTS));
            return;
        }
    }

    private static String correctionLabel(com.gpmanager.model.TransactionCorrection c)
    {
        switch (c)
        {
            case REVENUE:
                return "Count as gain";
            case COST:
                return "Count as cost";
            case TRANSFER:
                return "Mark as transfer";
            case IGNORE:
                return "Ignore";
            default:
                return c.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private void setConfig(String key, Object value)
    {
        if (configManager != null)
        {
            configManager.setConfiguration(GpManagerConfig.GROUP, key, value);
        }
        refresh();
    }

    private boolean commitState()
    {
        if (persistence != null)
        {
            return persistence.replaceStateNow().isCommitted();
        }
        if (repository != null)
        {
            return repository.replaceStateDetailed(repository.detach(engine.createSavedState())).isCommitted();
        }
        return true;
    }

    private boolean confirm(String message, String title)
    {
        return JOptionPane.showConfirmDialog(BentoPanel.this, message, title, JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /** Offers a recovery CSV of the given sessions first; false means the caller must stop. */
    private boolean recoveryExport(java.util.List<ProfitSession> sessions)
    {
        if (csvExporter == null || repository == null)
        {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(BentoPanel.this,
            "Create a recovery CSV export first? Existing exports remain untouched.",
            "Recovery export", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION)
        {
            return false;
        }
        if (answer != JOptionPane.YES_OPTION)
        {
            return true;
        }
        try
        {
            for (ProfitSession session : sessions)
            {
                if (session != null)
                {
                    csvExporter.exportSession(session, repository.getExportDirectory(), config.rollingRateMinutes(),
                        engine.activeContributionEligibility());
                }
            }
            return true;
        }
        catch (java.io.IOException | RuntimeException ex)
        {
            JOptionPane.showMessageDialog(BentoPanel.this, "Export failed: " + ex.getMessage(), "Export", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void exportSession(ProfitSession session)
    {
        if (csvExporter == null || repository == null)
        {
            shell.toasts().info("!", "Export unavailable", "no export directory in this client", null, BentoTheme.WARN, null);
            return;
        }
        try
        {
            com.gpmanager.persistence.CsvExportResult result = csvExporter.exportSession(session, repository.getExportDirectory(),
                config.rollingRateMinutes(), engine.activeContributionEligibility());
            shell.toasts().info("⇪", "Exported " + session.getName(), result.getDetailPath().getFileName().toString(), null,
                BentoTheme.accentColor(), null);
        }
        catch (java.io.IOException | RuntimeException ex)
        {
            JOptionPane.showMessageDialog(BentoPanel.this, "Export failed: " + ex.getMessage(), "Export", JOptionPane.ERROR_MESSAGE);
        }
    }

    @Nullable
    private ProfitSession pickHistorySession(String title)
    {
        java.util.List<ProfitSession> history = engine.getHistory();
        if (history.isEmpty())
        {
            return null;
        }
        String[] labels = new String[history.size()];
        for (int i = 0; i < labels.length; i++)
        {
            ProfitSession s = history.get(i);
            labels[i] = SessionsSnapshot.displayName(s) + " · " + SessionsSnapshot.dayLabel(s.getStartedAtEpochMillis(), java.time.LocalDate.now());
        }
        Object pick = JOptionPane.showInputDialog(BentoPanel.this, "Session", title, JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
        if (pick == null)
        {
            return null;
        }
        for (int i = 0; i < labels.length; i++)
        {
            if (labels[i].equals(pick))
            {
                return history.get(i);
            }
        }
        return null;
    }

    private String itemName(int itemId)
    {
        java.util.List<ProfitSession> sessions = new java.util.ArrayList<>(engine.getHistory());
        if (engine.getActiveSession() != null)
        {
            sessions.add(0, engine.getActiveSession());
        }
        for (ProfitSession session : sessions)
        {
            for (com.gpmanager.model.ProfitTransaction t : session.getTransactions())
            {
                for (com.gpmanager.model.ItemFlow f : t.getFlows())
                {
                    if (f != null && f.getItemId() == itemId && f.getItemName() != null && !f.getItemName().isEmpty())
                    {
                        return f.getItemName();
                    }
                }
            }
        }
        if (itemManager != null)
        {
            try
            {
                return itemManager.getItemComposition(itemId).getName();
            }
            catch (RuntimeException ex)
            {
                // off the client thread or not cached
            }
        }
        return "Item " + itemId;
    }

    private final class ToolsActions implements ToolsPage.Actions
    {
        @Override
        public void decide(ToolsSnapshot.Decision d, com.gpmanager.model.TransactionCorrection correction)
        {
            if (engine.correctTransaction(d.transactionId, correction, System.currentTimeMillis(), "Review decision"))
            {
                shell.toasts().undo("Decision applied", d.name + " → " + correction.name().toLowerCase(), () ->
                {
                    engine.undoLastCorrection(System.currentTimeMillis());
                    refresh();
                }, 8);
            }
            refresh();
        }

        @Override
        public void decideAll(com.gpmanager.model.TransactionCorrection correction)
        {
            long now = System.currentTimeMillis();
            com.gpmanager.model.ReviewInbox inbox = engine.getReviewInbox(now);
            int n = inbox == null ? 0 : inbox.getPendingCount();
            if (n == 0)
            {
                return;
            }
            com.gpmanager.model.ReviewDecision decision = decisionFor(correction);
            int choice = JOptionPane.showConfirmDialog(BentoPanel.this,
                "Apply \"" + correctionLabel(correction) + "\" to all " + n + (n == 1 ? " pending receipt?" : " pending receipts?")
                    + "\nOne Undo last reverts the whole batch.",
                "Decide all", JOptionPane.OK_CANCEL_OPTION);
            if (choice != JOptionPane.OK_OPTION)
            {
                return;
            }
            // The engine's atomic batch (pass 9 step 36): one correction record, one undo.
            int applied = engine.decideAll(decision, row -> row.getValidDecisions().contains(decision), now);
            int skipped = n - applied;
            shell.toasts().info("✓", applied + (applied == 1 ? " receipt decided" : " receipts decided"),
                correctionLabel(correction) + (skipped > 0 ? " · " + skipped + " could not take it" : ""), null, BentoTheme.accentColor(), null);
            refresh();
        }

        private com.gpmanager.model.ReviewDecision decisionFor(com.gpmanager.model.TransactionCorrection c)
        {
            switch (c)
            {
                case REVENUE:
                    return com.gpmanager.model.ReviewDecision.GAIN;
                case COST:
                    return com.gpmanager.model.ReviewDecision.COST;
                case TRANSFER:
                    return com.gpmanager.model.ReviewDecision.TRANSFER;
                default:
                    return com.gpmanager.model.ReviewDecision.IGNORE;
            }
        }

        @Override
        public void undoLastCorrection()
        {
            if (engine.undoLastCorrection(System.currentTimeMillis()))
            {
                shell.toasts().info("↶", "Correction undone", "the automatic call is back", null, BentoTheme.accentColor(), null);
            }
            refresh();
        }

        @Override
        public void openInsightsWealth()
        {
            shell.show(BentoShell.INSIGHTS);
            insights.showMode(InsightsPage.Mode.WEALTH);
            refresh();
        }

        @Override
        public void setPartySharing(boolean on)
        {
            setConfig("enablePartyTracking", on);
        }

        @Override
        public void setPartyHud(boolean on)
        {
            setConfig("showPartyMetricsInOverlay", on);
        }

        @Override
        public void showSplit()
        {
            com.gpmanager.model.PartyProfitSummary party = partySource.get();
            java.util.List<long[]> rows = ToolsSnapshot.evenSplit(party);
            if (party == null || rows.isEmpty())
            {
                shell.toasts().info("!", "Nothing to split", "no member is sharing figures", null, BentoTheme.WARN, null);
                return;
            }
            java.util.List<com.gpmanager.model.PartyProfitSummary.Member> reporting = new java.util.ArrayList<>();
            for (com.gpmanager.model.PartyProfitSummary.Member m : party.getMembers())
            {
                if (m.isReporting())
                {
                    reporting.add(m);
                }
            }
            StringBuilder card = new StringBuilder("Even split · counted net\n");
            for (long[] r : rows)
            {
                String name = reporting.get((int) r[0]).getDisplayName();
                card.append(name).append(": ").append(Fmt.exactSigned(r[1])).append("  ")
                    .append(r[3] > 0L ? "owes " + Fmt.exact(r[3]) : r[3] < 0L ? "is owed " + Fmt.exact(-r[3]) : "even").append('\n');
            }
            card.append("Share each: ").append(Fmt.exactSigned(rows.get(0)[2]));
            Object[] options = {"Copy split", "Close"};
            int choice = JOptionPane.showOptionDialog(BentoPanel.this, card.toString(), "Party split", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (choice == 0)
            {
                PopoutWindow.copyText(card.toString());
                shell.toasts().info("⇪", "Split copied", null, null, BentoTheme.accentColor(), null);
            }
        }

        @Override
        public void copyPartyCard()
        {
            com.gpmanager.model.PartyProfitSummary party = partySource.get();
            if (party == null || !party.isInParty())
            {
                return;
            }
            StringBuilder sb = new StringBuilder("Party · net " + Fmt.exactSigned(party.getNet()) + "\n");
            for (com.gpmanager.model.PartyProfitSummary.Member m : party.getMembers())
            {
                sb.append(m.getDisplayName()).append(": ")
                    .append(m.isReporting() ? Fmt.exactSigned(m.getNet()) + (m.hasReportedRate() ? " · " + Fmt.exact(m.getProfitPerHour()) + "/h" : "") : "not sharing")
                    .append('\n');
            }
            PopoutWindow.copyText(sb.toString());
            shell.toasts().info("⇪", "Party card copied", null, null, BentoTheme.accentColor(), null);
        }

        @Override
        public void setLiveTileHidden(String tileId, boolean hidden)
        {
            com.gpmanager.model.TileLayout current = engine.getTileLayout();
            java.util.Map<String, com.gpmanager.model.TileLayout.PageLayout> pages = new java.util.LinkedHashMap<>(
                current == null ? java.util.Collections.emptyMap() : current.getPages());
            com.gpmanager.model.TileLayout.PageLayout page = current == null
                ? new com.gpmanager.model.TileLayout.PageLayout() : current.getPage(ToolsSnapshot.LIVE_PAGE);
            java.util.Set<String> hiddenIds = new java.util.LinkedHashSet<>(page.getHiddenTileIds());
            if (hidden)
            {
                hiddenIds.add(tileId);
            }
            else
            {
                hiddenIds.remove(tileId);
            }
            pages.put(ToolsSnapshot.LIVE_PAGE, new com.gpmanager.model.TileLayout.PageLayout(page.getOrderedTileIds(), hiddenIds));
            engine.setTileLayout(new com.gpmanager.model.TileLayout(pages));
            refresh();
        }

        @Override
        public void moveLiveTile(String tileId, boolean up)
        {
            com.gpmanager.model.TileLayout current = engine.getTileLayout();
            java.util.List<String> order = new java.util.ArrayList<>(ToolsSnapshot.liveTileOrder(current));
            int i = order.indexOf(tileId);
            int j = up ? i - 1 : i + 1;
            if (i < 0 || j < 0 || j >= order.size())
            {
                return;
            }
            java.util.Collections.swap(order, i, j);
            java.util.Map<String, com.gpmanager.model.TileLayout.PageLayout> pages = new java.util.LinkedHashMap<>(
                current == null ? java.util.Collections.emptyMap() : current.getPages());
            com.gpmanager.model.TileLayout.PageLayout page = current == null
                ? new com.gpmanager.model.TileLayout.PageLayout() : current.getPage(ToolsSnapshot.LIVE_PAGE);
            pages.put(ToolsSnapshot.LIVE_PAGE, new com.gpmanager.model.TileLayout.PageLayout(order, page.getHiddenTileIds()));
            engine.setTileLayout(new com.gpmanager.model.TileLayout(pages));
            refresh();
        }

        @Override
        public void backupProfile()
        {
            writeBackup(true);
        }

        @Override
        public void restoreProfile()
        {
            if (repository == null)
            {
                shell.toasts().info("!", "Restore unavailable", "no data directory in this client", null, BentoTheme.WARN, null);
                return;
            }
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(repository.getExportDirectory().toFile());
            chooser.setDialogTitle("Restore from backup");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("GP Manager profile backup (*.json)", "json"));
            if (chooser.showOpenDialog(BentoPanel.this) != javax.swing.JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null)
            {
                return;
            }
            String json;
            try
            {
                json = new String(java.nio.file.Files.readAllBytes(chooser.getSelectedFile().toPath()), java.nio.charset.StandardCharsets.UTF_8);
            }
            catch (java.io.IOException ex)
            {
                JOptionPane.showMessageDialog(BentoPanel.this, "Could not read the file: " + ex.getMessage(), "Restore", JOptionPane.ERROR_MESSAGE);
                return;
            }
            // Dry run first: the engine says what it would do, or why it refuses, before anything changes.
            com.gpmanager.persistence.ProfileBackupReport report = engine.inspectBackup(json);
            if (report == null || !report.isAccepted())
            {
                JOptionPane.showMessageDialog(BentoPanel.this, "This backup cannot be restored:\n"
                    + (report == null ? "unreadable" : report.getRefusalReason()), "Restore", JOptionPane.WARNING_MESSAGE);
                return;
            }
            StringBuilder what = new StringBuilder();
            what.append("Backup: ").append(report.getBackupDescription()).append("\n");
            what.append("Schema ").append(report.getSchemaVersion());
            if (!report.getMigrationSteps().isEmpty())
            {
                what.append(" · migrates: ").append(String.join(", ", report.getMigrationSteps()));
            }
            what.append("\nReplaces: ").append(report.getReplaces()).append("\n\n");
            what.append("A recovery export of the current profile is written first. Restore now?");
            int choice = JOptionPane.showConfirmDialog(BentoPanel.this, what.toString(), "Restore from backup", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION)
            {
                return;
            }
            if (!writeBackup(false))
            {
                return;
            }
            com.gpmanager.persistence.ProfileBackupReport applied = persistence != null
                ? persistence.restoreProfile(json, System.currentTimeMillis())
                : engine.restoreProfile(json, System.currentTimeMillis());
            if (applied == null || !applied.isApplied())
            {
                JOptionPane.showMessageDialog(BentoPanel.this, "Restore refused: " + (applied == null ? "unknown" : applied.getRefusalReason()),
                    "Restore", JOptionPane.ERROR_MESSAGE);
                return;
            }
            SessionNetCache.invalidate();
            shell.toasts().info("↺", "Profile restored", applied.getBackupDescription()
                + (applied.isPersistenceAttempted() && !applied.isDurablyCommitted() ? " · not yet saved: " + applied.getPersistenceDetail() : ""),
                null, BentoTheme.accentColor(), null);
            refresh();
        }

        /** The engine's validated backup envelope, dated, next to the CSVs; returns whether it was written. */
        private boolean writeBackup(boolean toast)
        {
            if (repository == null)
            {
                shell.toasts().info("!", "Backup unavailable", "no data directory in this client", null, BentoTheme.WARN, null);
                return false;
            }
            try
            {
                java.nio.file.Path dir = repository.getExportDirectory();
                java.nio.file.Files.createDirectories(dir);
                java.time.LocalDate today = java.time.LocalDate.now();
                java.nio.file.Path file = dir.resolve(com.gpmanager.persistence.ProfileBackup.fileNameFor(today));
                for (int n = 1; java.nio.file.Files.exists(file) && n < 1_000; n++)
                {
                    file = dir.resolve(com.gpmanager.persistence.ProfileBackup.fileNameFor(today, n));
                }
                com.gpmanager.persistence.ProfileBackup backup = engine.exportProfile();
                java.nio.file.Files.write(file, backup.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (toast)
                {
                    shell.toasts().info("⇪", "Profile backed up", file.getFileName() + " · " + backup.describe(), null, BentoTheme.accentColor(), null);
                }
                return true;
            }
            catch (java.io.IOException | RuntimeException ex)
            {
                JOptionPane.showMessageDialog(BentoPanel.this, "Backup failed: " + ex.getMessage(), "Backup", JOptionPane.ERROR_MESSAGE);
                return false;
            }
        }

        @Override
        public void openExportFolder()
        {
            if (repository == null)
            {
                shell.toasts().info("!", "No export folder in this client", null, null, BentoTheme.WARN, null);
                return;
            }
            try
            {
                java.nio.file.Files.createDirectories(repository.getExportDirectory());
                java.awt.Desktop.getDesktop().open(repository.getExportDirectory().toFile());
            }
            catch (java.io.IOException | RuntimeException ex)
            {
                shell.toasts().info("!", "Could not open the folder", ex.getMessage(), null, BentoTheme.WARN, null);
            }
        }

        @Override
        public void copyDataPath()
        {
            if (repository == null)
            {
                shell.toasts().info("!", "No data folder in this client", null, null, BentoTheme.WARN, null);
                return;
            }
            PopoutWindow.copyText(repository.getDataDirectory().toAbsolutePath().toString());
            shell.toasts().info("⎘", "Path copied", repository.getDataDirectory().toAbsolutePath().toString(), null, BentoTheme.accentColor(), null);
        }

        @Override
        public void exportSessionCsv()
        {
            ProfitSession active = engine.getActiveSession();
            if (active == null)
            {
                shell.toasts().info("!", "Nothing is running", null, null, BentoTheme.WARN, null);
                return;
            }
            exportSession(active);
        }

        @Override
        public void exportHistoryCsv()
        {
            ProfitSession pick = pickHistorySession("Export which session?");
            if (pick != null)
            {
                exportSession(pick);
            }
        }

        @Override
        public void copyAsImage()
        {
            PopoutWindow.copyImage(BentoPanel.this);
            shell.toasts().info("⇪", "Sidebar copied as image", null, null, BentoTheme.accentColor(), null);
        }

        @Override
        public void restoreLastUndo()
        {
            com.gpmanager.model.ProfitTransaction restored = engine.restoreLastUndo(System.currentTimeMillis());
            shell.toasts().info(restored == null ? "!" : "↶", restored == null ? "Nothing to restore" : "Receipt restored",
                restored == null ? "no undone receipt on this session" : restored.getNote(), null,
                restored == null ? BentoTheme.WARN : BentoTheme.accentColor(), null);
            refresh();
        }

        @Override
        public void clearCompletedHistory()
        {
            java.util.List<ProfitSession> history = new java.util.ArrayList<>(engine.getHistory());
            if (history.isEmpty() || !confirm("Clear " + history.size() + " finished sessions? Free play keeps going.", "Clear history")
                || !recoveryExport(history))
            {
                return;
            }
            com.gpmanager.persistence.SavedState before = engine.createSavedState();
            writeBackup(false); // the recovery export the Danger zone promises
            engine.clearCompletedHistory();
            if (!commitState())
            {
                engine.restore(before);
                JOptionPane.showMessageDialog(BentoPanel.this, "History clear was not applied because the state could not be saved.", "Clear history", JOptionPane.ERROR_MESSAGE);
            }
            refresh();
        }

        @Override
        public void restartOverall()
        {
            if (engine.isCustomSessionActive())
            {
                shell.toasts().info("!", "End the session first", null, null, BentoTheme.WARN, null);
                return;
            }
            if (!confirm("Archive free play so far as a finished session and start a fresh, stopped one?", "Archive free play"))
            {
                return;
            }
            com.gpmanager.persistence.SavedState before = engine.createSavedState();
            engine.restartGeneral(System.currentTimeMillis(), true, true);
            if (!commitState())
            {
                engine.restore(before);
                JOptionPane.showMessageDialog(BentoPanel.this, "Archive was not applied because the state could not be saved.", "Archive free play", JOptionPane.ERROR_MESSAGE);
            }
            refresh();
        }

        @Override
        public void setRetention(com.gpmanager.ReceiptRetentionPeriod period)
        {
            setConfig("receiptRetentionDays", period);
        }

        @Override
        public void compactOlderNow()
        {
            int days = config.receiptRetentionDays().getDays();
            if (days <= 0)
            {
                return;
            }
            if (!confirm("Fold receipts older than " + days + " days into session summaries? Totals, runs and item aggregates stay; individual receipts go.", "Compact older"))
            {
                return;
            }
            int n = engine.compactOlderThan(days, System.currentTimeMillis());
            commitState();
            SessionNetCache.invalidate();
            shell.toasts().info("▤", n == 0 ? "Nothing to compact" : "Compacted " + n + (n == 1 ? " session" : " sessions"), null, null, BentoTheme.accentColor(), null);
            refresh();
        }

        @Override
        public void includeItem(int itemId)
        {
            rules.setExcluded(itemId, false);
            refresh();
        }

        @Override
        public void clearOverride(int itemId)
        {
            rules.setOverride(itemId, null);
            refresh();
        }

        @Override
        public void addOverride()
        {
            if (itemManager == null)
            {
                shell.toasts().info("!", "Item search unavailable in this client", null, null, BentoTheme.WARN, null);
                return;
            }
            String query = (String) JOptionPane.showInputDialog(BentoPanel.this, "Item name", "Add a price override",
                JOptionPane.PLAIN_MESSAGE, null, null, "");
            if (query == null || query.trim().isEmpty())
            {
                return;
            }
            java.util.List<net.runelite.http.api.item.ItemPrice> found = itemManager.search(query.trim());
            if (found.isEmpty())
            {
                shell.toasts().info("!", "No item called \"" + query.trim() + "\"", null, null, BentoTheme.WARN, null);
                return;
            }
            java.util.List<net.runelite.http.api.item.ItemPrice> top = found.subList(0, Math.min(12, found.size()));
            String[] labels = new String[top.size()];
            for (int i = 0; i < labels.length; i++)
            {
                labels[i] = top.get(i).getName() + " (" + top.get(i).getId() + ")";
            }
            String picked = (String) JOptionPane.showInputDialog(BentoPanel.this, "Which item?", "Add a price override",
                JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
            if (picked == null)
            {
                return;
            }
            net.runelite.http.api.item.ItemPrice item = top.get(java.util.Arrays.asList(labels).indexOf(picked));
            String value = (String) JOptionPane.showInputDialog(BentoPanel.this, "Price for " + item.getName() + " (gp each)",
                "Add a price override", JOptionPane.PLAIN_MESSAGE, null, null, "");
            if (value == null || value.trim().isEmpty())
            {
                return;
            }
            try
            {
                long gp = parseGp(value.trim().replace(",", "").replace("_", "").toLowerCase());
                rules.setOverride(item.getId(), (int) Math.max(0L, Math.min(Integer.MAX_VALUE, gp)));
                refresh();
            }
            catch (RuntimeException ex)
            {
                shell.toasts().info("!", "Not a price", value, null, BentoTheme.WARN, null);
            }
        }

        @Override
        public void setNotableDrop(long gp)
        {
            setConfig("notableDropThresholdGp", (int) Math.min(Integer.MAX_VALUE, gp));
        }

        @Override
        public void setAlertGoalReached(boolean on)
        {
            setConfig("alertGoalReached", on);
        }

        @Override
        public void setWealthMilestone(long gp)
        {
            setConfig("wealthMilestoneGp", (int) Math.min(Integer.MAX_VALUE, gp));
        }

        @Override
        public void setGeBookingObserved(boolean on)
        {
            setConfig("geBookingObserved", on);
            engine.setGeBookingMode(on ? com.gpmanager.engine.GeBookingMode.OBSERVED : com.gpmanager.engine.GeBookingMode.PROVENANCE_ONLY);
            refresh();
        }

        @Override
        public void setGeSellSpentIsNet(boolean on)
        {
            setConfig("geSellSpentIsNet", on);
            engine.setGeSellSpentIsNet(on);
            refresh();
        }

        @Override
        public void setSessionIdleAutoEnd(int minutes)
        {
            setConfig("sessionIdleAutoEndMinutes", Math.max(0, minutes));
            engine.setSessionIdleAutoEnd(Math.max(0, minutes));
            refresh();
        }

        @Override
        public void setReducedMotion(boolean on)
        {
            setConfig("reducedMotion", on);
        }

        @Override
        public void setExactFigures(boolean on)
        {
            setConfig("sidebarExactFigures", on);
            BentoTheme.setExactFigures(on);
            refresh();
        }

        @Override
        public void resetLayout()
        {
            com.gpmanager.model.TileLayout current = engine.getTileLayout();
            java.util.Map<String, com.gpmanager.model.TileLayout.PageLayout> pages = new java.util.LinkedHashMap<>(
                current == null ? java.util.Collections.emptyMap() : current.getPages());
            pages.put(ToolsSnapshot.LIVE_PAGE, new com.gpmanager.model.TileLayout.PageLayout());
            engine.setTileLayout(new com.gpmanager.model.TileLayout(pages));
            refresh();
        }

        @Override
        public void reportIssue()
        {
            copyDiagnostics();
            shell.toasts().info("⚑", "Report copied", "paste it into a new issue on the GP Manager repository", null, BentoTheme.INFO, null);
        }

        @Override
        public void setAccent(BentoTheme.Accent accent)
        {
            setConfig("sidebarAccent", accent);
        }

        @Override
        public void setPvpMode(PvpMode mode)
        {
            setConfig("pvpLayoutMode", mode);
        }

        @Override
        public void setNetGraph(boolean on)
        {
            setConfig("sidebarNetGraph", on);
        }

        @Override
        public void setBoundary(String key, BoundaryMode mode)
        {
            setConfig(key, mode);
        }

        @Override
        public void openPluginSettings()
        {
            configurationOpener.run();
        }

        @Override
        public void setDebugTrace(boolean on)
        {
            if (debugTrace != null)
            {
                debugTrace.setEnabled(on);
                debugTrace.record("diagnostics", on ? "debug trace enabled" : "debug trace disabled");
            }
            setConfig("enableDebugTrace", on);
        }

        @Override
        public void copyDiagnostics()
        {
            if (diagnostics == null)
            {
                shell.toasts().info("!", "Diagnostics unavailable", null, null, BentoTheme.WARN, null);
                return;
            }
            PopoutWindow.copyText(diagnostics.buildReport(BentoPanel.this));
            shell.toasts().info("⇪", "Diagnostics copied", "paste it into your report", null, BentoTheme.accentColor(), null);
        }

        @Override
        public void showShortcuts()
        {
            JOptionPane.showMessageDialog(BentoPanel.this,
                "Rail: click a tab, or ← → with the rail focused\n"
                    + "Ledger: click a row to expand · right-click for Correct\n"
                    + "Sessions: ☐ picks two sessions to compare\n"
                    + "Segmented switches: ← → with focus\n"
                    + "Any item row: click for the item sheet",
                "Shortcuts", JOptionPane.PLAIN_MESSAGE);
        }

        @Override
        public void showHelp()
        {
            JOptionPane.showMessageDialog(BentoPanel.this,
                "GP Manager books what actually leaves or enters your account.\n\n"
                    + "• Net = Gains − Supplies − Loss. Supplies are consumables; Loss is deaths, tax, fees, drops.\n"
                    + "• Transfers (bank, GE, pouches, coffers) are neutral and never part of Net.\n"
                    + "• Uncertain calls wait in Review; nothing is guessed into your figure.\n"
                    + "• Prices are captured when the receipt books; Wealth uses live prices and is never part of Net.\n"
                    + "• Overall is everything, always. Free play is whatever is not in a session.\n"
                    + "• Start a session to name a stretch (Vorkath, Wildy slayer, CoX); automatic boundaries can start one for you.\n"
                    + "• Compare lines up two sessions with deltas; same-name sessions fold on the Sessions page.",
                "How tracking works", JOptionPane.PLAIN_MESSAGE);
        }

        @Override
        public void deleteSession()
        {
            ProfitSession pick = pickHistorySession("Delete which session?");
            if (pick == null)
            {
                return;
            }
            if (!confirm("Delete \"" + pick.getName() + "\" and its receipts?", "Delete session")
                || !confirm("This cannot be undone. Delete for good?", "Confirm delete"))
            {
                return;
            }
            engine.deleteHistorySession(pick.getId());
            commitState();
            runs.clearSelection();
            refresh();
        }

        @Override
        public void resetTracking()
        {
            java.util.List<ProfitSession> all = new java.util.ArrayList<>(engine.getHistory());
            if (engine.getGeneralSession() != null)
            {
                all.add(engine.getGeneralSession());
            }
            if (engine.isCustomSessionActive())
            {
                all.add(engine.getActiveSession());
            }
            if (!confirm("Clear " + all.size() + " session(s), history, goals and corrections? Free play starts over.", "Reset tracking")
                || !recoveryExport(all))
            {
                return;
            }
            com.gpmanager.persistence.SavedState before = engine.createSavedState();
            writeBackup(false); // the recovery export the Danger zone promises
            engine.resetTrackingData(System.currentTimeMillis());
            if (!commitState())
            {
                engine.restore(before);
                JOptionPane.showMessageDialog(BentoPanel.this, "Reset was not applied because the state could not be saved.", "Reset tracking", JOptionPane.ERROR_MESSAGE);
            }
            runs.clearSelection();
            refresh();
        }

        @Override
        public void factoryReset()
        {
            java.util.List<ProfitSession> all = new java.util.ArrayList<>(engine.getHistory());
            if (engine.getGeneralSession() != null)
            {
                all.add(engine.getGeneralSession());
            }
            if (engine.isCustomSessionActive())
            {
                all.add(engine.getActiveSession());
            }
            String typed = JOptionPane.showInputDialog(BentoPanel.this,
                "This clears " + all.size() + " session(s) and resets GP Manager settings to a fresh install. Type RESET to continue.");
            if (!"RESET".equals(typed) || !recoveryExport(all))
            {
                return;
            }
            com.gpmanager.persistence.SavedState before = engine.createSavedState();
            writeBackup(false); // the recovery export the Danger zone promises
            engine.resetTrackingData(System.currentTimeMillis());
            if (!commitState())
            {
                engine.restore(before);
                JOptionPane.showMessageDialog(BentoPanel.this, "Reset was not applied because the state could not be saved.", "Factory reset", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (configManager != null)
            {
                com.gpmanager.GpManagerConfigMigration.resetToFreshInstall(configManager, config);
                engine.ensureArmedAfterConfigReseed(System.currentTimeMillis());
                commitState();
            }
            runs.clearSelection();
            refresh();
        }

        @Override
        public String itemName(int itemId)
        {
            return BentoPanel.this.itemName(itemId);
        }
    }

    /** Esc / Backspace anywhere in the panel: the same ‹ the page bar offers. */
    private void installBackKeys()
    {
        javax.swing.InputMap im = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        javax.swing.ActionMap am = getActionMap();
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "bento.back");
        am.put("bento.back", new javax.swing.AbstractAction()
        {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e)
            {
                goBack();
            }
        });
    }

    /** One step out: sub-page → its page; a Tools sub-page → Tools; otherwise nothing. */
    void goBack()
    {
        String page = shell.currentPage();
        if (SearchPage.ID.equals(page))
        {
            new SearchSource().back();
        }
        else if (ItemSheet.ID.equals(page))
        {
            showLedger();
        }
        else if (ComparePage.ID.equals(page))
        {
            shell.show(BentoShell.SESSIONS);
            refresh();
        }
        else if (BentoShell.TOOLS.equals(page) && tools.view() != ToolsPage.View.ROOT)
        {
            tools.show(ToolsPage.View.ROOT);
        }
    }

    /** ⌕: the palette over items in the Ledger's scope, sessions and settings; ‹ returns here. */
    void openSearchPalette()
    {
        String current = shell.currentPage();
        searchReturnPage = SearchPage.ID.equals(current) ? searchReturnPage : current;
        search.open("");
        shell.show(SearchPage.ID);
    }

    private final class SearchSource implements SearchPage.Source
    {
        @Override
        public java.util.List<SearchPage.Hit> hits(String query)
        {
            long now = System.currentTimeMillis();
            java.util.List<SearchPage.Hit> out = new java.util.ArrayList<>();
            // Items in the Ledger's scope.
            LedgerSnapshot scope = LedgerSnapshot.capture(engine, ledger.scope(), now, null);
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (LedgerSnapshot.Section section : LedgerSnapshot.Section.values())
            {
                for (LedgerSnapshot.Row row : scope.rows(section))
                {
                    if (row.itemId <= 0 || !seen.add(row.itemId))
                    {
                        continue;
                    }
                    int id = row.itemId;
                    String name = row.name;
                    out.add(new SearchPage.Hit("Items", Icon.sprite(sprite(id), "◈", BentoTheme.MUTED), name,
                        section.label + " · " + (scope.scope == LedgerSnapshot.Scope.TODAY ? "today" : "this session"),
                        row.counted ? Fmt.signed(row.value) : "0", row.counted ? BentoTheme.signColor(row.value) : BentoTheme.DIM,
                        () -> showItem(id, name)));
                }
            }
            // Sessions by name, category and activity.
            SessionsSnapshot sessions = SessionsSnapshot.capture(engine, now, false);
            for (SessionsSnapshot.Group g : sessions.groups)
            {
                for (SessionsSnapshot.Fold f : g.folds)
                {
                    for (SessionsSnapshot.SessionRow r : f.sessions)
                    {
                        String sub = (r.kind.isEmpty() ? "" : r.kind + " · ") + (r.activity.isEmpty() || r.activity.equalsIgnoreCase(r.name) ? "" : r.activity + " · ")
                            + Fmt.when(r.startedAt, now);
                        out.add(new SearchPage.Hit("Sessions", ActivityIcons.icon(r.activity.isEmpty() ? r.name : r.activity, r.kind, BentoPanel.this::sprite),
                            r.name, sub, Fmt.signed(r.net), BentoTheme.signColor(r.net), () -> new SessionsActions().openLedgerFor(r.id, r.name)));
                    }
                }
            }
            // Settings: every Tools row.
            out.addAll(tools.hits(() -> shell.show(BentoShell.TOOLS)));
            return out;
        }

        @Override
        public void back()
        {
            shell.show(shell.currentPage().equals(SearchPage.ID) ? searchReturnPage : shell.currentPage());
            refresh();
        }
    }

    private final class CompareActions implements ComparePage.Actions
    {
        @Override
        public void back()
        {
            shell.show(BentoShell.SESSIONS);
            refresh();
        }

        @Override
        public void popOut(SessionsSnapshot.Statement left, SessionsSnapshot.Statement right, ComparePage.Average average)
        {
            PopoutWindow.compare(left, right, average).setVisible(true);
        }
    }

    private final class SheetActions implements ItemSheet.Actions
    {
        @Override
        public void back()
        {
            showLedger();
        }

        @Override
        public void setPrice(int itemId, String name, @Nullable Integer currentOverride)
        {
            if (!rules.canWrite())
            {
                shell.toasts().info("!", "Settings unavailable", "cannot write price overrides here", null, BentoTheme.WARN, null);
                return;
            }
            String value = (String) JOptionPane.showInputDialog(BentoPanel.this,
                "Price per unit for " + name + " (gp). Leave empty to clear the override.", "Set price",
                JOptionPane.PLAIN_MESSAGE, null, null, currentOverride == null ? "" : Integer.toString(currentOverride));
            if (value == null)
            {
                return;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty())
            {
                rules.setOverride(itemId, null);
            }
            else
            {
                try
                {
                    long gp = parseGp(trimmed);
                    rules.setOverride(itemId, (int) Math.min(Integer.MAX_VALUE, Math.max(0L, gp)));
                }
                catch (NumberFormatException ex)
                {
                    shell.toasts().info("!", "Not a number", "use 200k, 1.5m or plain digits", null, BentoTheme.WARN, null);
                    return;
                }
            }
            refresh();
        }

        @Override
        public void toggleExclude(int itemId, String name, boolean currentlyExcluded)
        {
            if (!rules.canWrite())
            {
                shell.toasts().info("!", "Settings unavailable", "cannot write exclusions here", null, BentoTheme.WARN, null);
                return;
            }
            rules.setExcluded(itemId, !currentlyExcluded);
            refresh();
        }

        @Override
        public void openWiki(String name)
        {
            String slug = name.trim().replace(' ', '_');
            net.runelite.client.util.LinkBrowser.browse("https://oldschool.runescape.wiki/w/" + slug);
        }
    }

    static long parseGp(String text)
    {
        String t = text.trim().toLowerCase();
        double multiplier = 1d;
        if (t.endsWith("k"))
        {
            multiplier = 1_000d;
            t = t.substring(0, t.length() - 1);
        }
        else if (t.endsWith("m"))
        {
            multiplier = 1_000_000d;
            t = t.substring(0, t.length() - 1);
        }
        else if (t.endsWith("b"))
        {
            multiplier = 1_000_000_000d;
            t = t.substring(0, t.length() - 1);
        }
        return Math.round(Double.parseDouble(t) * multiplier);
    }

    private static JMenuItem item(String text)
    {
        JMenuItem item = new JMenuItem(text);
        item.setFont(BentoTheme.secondary());
        item.setForeground(BentoTheme.TEXT);
        item.setBackground(BentoTheme.ALT);
        item.setOpaque(true);
        return item;
    }
}
