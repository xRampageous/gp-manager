package com.gpmanager;

import java.awt.Component;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.*;

/** Offline header lifecycle checks: stable updates retain controls, real changes still apply. */
public class TableHeaderReuseTest {
    static void edt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    static List<Table.Row> rows(int count) {
        List<Table.Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++)
            rows.add(TableRows.row("id" + i, "Item " + i, "x1", "+500", Kit.Tone.GAIN));
        return rows;
    }

    static AtomicInteger changes(Table table) {
        AtomicInteger count = new AtomicInteger();
        table.right.addContainerListener(new ContainerAdapter() {
            public void componentAdded(ContainerEvent event) { count.incrementAndGet(); }
            public void componentRemoved(ContainerEvent event) { count.incrementAndGet(); }
        });
        return count;
    }

    static List<Component> parts(Table table) {
        return Arrays.asList(table.right.getComponents());
    }

    @Test public void stableUpdatesRetainHeaderControls() throws Exception {
        edt(() -> {
            Table table = new Table("RECENT").setTotal("+500", Kit.Tone.GAIN)
                    .setAction(Kit.button("Ledger", false, () -> {})).setRows(rows(2));
            AtomicInteger count = changes(table);
            for (int i = 0; i < 100; i++) {
                table.setRows(rows(2));
                table.setTotal("+500", Kit.Tone.GAIN);
            }
            assertEquals("unchanged updates do not reparent header controls", 0, count.get());
        });
    }

    @Test public void textAndToneChangeInPlace() throws Exception {
        edt(() -> {
            Table table = new Table("RECENT").setTotal("+500", Kit.Tone.GAIN)
                    .setAction(Kit.button("Ledger", false, () -> {}));
            AtomicInteger count = changes(table);
            table.setTotal("-2.14B", Kit.Tone.LOSS);
            assertEquals("-2.14B", table.total.getText());
            assertEquals(Kit.Tone.LOSS.color, table.total.getForeground());
            assertEquals(0, count.get());
            for (int width : new int[]{201, 213, 288}) {
                DirectHeaderControlsTest.size(table, width);
                assertTrue(DirectHeaderControlsTest.bounds(table.action, table).getMaxX() <= width);
            }
        });
    }

    @Test public void pagesStillUpdateAndClampWithoutReparenting() throws Exception {
        edt(() -> {
            Table table = new Table("GAINS").setPageSize(10).setRows(rows(23));
            AtomicInteger count = changes(table);
            table.showPage(2);
            assertEquals("3/3", table.counter.getText());
            assertTrue(table.previous.isEnabled());
            assertFalse(table.next.isEnabled());
            table.setRows(rows(12));
            assertEquals("2/2", table.counter.getText());
            assertEquals(2, table.body.getComponentCount());
            assertFalse(table.next.isEnabled());
            assertEquals(0, count.get());
        });
    }

    @Test public void headerShapeFollowsFoldEmptyAndTotalTransitions() throws Exception {
        edt(() -> {
            Table table = new Table("GAINS").setFoldable(true).setRows(rows(20)).setTotal("+500", Kit.Tone.GAIN);
            assertEquals(Arrays.asList(table.total, table.previous, table.counter, table.next), parts(table));
            table.setFolded(true);
            assertEquals(Collections.singletonList(table.total), parts(table));
            assertEquals("Expand GAINS", table.arrow.getAccessibleContext().getAccessibleName());
            table.setTotal("", Kit.Tone.DIM);
            assertTrue(parts(table).isEmpty());
            table.setFolded(false);
            assertEquals(Arrays.asList(table.previous, table.counter, table.next), parts(table));
            table.setRows(Collections.emptyList());
            assertTrue(parts(table).isEmpty());
        });
    }

    @Test public void replacementActionsAndPagerKeepTheirOwnCommands() throws Exception {
        edt(() -> {
            AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
            Table table = new Table("RECENT").setRows(rows(20));
            JButton old = Kit.button("Old", false, first::incrementAndGet);
            JButton current = Kit.button("Ledger", false, second::incrementAndGet);
            table.setAction(old);
            table.setAction(current);
            assertNull(old.getParent());
            assertEquals(Collections.singletonList(current), parts(table));
            current.doClick();
            assertEquals(0, first.get());
            assertEquals(1, second.get());
            table.setAction(null);
            assertEquals(Arrays.asList(table.previous, table.counter, table.next), parts(table));
        });
    }

    @Test public void changedReceiptMenusStillRebuildTheirRows() throws Exception {
        edt(() -> {
            Table table = new Table("RECENT");
            table.setRows(Collections.singletonList(TableRows.row("id", "Item", "x1", "+500", Kit.Tone.GAIN)));
            Component old = table.body.getComponent(0);
            Table.Row row = TableRows.row("id", "Item", "x1", "+500", Kit.Tone.GAIN);
            row.menu = Collections.singletonList(new Table.Menu("Correct", () -> {}));
            table.setRows(Collections.singletonList(row));
            assertNotSame(old, table.body.getComponent(0));
            JComponent current = (JComponent) table.body.getComponent(0);
            assertEquals("Correct", ((JMenuItem) current.getComponentPopupMenu().getComponent(0)).getText());
        });
    }
}
