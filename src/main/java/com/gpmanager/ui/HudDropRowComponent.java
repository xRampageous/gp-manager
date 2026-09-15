package com.gpmanager.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.TextComponent;

/**
 * One measured HUD row: fixed icon slot + truncated name/qty + right-aligned value.
 * Keeps sprite and text on the same horizontal line (unlike stacking ImageComponent
 * above a LineComponent in PanelComponent).
 */
public final class HudDropRowComponent implements LayoutableRenderableEntity
{
    public static final int ICON_SLOT = 18;

    private BufferedImage icon;
    private String leftText = "";
    private String rightText = "";
    private Color leftColor = Color.WHITE;
    private Color rightColor = Color.WHITE;
    private Font font;
    private float iconScale = 1f;
    private Dimension preferredSize;
    private Point preferredLocation = new Point();
    private final Rectangle bounds = new Rectangle();

    public void setIcon(@Nullable BufferedImage icon)
    {
        this.icon = icon;
    }

    public void setLeftText(String leftText)
    {
        this.leftText = leftText == null ? "" : leftText;
    }

    public void setRightText(String rightText)
    {
        this.rightText = rightText == null ? "" : rightText;
    }

    public void setLeftColor(Color leftColor)
    {
        this.leftColor = leftColor == null ? Color.WHITE : leftColor;
    }

    public void setRightColor(Color rightColor)
    {
        this.rightColor = rightColor == null ? Color.WHITE : rightColor;
    }

    public void setFont(Font font)
    {
        this.font = font;
    }

    /**
     * Subtle arrival scale inside the reserved icon slot only (does not change row height).
     */
    public void setIconScale(float iconScale)
    {
        this.iconScale = Math.max(0.85f, Math.min(1.15f, iconScale));
    }

    @Override
    public void setPreferredSize(Dimension preferredSize)
    {
        this.preferredSize = preferredSize;
    }

    @Override
    public void setPreferredLocation(Point preferredLocation)
    {
        this.preferredLocation = preferredLocation == null ? new Point() : preferredLocation;
    }

    @Override
    public Rectangle getBounds()
    {
        return bounds;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        Font drawFont = font != null ? font : graphics.getFont();
        FontMetrics metrics = graphics.getFontMetrics(drawFont);
        int rowHeight = Math.max(ICON_SLOT, metrics.getHeight());
        int width = preferredSize == null ? 160 : Math.max(80, preferredSize.width);
        int x = preferredLocation.x;
        int y = preferredLocation.y;

        int iconBox = ICON_SLOT;
        int gap = 4;
        int contentLeft = x + iconBox + gap;

        String right = rightText;
        int rightWidth = metrics.stringWidth(right);
        int rightX = x + width - rightWidth;
        int maxLeftWidth = Math.max(8, rightX - contentLeft - 6);
        String left = truncateToWidth(leftText, metrics, maxLeftWidth);

        if (icon != null)
        {
            int srcW = Math.max(1, icon.getWidth());
            int srcH = Math.max(1, icon.getHeight());
            float fit = iconBox / (float) Math.max(srcW, srcH);
            int drawW = Math.max(1, Math.min(iconBox, Math.round(srcW * fit * iconScale)));
            int drawH = Math.max(1, Math.min(iconBox, Math.round(srcH * fit * iconScale)));
            int ix = x + (iconBox - drawW) / 2;
            int iy = y + (rowHeight - drawH) / 2;
            Object oldHint = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(icon, ix, iy, drawW, drawH, null);
            if (oldHint != null)
            {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldHint);
            }
        }

        int textY = y + (rowHeight - metrics.getHeight()) / 2 + metrics.getAscent();
        TextComponent leftComponent = new TextComponent();
        leftComponent.setText(left);
        leftComponent.setColor(leftColor);
        leftComponent.setFont(drawFont);
        leftComponent.setPosition(new Point(contentLeft, textY));
        leftComponent.render(graphics);

        TextComponent rightComponent = new TextComponent();
        rightComponent.setText(right);
        rightComponent.setColor(rightColor);
        rightComponent.setFont(drawFont);
        rightComponent.setPosition(new Point(rightX, textY));
        rightComponent.render(graphics);

        Dimension size = new Dimension(width, rowHeight);
        bounds.setLocation(x, y);
        bounds.setSize(size);
        return size;
    }

    public static String truncateToWidth(String text, FontMetrics metrics, int maxWidth)
    {
        if (text == null || text.isEmpty() || metrics.stringWidth(text) <= maxWidth)
        {
            return text == null ? "" : text;
        }
        String ellipsis = "…";
        int ellipsisWidth = metrics.stringWidth(ellipsis);
        if (maxWidth <= ellipsisWidth)
        {
            return ellipsis;
        }
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) + ellipsisWidth > maxWidth)
        {
            end--;
        }
        return end <= 0 ? ellipsis : text.substring(0, end) + ellipsis;
    }
}
