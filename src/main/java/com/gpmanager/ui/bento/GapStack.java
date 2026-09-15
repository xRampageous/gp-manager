package com.gpmanager.ui.bento;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * A vertical stack that puts one gap between <em>visible</em> children only, so a hidden
 * card leaves no hole. Children take the full width; heights follow their preferred size.
 */
public final class GapStack implements LayoutManager
{
    private final int gap;

    public GapStack(int gap)
    {
        this.gap = gap;
    }

    @Override
    public void addLayoutComponent(String name, Component comp)
    {
    }

    @Override
    public void removeLayoutComponent(Component comp)
    {
    }

    @Override
    public Dimension preferredLayoutSize(Container parent)
    {
        Insets in = parent.getInsets();
        int h = 0;
        int w = 0;
        boolean any = false;
        for (Component c : parent.getComponents())
        {
            if (!c.isVisible())
            {
                continue;
            }
            Dimension d = c.getPreferredSize();
            if (d.height <= 0 && d.width <= 0)
            {
                continue;
            }
            h += (any ? gap : 0) + d.height;
            w = Math.max(w, d.width);
            any = true;
        }
        return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent)
    {
        return preferredLayoutSize(parent);
    }

    @Override
    public void layoutContainer(Container parent)
    {
        Insets in = parent.getInsets();
        int width = parent.getWidth() - in.left - in.right;
        int y = in.top;
        boolean any = false;
        for (Component c : parent.getComponents())
        {
            if (!c.isVisible())
            {
                continue;
            }
            Dimension d = c.getPreferredSize();
            if (d.height <= 0 && d.width <= 0)
            {
                c.setBounds(in.left, y, width, 0);
                continue;
            }
            if (any)
            {
                y += gap;
            }
            Dimension max = c.getMaximumSize();
            int w = Math.min(width, max == null ? width : max.width);
            c.setBounds(in.left, y, w, d.height);
            y += d.height;
            any = true;
        }
    }
}
