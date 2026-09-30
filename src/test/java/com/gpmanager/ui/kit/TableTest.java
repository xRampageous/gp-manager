package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TableTest
{
    private static List<Table.Row> rows(int count)
    {
        List<Table.Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            rows.add(Table.Row.of("k" + i, "Item " + i, "×" + i, "+" + i, Kit.Tone.GAIN));
        }
        return rows;
    }

    @Test
    public void pagesClampAndReset()
    {
        Table table = new Table("GAINS").setRowsPerPage(10).setRows(rows(23));
        assertEquals(3, table.pages());
        assertEquals("1/3", table.pageLabel.getText());

        // Last page, then a shorter list: the page clamps instead of going blank.
        lastPage(table);
        assertEquals(2, TableRows.page(table));
        assertEquals(3, table.body.getComponentCount());
        table.setRows(rows(12));
        assertEquals(1, TableRows.page(table));
        assertEquals(2, table.body.getComponentCount());
        table.avd();
        assertEquals(0, TableRows.page(table));
        assertEquals(10, table.body.getComponentCount());
    }

    private static void lastPage(Table table)
    {
        while (TableRows.page(table) < table.pages() - 1)
        {
            int before = TableRows.page(table);
            clickNext(table);
            assertTrue(TableRows.page(table) > before);
        }
    }

    private static void clickNext(Table table)
    {
        for (java.awt.Component c : table.pageLabel.getParent().getComponents())
        {
            if (c instanceof javax.swing.JButton && "›".equals(((javax.swing.JButton) c).getText()))
            {
                ((javax.swing.JButton) c).doClick();
                return;
            }
        }
        throw new AssertionError("no next button");
    }

    @Test
    public void rowsPerPageIsCappedAtFifteen()
    {
        Table table = new Table("RECENT").setRowsPerPage(40).setRows(rows(30));
        assertEquals(15, table.body.getComponentCount());
        assertEquals(2, table.pages());
    }

    @Test
    public void minRowsFixesBodyHeight()
    {
        Table table = new Table("COSTS").setRowsPerPage(5).setMinRows(5).setRows(rows(2));
        assertEquals(5 * Kit.ROW, table.body.getPreferredSize().height);
        table.setRows(rows(5));
        assertEquals(5 * Kit.ROW, table.body.getPreferredSize().height);
    }

    @Test
    public void emptyTableShowsItsText()
    {
        Table table = new Table("MARKET").setEmptyText("No settled sales").setRows(Collections.emptyList());
        assertEquals(1, table.body.getComponentCount());
        assertEquals("No settled sales", ((JLabel) table.body.getComponent(0)).getText());
        assertTrue(table.pageLabel.getParent() == null);
    }

    @Test
    public void foldHidesBodyOnlyWhenFoldable()
    {
        Table table = new Table("GAINS").setRows(rows(3));
        table.setFolded(true);
        assertFalse(table.isFolded());
        table.setFoldable(true).setFolded(true);
        assertTrue(table.isFolded());
        assertFalse(table.body.isVisible());
        table.setFolded(false);
        assertTrue(table.body.isVisible());
    }

    @Test
    public void rowCarriesTooltipsMenuAndFixedColumns()
    {
        int[] opened = {0};
        Table.Row row = new Table.Row("a", null, "Dragon bones", "×6", "+19.2k", Kit.Tone.GAIN,
            "Picked up", "+19,236 gp", true, () -> opened[0]++,
            Collections.singletonList(new Table.Menu("Correct…", () -> { })));
        Table table = new Table("GAINS").setRows(Collections.singletonList(row));
        JPanel line = (JPanel) table.body.getComponent(0);
        assertEquals("Picked up", line.getToolTipText());
        assertEquals(Kit.SELECTED, line.getBackground());
        assertNotNull(line.getComponentPopupMenu());
        JPanel numbers = (JPanel) line.getComponent(1);
        JLabel qty = (JLabel) numbers.getComponent(0);
        JLabel value = (JLabel) numbers.getComponent(1);
        int qtyWidth = qty.getPreferredSize().width;
        assertTrue("quantity column fits its text: " + qtyWidth, qtyWidth > 0 && qtyWidth <= 70);
        assertEquals(Kit.VALUE_WIDTH, value.getPreferredSize().width);
        assertEquals("+19,236 gp", value.getToolTipText());
        assertEquals(Kit.Tone.GAIN.color, value.getForeground());
    }

    @Test
    public void iconTablesUseIconHeightRowsAndKeepNamesAligned()
    {
        javax.swing.ImageIcon icon = new javax.swing.ImageIcon(
            new java.awt.image.BufferedImage(36, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB));
        Table table = new Table("RECENT").setRows(java.util.Arrays.asList(
            new Table.Row("a", icon, "Oak plank", "", "+4.9k", Kit.Tone.GAIN, "", "", false, null, null),
            Table.Row.of("b", "Coins", "", "+10", Kit.Tone.GAIN)));
        assertEquals(2 * Kit.ICON_ROW, table.body.getPreferredSize().height);
        for (java.awt.Component row : table.body.getComponents())
        {
            JPanel line = (JPanel) row;
            assertEquals(Kit.ICON_ROW, line.getPreferredSize().height);
            assertEquals("every row keeps the icon slot", Kit.ICON_WIDTH,
                line.getComponent(0).getPreferredSize().width);
            JPanel numbers = (JPanel) line.getComponent(2);
            assertEquals("no quantity column when no row has one", 1, numbers.getComponentCount());
        }
    }

    @Test
    public void fillFitsRowsToTheRoomLeft()
    {
        assertEquals(3, Table.fits(10, Kit.ICON_ROW));
        assertEquals(10, Table.fits(10 * Kit.ICON_ROW + 5, Kit.ICON_ROW));
        assertEquals(15, Table.fits(2_000, Kit.ROW));
    }

    @Test
    public void unchangedPageIsNotRebuilt()
    {
        Table table = new Table("GAINS").setRows(rows(3));
        java.awt.Component first = table.body.getComponent(0);
        table.setRows(rows(3));
        assertTrue(first == table.body.getComponent(0));
    }

    @Test
    public void toneFollowsCategory()
    {
        assertEquals(Kit.Tone.GAIN, Kit.Tone.of(Af.Category.GAIN));
        assertEquals(Kit.Tone.SUPPLY, Kit.Tone.of(Af.Category.SUPPLY));
        assertEquals(Kit.Tone.LOSS, Kit.Tone.of(Af.Category.FEE));
        assertEquals(Kit.Tone.MARKET, Kit.Tone.of(Af.Category.MARKET));
        assertEquals(Kit.Tone.DIM, Kit.Tone.of(null));
    }

    @Test
    public void keyValueAddsFixedLines()
    {
        Dd kv = new Dd("WHY").put("How", "Charges used", Kit.Tone.PLAIN)
            .put("Net effect", "−24.2k", Kit.Tone.SUPPLY, "−24,190 gp");
        assertEquals(2, kv.lines());
        assertEquals(0, kv.clear().lines());
    }

    @Test
    public void replacingTheExtraStripLeavesNoStaleCopyBehind()
    {
        Table table = new Table("COSTS");
        table.setExtra(new JLabel("Supply 0"));
        JLabel fresh = new JLabel("Supply 4");
        table.setExtra(fresh);
        List<String> texts = new ArrayList<>();
        collect(table, texts);
        assertTrue(texts.contains("Supply 4"));
        assertFalse("the old chip row is gone", texts.contains("Supply 0"));
        table.setFolded(false);
        assertTrue(fresh.isVisible());
    }

    private static void collect(java.awt.Container root, List<String> texts)
    {
        for (java.awt.Component child : root.getComponents())
        {
            if (child instanceof JLabel)
            {
                texts.add(((JLabel) child).getText());
            }
            if (child instanceof java.awt.Container)
            {
                collect((java.awt.Container) child, texts);
            }
        }
    }
}
