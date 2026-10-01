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
        Transaction honest = new Transaction(
            1L, 1L, TransactionType.TRANSFER, Context.TRANSFER,
            "Seed vault transfer", "Transfer", false,
            Collections.singletonList(new Flow(995, "Coins", -100L, 1, -100L)),
            null, "", null);
        assertTrue(AccountingInvariants.transferNeverCounts(honest));
        Transaction wronglyCounted = new Transaction(
            1L, 1L, TransactionType.TRANSFER, Context.TRANSFER,
            "Seed vault transfer", "Transfer", true,
            Collections.singletonList(new Flow(995, "Coins", -100L, 1, -100L)),
            null, "", null);
        assertFalse(AccountingInvariants.transferNeverCounts(wronglyCounted));
    }

    @Test
    public void retiredSyntheticTaxProvenanceRemainsReadable()
    {
        // Band 3 C2 stopped synthetic GE tax booking. A historical row that carries the retired
        // tax flow stays readable, keeps its typed loss category and keeps its observed cost.
        Transaction legacyTax = Tx.of(1L, null, TransactionType.ADJUSTMENT,
            Context.GENERIC, "GE sell tax", "GE", true,
            Collections.singletonList(new Flow(CostKind.GE_TAX_ITEM_ID, "GE sell tax", -1L, 160,
                -160L, PriceSource.GRAND_EXCHANGE)));
        assertEquals(CostKind.LOSS, CostKind.of(legacyTax, legacyTax.getFlows().get(0)));

        Session session = new Session("Legacy", 0L);
        session.addTransaction(legacyTax, 100);
        assertEquals(160L, session.metrics(1_000L).costs);
    }

    @Test
    public void rewardChestsAndNeutralStorageAndCrates()
    {
        assertTrue(RewardChestCatalogue.isPendingRewardName("Barrows chest"));
        for (KeyChestCatalogue.Entry chest : KeyChestCatalogue.entries())
        {
            assertTrue(chest.getChestName(), RewardChestCatalogue.isPendingRewardName(chest.getChestName()));
        }
        assertTrue(RewardChestCatalogue.isPendingRewardName("Corrupted Gauntlet"));
        assertTrue(RewardChestCatalogue.isPendingRewardName("Tempoross"));
        assertTrue(RewardChestCatalogue.isPendingRewardName("Wintertodt"));
        assertTrue(RewardChestCatalogue.isPendingRewardName("Guardians of the Rift"));
        assertFalse(RewardChestCatalogue.isPendingRewardName("Goblin"));

        assertEquals(NeutralStorageClassifier.Kind.SEED_VAULT,
            NeutralStorageClassifier.classify("Seed vault"));
        assertEquals(NeutralStorageClassifier.Kind.TOOL_LEPRECHAUN,
            NeutralStorageClassifier.classify("Tool Leprechaun"));
        assertEquals(NeutralStorageClassifier.Kind.MLM_HOPPER,
            NeutralStorageClassifier.classify("Motherlode hopper"));
        assertEquals(NeutralStorageClassifier.Kind.NMZ_COFFER,
            NeutralStorageClassifier.classify("NMZ coffer"));

        assertTrue(RewardChestCatalogue.isPendingRewardName("Bird house"));
        assertTrue(RewardChestCatalogue.isPendingRewardName("Herbiboar"));
        assertTrue(RewardChestCatalogue.isPendingRewardName("Giants' Foundry"));
    }

    @Test
    public void utilityContainersAndDepositBoxEmpty()
    {
        assertEquals("forestry_kit",
            UtilityContainerCatalogue.familyForItemName("Forestry kit"));
        assertEquals("plank_sack",
            UtilityContainerCatalogue.familyForItemName("Plank sack"));
        assertEquals("silk_lined_herb_sack",
            UtilityContainerCatalogue.familyForItemName("Silk-lined herb sack"));
        assertEquals("pre_pot_device",
            UtilityContainerCatalogue.familyForItemName("Pre-pot device"));
        assertEquals("pre_pot_device",
            UtilityContainerCatalogue.familyForItemName("Filled Pre-pot device"));
        assertTrue(UtilityContainerCatalogue.isOwnershipNeutral(
            UtilityContainerCatalogue.classifyOption("Fill", false)));
        assertTrue(UtilityContainerCatalogue.isOwnershipNeutral(
            UtilityContainerCatalogue.classifyOption("Empty", false)));
        assertEquals(UtilityContainerCatalogue.Kind.EMPTY_TO_BANK,
            UtilityContainerCatalogue.classifyOption("Empty", true));
        assertTrue(UtilityContainerCatalogue.isOwnershipNeutral(
            UtilityContainerCatalogue.Kind.EMPTY_TO_BANK));
        // Wiki storage items added from the 2026-09-13 research pass.
        assertEquals("bolt_pouch", UtilityContainerCatalogue.familyForItemName("Bolt pouch"));
        assertEquals("tackle_box", UtilityContainerCatalogue.familyForItemName("Tackle box"));
        assertEquals("reagent_pouch", UtilityContainerCatalogue.familyForItemName("Reagent pouch"));
        assertEquals("huntsmans_kit", UtilityContainerCatalogue.familyForItemName("Huntsman's kit"));
        assertEquals("meat_pouch", UtilityContainerCatalogue.familyForItemName("Large meat pouch"));
        assertEquals("fur_pouch", UtilityContainerCatalogue.familyForItemName("Small fur pouch"));
        // The rune pouch is normally tracked through its varbits; its custody options must stay
        // neutral regardless, so an untracked pouch cannot book revenue or cost.
        assertEquals("rune_pouch", UtilityContainerCatalogue.familyForItemName("Rune pouch"));
        assertEquals("rune_pouch", UtilityContainerCatalogue.familyForItemName("Divine rune pouch"));
        assertTrue(UtilityContainerCatalogue.isCustodyOption("Empty"));
        assertTrue(UtilityContainerCatalogue.isCustodyOption("Use"));
        assertTrue(UtilityContainerCatalogue.isCustodyOption("Store"));
        assertTrue(UtilityContainerCatalogue.isCustodyOption("Remove-1"));
        assertTrue(UtilityContainerCatalogue.isCustodyOption("Withdraw-5"));
        assertFalse(UtilityContainerCatalogue.isCustodyOption("Drop"));
    }

    @Test
    public void clueCostPairingAttachesDig()
    {
        ClueCostPairing pairing = new ClueCostPairing();
        pairing.beginClue("clue-1", "Hard clue");
        assertTrue(pairing.isClueCost("Dig"));
        assertFalse(pairing.isClueCost("Eat shark"));
        assertTrue(pairing.costNote().contains("Hard clue"));
    }

    @Test
    public void lmsRaidGimNeutral()
    {
        assertEquals(MinigameTransferClassifier.Event.LMS_ENTER,
            MinigameTransferClassifier.classify("LMS enter loadout"));
        assertEquals(MinigameTransferClassifier.Event.GIM_SHARED_DEPOSIT,
            MinigameTransferClassifier.classify("GIM shared storage deposit"));
        assertTrue(MinigameTransferClassifier.isOwnershipNeutral(
            MinigameTransferClassifier.Event.RAID_BAG_CLEAR));
    }
}
