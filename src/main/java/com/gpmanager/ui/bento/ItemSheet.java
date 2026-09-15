package com.gpmanager.ui.bento;

import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TransactionType;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Item sheet (SIDEBAR_BENTO.md §4): one place for every item — all-time totals across
 * sessions, price provenance and override, where it goes, by activity, actions.
 */
public final class ItemSheet implements BentoShell.Page
{
    public static final String ID = "item";

    public interface Actions
    {
        void back();

        void setPrice(int itemId, String name, @Nullable Integer currentOverride);

        void toggleExclude(int itemId, String name, boolean currentlyExcluded);

        void openWiki(String name);
    }

    /** All-time read model for one item. */
    public static final class Snapshot
    {
        public final int itemId;
        public final String name;
        public final long netValue;
        public final long receivedQty;
        public final long usedQty;
        public final int sessions;
        public final long firstSeen;
        public final int latestPrice;
        public final String latestSource;
        public final long latestCapturedAt;
        @Nullable
        public final Integer override;
        public final boolean excluded;
        public final Map<String, Long> byVerb;
        public final Map<String, Long> byActivity;

        Snapshot(int itemId, String name, long netValue, long receivedQty, long usedQty, int sessions, long firstSeen,
            int latestPrice, String latestSource, long latestCapturedAt, @Nullable Integer override, boolean excluded,
            Map<String, Long> byVerb, Map<String, Long> byActivity)
        {
            this.itemId = itemId;
            this.name = name;
            this.netValue = netValue;
            this.receivedQty = receivedQty;
            this.usedQty = usedQty;
            this.sessions = sessions;
            this.firstSeen = firstSeen;
            this.latestPrice = latestPrice;
            this.latestSource = latestSource;
            this.latestCapturedAt = latestCapturedAt;
            this.override = override;
            this.excluded = excluded;
            this.byVerb = byVerb;
            this.byActivity = byActivity;
        }

        public static Snapshot capture(GpManagerEngine engine, int itemId, String fallbackName,
            @Nullable Integer override, boolean excluded)
        {
            List<ProfitSession> sessions = new ArrayList<>(engine.getHistory());
            ProfitSession active = engine.getActiveSession();
            if (active != null && !sessions.contains(active))
            {
                sessions.add(active);
            }
            String name = fallbackName;
            long net = 0L;
            long received = 0L;
            long used = 0L;
            int touched = 0;
            long firstSeen = Long.MAX_VALUE;
            int latestPrice = 0;
            String latestSource = "";
            long latestCaptured = 0L;
            long latestTs = -1L;
            Map<String, Long> byVerb = new LinkedHashMap<>();
            Map<String, Long> byActivity = new LinkedHashMap<>();
            for (ProfitSession s : sessions)
            {
                if (s == null)
                {
                    continue;
                }
                boolean hit = false;
                for (ProfitTransaction t : s.getTransactions())
                {
                    if (t == null)
                    {
                        continue;
                    }
                    for (ItemFlow f : t.getFlows())
                    {
                        if (f == null || f.getItemId() != itemId)
                        {
                            continue;
                        }
                        hit = true;
                        name = f.getItemName();
                        firstSeen = Math.min(firstSeen, t.getTimestampEpochMillis());
                        boolean neutral = t.getType() == TransactionType.TRANSFER;
                        if (f.getQuantityDelta() > 0L)
                        {
                            received += f.getQuantityDelta();
                        }
                        else
                        {
                            used += -f.getQuantityDelta();
                        }
                        if (t.isCounted() && !neutral)
                        {
                            net += f.getValueDelta();
                            String activity = t.getActivityName();
                            byActivity.merge(activity == null || activity.isEmpty() ? "General" : activity, f.getValueDelta(), Long::sum);
                        }
                        String verb = neutral ? "transferred"
                            : t.getActionKind() != null ? t.getActionKind().completedVerb().toLowerCase()
                            : f.getQuantityDelta() >= 0L ? "received" : "used";
                        byVerb.merge(verb, Math.abs(f.getQuantityDelta()), Long::sum);
                        if (t.getTimestampEpochMillis() > latestTs)
                        {
                            latestTs = t.getTimestampEpochMillis();
                            latestPrice = f.getUnitPrice();
                            latestSource = f.getPriceSource() == null ? "" : f.getPriceSource().toString();
                            latestCaptured = f.getPriceCapturedAtEpochMillis();
                        }
                    }
                }
                if (hit)
                {
                    touched++;
                }
            }
            return new Snapshot(itemId, name, net, received, used, touched, firstSeen == Long.MAX_VALUE ? 0L : firstSeen,
                latestPrice, latestSource, latestCaptured, override, excluded,
                Collections.unmodifiableMap(byVerb), Collections.unmodifiableMap(byActivity));
        }
    }

