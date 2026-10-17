package com.gpmanager;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Owner 1.5.0: the target form offers quick picks, one bond's live price among them. */
public class BondTargetPresetTest
{
    @After
    public void reset()
    {
        GrindsController.bondPrice = () -> 0;
    }

    @Test
    public void theBondButtonFillsTheNetTarget()
    {
        GrindsController.bondPrice = () -> 9_120_000L;
        JPanel form = GrindsController.grindForm(null, null, null);
        JButton bond = button(form, "Bond");
        assertNotNull("a priced bond offers the preset", bond);
        bond.doClick();
        assertEquals("9.12m", net(form).getText());
        assertEquals(9_120_000L, GrindsData.parseNet(net(form).getText()));
        assertEquals("the chosen pick is lit", Kit.ACCENT, bond.getForeground());
    }

    @Test
    public void noPriceNoButton()
    {
        JPanel form = GrindsController.grindForm(null, null, null);
        assertNull("without a bond price there is no Bond pick", button(form, "Bond"));
        assertNotNull("10m keeps the rows aligned", button(form, "10m"));
    }

    @Test
    public void offClearsAndExistingTargetsReadBack()
    {
        JPanel form = GrindsController.grindForm(null, 5_000_000L, 3L * 3_600_000L);
        assertEquals("5m", net(form).getText());
        assertEquals("3h", ((JTextField) form.getClientProperty("timeField")).getText());
        Color lit = button(form, "5m").getForeground();
        assertEquals(Kit.ACCENT, lit);
        button(form, "Clear").doClick();
        assertEquals("", net(form).getText());
    }

    @Test
    public void readableTargetsParseBackExactly()
    {
        for (long gp : new long[] {750_000L, 1_000_000L, 9_120_000L, 10_000_000L, 9_120_345L, 1_234L,
            1_000_000_000L, 2_500_000_000L, 1_005_000_000L})
        {
            assertEquals(gp, GrindsData.parseNet(Fmt.targetNet(gp)));
        }
        assertEquals("the 1b pick lights", "1b", Fmt.targetNet(1_000_000_000L));
        for (long minutes : new long[] {30L, 60L, 90L, 180L, 1_590L})
        {
            assertEquals(minutes * 60_000L, GrindsData.parseTime(Fmt.targetTime(minutes * 60_000L)));
        }
    }

    private static JTextField net(JPanel form)
    {
        return (JTextField) form.getClientProperty("netField");
    }

    /** The first pick with this text; the picks sit in a row of their own. */
    private static JButton button(Container parent, String text)
    {
        for (Component c : parent.getComponents())
        {
            if (c instanceof JButton && text.equals(((JButton) c).getText())) return (JButton) c;
            if (c instanceof Container)
            {
                JButton found = button((Container) c, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
