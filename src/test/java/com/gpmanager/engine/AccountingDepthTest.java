package com.gpmanager.engine;

import com.gpmanager.engine.evidence.ContainerEvidence;
import com.gpmanager.engine.evidence.ChargeFamilyIds;
import com.gpmanager.engine.evidence.UtilityContainerCatalogue;
import com.gpmanager.model.KeyChestCatalogue;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
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
        ProfitTransaction honest = new ProfitTransaction(
            1L, 1L, TransactionType.TRANSFER, TrackingContext.TRANSFER,
            "Seed vault transfer", "Transfer", false,
            Collections.singletonList(new ItemFlow(995, "Coins", -100L, 1, -100L)),
            null, "", null);
        assertTrue(AccountingInvariants.transferNeverCounts(honest));
        ProfitTransaction wronglyCounted = new ProfitTransaction(
            1L, 1L, TransactionType.TRANSFER, TrackingContext.TRANSFER,
            "Seed vault transfer", "Transfer", true,
            Collections.singletonList(new ItemFlow(995, "Coins", -100L, 1, -100L)),
            null, "", null);
        assertFalse(AccountingInvariants.transferNeverCounts(wronglyCounted));
    }

    @Test
    public void geTaxXorAndNoTaxOnPlayerTrade()
    {
        List<GeSellTaxBooking.PendingSale> pending = new ArrayList<>();
        pending.add(new GeSellTaxBooking.PendingSale(4151, 1L, 1_000, false));
        List<ItemFlow> flows = new ArrayList<>();
        flows.add(new ItemFlow(995, "Coins", 980L, 1, 980L, ItemPriceSource.GRAND_EXCHANGE));

        GeSellTaxBooking.Result collect = GeSellTaxBooking.applyOnCollect(
            flows, "Collect", true, new ArrayList<>(pending));
        assertTrue(AccountingInvariants.geTaxXorHolds(collect));
        assertTrue(collect.explicitRow);

        GeSellTaxBooking.Result priorCounted = GeSellTaxBooking.applyOnCollect(
            flows, "Collect", true,
            new ArrayList<>(Collections.singletonList(
                new GeSellTaxBooking.PendingSale(4151, 1L, 1_000, true))));
        assertTrue(AccountingInvariants.geTaxXorHolds(priorCounted));
        assertFalse(priorCounted.explicitRow);

        assertEquals(0L, GeSellTaxBooking.applyOnCollect(
            flows, "Player trade", true, new ArrayList<>(pending)).taxBooked);
        assertEquals(0L, GeSellTaxBooking.applyOnCollect(
            flows, "Buy-1 Shark", true, new ArrayList<>(pending)).taxBooked);
        assertEquals(0L, GeSellTaxBooking.applyOnCollect(
            flows, "Sell shop", true, new ArrayList<>(pending)).taxBooked);
        assertTrue(AccountingInvariants.noTaxOnPlayerTradeOrBuy("Player trade", 0L));
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
        assertTrue(RewardChestCatalogue.isOwnershipNeutralGotrDeposit(
            "Guardians of the Rift catalytic deposit"));
        assertFalse(RewardChestCatalogue.isPendingRewardName("Goblin"));

        assertEquals(NeutralStorageClassifier.Kind.SEED_VAULT,
            NeutralStorageClassifier.classify("Seed vault"));
        assertEquals(NeutralStorageClassifier.Kind.TOOL_LEPRECHAUN,
            NeutralStorageClassifier.classify("Tool Leprechaun"));
        assertEquals(NeutralStorageClassifier.Kind.MLM_HOPPER,
            NeutralStorageClassifier.classify("Motherlode hopper"));
        assertEquals(NeutralStorageClassifier.Kind.NMZ_COFFER,
            NeutralStorageClassifier.classify("NMZ coffer"));

        assertEquals(ActivityCrateCatalogue.Kind.BIRDHOUSE,
            ActivityCrateCatalogue.classify("Bird house"));
        assertEquals(ActivityCrateCatalogue.Kind.HERBIBOAR,
            ActivityCrateCatalogue.classify("Herbiboar"));
        assertEquals(ActivityCrateCatalogue.Kind.GIANTS_FOUNDRY,
            ActivityCrateCatalogue.classify("Giants' Foundry"));
        assertTrue(ActivityCrateCatalogue.usesPendingRewards(
            ActivityCrateCatalogue.Kind.BOUNTY_HUNTER_CRATE));
    }

    @Test
    public void utilityContainersAndDepositBoxEmpty()
    {
        assertEquals(ChargeFamilyIds.FORESTRY_KIT,
            UtilityContainerCatalogue.familyForItemName("Forestry kit"));
        assertEquals(ChargeFamilyIds.PLANK_SACK,
            UtilityContainerCatalogue.familyForItemName("Plank sack"));
        assertEquals("silk_lined_herb_sack",
            UtilityContainerCatalogue.familyForItemName("Silk-lined herb sack"));
        assertEquals("pre_pot_device",
            UtilityContainerCatalogue.familyForItemName("Pre-pot device"));
        assertEquals("pre_pot_device",
            UtilityContainerCatalogue.familyForItemName("Filled Pre-pot device"));
        assertEquals(UtilityContainerCatalogue.Ambiguity.NONE,
            UtilityContainerCatalogue.entryFor("pre_pot_device").defaultAmbiguity);
        assertTrue(UtilityContainerCatalogue.isOwnershipNeutral(
            UtilityContainerCatalogue.classifyOption("Fill", false)));
        assertTrue(UtilityContainerCatalogue.isOwnershipNeutral(
            UtilityContainerCatalogue.classifyOption("Empty", false)));
        assertEquals(ContainerEvidence.Kind.EMPTY_TO_BANK,
            UtilityContainerCatalogue.classifyOption("Empty", true));
        assertTrue(UtilityContainerCatalogue.isOwnershipNeutral(
            ContainerEvidence.Kind.EMPTY_TO_BANK));
        assertEquals(UtilityContainerCatalogue.Ambiguity.UNCERTAIN_REVIEW,
            UtilityContainerCatalogue.entryFor(ChargeFamilyIds.PLANK_SACK).defaultAmbiguity);
        // Wiki storage items added from the 2026-09-13 research pass.
        assertEquals("bolt_pouch", UtilityContainerCatalogue.familyForItemName("Bolt pouch"));
        assertEquals("tackle_box", UtilityContainerCatalogue.familyForItemName("Tackle box"));
        assertEquals("reagent_pouch", UtilityContainerCatalogue.familyForItemName("Reagent pouch"));
        assertEquals("huntsmans_kit", UtilityContainerCatalogue.familyForItemName("Huntsman's kit"));
        assertEquals("meat_pouch", UtilityContainerCatalogue.familyForItemName("Large meat pouch"));
        assertEquals("fur_pouch", UtilityContainerCatalogue.familyForItemName("Small fur pouch"));
        assertEquals(UtilityContainerCatalogue.Ambiguity.NONE,
            UtilityContainerCatalogue.entryFor("bolt_pouch").defaultAmbiguity);
    }

    @Test
    public void clueCostPairingAttachesDig()
    {
        ClueCostPairing pairing = new ClueCostPairing();
        pairing.beginClue("clue-1", "Hard clue");
        assertEquals(ClueCostPairing.SupplyKind.CLUE_COST, pairing.classifySpend("Dig"));
        assertEquals(ClueCostPairing.SupplyKind.UNRELATED_USED, pairing.classifySpend("Eat shark"));
        assertTrue(pairing.costNote().contains("Hard clue"));
    }

    @Test
    public void deathReclaimFeeTiers()
    {
        assertEquals(0L, DeathReclaimFees.graveReclaimFee(50_000L, false));
        assertEquals(1_000L, DeathReclaimFees.graveReclaimFee(200_000L, false));
        assertEquals(500L, DeathReclaimFees.graveReclaimFee(200_000L, true));
        assertEquals(5_000L, DeathReclaimFees.deathsOfficeFee(100_000L, false));
        assertEquals(2_500L, DeathReclaimFees.deathsOfficeFee(100_000L, true));
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

    @Test
    public void coinAnchoredProxyStaysUnpricedWithoutOverride()
    {
        assertTrue(CurrencyProxyCatalogue.isCoinAnchored(21649));
        CurrencyProxyCatalogue.Proxy proxy = CurrencyProxyCatalogue.proxyFor(21649);
        assertNotNull(proxy);
        assertEquals(0, CurrencyProxyCatalogue.unitPriceFromCounterpart(proxy, 1));
        assertTrue(CurrencyProxyCatalogue.whyCountedNote(6529).contains("Tokkul"));
    }
}