    private final Actions actions;
    private final Function<Integer, BufferedImage> sprites;
    private final JPanel bar = new JPanel();
    private final javax.swing.JLabel title = Tile.label("", BentoTheme.font(java.awt.Font.BOLD, 13f), BentoTheme.TEXT);
    private final Controls.IconButton wiki = new Controls.IconButton("W", "Open wiki page", true);
    private final JPanel body = BentoShell.stack();
    @Nullable
    private Snapshot current;

    public ItemSheet(Actions actions, Function<Integer, BufferedImage> sprites)
    {
        this.actions = actions;
        this.sprites = sprites == null ? id -> null : sprites;
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setOpaque(false);
        Controls.Button back = new Controls.Button("‹", Controls.Button.Kind.GHOST);
        back.onClick(actions::back);
        bar.add(back);
        bar.add(Box.createHorizontalStrut(2));
        bar.add(title);
        bar.add(Box.createHorizontalGlue());
        wiki.onClick(() ->
        {
            if (current != null)
            {
                actions.openWiki(current.name);
            }
        });
        bar.add(wiki);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, bar.getPreferredSize().height));
    }

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public JComponent pageBar()
    {
        return bar;
    }

    @Override
    public JComponent body()
    {
        return body;
    }

    public void apply(Snapshot s)
    {
        current = s;
        title.setText(s.name);
        body.removeAll();

        HeroCard block = new HeroCard().title("All time", null)
            .status(s.firstSeen > 0L ? "since " + dayOf(s.firstSeen) : "", null, true)
            .hero(Fmt.exactSigned(s.netValue), BentoTheme.signColor(s.netValue)).caption("Total")
            .strip(Arrays.asList(
                new StatGrid.Cell(Icon.glyph("", BentoTheme.MUTED), "Received", Fmt.exact(s.receivedQty), BentoTheme.TEXT),
                new StatGrid.Cell(Icon.glyph("", BentoTheme.MUTED), "Used", Fmt.exact(s.usedQty), BentoTheme.TEXT),
                new StatGrid.Cell(Icon.glyph("", BentoTheme.MUTED), "Sessions", Integer.toString(s.sessions), BentoTheme.TEXT)));
        block.setToolTipText("Counted net for this item across every session · " + Fmt.exactSigned(s.netValue) + " gp");
        BentoShell.stackAdd(body, block);

        Tile price = new Tile().header("Price", null, null);
        price.kv("Now", s.latestPrice > 0 ? Fmt.exact(s.latestPrice) + " gp" : "unpriced");
        price.kv("Source", s.latestSource + (s.latestCapturedAt > 0L ? " · " + timeOf(s.latestCapturedAt) : ""));
        price.kv("Override", s.override == null ? "none" : Fmt.exact(s.override) + " gp", s.override == null ? BentoTheme.MUTED : BentoTheme.accentColor());
        BentoShell.stackAdd(body, price);

        if (!s.byVerb.isEmpty())
        {
            Tile where = new Tile().header("Where it goes", null, null);
            for (Map.Entry<String, Long> e : s.byVerb.entrySet())
            {
                where.kv(capitalize(e.getKey()), Fmt.exact(e.getValue()));
            }
            BentoShell.stackAdd(body, where);
        }
        if (!s.byActivity.isEmpty())
        {
            Tile by = new Tile().header("By activity", null, null);
            List<Map.Entry<String, Long>> entries = new ArrayList<>(s.byActivity.entrySet());
            entries.sort((a, b) -> Long.compare(Math.abs(b.getValue()), Math.abs(a.getValue())));
            int shown = 0;
            for (Map.Entry<String, Long> e : entries)
            {
                if (shown++ == 6)
                {
                    break;
                }
                by.kv(e.getKey(), Fmt.signed(e.getValue()), BentoTheme.signColor(e.getValue()));
            }
            BentoShell.stackAdd(body, by);
        }

        Controls.Button setPrice = new Controls.Button(s.override == null ? "Set price…" : "Change price…", Controls.Button.Kind.DEFAULT);
        setPrice.onClick(() -> actions.setPrice(s.itemId, s.name, s.override));
        Controls.Button exclude = new Controls.Button(s.excluded ? "Include again" : "Exclude", Controls.Button.Kind.DEFAULT);
        exclude.setToolTipText(s.excluded ? "This item is excluded from accounting" : "Never count this item");
        exclude.onClick(() -> actions.toggleExclude(s.itemId, s.name, s.excluded));
        JPanel buttons = Tile.buttons(setPrice, exclude);
        BentoShell.stackAdd(body, buttons);
        if (s.excluded)
        {
            BentoShell.stackAdd(body, new Notice("∅", "Excluded from accounting", "never counted until included again", null, Notice.Tone.WARN));
        }
        body.revalidate();
        body.repaint();
    }

    private static String dayOf(long epochMillis)
    {
        return java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"));
    }

    private static String timeOf(long epochMillis)
    {
        return java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault())
            .toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static String capitalize(String s)
    {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
