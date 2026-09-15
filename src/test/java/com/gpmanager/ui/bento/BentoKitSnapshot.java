package com.gpmanager.ui.bento;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import javax.imageio.ImageIO;
import javax.swing.JPanel;

/**
 * Renders a Live-like column built from the kit to a PNG for eyeballing. Not a test: run
 * with {@code -Dbento.snapshot=<path>} from the fixture test or directly via main.
 */
public final class BentoKitSnapshot
{
    private BentoKitSnapshot()
    {
    }

    public static void main(String[] args) throws Exception
    {
        File out = new File(args.length > 0 ? args[0] : "build/bento-kit.png");
        out.getParentFile().mkdirs();
        BentoShell shell = new BentoShell();
        shell.addPage(new BentoShell.Page()
        {
            @Override
            public String id()
            {
                return BentoShell.LIVE;
            }

            @Override
            public javax.swing.JComponent pageBar()
            {
                JPanel bar = new JPanel();
                bar.setLayout(new javax.swing.BoxLayout(bar, javax.swing.BoxLayout.X_AXIS));
                bar.setOpaque(false);
                Controls.Button pause = new Controls.Button("❚❚ Pause tracking", Controls.Button.Kind.STOP);
                pause.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, pause.getPreferredSize().height));
                bar.add(pause);
                bar.add(javax.swing.Box.createHorizontalStrut(5));
                bar.add(new Controls.Button("⑂", Controls.Button.Kind.DEFAULT));
                bar.add(javax.swing.Box.createHorizontalStrut(5));
                bar.add(new Controls.Button("Session ▾", Controls.Button.Kind.DEFAULT));
                return bar;
            }

            @Override
            public javax.swing.JComponent body()
            {
                JPanel body = BentoShell.stack();
                JPanel ctx = new JPanel();
                ctx.setLayout(new javax.swing.BoxLayout(ctx, javax.swing.BoxLayout.X_AXIS));
                ctx.setOpaque(false);
                ctx.add(new Controls.Segmented(Arrays.asList("General", "PvP")));
                ctx.add(javax.swing.Box.createHorizontalGlue());
                ctx.add(Tile.label("Woodcutting", BentoTheme.bodyBold(), BentoTheme.TEXT));
                ctx.add(javax.swing.Box.createHorizontalStrut(6));
                ctx.add(new Controls.IconButton("⌕", "Search", false));
                ctx.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, ctx.getPreferredSize().height));
                BentoShell.stackAdd(body, ctx);

                StatBlock stat = new StatBlock()
                    .header("Net · General", "")
                    .clock("30:12", StatBlock.ClockState.LIVE, "")
                    .net(124_318L, false)
                    .micro(Arrays.asList(
                        new StatBlock.Micro("Gains", "160.2k"),
                        new StatBlock.Micro("Loss", "36.0k", BentoTheme.NEGATIVE),
                        new StatBlock.Micro("GP/h", "248k", "▲12%", null, BentoTheme.POSITIVE)),
                        Collections.emptyList());
                BentoShell.stackAdd(body, stat);

                JPanel runLine = Tile.line(
                    Tile.label("RUN 3 ★ BEST  ", BentoTheme.micro(), BentoTheme.DIM),
                    Tile.label("+41.2k · 306k/h", BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary), BentoTheme.POSITIVE));
                BentoShell.stackAdd(body, runLine);
                Ribbon ribbon = new Ribbon().segments(Arrays.asList(
                    new Ribbon.Segment("r1", 0, 0.24, false, "Run 1"),
                    new Ribbon.Segment("r2", 0.24, 0.5, false, "Run 2"),
                    new Ribbon.Segment("r3", 0.5, 0.72, true, "Run 3")),
                    Arrays.asList(new Ribbon.Mark(0.24, Ribbon.MarkKind.BANK), new Ribbon.Mark(0.5, Ribbon.MarkKind.DEATH)));
                BentoShell.stackAdd(body, ribbon);
                BentoShell.stackAdd(body, new GoalLine().set("200k", 0.62, 0.8, "62%", "~18m"));
                BentoShell.stackAdd(body, new Notice("◈", "Neutral zone", "The Gauntlet · nothing books", null, Notice.Tone.ACCENT));
                BentoShell.stackAdd(body, new Notice("☠", "Reclaim pending", "Vorkath · 100k on collect", "›", Notice.Tone.WARN));
                BentoShell.stackAdd(body, new Notice("◔", "Blowpipe", "1,240 → 803 · Trident unmeasured", "−12.9k", Notice.Tone.PLAIN));
                BentoShell.stackAdd(body, new Tile().header("Encounter", "41 · 58.7k/kill · ×12", BentoTheme.MUTED).text("Vorkath", BentoTheme.TEXT));
                ListBlock recent = new ListBlock().header("Recent", "ledger ›", null)
                    .row(new ItemRow().name("Willow logs", "×27").verb("received").value(1_200L))
                    .row(new ItemRow().name("Prayer potion", "1 dose").verb("drank").value(-2_100L))
                    .row(new ItemRow().name("Shark", "×2").verb("ate").value(-1_600L))
                    .row(new ItemRow().name("Bank", "4 in · 2 out").verb("neutral").neutral())
                    .row(new ItemRow().name("Fire rune", "×45").tag("quiet", BentoTheme.QUIET).verb("runes used").value(-180L));
                BentoShell.stackAdd(body, recent);
                return body;
            }
        });
        shell.setSize(BentoTheme.OWNED_WIDTH, 640);
        shell.doLayout();
        layoutTree(shell);
        shell.toasts().undo("Correction applied", "Fire rune → ignored", () -> { }, 8);
        BufferedImage image = new BufferedImage(BentoTheme.OWNED_WIDTH, 640, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try
        {
            shell.paint(g);
        }
        finally
        {
            g.dispose();
        }
        ImageIO.write(image, "png", out);
        System.out.println("wrote " + out.getAbsolutePath());
    }

    private static void layoutTree(java.awt.Container container)
    {
        container.doLayout();
        for (java.awt.Component child : container.getComponents())
        {
            if (child instanceof java.awt.Container)
            {
                layoutTree((java.awt.Container) child);
            }
        }
    }
}
