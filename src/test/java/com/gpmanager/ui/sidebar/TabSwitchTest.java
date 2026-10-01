package com.gpmanager;

import java.util.Collections;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/** A tab fills at once when shown, and an unchanged page is not re-read or rebuilt on the way back. */
public class TabSwitchTest
{
    @Test
    public void aShownTabFillsAtOnceAndAnUnchangedPageKeepsItsRows() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(Tx.of(now, TransactionType.LOOT, Context.GENERIC, "", true,
            Collections.singletonList(new Flow(536, "Dragon bones", 1, 2_000, 2_000L))), 2_000);
        SidebarPanel[] holder = new SidebarPanel[1];
        SwingUtilities.invokeAndWait(() ->
        {
            holder[0] = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            holder[0].active = true;
        });
        SidebarPanel panel = holder[0];

        SwingUtilities.invokeAndWait(() -> panel.shell().show(Shell.LEDGER));
        SwingUtilities.invokeAndWait(() -> { });
        LedgerData first = panel.ledger.data;
        assertNotNull("the Ledger fills when shown, without waiting for a game tick", first);

        SwingUtilities.invokeAndWait(() -> panel.shell().show(Shell.LIVE));
        SwingUtilities.invokeAndWait(() -> panel.shell().show(Shell.LEDGER));
        SwingUtilities.invokeAndWait(() -> { });
        assertSame("nothing changed: the Ledger keeps its rows", first, panel.ledger.data);

        engine.getActiveSession().addTransaction(Tx.of(now + 1_000L, TransactionType.LOOT, Context.GENERIC,
            "", true, Collections.singletonList(new Flow(536, "Dragon bones", 1, 2_000, 2_000L))), 2_000);
        SwingUtilities.invokeAndWait(() -> panel.shell().show(Shell.LIVE));
        SwingUtilities.invokeAndWait(() -> panel.shell().show(Shell.LEDGER));
        SwingUtilities.invokeAndWait(() -> { });
        assertNotSame("a new booking re-reads the Ledger", first, panel.ledger.data);
    }
}
