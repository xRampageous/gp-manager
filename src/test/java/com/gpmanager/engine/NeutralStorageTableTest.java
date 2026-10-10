package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Every storage phrase, its first-match order and its transfer note, pinned row by row. */
public class NeutralStorageTableTest
{
    private static final Object[][] CASES = {
        {"Seed vault", "Seed vault transfer"},
        {"Tool Leprechaun", "Tool Leprechaun store"},
        {"open tool store", "Tool Leprechaun store"},
        {"Nightmare Zone rewards", "NMZ coffer transfer"},
        {"NMZ coffer", "NMZ coffer transfer"},
        {"nmz", null},
        {"Blast Furnace coffer", "Blast Furnace coffer transfer"},
        {"blast furnace deposit", "Blast Furnace coffer transfer"},
        {"blast furnace", null},
        {"Last Man Standing", "LMS coffer transfer"},
        {"lms coffer", "LMS coffer transfer"},
        {"lms", null},
        {"Pay-dirt", "Motherlode hopper transfer"},
        {"pay dirt sack", "Motherlode hopper transfer"},
        {"Motherlode hopper", "Motherlode hopper transfer"},
        {"motherlode sack", "Motherlode hopper transfer"},
        {"motherlode", null},
        {"Group storage", "GIM shared storage transfer"},
        {"shared storage", "GIM shared storage transfer"},
        {"GIM storage", "GIM shared storage transfer"},
        {"raid party", "Raid private bag transfer"},
        {"Private storage", "Raid private bag transfer"},
        {"raid bag", "Raid private bag transfer"},
        {"shared raid chest", "Raid shared bag transfer"},
        {"CoX shared", "Raid shared bag transfer"},
        {"STASH unit", "STASH unit transfer"},
        {"search stash (medium)", "STASH unit transfer"},
        {"stash", null},
        {"Costume room", "PoH costume room transfer"},
        {"poh costume", "PoH costume room transfer"},
        {"Bank deposit box", "Deposit box transfer"},
        {"seed vault deposit box", "Seed vault transfer"},
        {"shared storage raid bag", "GIM shared storage transfer"},
        {"  ", null},
        {null, null},
        {"bank", null},
    };

    @Test
    public void everyPhraseClassifiesInOrder()
    {
        for (Object[] row : CASES)
        {
            assertEquals(String.valueOf(row[0]), row[1], NeutralTransfers.storage((String) row[0]));
        }
    }
}
