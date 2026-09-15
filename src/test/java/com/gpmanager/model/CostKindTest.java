package com.gpmanager.model;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CostKindTest
{
    @Test
    public void classifiesSupplyLossAndMarketFromEffectiveCountedFlows()
    {
        assertEquals(CostKind.SUPPLIES, kind(TransactionType.CONSUMPTION, "Eating", 7, -100L));
        // A decrease nobody evidenced (no action kind) is an item gone: a loss, never a supply.
        assertEquals(CostKind.LOSS, kind(TransactionType.CONSUMPTION, "Unexplained", 7, -100L));
        assertEquals(CostKind.SUPPLIES, kind(TransactionType.PROCESSING, "Herblore", 7, -100L));
        assertEquals(CostKind.SUPPLIES, kind(TransactionType.PK_SUPPLY_COST, "PKing", 7, -100L));
        assertEquals(CostKind.LOSS, kind(TransactionType.PK_DEATH_LOSS, "PKing", 7, -100L));
        assertEquals(CostKind.LOSS, kind(TransactionType.PK_FEE, "Death fee", 7, -100L));
        assertEquals(CostKind.LOSS, kind(TransactionType.GAIN, "Death reclaim", 7, -100L));
        assertEquals(CostKind.MARKET, kind(TransactionType.TRADE, "GE buy", 7, -100L));
    }

    @Test
    public void actionTaxAndCorrectionsUseEffectiveContribution()
    {
        ProfitTransaction action = transaction(TransactionType.GAIN, "General", 7, -100L);
        action.setActionKind(ActionKind.EAT);
        assertEquals(CostKind.SUPPLIES, CostKind.of(action, action.getFlows().get(0)));

        ProfitTransaction tax = transaction(TransactionType.ADJUSTMENT, "GE", CostKind.GE_TAX_ITEM_ID, -100L);
        assertEquals(CostKind.LOSS, CostKind.of(tax, tax.getFlows().get(0)));

        ProfitTransaction reasonTax = transaction(TransactionType.ADJUSTMENT, "GE", 7, -100L);
        reasonTax.applyCorrection(TransactionCorrection.COST, 10L, "GE tax settlement");
        assertEquals(CostKind.LOSS, CostKind.of(reasonTax, reasonTax.getFlows().get(0)));

        ProfitTransaction corrected = transaction(TransactionType.LOOT, "Loot", 7, 100L);
        corrected.applyCorrection(TransactionCorrection.COST, 10L, "Owner correction");
        assertEquals(CostKind.LOSS, CostKind.of(corrected, corrected.getFlows().get(0)));
        corrected.applyCorrection(TransactionCorrection.IGNORE, 11L, "Owner exclusion");
        assertEquals(CostKind.NONE, CostKind.of(corrected, corrected.getFlows().get(0)));
    }

    @Test
    public void uncountedTransfersAndDeferredClaimsAreNotCostKinds()
    {
        ProfitTransaction transfer = transaction(TransactionType.TRANSFER, "Bank", 7, -100L);
        assertEquals(CostKind.NONE, CostKind.of(transfer, transfer.getFlows().get(0)));

        ProfitTransaction uncounted = new ProfitTransaction(1L, TransactionType.CONSUMPTION,
            TrackingContext.GENERIC, "review", false,
            Collections.singletonList(new ItemFlow(7, "Food", -1L, 100, -100L)));
        assertEquals(CostKind.NONE, CostKind.of(uncounted, uncounted.getFlows().get(0)));

        ProfitTransaction deferred = transaction(TransactionType.CONSUMPTION, "Chest", 7, -100L);
        deferred.setActionKind(ActionKind.DEFERRED_CLAIM);
        assertEquals(CostKind.NONE, CostKind.of(deferred, deferred.getFlows().get(0)));
    }

    private static CostKind kind(TransactionType type, String activity, int itemId, long value)
    {
        ProfitTransaction transaction = transaction(type, activity, itemId, value);
        return CostKind.of(transaction, transaction.getFlows().get(0));
    }

    private static ProfitTransaction transaction(TransactionType type, String activity,
        int itemId, long value)
    {
        ProfitTransaction transaction = new ProfitTransaction(1L, null, type, TrackingContext.GENERIC,
            activity, activity, true,
            Collections.singletonList(new ItemFlow(itemId, "Item", value >= 0 ? 1L : -1L,
                (int) Math.min(Integer.MAX_VALUE, Math.abs(value)), value)));
        if ("Eating".equals(activity)) transaction.setActionKind(com.gpmanager.model.ActionKind.EAT);
        return transaction;
    }
}
