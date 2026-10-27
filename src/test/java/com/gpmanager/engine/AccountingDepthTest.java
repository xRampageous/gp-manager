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
            1L, TransactionType.TRANSFER, Context.TRANSFER,
            "Seed vault transfer", "Transfer", false,
            Collections.singletonList(new Flow(995, "Coins", -100L, 1, -100L)),
            null, "", null);
        assertTrue(AccountingInvariants.transferNeverCounts(honest));
        Transaction wronglyCounted = new Transaction(
            1L, TransactionType.TRANSFER, Context.TRANSFER,
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
        assertEquals(CostKind.LOSS, CostKind.of(legacyTax, legacyTax.flows().get(0)));

        Session session = new Session("Legacy", 0L, SessionMode.GENERAL);
        session.addTransaction(legacyTax, 100, false);
        assertEquals(160L, session.metrics(1_000L).cost);
    }

    @Test
    public void rewardChestsAndNeutralStorageAndCrates()
    {
        assertTrue(RewardChestCatalogue.isRewardChest("Barrows chest"));
        for (KeyChestCatalogue.Entry chest : KeyChestCatalogue.entries())
        {
            assertTrue(chest.getChestName(), RewardChestCatalogue.isRewardChest(chest.getChestName()));
        }
        assertTrue(RewardChestCatalogue.isRewardChest("Corrupted Gauntlet"));
        assertTrue(RewardChestCatalogue.isRewardChest("Tempoross"));
        assertTrue(RewardChestCatalogue.isRewardChest("Wintertodt"));
        // GOTR rewards arrive as received loot (no held window): they ride the tray as loot, not
        // the claim path (owner 2026-10-13).
        assertFalse(RewardChestCatalogue.isRewardChest("Guardians of the Rift"));
        assertFalse(RewardChestCatalogue.isRewardChest("Goblin"));

        assertEquals(GameData.msg("seed-vault"), NeutralTransfers.storage("Seed vault"));
        assertEquals(GameData.msg("tool-leprechaun"), NeutralTransfers.storage("Tool Leprechaun"));
        assertEquals(GameData.msg("motherlode-hopper"), NeutralTransfers.storage("Motherlode hopper"));
        assertEquals(GameData.msg("nmz-coffer"), NeutralTransfers.storage("NMZ coffer"));
        assertEquals(GameData.msg("deposit-pool"),
            NeutralTransfers.storage("Deposit-runes Deposit Pool"));

        assertTrue(RewardChestCatalogue.isRewardChest("Bird house"));
        assertTrue(RewardChestCatalogue.isRewardChest("Herbiboar"));
        assertTrue(RewardChestCatalogue.isRewardChest("Giants' Foundry"));
    }

    @Test
    public void utilityContainersAndDepositBoxEmpty()
    {
        assertEquals("forestry_kit",
            UtilityContainerCatalogue.familyFor("Forestry kit"));
        assertEquals("plank_sack",
            UtilityContainerCatalogue.familyFor("Plank sack"));
        assertEquals("silk_lined_herb_sack",
            UtilityContainerCatalogue.familyFor("Silk-lined herb sack"));
        assertEquals("pre_pot_device",
            UtilityContainerCatalogue.familyFor("Pre-pot device"));
        assertEquals("pre_pot_device",
            UtilityContainerCatalogue.familyFor("Filled Pre-pot device"));
        assertTrue(UtilityContainerCatalogue.isNeutral(
            UtilityContainerCatalogue.classify("Fill", false)));
        assertTrue(UtilityContainerCatalogue.isNeutral(
            UtilityContainerCatalogue.classify("Empty", false)));
        assertEquals(UtilityContainerCatalogue.Kind.EMPTY_TO_BANK,
            UtilityContainerCatalogue.classify("Empty", true));
        assertTrue(UtilityContainerCatalogue.isNeutral(
            UtilityContainerCatalogue.Kind.EMPTY_TO_BANK));
        // Wiki storage items added from the 2026-09-13 research pass.
        assertEquals("bolt_pouch", UtilityContainerCatalogue.familyFor("Bolt pouch"));
        assertEquals("tackle_box", UtilityContainerCatalogue.familyFor("Tackle box"));
        assertEquals("reagent_pouch", UtilityContainerCatalogue.familyFor("Reagent pouch"));
        assertEquals("huntsmans_kit", UtilityContainerCatalogue.familyFor("Huntsman's kit"));
        assertEquals("meat_pouch", UtilityContainerCatalogue.familyFor("Large meat pouch"));
        assertEquals("fur_pouch", UtilityContainerCatalogue.familyFor("Small fur pouch"));
        // 2026 container families found by the gap pass (owner 2026-10-13): the gem
        // pouch/satchel/tote/sack chain and the upgraded baskets.
        assertEquals("gem_pouch", UtilityContainerCatalogue.familyFor("Gem pouch"));
        assertEquals("gem_satchel", UtilityContainerCatalogue.familyFor("Gem satchel"));
        assertEquals("gem_tote", UtilityContainerCatalogue.familyFor("Gem tote"));
        assertEquals("gem_sack", UtilityContainerCatalogue.familyFor("Gem sack"));
        assertEquals("forestry_basket", UtilityContainerCatalogue.familyFor("Forestry basket"));
        assertEquals("fish_sack_barrel", UtilityContainerCatalogue.familyFor("Fish sack barrel"));
        // The rune pouch is normally tracked through its varbits; its custody options must stay
        // neutral regardless, so an untracked pouch cannot book revenue or cost.
        assertEquals("rune_pouch", UtilityContainerCatalogue.familyFor("Rune pouch"));
        assertEquals("rune_pouch", UtilityContainerCatalogue.familyFor("Divine rune pouch"));
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
        assertEquals(GameData.msg("lms-invent-wipe"), NeutralTransfers.minigame("LMS enter loadout", -1));
        assertEquals(GameData.msg("gim-shared-storage"),
            NeutralTransfers.minigame("GIM shared storage deposit", -1));
        assertEquals(GameData.msg("raid-bag"), NeutralTransfers.minigame("raid bag clear", -1));
        assertNull("an LMS region-only phrase needs the LMS region", NeutralTransfers.minigame("enter", -1));
    }
}
