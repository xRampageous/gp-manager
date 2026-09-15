package com.gpmanager.ui.bento;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

/**
 * A resizable Swing window for the one thing that earns width: the two-column compare.
 * Offers Copy as image (clipboard) and Copy CSV.
 */
public final class PopoutWindow
{
    private PopoutWindow()
    {
    }

    public static JFrame compare(SessionsSnapshot.Statement left, SessionsSnapshot.Statement right, @Nullable ComparePage.Average average)
    {
        JFrame frame = new JFrame("GP Manager — Compare");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BentoTheme.BG);
        root.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel columns = new JPanel();
        columns.setLayout(new BoxLayout(columns, BoxLayout.X_AXIS));
        columns.setOpaque(false);
        columns.add(column(left));
        columns.add(Box.createHorizontalStrut(12));
        columns.add(column(right));
        root.add(columns, BorderLayout.CENTER);

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.X_AXIS));
        south.setOpaque(false);
        south.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 0, 0, 0));
        String avg = average == null || average.sessions == 0 ? "" :
            "Same activity average: " + Fmt.rate(average.gpPerHour) + "/h over " + average.sessions + " sessions";
        south.add(Tile.label(avg, BentoTheme.secondary(), BentoTheme.MUTED));
        south.add(Box.createHorizontalGlue());
        Controls.Button csv = new Controls.Button("Copy CSV", Controls.Button.Kind.DEFAULT);
        csv.onClick(() -> copyText(csv(left, right)));
        Controls.Button image = new Controls.Button("Copy as image", Controls.Button.Kind.DEFAULT);
        image.onClick(() -> copyImage(root));
        south.add(csv);
        south.add(Box.createHorizontalStrut(6));
        south.add(image);
        root.add(south, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(520, 300));
        frame.pack();
        frame.setSize(Math.max(560, frame.getWidth()), Math.max(340, frame.getHeight()));
        frame.setLocationByPlatform(true);
        return frame;
    }

    private static JComponent column(SessionsSnapshot.Statement s)
    {
        Tile tile = new Tile();
        tile.row(Tile.label(s.title + (s.subtitle.isEmpty() ? "" : " · " + s.subtitle), BentoTheme.bodyBold(), BentoTheme.TEXT));
        tile.gap(4);
        tile.kv("Net", Fmt.exactSigned(s.net), BentoTheme.signColor(s.net));
        tile.kv("GP/h", s.rateAvailable ? Fmt.exact(s.gpPerHour) : "—");
        tile.kv("Gains", Fmt.exact(s.loot));
        tile.kv("Supplies", Fmt.exact(s.supplies), BentoTheme.NEGATIVE);
        tile.kv("Loss", Fmt.exact(s.loss), BentoTheme.NEGATIVE);
        if (s.kills > 0 || s.deaths > 0)
        {
            tile.kv("Kills · deaths", s.kills + " · " + s.deaths);
        }
        tile.kv("Duration", Fmt.duration(s.durationMillis));
        if (s.leftOnGround != null)
        {
            tile.kv("Left on ground", Fmt.exact(s.leftOnGround), BentoTheme.DIM);
        }
        tile.setPreferredSize(new Dimension(250, tile.getPreferredSize().height));
        return tile;
    }

    static String csv(SessionsSnapshot.Statement l, SessionsSnapshot.Statement r)
    {
        StringBuilder sb = new StringBuilder("metric,").append(q(l.title)).append(',').append(q(r.title)).append('\n');
        sb.append("net,").append(l.net).append(',').append(r.net).append('\n');
        sb.append("gp_per_hour,").append(l.rateAvailable ? l.gpPerHour : "").append(',').append(r.rateAvailable ? r.gpPerHour : "").append('\n');
        sb.append("loot,").append(l.loot).append(',').append(r.loot).append('\n');
        sb.append("supplies,").append(l.supplies).append(',').append(r.supplies).append('\n');
        sb.append("loss,").append(l.loss).append(',').append(r.loss).append('\n');
        sb.append("kills,").append(l.kills).append(',').append(r.kills).append('\n');
        sb.append("deaths,").append(l.deaths).append(',').append(r.deaths).append('\n');
        sb.append("duration_ms,").append(l.durationMillis).append(',').append(r.durationMillis).append('\n');
        sb.append("left_on_ground,").append(l.leftOnGround == null ? "" : l.leftOnGround).append(',')
            .append(r.leftOnGround == null ? "" : r.leftOnGround).append('\n');
        return sb.toString();
    }

    private static String q(String s)
    {
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    static void copyText(String text)
    {
        try
        {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text), null);
        }
        catch (RuntimeException ignored)
        {
            // headless or clipboard unavailable
        }
    }

    static void copyImage(JComponent component)
    {
        int w = Math.max(1, component.getWidth());
        int h = Math.max(1, component.getHeight());
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try
        {
            component.paint(g);
        }
        finally
        {
            g.dispose();
        }
        try
        {
            Transferable transferable = new Transferable()
            {
                @Override
                public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors()
                {
                    return new java.awt.datatransfer.DataFlavor[] {java.awt.datatransfer.DataFlavor.imageFlavor};
                }

                @Override
                public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor)
                {
                    return java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor);
                }

                @Override
                public Object getTransferData(java.awt.datatransfer.DataFlavor flavor)
                {
                    return image;
                }
            };
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(transferable, null);
        }
        catch (RuntimeException ignored)
        {
            // headless or clipboard unavailable
        }
    }
}
