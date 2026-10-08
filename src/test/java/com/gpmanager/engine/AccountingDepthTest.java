package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountingDepthTest
{
    @Test
    public void transferNeverCountsInvariant()
    {
        Ac honest = new Ac(
            1L, 1L, Ai.TRANSFER, Aj.TRANSFER,
            "Seed vault transfer", "Transfer", false,
            Collections.singletonList(new Ab(995, "Coins", -100L, 1, -100L)),
            null, "", null);
        assertTrue(AccountingInvariants.transferNeverCounts(honest));
        Ac wronglyCounted = new Ac(
            1L, 1L, Ai.TRANSFER, Aj.TRANSFER,
            "Seed vault transfer", "Transfer", true,
            Collections.singletonList(new Ab(995, "Coins", -100L, 1, -100L)),
            null, "", null);
        assertFalse(AccountingInvariants.transferNeverCounts(wronglyCounted));
    }

    @Test
    public void retiredSyntheticTaxProvenanceRemainsReadable()
    {
        // Band 3 C2 stopped synthetic GE tax booking. A historical row that carries the retired
        // tax flow stays readable, keeps its typed loss category and keeps its observed cost.
        Ac legacyTax = Tx.of(1L, null, Ai.ADJUSTMENT,
            Aj.GENERIC, "GE sell tax", "GE", true,
            Collections.singletonList(new Ab(CostKind.GE_TAX_ITEM_ID, "GE sell tax", -1L, 160,
                -160L, Av.GRAND_EXCHANGE)));
        assertEquals(CostKind.LOSS, CostKind.of(legacyTax, legacyTax.getFlows().get(0)));

        Ad session = new Ad("Legacy", 0L);
        session.kf(legacyTax, 100);
        assertEquals(160L, session.metrics(1_000L).costs);
    }

    @Test
    public void rewardChestsAndNeutralStorageAndCrates()
    {
        assertTrue(RewardChestCatalogue.a1("Barrows chest"));
        for (KeyChestCatalogue.Entry chest : KeyChestCatalogue.entries())
        {
            assertTrue(chest.getChestName(), RewardChestCatalogue.a1(chest.getChestName()));
        }
        assertTrue(RewardChestCatalogue.a1("Corrupted Gauntlet"));
        assertTrue(RewardChestCatalogue.a1("Tempoross"));
        assertTrue(RewardChestCatalogue.a1("Wintertodt"));
        assertTrue(RewardChestCatalogue.a1("Guardians of the Rift"));
        assertFalse(RewardChestCatalogue.a1("Goblin"));

        assertEquals(NeutralStorageClassifier.Kind.SEED_VAULT,
            NeutralStorageClassifier.a1("Seed vault"));
        assertEquals(NeutralStorageClassifier.Kind.TOOL_LEPRECHAUN,
            NeutralStorageClassifier.a1("Tool Leprechaun"));
        assertEquals(NeutralStorageClassifier.Kind.MLM_HOPPER,
            NeutralStorageClassifier.a1("Motherlode hopper"));
        assertEquals(NeutralStorageClassifier.Kind.NMZ_COFFER,
            NeutralStorageClassifier.a1("NMZ coffer"));

        assertTrue(RewardChestCatalogue.a1("Bird house"));
        assertTrue(RewardChestCatalogue.a1("Herbiboar"));
        assertTrue(RewardChestCatalogue.a1("Giants' Foundry"));
    }

    @Test
    public void utilityContainersAndDepositBoxEmpty()
    {
        assertEquals("forestry_kit",
            UtilityContainerCatalogue.sv("Forestry kit"));
        assertEquals("plank_sack",
            UtilityContainerCatalogue.sv("Plank sack"));
        assertEquals("silk_lined_herb_sack",
            UtilityContainerCatalogue.sv("Silk-lined herb sack"));
        assertEquals("pre_pot_device",
            UtilityContainerCatalogue.sv("Pre-pot device"));
        assertEquals("pre_pot_device",
            UtilityContainerCatalogue.sv("Filled Pre-pot device"));
        assertTrue(UtilityContainerCatalogue.xg(
            UtilityContainerCatalogue.oq("Fill", false)));
        assertTrue(UtilityContainerCatalogue.xg(
            UtilityContainerCatalogue.oq("Empty", false)));
        assertEquals(UtilityContainerCatalogue.Kind.EMPTY_TO_BANK,
            UtilityContainerCatalogue.oq("Empty", true));
        assertTrue(UtilityContainerCatalogue.xg(
            UtilityContainerCatalogue.Kind.EMPTY_TO_BANK));
        // Wiki storage items added from the 2026-09-13 research pass.
        assertEquals("bolt_pouch", UtilityContainerCatalogue.sv("Bolt pouch"));
        assertEquals("tackle_box", UtilityContainerCatalogue.sv("Tackle box"));
        assertEquals("reagent_pouch", UtilityContainerCatalogue.sv("Reagent pouch"));
        assertEquals("huntsmans_kit", UtilityContainerCatalogue.sv("Huntsman's kit"));
        assertEquals("meat_pouch", UtilityContainerCatalogue.sv("Large meat pouch"));
        assertEquals("fur_pouch", UtilityContainerCatalogue.sv("Small fur pouch"));
        // The rune pouch is normally tracked through its varbits; its custody options must stay
        // neutral regardless, so an untracked pouch cannot book revenue or cost.
        assertEquals("rune_pouch", UtilityContainerCatalogue.sv("Rune pouch"));
        assertEquals("rune_pouch", UtilityContainerCatalogue.sv("Divine rune pouch"));
        assertTrue(UtilityContainerCatalogue.wp("Empty"));
        assertTrue(UtilityContainerCatalogue.wp("Use"));
        assertTrue(UtilityContainerCatalogue.wp("Store"));
        assertTrue(UtilityContainerCatalogue.wp("Remove-1"));
        assertTrue(UtilityContainerCatalogue.wp("Withdraw-5"));
        assertFalse(UtilityContainerCatalogue.wp("Drop"));
    }

    @Test
    public void clueCostPairingAttachesDig()
    {
        ClueCostPairing pairing = new ClueCostPairing();
        pairing.aue("clue-1", "Hard clue");
        assertTrue(pairing.wt("Dig"));
        assertFalse(pairing.wt("Eat shark"));
        assertTrue(pairing.awb().contains("Hard clue"));
    }

    @Test
    public void lmsRaidGimNeutral()
    {
        assertEquals(MinigameTransferClassifier.Event.LMS_ENTER,
            MinigameTransferClassifier.a1("LMS enter loadout"));
        assertEquals(MinigameTransferClassifier.Event.GIM_SHARED_DEPOSIT,
            MinigameTransferClassifier.a1("GIM shared storage deposit"));
        assertTrue(MinigameTransferClassifier.xg(
            MinigameTransferClassifier.Event.RAID_BAG_CLEAR));
    }
}
