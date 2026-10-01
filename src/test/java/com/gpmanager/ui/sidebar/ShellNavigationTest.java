package com.gpmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The shell restores exactly the four selected top-level pages, Live by default. */
public class ShellNavigationTest
{
    @Test
    public void navigationHasExactlyTheThreeFinalLabelsWithoutDecorativeGlyphs() throws Exception
    {
        SidebarPanel panel = onEdt(() -> new SidebarPanel(PresentationLifecycleTest.engine(),
            PresentationLifecycleTest.config(), null));
        Shell shell = panel.shell();
        assertEquals(3, ShellProbe.tabLabels(shell).length);
        assertEquals("live", shell.currentPage());
        assertEquals(List.of("Live", "Ledger", "Grinds"), List.of(ShellProbe.tabLabels(shell)));
        for (String label : ShellProbe.tabLabels(shell))
        {
            assertEquals("text-first tab label", label, label.trim());
            assertTrue("no decorative pictogram in the final navigation: " + label,
                label.matches("[A-Za-z ]+"));
        }
    }

    @Test
    public void ledgerPlaceholderIsReplacedByTheRealAuditSurface() throws Exception
    {
        SidebarPanel panel = onEdt(() -> new SidebarPanel(PresentationLifecycleTest.engine(),
            PresentationLifecycleTest.config(), null));
        panel.shell().show(Shell.LEDGER);
        java.util.List<String> labels = new java.util.ArrayList<>();
        collectLabels(panel, labels);
        assertFalse("no placeholder copy remains on Ledger", labels.contains("Ledger recovery pending R2"));
    }

    @Test
    public void notificationShowsOneMessageAndDismissesOnDemand() throws Exception
    {
        SidebarPanel panel = onEdt(() -> new SidebarPanel(PresentationLifecycleTest.engine(),
            PresentationLifecycleTest.config(), null));
        Shell shell = panel.shell();
        onEdt(() ->
        {
            shell.notify("Exported Vorkath", "vorkath.csv", false, null, null);
            shell.notify("Backup failed", "disk full", true, null, null);
            return null;
        });
        assertEquals("only the latest message shows", "Backup failed \u00b7 disk full", ShellProbe.noticeText(shell));
        onEdt(() ->
        {
            shell.dismiss();
            return null;
        });
        assertEquals("", ShellProbe.noticeText(shell));
    }

    private static void collectLabels(java.awt.Container root, List<String> out)
    {
        if (root instanceof javax.swing.JLabel)
        {
            String text = ((javax.swing.JLabel) root).getText();
            if (text != null && !text.isEmpty())
            {
                out.add(text);
            }
        }
        for (java.awt.Component child : root.getComponents())
        {
            if (child instanceof java.awt.Container)
            {
                collectLabels((java.awt.Container) child, out);
            }
            else if (child instanceof javax.swing.JLabel)
            {
                String text = ((javax.swing.JLabel) child).getText();
                if (text != null && !text.isEmpty())
                {
                    out.add(text);
                }
            }
        }
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
    @Test
    public void everyPageIsSelectableAndTheShellReturnsToLive() throws Exception
    {
        SidebarPanel panel = onEdt(() -> new SidebarPanel(PresentationLifecycleTest.engine(),
            PresentationLifecycleTest.config(), null));
        Shell shell = panel.shell();

        shell.show(Shell.LEDGER);
        assertEquals("ledger", shell.currentPage());
        shell.show(Shell.GRINDS);
        assertEquals("grinds", shell.currentPage());
        shell.show(Shell.LIVE);
        assertEquals("live", shell.currentPage());
        assertEquals("live", ShellProbe.selectedTab(shell));
    }
}
