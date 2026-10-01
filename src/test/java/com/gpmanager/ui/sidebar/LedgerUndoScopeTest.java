package com.gpmanager;

import com.google.gson.Gson;
import java.awt.Component;
import java.util.Collections;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-10-01 (F01): undo mutates the live run only. Historical and Today scopes
 * disable it, and a menu opened while Current cannot mutate after a scope or owner switch.
 */
public class LedgerUndoScopeTest
{
    static
    {
        JsonCodec.bind(new Gson());
    }

    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void historicalAndTodayDisableUndoWhileCurrentEnablesIt()
    {
        Fixture fixture = new Fixture();
        fixture.apply(Ao.Entry.current());
        JPopupMenu current = fixture.page().awo();
        assertTrue(undo(current, "Undo latest correction").isEnabled());
        assertTrue(undo(current, "Undo last change").isEnabled());
        assertTrue(undo(current, "Restore last undo").isEnabled());

        fixture.apply(Ao.Entry.current().withScope(Ao.Scope.HISTORY, "past-run", "Past run"));
        JPopupMenu historical = fixture.page().awo();
        assertFalse(undo(historical, "Undo latest correction").isEnabled());
        assertFalse(undo(historical, "Undo last change").isEnabled());
        assertFalse(undo(historical, "Restore last undo").isEnabled());

        fixture.apply(Ao.Entry.current().withScope(Ao.Scope.TODAY, null, null));
        assertFalse(undo(fixture.page().awo(), "Undo last change").isEnabled());
    }

    @Test
    public void aStaleMenuCannotMutateAfterAScopeSwitch()
    {
        Fixture fixture = new Fixture();
        fixture.apply(Ao.Entry.current());
        JMenuItem undoLast = undo(fixture.page().awo(), "Undo last change");
        assertTrue(undoLast.isEnabled());

        fixture.apply(Ao.Entry.current().withScope(Ao.Scope.HISTORY, "past-run", "Past run"));
        undoLast.doClick();

        assertEquals("an open menu must not undo across a scope switch", 0, fixture.engine.lastChanges);
    }

    @Test
    public void currentScopeStillUndoesAllThreeOperations()
    {
        Fixture fixture = new Fixture();
        fixture.apply(Ao.Entry.current());
        undo(fixture.page().awo(), "Undo latest correction").doClick();
        undo(fixture.page().awo(), "Undo last change").doClick();
        undo(fixture.page().awo(), "Restore last undo").doClick();

        assertEquals(1, fixture.engine.corrections);
        assertEquals(1, fixture.engine.lastChanges);
        assertEquals(1, fixture.engine.restores);
    }

    @Test
    public void anOwnerSwitchLeavesAnOpenMenuInert()
    {
        Fixture fixture = new Fixture();
        fixture.apply(Ao.Entry.current());
        JMenuItem undoLast = undo(fixture.page().awo(), "Undo last change");

        fixture.engine.agl("profile-b", new SavedState(), NOW + 1_000L);
        undoLast.doClick();

        assertEquals("an open menu must not undo another owner's run", 0, fixture.engine.lastChanges);
    }

    private static JMenuItem undo(JPopupMenu menu, String text)
    {
        for (Component component : menu.getComponents())
        {
            if (component instanceof JMenuItem && text.equals(((JMenuItem) component).getText()))
            {
                return (JMenuItem) component;
            }
        }
        throw new AssertionError("missing menu item: " + text);
    }

    private static final class Fixture
    {
        final UndoSpy engine = new UndoSpy();
        final Dp panel;

        Fixture()
        {
            try
            {
                Dp[] holder = new Dp[1];
                SwingUtilities.invokeAndWait(() -> holder[0] = new Dp(engine, new GpManagerConfig() {}, null));
                panel = holder[0];
            }
            catch (Exception ex)
            {
                throw new AssertionError(ex);
            }
            engine.rm(NOW);
        }

        LedgerPage page()
        {
            return panel.ledger;
        }

        void apply(Ao.Entry entry)
        {
            panel.ledgerEntry = entry;
            panel.ledger.apply(Ao.capture(engine, NOW + 10L, entry));
        }
    }

    /** Counts the engine mutations the controller would perform. */
    private static final class UndoSpy extends Am
    {
        int corrections;
        int lastChanges;
        int restores;

        UndoSpy()
        {
            super(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        }

        @Override
        synchronized boolean akb(long now)
        {
            corrections++;
            return true;
        }

        @Override
        synchronized Ac akc(long now)
        {
            lastChanges++;
            return null;
        }

        @Override
        synchronized Ac agn(long now)
        {
            restores++;
            return null;
        }
    }
}
