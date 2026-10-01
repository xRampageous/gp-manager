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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(Tx.of(now, Ai.LOOT, Aj.GENERIC, "", true,
            Collections.singletonList(new Ab(536, "Dragon bones", 1, 2_000, 2_000L))), 2_000);
        Dp[] holder = new Dp[1];
        SwingUtilities.invokeAndWait(() ->
        {
            holder[0] = new Dp(engine, PresentationLifecycleTest.config(), null);
            holder[0].active = true;
        });
        Dp panel = holder[0];

        SwingUtilities.invokeAndWait(() -> panel.shell().show(Shell.LEDGER));
        SwingUtilities.invokeAndWait(() -> { });
        Ao first = panel.ledger.data;
        assertNotNull("the Ledger fills when shown, without waiting for a game tick", first);

        SwingUtilities.invokeAndWait(() -> panel.shell().show(Shell.LIVE));
        SwingUtilities.invokeAndWait(() -> panel.shell().show(Shell.LEDGER));
        SwingUtilities.invokeAndWait(() -> { });
        assertSame("nothing changed: the Ledger keeps its rows", first, panel.ledger.data);

        engine.getActiveSession().kf(Tx.of(now + 1_000L, Ai.LOOT, Aj.GENERIC,
            "", true, Collections.singletonList(new Ab(536, "Dragon bones", 1, 2_000, 2_000L))), 2_000);
        SwingUtilities.invokeAndWait(() -> panel.shell().show(Shell.LIVE));
        SwingUtilities.invokeAndWait(() -> panel.shell().show(Shell.LEDGER));
        SwingUtilities.invokeAndWait(() -> { });
        assertNotSame("a new booking re-reads the Ledger", first, panel.ledger.data);
    }
}
