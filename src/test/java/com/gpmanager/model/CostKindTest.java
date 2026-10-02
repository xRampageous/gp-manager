package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CostKindTest
{
    @Test
    public void classifiesSupplyLossAndMarketFromEffectiveCountedFlows()
    {
        assertEquals(CostKind.SUPPLIES, kind(Ai.CONSUMPTION, "Eating", 7, -100L));
        // A decrease nobody evidenced (no action kind) is an item gone: a loss, never a supply.
        assertEquals(CostKind.LOSS, kind(Ai.CONSUMPTION, "Unexplained", 7, -100L));
        assertEquals(CostKind.SUPPLIES, kind(Ai.PROCESSING, "Herblore", 7, -100L));
        assertEquals(CostKind.SUPPLIES, kind(Ai.PK_SUPPLY_COST, "PKing", 7, -100L));
        assertEquals(CostKind.LOSS, kind(Ai.PK_DEATH_LOSS, "PKing", 7, -100L));
        assertEquals(CostKind.LOSS, kind(Ai.PK_FEE, "Death fee", 7, -100L));
        // The death-reclaim fee is a plain typed consumption; the label is not the authority.
        assertEquals(CostKind.LOSS, kind(Ai.CONSUMPTION, "Death reclaim", 7, -100L));
        assertEquals(CostKind.MARKET, kind(Ai.TRADE, "GE buy", 7, -100L));
    }

    /** Owner 1.1: burying, scattering and offering are Supplies, like eating; never a loss. */
    @Test
    public void buryScatterAndOfferAreSupplies()
    {
        for (Au kind : new Au[] {Au.BURY, Au.SCATTER, Au.OFFER})
        {
            Ac spend = transaction(Ai.CONSUMPTION, "Prayer", 526, -100L);
            spend.setActionKind(kind);
            assertEquals(kind.name(), CostKind.SUPPLIES, CostKind.of(spend, spend.getFlows().get(0)));
        }
    }

    @Test
    public void actionTaxAndCorrectionsUseEffectiveContribution()
    {
        Ac action = transaction(Ai.GAIN, "General", 7, -100L);
        action.setActionKind(Au.EAT);
        assertEquals(CostKind.SUPPLIES, CostKind.of(action, action.getFlows().get(0)));

        Ac tax = transaction(Ai.ADJUSTMENT, "GE", CostKind.GE_TAX_ITEM_ID, -100L);
        assertEquals(CostKind.LOSS, CostKind.of(tax, tax.getFlows().get(0)));

        Ac corrected = transaction(Ai.LOOT, "Loot", 7, 100L);
        corrected.ko(Ah.COST, 10L, "Owner correction");
        assertEquals(CostKind.LOSS, CostKind.of(corrected, corrected.getFlows().get(0)));
        corrected.ko(Ah.IGNORE, 11L, "Owner exclusion");
        assertEquals(CostKind.NONE, CostKind.of(corrected, corrected.getFlows().get(0)));
    }

    @Test
    public void presentationTextNeverSelectsTheCategory()
    {
        // A tax-looking explanation cannot reclassify a typed trade.
        Ac annotated = transaction(Ai.TRADE, "GE buy", 7, -100L);
        annotated.agt("Grand Exchange collect. GE tax 2% (capped).");
        assertEquals(CostKind.MARKET, CostKind.of(annotated, annotated.getFlows().get(0)));

        // A death-reclaim activity label cannot reclassify a typed trade either.
        assertEquals(CostKind.MARKET, kind(Ai.TRADE, "Death reclaim", 7, -100L));

        // A typed action kind, not a tax-looking label, selects Supplies.
        Ac labelled = transaction(Ai.CONSUMPTION, "ge tax 2% (capped)", 7, -100L);
        labelled.setActionKind(Au.EAT);
        assertEquals("a typed action kind, not the label, selects Supplies",
            CostKind.SUPPLIES, CostKind.of(labelled, labelled.getFlows().get(0)));

        // A free-form owner reason cannot manufacture a tax category on a market movement.
        Ac reasonTax = transaction(Ai.TRADE, "GE", 7, -100L);
        reasonTax.ko(Ah.COST, 10L, "GE tax settlement");
        assertEquals(CostKind.MARKET, CostKind.of(reasonTax, reasonTax.getFlows().get(0)));

        // The synthetic tax flow id remains the typed tax authority whatever the label says.
        assertEquals(CostKind.LOSS, kind(Ai.ADJUSTMENT, "anything", CostKind.GE_TAX_ITEM_ID, -100L));
    }

    @Test
    public void uncountedTransfersAndDeferredClaimsAreNotCostKinds()
    {
        Ac transfer = transaction(Ai.TRANSFER, "Bank", 7, -100L);
        assertEquals(CostKind.NONE, CostKind.of(transfer, transfer.getFlows().get(0)));

        Ac uncounted = Tx.of(1L, Ai.CONSUMPTION,
            Aj.GENERIC, "review", false,
            Collections.singletonList(new Ab(7, "Food", -1L, 100, -100L)));
        assertEquals(CostKind.NONE, CostKind.of(uncounted, uncounted.getFlows().get(0)));

        Ac deferred = transaction(Ai.CONSUMPTION, "Chest", 7, -100L);
        deferred.setActionKind(Au.DEFERRED_CLAIM);
        assertEquals(CostKind.NONE, CostKind.of(deferred, deferred.getFlows().get(0)));
    }

    @Test
    public void typedCategoryIsDeterministicAcrossSerialization()
    {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        Ac tax = transaction(Ai.ADJUSTMENT, "GE", CostKind.GE_TAX_ITEM_ID, -100L);
        Ac trade = transaction(Ai.TRADE, "GE buy", 7, -100L);
        Ac fee = transaction(Ai.CONSUMPTION, "Death reclaim", 7, -100L);

        Ac restoredTax = gson.fromJson(gson.toJson(tax), Ac.class);
        Ac restoredTrade = gson.fromJson(gson.toJson(trade), Ac.class);
        Ac restoredFee = gson.fromJson(gson.toJson(fee), Ac.class);

        assertEquals(CostKind.LOSS, CostKind.of(restoredTax, restoredTax.getFlows().get(0)));
        assertEquals(CostKind.MARKET, CostKind.of(restoredTrade, restoredTrade.getFlows().get(0)));
        assertEquals("a legacy reclaim fee row stays a typed loss after reload",
            CostKind.LOSS, CostKind.of(restoredFee, restoredFee.getFlows().get(0)));
    }

    private static CostKind kind(Ai type, String activity, int itemId, long value)
    {
        Ac transaction = transaction(type, activity, itemId, value);
        return CostKind.of(transaction, transaction.getFlows().get(0));
    }

    private static Ac transaction(Ai type, String activity,
        int itemId, long value)
    {
        Ac transaction = Tx.of(1L, null, type, Aj.GENERIC,
            activity, activity, true,
            Collections.singletonList(new Ab(itemId, "Item", value >= 0 ? 1L : -1L,
                (int) Math.min(Integer.MAX_VALUE, Math.abs(value)), value)));
        if ("Eating".equals(activity)) transaction.setActionKind(Au.EAT);
        return transaction;
    }
}
