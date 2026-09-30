package com.gpmanager;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import net.runelite.client.ui.components.materialtabs.MaterialTab;

/** Test-side access to Shell state. */
public final class ShellProbe
{
    private ShellProbe()
    {
    }

    /** Tab labels in order. */
    public static String[] tabLabels(Shell shell)
    {
        String[] labels = new String[Shell.TABS.length];
        for (int i = 0; i < Shell.TABS.length; i++)
        {
            labels[i] = Shell.TABS[i][1];
        }
        return labels;
    }

    /** The selected tab's page id, as the tab strip shows it. */
    public static String selectedTab(Shell shell)
    {
        for (Map.Entry<String, MaterialTab> entry : shell.tabById.entrySet())
        {
            if (entry.getValue().isSelected())
            {
                return entry.getKey();
            }
        }
        return "";
    }

    public static void scrollCurrentToTop(Shell shell)
    {
        JScrollPane pane = shell.scrolls.get(shell.current);
        if (pane != null)
        {
            pane.getVerticalScrollBar().setValue(0);
            shell.scrollMemory.put(shell.current, 0);
        }
    }

    private static final String DEV_FOOTER = "gp-dev-footer";

    /** Dev builds only: a badge row under the notice line (null clears it). Production has no footer. */
    public static void setBadge(Shell shell, @Nullable JComponent badge)
    {
        JPanel footer = footer(shell);
        if (footer == null)
        {
            Component notice = ((BorderLayout) shell.getLayout()).getLayoutComponent(BorderLayout.SOUTH);
            JPanel bottom = new JPanel(new BorderLayout());
            bottom.setName(DEV_FOOTER);
            bottom.setOpaque(false);
            shell.remove(notice);
            bottom.add(notice, BorderLayout.NORTH);
            footer = new JPanel(new BorderLayout());
            footer.setOpaque(false);
            Kit.pad(footer, 2, Kit.GAP, 3, Kit.GAP);
            bottom.add(footer, BorderLayout.SOUTH);
            shell.add(bottom, BorderLayout.SOUTH);
        }
        footer.removeAll();
        if (badge != null)
        {
            footer.add(badge, BorderLayout.WEST);
        }
        footer.setVisible(badge != null);
        shell.revalidate();
    }

    @Nullable
    public static JComponent badge(Shell shell)
    {
        JPanel footer = footer(shell);
        return footer == null || footer.getComponentCount() == 0 ? null : (JComponent) footer.getComponent(0);
    }

    @Nullable
    private static JPanel footer(Shell shell)
    {
        Component south = ((BorderLayout) shell.getLayout()).getLayoutComponent(BorderLayout.SOUTH);
        return south != null && DEV_FOOTER.equals(south.getName())
            ? (JPanel) ((JPanel) south).getComponent(1) : null;
    }

    /** Presses the sheet button with this label, wherever it nests. */
    public static void pressSheet(Shell shell, String label)
    {
        for (JButton button : sheetButtons(shell))
        {
            if (label.equals(button.getText()))
            {
                button.doClick();
                return;
            }
        }
        throw new IllegalArgumentException("no sheet button " + label);
    }

    /** Types into the sheet's text field. */
    public static void typeSheet(Shell shell, String text)
    {
        for (java.awt.Component row : shell.sheet.getComponents())
        {
            if (row instanceof JPanel)
            {
                for (java.awt.Component c : ((JPanel) row).getComponents())
                {
                    if (c instanceof javax.swing.JTextField)
                    {
                        ((javax.swing.JTextField) c).setText(text);
                        return;
                    }
                }
            }
        }
        throw new IllegalArgumentException("no sheet field");
    }

    /** The sheet's buttons, in tree order (rows, pager, choices). */
    public static List<JButton> sheetButtons(Shell shell)
    {
        List<JButton> out = new ArrayList<>();
        collectButtons(shell.sheet, out);
        return out;
    }

    /** The sheet's button texts, in tree order. */
    public static List<String> sheetButtonTexts(Shell shell)
    {
        List<String> out = new ArrayList<>();
        for (JButton button : sheetButtons(shell))
        {
            out.add(button.getText());
        }
        return out;
    }

    /** Presses the first enabled sheet button with this exact text, wherever it nests. */
    public static boolean pressSheetText(Shell shell, String text)
    {
        for (JButton button : sheetButtons(shell))
        {
            if (text.equals(button.getText()) && button.isEnabled())
            {
                button.doClick();
                return true;
            }
        }
        return false;
    }

    private static void collectButtons(Component root, List<JButton> out)
    {
        if (root instanceof JButton)
        {
            out.add((JButton) root);
        }
        if (root instanceof java.awt.Container)
        {
            for (Component child : ((java.awt.Container) root).getComponents())
            {
                collectButtons(child, out);
            }
        }
    }

    /** The sheet's checkbox whose label contains this text, or null. */
    @Nullable
    public static JCheckBox sheetCheckbox(Shell shell, String label)
    {
        for (java.awt.Component row : shell.sheet.getComponents())
        {
            if (row instanceof JPanel)
            {
                for (java.awt.Component c : ((JPanel) row).getComponents())
                {
                    if (c instanceof JCheckBox && ((JCheckBox) c).getText().contains(label))
                    {
                        return (JCheckBox) c;
                    }
                }
            }
        }
        return null;
    }

    public static String sheetTitle(Shell shell)
    {
        return shell.sheetTitle;
    }

    /** The notification text now showing, or "". */
    public static String noticeText(Shell shell)
    {
        return shell.notice.isVisible() ? shell.noticeText.getToolTipText() : "";
    }
}
