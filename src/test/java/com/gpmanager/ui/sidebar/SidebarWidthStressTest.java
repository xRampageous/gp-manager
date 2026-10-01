package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-10-01 (F17): the pinned sidebar bands stay inside 225px with hostile content -
 * a 34-character name, a maximum signed Net, count 2,000 chips and an open pager.
 */
public class SidebarWidthStressTest
{
    private static final int SIDEBAR = 225;
    private static final long NOW = System.currentTimeMillis();

    @Test
    public void heroBoundsALongNameAndKeepsTheFullTextInItsTooltip() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        String name = "Fishing trawler veteran of the north";
        engine.startCustomSession(name, SessionMode.GENERAL, NOW);
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        LivePage live = (LivePage) SidebarPanelProbe.livePage(panel);
        assertTrue("the name is bounded: " + onEdt(() -> live.hero.name.getText()),
            onEdt(() -> live.hero.name.getText()).endsWith("\u2026"));
        assertTrue("the full name stays in the tooltip: " + onEdt(() -> live.hero.name.getToolTipText()),
            onEdt(() -> live.hero.name.getToolTipText()).contains(name));
        assertTrue("the hero fits the sidebar: " + onEdt(() -> live.hero.getPreferredSize()),
            onEdt(() -> live.hero.getPreferredSize().width) <= SIDEBAR);
    }

    @Test
    public void ledgerToolbarCarriesItsActionsAndFits() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.startCustomSession("Fishing trawler veteran of the north", SessionMode.GENERAL, NOW);
        engine.getActiveSession().addTransaction(booked(NOW + 1_000L, "Twisted bow", 20997, 1L,
            2_147_483_647, 2_147_483_647L), 2_000);
        engine.setActiveSessionTargets(2_147_483_647L, null, NOW + 2_000L);
        SidebarPanel panel = onEdt(() ->
        {
            SidebarPanel created = new SidebarPanel(engine, PresentationLifecycleTest.config(), null);
            created.shell().show(Shell.LEDGER);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        java.awt.Container row = onEdt(() -> panel.ledger.scope.getParent().getParent());
        java.awt.Component search = onEdt(() -> findButton(panel.ledger.pageBar(), "Search"));
        assertTrue("Search shares the scope's toolbar row",
            search != null && javax.swing.SwingUtilities.isDescendingFrom(search, row));
        assertTrue("the pinned bar fits the sidebar: " + onEdt(() -> panel.ledger.pageBar().getPreferredSize()),
            onEdt(() -> panel.ledger.pageBar().getPreferredSize().width) <= SIDEBAR);
    }

    private static java.awt.Component findButton(java.awt.Component root, String text)
    {
        if (root instanceof javax.swing.AbstractButton
            && text.equals(((javax.swing.AbstractButton) root).getText()))
        {
            return root;
        }
        if (root instanceof java.awt.Container)
        {
            for (java.awt.Component child : ((java.awt.Container) root).getComponents())
            {
                java.awt.Component found = findButton(child, text);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void costChipsWrapTwoByTwoWithRoomForEveryCount() throws Exception
    {
        SidebarPanel panel = onEdt(() -> new SidebarPanel(PresentationLifecycleTest.engine(),
            PresentationLifecycleTest.config(), null));
        javax.swing.JPanel chips = onEdt(() -> panel.ledger.costChips(LedgerData.CostView.ALL,
            new int[] {2_000, 2_000, 2_000, 2_000}));
        assertTrue("every choice stays present", onEdt(() -> chips.getComponentCount()) == 4);
        Object layout = onEdt(() -> chips.getLayout());
        assertTrue("two columns, two rows: " + layout, layout instanceof java.awt.GridLayout
            && ((java.awt.GridLayout) layout).getColumns() == 2);
        assertTrue("the chips fit the sidebar: " + onEdt(() -> chips.getPreferredSize()),
            onEdt(() -> chips.getPreferredSize().width) <= SIDEBAR);
    }

    @Test
    public void tableHeaderFitsWithMaximumValuesAndAnOpenPager() throws Exception
    {
        Table table = onEdt(() ->
        {
            Table created = new Table("");
            created.setTitle("CORRECTED \u00b7 2,000");
            created.setTotal(Fmt.signed(2_147_483_647L), Kit.Tone.GAIN);
            created.setRowsPerPage(1);
            created.setRows(new ArrayList<>(java.util.Arrays.asList(
                Table.Row.of("a", "A", "1", "+1", Kit.Tone.GAIN),
                Table.Row.of("b", "B", "1", "+1", Kit.Tone.GAIN))));
            return created;
        });
        assertTrue("the header fits the sidebar: " + onEdt(() -> table.header.getPreferredSize()),
            onEdt(() -> table.header.getPreferredSize().width) <= SIDEBAR);
    }

    private static Transaction booked(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Transaction(at, null, TransactionType.GAIN, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            ClassificationConfidence.LIKELY, "Test sample.", null);
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                result.set(callable.call());
            }
            catch (Exception ex)
            {
                failure.set(ex);
            }
        });
        if (failure.get() != null)
        {
            throw failure.get();
        }
        return result.get();
    }
}
