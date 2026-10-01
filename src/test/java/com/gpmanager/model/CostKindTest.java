package com.gpmanager;

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
        // The death-reclaim fee is a plain typed consumption; the label is not the authority.
        assertEquals(CostKind.LOSS, kind(TransactionType.CONSUMPTION, "Death reclaim", 7, -100L));
        assertEquals(CostKind.MARKET, kind(TransactionType.TRADE, "GE buy", 7, -100L));
    }

    @Test
    public void actionTaxAndCorrectionsUseEffectiveContribution()
    {
        Transaction action = transaction(TransactionType.GAIN, "General", 7, -100L);
        action.setActionKind(ActionKind.EAT);
        assertEquals(CostKind.SUPPLIES, CostKind.of(action, action.getFlows().get(0)));

        Transaction tax = transaction(TransactionType.ADJUSTMENT, "GE", CostKind.GE_TAX_ITEM_ID, -100L);
        assertEquals(CostKind.LOSS, CostKind.of(tax, tax.getFlows().get(0)));

        Transaction corrected = transaction(TransactionType.LOOT, "Loot", 7, 100L);
        corrected.applyCorrection(Correction.COST, 10L, "Owner correction");
        assertEquals(CostKind.LOSS, CostKind.of(corrected, corrected.getFlows().get(0)));
        corrected.applyCorrection(Correction.IGNORE, 11L, "Owner exclusion");
        assertEquals(CostKind.NONE, CostKind.of(corrected, corrected.getFlows().get(0)));
    }

    @Test
    public void presentationTextNeverSelectsTheCategory()
    {
        // A tax-looking explanation cannot reclassify a typed trade.
        Transaction annotated = transaction(TransactionType.TRADE, "GE buy", 7, -100L);
        annotated.rewriteExplanation("Grand Exchange collect. GE tax 2% (capped).");
        assertEquals(CostKind.MARKET, CostKind.of(annotated, annotated.getFlows().get(0)));

        // A death-reclaim activity label cannot reclassify a typed trade either.
        assertEquals(CostKind.MARKET, kind(TransactionType.TRADE, "Death reclaim", 7, -100L));

        // A typed action kind, not a tax-looking label, selects Supplies.
        Transaction labelled = transaction(TransactionType.CONSUMPTION, "ge tax 2% (capped)", 7, -100L);
        labelled.setActionKind(ActionKind.EAT);
        assertEquals("a typed action kind, not the label, selects Supplies",
            CostKind.SUPPLIES, CostKind.of(labelled, labelled.getFlows().get(0)));

        // A free-form owner reason cannot manufacture a tax category on a market movement.
        Transaction reasonTax = transaction(TransactionType.TRADE, "GE", 7, -100L);
        reasonTax.applyCorrection(Correction.COST, 10L, "GE tax settlement");
        assertEquals(CostKind.MARKET, CostKind.of(reasonTax, reasonTax.getFlows().get(0)));

        // The synthetic tax flow id remains the typed tax authority whatever the label says.
        assertEquals(CostKind.LOSS, kind(TransactionType.ADJUSTMENT, "anything", CostKind.GE_TAX_ITEM_ID, -100L));
    }

    @Test
    public void uncountedTransfersAndDeferredClaimsAreNotCostKinds()
    {
        Transaction transfer = transaction(TransactionType.TRANSFER, "Bank", 7, -100L);
        assertEquals(CostKind.NONE, CostKind.of(transfer, transfer.getFlows().get(0)));

        Transaction uncounted = Tx.of(1L, TransactionType.CONSUMPTION,
            Context.GENERIC, "review", false,
            Collections.singletonList(new Flow(7, "Food", -1L, 100, -100L)));
        assertEquals(CostKind.NONE, CostKind.of(uncounted, uncounted.getFlows().get(0)));

        Transaction deferred = transaction(TransactionType.CONSUMPTION, "Chest", 7, -100L);
        deferred.setActionKind(ActionKind.DEFERRED_CLAIM);
        assertEquals(CostKind.NONE, CostKind.of(deferred, deferred.getFlows().get(0)));
    }

    @Test
    public void typedCategoryIsDeterministicAcrossSerialization()
    {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        Transaction tax = transaction(TransactionType.ADJUSTMENT, "GE", CostKind.GE_TAX_ITEM_ID, -100L);
        Transaction trade = transaction(TransactionType.TRADE, "GE buy", 7, -100L);
        Transaction fee = transaction(TransactionType.CONSUMPTION, "Death reclaim", 7, -100L);

        Transaction restoredTax = gson.fromJson(gson.toJson(tax), Transaction.class);
        Transaction restoredTrade = gson.fromJson(gson.toJson(trade), Transaction.class);
        Transaction restoredFee = gson.fromJson(gson.toJson(fee), Transaction.class);

        assertEquals(CostKind.LOSS, CostKind.of(restoredTax, restoredTax.getFlows().get(0)));
        assertEquals(CostKind.MARKET, CostKind.of(restoredTrade, restoredTrade.getFlows().get(0)));
        assertEquals("a legacy reclaim fee row stays a typed loss after reload",
            CostKind.LOSS, CostKind.of(restoredFee, restoredFee.getFlows().get(0)));
    }

    private static CostKind kind(TransactionType type, String activity, int itemId, long value)
    {
        Transaction transaction = transaction(type, activity, itemId, value);
        return CostKind.of(transaction, transaction.getFlows().get(0));
    }

    private static Transaction transaction(TransactionType type, String activity,
        int itemId, long value)
    {
        Transaction transaction = Tx.of(1L, null, type, Context.GENERIC,
            activity, activity, true,
            Collections.singletonList(new Flow(itemId, "Item", value >= 0 ? 1L : -1L,
                (int) Math.min(Integer.MAX_VALUE, Math.abs(value)), value)));
        if ("Eating".equals(activity)) transaction.setActionKind(ActionKind.EAT);
        return transaction;
    }
}
