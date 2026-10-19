package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import org.junit.Test;

import static com.gpmanager.MilestoneTest.onEdt;
import static org.junit.Assert.*;

/** GE state words need their full measured width, including retained Offering labels. */
public class GeStatusPresentationTest
{
    static final String[] STATES = {"Selling", "Offering", "Buying", "Pending", "Sold", "Bought", "Canceled", "Review", "Unavailable"};

    @Test public void everyGeStatusFitsBesideTheQuantityAndIconAtSidebarWidths() throws Exception
    {
        onEdt(() ->
        {
            for (int width : new int[]{213, 225, 300})
                for (String state : STATES)
                {
                    Table.Row row = TableRows.row(state, "Rune kiteshield", "×20,000", state, Kit.Tone.DIM);
                    row.icon = new ImageIcon(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
                    JComponent line = Table.rowComponent(row, Kit.ICON_ROW);
                    line.setSize(width - 2 * Kit.GAP, Kit.ICON_ROW);
                    layout(line);
                    JLabel label = find(line, state);
                    assertNotNull(label);
                    int textWidth = label.getFontMetrics(label.getFont()).stringWidth(state);
                    assertTrue(state + " at " + width + "px needs " + textWidth + "px; got " + label.getWidth(),
                        label.getWidth() >= textWidth + label.getInsets().left + label.getInsets().right);
                    assertTrue("the item name keeps room", find(line, "Rune kiteshield").getWidth() > 0);
                    assertTrue("quantity remains visible", find(line, "×20,000").getWidth() > 0);
                }
            return null;
        });
    }

    @Test public void ordinaryMoneyKeepsItsExistingColumnWidth() throws Exception
    {
        onEdt(() ->
        {
            JComponent line = Table.rowComponent(TableRows.row("money", "Rune kiteshield", "×1", "+31.7k", Kit.Tone.GAIN), Kit.ROW);
            line.setSize(213, Kit.ROW);
            layout(line);
            assertEquals(Kit.VALUE_WIDTH, find(line, "+31.7k").getWidth());
            return null;
        });
    }

    static Table preview(int width)
    {
        var rows = new ArrayList<Table.Row>();
        for (String state : STATES) rows.add(TableRows.row(state, "Rune kiteshield", "×20,000", state, Kit.Tone.DIM));
        Table table = new Table("GE STATES").setRows(rows);
        table.setSize(width, Kit.HEADER + STATES.length * Kit.ROW);
        layout(table);
        return table;
    }

    static void layout(Container container)
    {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container) layout((Container) child);
    }

    static JLabel find(Component root, String text)
    {
        if (root instanceof JLabel && text.equals(((JLabel) root).getText())) return (JLabel) root;
        if (root instanceof Container)
            for (Component child : ((Container) root).getComponents())
            {
                JLabel found = find(child, text);
                if (found != null) return found;
            }
        return null;
    }
}
