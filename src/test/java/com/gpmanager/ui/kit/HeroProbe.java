package com.gpmanager;

import java.util.List;

/** Test-side access to Hero state. */
public final class HeroProbe
{
    private HeroProbe()
    {
    }

    /** Whether the status dot currently accepts a pause/resume click. */
    public static boolean dotClickable(Hero hero)
    {
        return hero.onDot != null;
    }
    public static String netText(Hero hero) {
        return hero.net.getText();
    }

    /** The value shown in the strip cell labelled {@code label}, or "" when there is none. */
    public static String cell(Hero hero, String label) {
        for (List<Hero.Cell> strip : hero.drawnStrips) {
            for (Hero.Cell cell : strip) {
                if (cell.label.equals(label)) {
                    return cell.value;
                }
            }
        }
        return "";
    }


}
