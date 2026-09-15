package com.gpmanager.ui.bento;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.ContainerSnapshot;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Paints the real {@link BentoPanel} (not a mock) inside a live off-screen JFrame, fed by a
 * real engine session, and writes PNGs: waiting state and a live General session.
 * Run: {@code java -cp ... com.gpmanager.ui.bento.BentoPreview <outDir>} with headless=false.
 */
public final class BentoPreview
{
    static
    {
        com.gpmanager.persistence.JsonCodec.bind(new com.google.gson.Gson());
    }

    private static final int LOGS = 1519;
    private static final int RUNE = 554;
    private static final int SHARK = 385;
    private static final int PPOT = 2434;
    private static final int WHIP = 4151;

    private BentoPreview()
    {
    }

    public static void main(String[] args) throws Exception
    {
        Path out = Paths.get(args.length == 0 ? "build/bento-preview" : args[0]);
        Files.createDirectories(out);
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                GpManagerConfig config = new GpManagerConfig()
                {
                    @Override public int stabilizationTicks() { return 0; }
                    @Override public int minimumTransactionValue() { return 1; }
                };
                // 1. Waiting (no session) on a fresh engine.
                GpManagerEngine empty = new GpManagerEngine(BentoPreview::value, new TransactionClassifier(), config);
                BentoPanel waiting = new BentoPanel(empty, config, null, null, null);
                JFrame waitingHost = host(waiting);
                waiting.onActivate();
                waiting.refresh();
                write(waitingHost, waiting, out.resolve("bento-live-waiting.png"));
                waitingHost.dispose();

                // Earlier days of play, so Runs has history to group and compare.
                GpManagerEngine engine = new GpManagerEngine(BentoPreview::value, new TransactionClassifier(), config);
                long day = 24L * 3_600_000L;
                // Oldest first: the engine's clocks and rollups assume time never runs backwards.
                history(engine, "Vorkath", System.currentTimeMillis() - 70 * day, 55 * 60_000L, 1, 380_000L, 35_000L);
                history(engine, "Wintertodt", System.currentTimeMillis() - 26 * day, 90 * 60_000L, 1, 140_000L, 9_000L);
                history(engine, "Zulrah", System.currentTimeMillis() - 4 * day, 45 * 60_000L, 1, 260_000L, 22_000L);
                pvpHistory(engine, "Edgeville PvP", System.currentTimeMillis() - 3 * day - 2 * 3_600_000L,
                    new long[] {1_240_000L, 310_000L, 86_000L}, new long[] {420_000L});
                history(engine, "Vorkath", System.currentTimeMillis() - 2 * day - 3_600_000L, 61 * 60_000L, 1, 330_000L, 41_000L);
                history(engine, "Vorkath", System.currentTimeMillis() - day - 3 * 3_600_000L, 52 * 60_000L, 1, 410_000L, 38_000L);
                history(engine, "Vorkath", System.currentTimeMillis() - 5 * 3_600_000L, 48 * 60_000L, 1, 380_000L, 30_000L);
                history(engine, "Vorkath", System.currentTimeMillis() - 4 * 3_600_000L, 52 * 60_000L, 1, 410_000L, 38_000L);
                BentoPanel panel = new BentoPanel(engine, config, null, null, null);
                panel.bindWealth(() -> new com.gpmanager.model.WealthLocationsSnapshot(System.currentTimeMillis() - 12 * 60_000L,
                    java.util.Arrays.asList(
                        new com.gpmanager.model.WealthLocationSnapshot("ge_offers", "GE offers", com.gpmanager.model.WealthLocationSnapshot.Status.AVAILABLE,
                            1_720_000L, System.currentTimeMillis() - 12 * 60_000L, Collections.emptyList(), ""),
                        new com.gpmanager.model.WealthLocationSnapshot("collection_box", "Collection box", com.gpmanager.model.WealthLocationSnapshot.Status.AVAILABLE,
                            96_000L, System.currentTimeMillis() - 12 * 60_000L, Collections.emptyList(), ""))));
                JFrame host = host(panel);
                panel.onActivate();

                // 2. A live session with receipts, a split run and a goal.
                long start = System.currentTimeMillis() - 30 * 60_000L - 12_000L;
                // A fresh owner from `start`, so the history above does not stretch its clock.
                engine.restartGeneral(start, false, false);
                engine.togglePause(start);
                engine.setBaseline(new ContainerSnapshot(Collections.emptyMap()));
                long t = start + 60_000L;
                settle(engine, map(LOGS, 27L), t);
                engine.setBaseline(snap(map(LOGS, 27L, PPOT, 4L)));
                ProfitTransaction drank = settle(engine, map(LOGS, 27L, PPOT, 3L), t += 90_000L);
                if (drank != null)
                {
                    drank.setActionKind(ActionKind.DRINK);
                }
                engine.setBaseline(snap(map(LOGS, 27L, PPOT, 3L, SHARK, 2L)));
                ProfitTransaction ate = settle(engine, map(LOGS, 27L, PPOT, 3L, SHARK, 0L), t += 60_000L);
                if (ate != null)
                {
                    ate.setActionKind(ActionKind.EAT);
                }
                engine.setBaseline(snap(map(LOGS, 27L, PPOT, 3L, RUNE, 100L)));
                ProfitTransaction cast = settle(engine, map(LOGS, 27L, PPOT, 3L, RUNE, 55L), t += 30_000L);
                if (cast != null)
                {
                    cast.setActionKind(ActionKind.CAST);
                }
                engine.setBaseline(snap(map(LOGS, 27L, PPOT, 3L, RUNE, 55L)));
                settle(engine, map(LOGS, 55L, PPOT, 3L, RUNE, 55L), t += 120_000L);
                engine.setBaseline(snap(map(LOGS, 55L, PPOT, 3L, RUNE, 55L)));
                settle(engine, map(LOGS, 82L, PPOT, 3L, RUNE, 55L), t += 240_000L);
                engine.getActiveSession().setProfitTargetGp(200_000L);

                panel.refresh();
                write(host, panel, out.resolve("bento-live-session.png"));

                // 3. Ledger for the same session, with one row expanded.
                panel.shell().show(BentoShell.LEDGER);
                panel.refresh();
                panel.ledgerForPreview().expandFirstForPreview();
                write(host, panel, out.resolve("bento-ledger.png"));

                // 4. Item sheet.
                panel.openItemForPreview(PPOT, "Prayer potion(4)");
                write(host, panel, out.resolve("bento-item-sheet.png"));

                // 5. Sessions: free play live, earlier days below, one session expanded and two picks.
                panel.shell().show(BentoShell.SESSIONS);
                panel.refresh();
                SessionsSnapshot rs = panel.sessionsForPreview().lastForPreview();
                SessionsSnapshot.SessionRow first = rs.groups.get(0).folds.get(0).first();
                SessionsSnapshot.SessionRow second = rs.groups.get(1).folds.get(0).first();
                panel.sessionsForPreview().selectForPreview(first.id, first.asStatement, second.asStatement);
                write(host, panel, out.resolve("bento-sessions.png"));

                // 6. Compare the two picks.
                panel.compareForPreview(first.asStatement, second.asStatement);
                write(host, panel, out.resolve("bento-compare.png"));

                // 7. Insights: General (30d), PvP, Wealth.
                panel.shell().show(BentoShell.INSIGHTS);
                panel.refresh();
                write(host, panel, out.resolve("bento-insights-general.png"));
                panel.insightsForPreview().showRange(InsightsSnapshot.Range.ALL);
                panel.refresh();
                write(host, panel, out.resolve("bento-insights-lifetime.png"));
                panel.insightsForPreview().showRange(InsightsSnapshot.Range.D30);
                panel.refresh();
                panel.insightsForPreview().showMode(InsightsPage.Mode.PVP);
                write(host, panel, out.resolve("bento-insights-pvp.png"));
                panel.insightsForPreview().showMode(InsightsPage.Mode.WEALTH);
                write(host, panel, out.resolve("bento-insights-wealth.png"));
                panel.insightsForPreview().showMode(InsightsPage.Mode.GENERAL);

                // 8. Tools: one uncertain receipt waiting, a party, an excluded item.
                ProfitTransaction odd = new ProfitTransaction(System.currentTimeMillis() - 90_000L, 29 * 60_000L,
                    com.gpmanager.model.TransactionType.CONSUMPTION, com.gpmanager.model.TrackingContext.GENERIC,
                    "Unknown change", "General", true,
                    Collections.singletonList(new ItemFlow(RUNE, "Fire rune", -20, 4, -80, ItemPriceSource.GRAND_EXCHANGE)),
                    com.gpmanager.model.ClassificationConfidence.UNCERTAIN, "Mixed change without a stronger context", null);
                engine.getActiveSession().addTransaction(odd, 500);
                com.gpmanager.model.PartyProfitSummary party = new com.gpmanager.model.PartyProfitSummary(true, 3, 0L, 0L, 1_240_000L, 900_000L, 900_000L,
                    java.util.Arrays.asList(
                        new com.gpmanager.model.PartyProfitSummary.Member("Rampageous", 0L, 0L, 612_000L, 1_100_000L, 1_100_000L, 40, true, true, true, true, System.currentTimeMillis()),
                        new com.gpmanager.model.PartyProfitSummary.Member("Zezima", 0L, 0L, 628_000L, 700_000L, 700_000L, 30, false, true, true, true, System.currentTimeMillis()),
                        com.gpmanager.model.PartyProfitSummary.Member.notReporting("Woox", false)));
                panel.bindParty(() -> party);
                panel.shell().show(BentoShell.TOOLS);
                panel.refresh();
                write(host, panel, out.resolve("bento-tools.png"));
                host.setSize(BentoTheme.OWNED_WIDTH, 1100);
                host.validate();
                write(host, panel, out.resolve("bento-tools-full.png"));
                host.setSize(BentoTheme.OWNED_WIDTH, 640);
                host.validate();
                // Sub-pages: Storage, Review, Alerts; then a search.
                panel.toolsForPreview().show(ToolsPage.View.STORAGE);
                write(host, panel, out.resolve("bento-tools-storage.png"));
                panel.toolsForPreview().show(ToolsPage.View.REVIEW);
                write(host, panel, out.resolve("bento-tools-review.png"));
                panel.toolsForPreview().show(ToolsPage.View.ALERTS);
                write(host, panel, out.resolve("bento-tools-alerts.png"));
                panel.toolsForPreview().show(ToolsPage.View.ROOT);
                panel.toolsForPreview().searchForPreview("pvp");
                write(host, panel, out.resolve("bento-tools-search.png"));
                panel.toolsForPreview().searchForPreview("");

                // The search palette over items, sessions and settings.
                panel.openSearchPalette();
                panel.searchForPreview().searchForPreview("vork");
                write(host, panel, out.resolve("bento-search.png"));
                panel.shell().show(BentoShell.TOOLS);

                // 9. Live in the PvP layout: in the Wilderness, skulled, Protect Item on, risk known; party tile on.
                panel.shell().show(BentoShell.LIVE);
                panel.observePvp(true, true, false, true, true, 1_240_000L);
                panel.refresh();
                write(host, panel, out.resolve("bento-live-pvp.png"));
                panel.observePvp(false, false, false, false, false, 0L);

                // 10. Sessions with a session running (the current card), then the full grouped history.
                panel.startSessionNamed("Vorkath", false, SessionKind.BOSSING);
                panel.shell().show(BentoShell.SESSIONS);
                panel.sessionsForPreview().clearSelection();
                panel.refresh();
                write(host, panel, out.resolve("bento-sessions-current.png"));
                panel.sessionsForPreview().showAllForPreview(true);
                host.setSize(BentoTheme.OWNED_WIDTH, 1100);
                host.validate();
                write(host, panel, out.resolve("bento-sessions-all.png"));
                host.dispose();
                System.out.println("wrote " + out.toAbsolutePath());
            }
            catch (Exception ex)
            {
                throw new RuntimeException(ex);
            }
        });
    }

    /** Plays a finished custom session into history at an earlier time. */
    private static void history(GpManagerEngine engine, String name, long startedAt, long length, int runs, long loot, long supplies)
    {
        engine.startCustomSession(name, com.gpmanager.model.SessionMode.AUTO, startedAt);
        // The category the picker would have written.
        engine.setActiveSessionCategory((name.contains("Wintertodt") ? SessionKind.SKILLING : SessionKind.BOSSING).category);
        long t = startedAt;
        long perRun = length / runs;
        for (int r = 0; r < runs; r++)
        {
            engine.setBaseline(snap(map(PPOT, 4L)));
            ProfitTransaction drank = settle(engine, map(PPOT, 4L - Math.max(1L, supplies / runs / 12_000L)), t += 60_000L);
            if (drank != null)
            {
                drank.setActionKind(ActionKind.DRINK);
            }
            engine.setBaseline(snap(map(PPOT, 3L)));
            settle(engine, map(PPOT, 3L, LOGS, loot / runs / 250L), t += perRun - 60_000L);
        }
        engine.finishCustomSession(startedAt + length);
        parkFreePlay(engine, startedAt + length);
    }

    /** Between fixture sessions nobody is playing: pause free play so Overall's Total time stays honest. */
    private static void parkFreePlay(GpManagerEngine engine, long at)
    {
        com.gpmanager.model.ProfitSession general = engine.getGeneralSession();
        if (general != null && !general.isPaused())
        {
            engine.togglePause(at);
        }
    }

    /** A finished PK session: each kill books loot after a supply cost, each death books a loss. */
    private static void pvpHistory(GpManagerEngine engine, String name, long startedAt, long[] kills, long[] deaths)
    {
        engine.startCustomSession(name, com.gpmanager.model.SessionMode.PK, startedAt);
        com.gpmanager.model.ProfitSession session = engine.getActiveSession();
        long t = startedAt;
        int n = 0;
        for (long value : kills)
        {
            t += 6 * 60_000L;
            ProfitTransaction supply = new ProfitTransaction(t, com.gpmanager.model.TransactionType.PK_SUPPLY_COST,
                com.gpmanager.model.TrackingContext.GENERIC, "Supplies", true,
                Collections.singletonList(new ItemFlow(SHARK, "Shark", -3, 800, -2_400, ItemPriceSource.GRAND_EXCHANGE)));
            session.addTransaction(supply, 500);
            com.gpmanager.model.PkEncounter kill = session.addPkEncounter(com.gpmanager.model.PkEncounterType.KILL, t + 1_000L,
                "Kill: Rival " + (++n), com.gpmanager.model.ClassificationConfidence.CONFIRMED, "RuneLite player loot");
            session.attachRecentCostsToEncounter(kill.getId(), t + 1_000L, 10 * 60_000L);
            ProfitTransaction loot = new ProfitTransaction(t + 2_000L, t + 2_000L - startedAt, com.gpmanager.model.TransactionType.PK_LOOT,
                com.gpmanager.model.TrackingContext.PK_LOOT, "PK loot", "PKing", true,
                Collections.singletonList(new ItemFlow(WHIP, "Abyssal whip", 1, (int) Math.min(Integer.MAX_VALUE, value), value, ItemPriceSource.GRAND_EXCHANGE)));
            session.addTransaction(loot, 500);
            session.attachTransactionToEncounter(loot.getId(), kill.getId(), false);
        }
        for (long value : deaths)
        {
            t += 8 * 60_000L;
            com.gpmanager.model.PkEncounter death = session.addPkEncounter(com.gpmanager.model.PkEncounterType.DEATH, t,
                "Death: Rival", com.gpmanager.model.ClassificationConfidence.CONFIRMED, "Died in the Wilderness");
            ProfitTransaction loss = new ProfitTransaction(t + 500L, t + 500L - startedAt, com.gpmanager.model.TransactionType.PK_DEATH_LOSS,
                com.gpmanager.model.TrackingContext.GENERIC, "Death: items lost", "PKing", true,
                Collections.singletonList(new ItemFlow(RUNE, "Risked gear", -1, (int) Math.min(Integer.MAX_VALUE, value), -value, ItemPriceSource.GRAND_EXCHANGE)));
            session.addTransaction(loss, 500);
            session.attachTransactionToEncounter(loss.getId(), death.getId(), false);
        }
        engine.finishCustomSession(t + 5 * 60_000L);
        parkFreePlay(engine, t + 5 * 60_000L);
    }

    private static JFrame host(BentoPanel panel)
    {
        JFrame host = new JFrame("Bento preview host");
        host.setUndecorated(true);
        host.setType(Window.Type.UTILITY);
        host.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        host.getContentPane().add(panel);
        host.pack();
        host.setSize(BentoTheme.OWNED_WIDTH, 640);
        host.setLocation(-10_000, -10_000);
        host.setVisible(true);
        host.validate();
        return host;
    }

    private static void write(JFrame host, BentoPanel panel, Path file) throws Exception
    {
        writeOnce(host, panel, file);
        String name = file.getFileName().toString();
        if (!name.contains("-full") && !name.contains("-all") && host.getHeight() < 1000)
        {
            int keep = host.getHeight();
            host.setSize(BentoTheme.OWNED_WIDTH, 1400);
            host.validate();
            writeOnce(host, panel, file.resolveSibling(name.replace(".png", "-tall.png")));
            host.setSize(BentoTheme.OWNED_WIDTH, keep);
            host.validate();
        }
    }

    private static void writeOnce(JFrame host, BentoPanel panel, Path file) throws Exception
    {
        host.validate();
        panel.doLayout();
        BufferedImage image = new BufferedImage(BentoTheme.OWNED_WIDTH, Math.max(1, panel.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try
        {
            panel.paint(g);
        }
        finally
        {
            g.dispose();
        }
        ImageIO.write(image, "png", file.toFile());
    }

    private static List<ItemFlow> value(Map<Integer, Long> deltas)
    {
        List<ItemFlow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> d : deltas.entrySet())
        {
            int id = d.getKey();
            int price = id == LOGS ? 48 : id == RUNE ? 4 : id == PPOT ? 8_700 : 800;
            String name = id == LOGS ? "Willow logs" : id == RUNE ? "Fire rune" : id == PPOT ? "Prayer potion(4)" : "Shark";
            flows.add(new ItemFlow(id, name, d.getValue(), price, d.getValue() * price, ItemPriceSource.GRAND_EXCHANGE));
        }
        return flows;
    }

    private static ProfitTransaction settle(GpManagerEngine engine, Map<Integer, Long> items, long now)
    {
        engine.markInventoryDirty();
        ProfitTransaction first = engine.processIfDirty(snap(items), now);
        ProfitTransaction settled = engine.processIfDirty(snap(items), now + 600L);
        return settled == null ? first : settled;
    }

    private static ContainerSnapshot snap(Map<Integer, Long> items)
    {
        return new ContainerSnapshot(items);
    }

    private static Map<Integer, Long> map(Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((Integer) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }
}
