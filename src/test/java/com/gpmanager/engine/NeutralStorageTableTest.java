package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Every storage phrase, its first-match order and its transfer note, pinned row by row. */
public class NeutralStorageTableTest
{
    private static final Object[][] CASES = {
        {"Seed vault", NeutralStorageClassifier.Kind.SEED_VAULT, "Seed vault transfer"},
        {"Tool Leprechaun", NeutralStorageClassifier.Kind.TOOL_LEPRECHAUN, "Tool Leprechaun store"},
        {"open tool store", NeutralStorageClassifier.Kind.TOOL_LEPRECHAUN, "Tool Leprechaun store"},
        {"Nightmare Zone rewards", NeutralStorageClassifier.Kind.NMZ_COFFER, "NMZ coffer transfer"},
        {"NMZ coffer", NeutralStorageClassifier.Kind.NMZ_COFFER, "NMZ coffer transfer"},
        {"nmz", null, null},
        {"Blast Furnace coffer", NeutralStorageClassifier.Kind.BLAST_FURNACE_COFFER, "Blast Furnace coffer transfer"},
        {"blast furnace deposit", NeutralStorageClassifier.Kind.BLAST_FURNACE_COFFER, "Blast Furnace coffer transfer"},
        {"blast furnace", null, null},
        {"Last Man Standing", NeutralStorageClassifier.Kind.LMS_COFFER, "LMS coffer transfer"},
        {"lms coffer", NeutralStorageClassifier.Kind.LMS_COFFER, "LMS coffer transfer"},
        {"lms", null, null},
        {"Pay-dirt", NeutralStorageClassifier.Kind.MLM_HOPPER, "Motherlode hopper transfer"},
        {"pay dirt sack", NeutralStorageClassifier.Kind.MLM_HOPPER, "Motherlode hopper transfer"},
        {"Motherlode hopper", NeutralStorageClassifier.Kind.MLM_HOPPER, "Motherlode hopper transfer"},
        {"motherlode sack", NeutralStorageClassifier.Kind.MLM_HOPPER, "Motherlode hopper transfer"},
        {"motherlode", null, null},
        {"Group storage", NeutralStorageClassifier.Kind.GIM_SHARED, "GIM shared storage transfer"},
        {"shared storage", NeutralStorageClassifier.Kind.GIM_SHARED, "GIM shared storage transfer"},
        {"GIM storage", NeutralStorageClassifier.Kind.GIM_SHARED, "GIM shared storage transfer"},
        {"raid party", NeutralStorageClassifier.Kind.RAID_PRIVATE_BAG, "Raid private bag transfer"},
        {"Private storage", NeutralStorageClassifier.Kind.RAID_PRIVATE_BAG, "Raid private bag transfer"},
        {"raid bag", NeutralStorageClassifier.Kind.RAID_PRIVATE_BAG, "Raid private bag transfer"},
        {"shared raid chest", NeutralStorageClassifier.Kind.RAID_SHARED_BAG, "Raid shared bag transfer"},
        {"CoX shared", NeutralStorageClassifier.Kind.RAID_SHARED_BAG, "Raid shared bag transfer"},
        {"STASH unit", NeutralStorageClassifier.Kind.STASH_UNIT, "STASH unit transfer"},
        {"search stash (medium)", NeutralStorageClassifier.Kind.STASH_UNIT, "STASH unit transfer"},
        {"stash", null, null},
        {"Costume room", NeutralStorageClassifier.Kind.POH_COSTUME, "PoH costume room transfer"},
        {"poh costume", NeutralStorageClassifier.Kind.POH_COSTUME, "PoH costume room transfer"},
        {"Bank deposit box", NeutralStorageClassifier.Kind.DEPOSIT_BOX, "Deposit box transfer"},
        {"seed vault deposit box", NeutralStorageClassifier.Kind.SEED_VAULT, "Seed vault transfer"},
        {"shared storage raid bag", NeutralStorageClassifier.Kind.GIM_SHARED, "GIM shared storage transfer"},
        {"  ", null, null},
        {null, null, null},
        {"bank", null, null},
    };

    @Test
    public void everyPhraseClassifiesInOrder()
    {
        for (Object[] row : CASES)
        {
            NeutralStorageClassifier.Kind kind = NeutralStorageClassifier.classify((String) row[0]);
            assertEquals(String.valueOf(row[0]), row[1], kind);
            if (kind != null)
            {
                assertEquals(String.valueOf(row[0]), row[2], NeutralStorageClassifier.ajw(kind));
            }
        }
        assertEquals("Ownership-neutral storage transfer", NeutralStorageClassifier.ajw(null));
        assertEquals("Ownership-neutral storage transfer",
            NeutralStorageClassifier.ajw(NeutralStorageClassifier.Kind.UNKNOWN_NEUTRAL));
    }
}
