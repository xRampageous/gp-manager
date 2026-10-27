package com.gpmanager;

import java.util.Collections;
import java.awt.GraphicsEnvironment;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/** Owner 1.5.0: a sidebar the client resizes without a layout pass still scrolls straight away. */
public class LedgerResizeScrollTest
{
    @Test
    public void aResizedPageLaysOutAgainWithoutAClick() throws Exception
    {
        assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        for (int i = 0; i < 30; i++)
        {
            engine.session().addTransaction(Tx.of(now + i, TransactionType.LOOT, Context.GENERIC, "", true,
                Collections.singletonList(new Flow(536 + i * 2, "Item " + i, 1, 2_000 + i, 2_000L + i))), 2_000, false);
        }
        SidebarPanel[] holder = new SidebarPanel[1];
        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() ->
        {
            holder[0] = new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
            holder[0].active = true;
            holder[0].shell.show(Shell.LEDGER);
            holder[0].refreshNow();
            frame[0] = new JFrame();
            frame[0].add(holder[0]);
            frame[0].setSize(260, 900);
            frame[0].setVisible(true);
            frame[0].validate();
        });
        for (int i = 0; i < 5; i++) SwingUtilities.invokeAndWait(() -> { });
        JScrollPane scroll = holder[0].shell.scrolls.get(Shell.LEDGER);
        // The client's layout sets the sidebar's bounds directly; nothing lays the pane out again.
        SwingUtilities.invokeAndWait(() -> scroll.setSize(scroll.getWidth(), 100));
        for (int i = 0; i < 3; i++) SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() ->
        {
            assertTrue("the pane laid out again after the resize", scroll.isValid());
            assertEquals("the pane keeps the height the client gave it", 100, scroll.getHeight());
            assertEquals("the scrollbar's window matches the pane it scrolls",
                scroll.getViewport().getExtentSize().height, scroll.getVerticalScrollBar().getVisibleAmount());
            frame[0].dispose();
        });
    }
}
