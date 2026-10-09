package com.gpmanager;

import java.util.Locale;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 1.3.2: the tray's routine-supply names moved to d18 "routine"; it must answer exactly as the old checks. */
public class RoutineSupplyTest
{
    private static boolean old(String name)
    {
        String b = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return b.endsWith(" rune") || b.endsWith(" runes") || b.contains("arrow")
            || b.endsWith(" bolts") || b.contains(" bolts (") || b.endsWith(" dart")
            || Ag.has(b, "javelin", "throwing", "zulrah's scales");
    }

    @Test
    public void sameAnswersAsTheOldChecks()
    {
        String[] names = {"Fire rune", "Death runes", "Rune platebody", "Runite bar", "Rune arrow", "Arrow shaft",
            "Dragon arrowtips", "Adamant bolts", "Ruby bolts (e)", "Bolt of linen", "Mithril dart", "Dart tip",
            "Dragon dart (p++)", "Amethyst javelin", "Javelin shaft", "Toktz-xil-ul", "Throwing axe", "Rune thrownaxe",
            "Zulrah's scales", "Shark", "Coins", "Bolts", "dart", "Rune", "Pure essence", "Broad arrowheads",
            "Rune knife", "Feather", null, ""};
        for (String name : names)
        {
            assertEquals(String.valueOf(name), old(name), HudTray.routine(name));
        }
    }
}
