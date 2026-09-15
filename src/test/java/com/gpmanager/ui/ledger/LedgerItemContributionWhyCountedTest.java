package com.gpmanager.ui.ledger;

import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.LootKeyProvenance;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class LedgerItemContributionWhyCountedTest
{
    @Test
    public void whyCountedSummaryUsesStoredExplanationAndPriceProvenance()
    {
        ItemFlow coins = new ItemFlow(995, "Coins", -1_000L, 1, -1_000L, ItemPriceSource.GRAND_EXCHANGE);
        ProfitTransaction trade = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.TRADE,
            TrackingContext.MARKET,
            "Market note",
            "Market",
            true,
            Collections.singletonList(coins),
            ClassificationConfidence.CONFIRMED,
            "Grand Exchange / shop inventory change observed as TRADE (inventory deltas; GE sell tax applied when enabled).",
            "");
        LedgerItemContribution contribution = LedgerItemContribution.from(trade, coins, 0);
        String why = contribution.whyCountedSummary();
        assertTrue(why, why.contains("TRADE"));
        assertTrue(why, why.contains("counted"));
        assertTrue(why, why.contains("RuneLite market"));
        assertTrue(why, why.contains("GE sell tax"));
        assertTrue(why, why.contains("GE tax"));
    }

    @Test
    public void whyCountedSummaryMarksCurrencyProxyAndSplitShare()
    {
        ItemFlow tokkul = new ItemFlow(6529, "Tokkul", 100L, 10, 1_000L, ItemPriceSource.CURRENCY_PROXY);
        ProfitTransaction loot = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Loot",
            "TzHaar",
            true,
            Collections.singletonList(tokkul),
            ClassificationConfidence.CONFIRMED,
            "Tokkul × onyx proxy",
            "");
        loot.stampSplitProvenance("Split keep 40/100 · Split share", 3_000L);
        LedgerItemContribution contribution = LedgerItemContribution.from(loot, tokkul, 0);
        String why = contribution.whyCountedSummary();
        assertTrue(why, why.contains("Currency proxy"));
        assertTrue(why, why.contains("split share"));
        assertTrue(LedgerItemGrouping.matchesSplitFilter(contribution));
    }

    @Test
    public void whyCountedSummaryShowsLootKeyManifestProvenance()
    {
        ItemFlow key = new ItemFlow(
            net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0, "Loot key", 1L, 0, 0L,
            ItemPriceSource.UNKNOWN);
        ProfitTransaction audit = new ProfitTransaction(
            2_000L, null, TransactionType.TRANSFER, TrackingContext.TRANSFER,
            "Loot key received - value deferred", "PKing", false,
            Collections.singletonList(key), ClassificationConfidence.CONFIRMED,
            "Loot-key pickup retained as an audit row.", "encounter-1");
        LootKeyProvenance provenance = new LootKeyProvenance(
            key.getItemId(), 1L, "encounter-1", "Rival", 1_000L, 2_000L);
        provenance.captureManifest(Collections.singletonMap(995, 3L),
            Collections.singletonMap(995, 100L), 300L, true, 2_500L);
        audit.addLootKeyProvenance(provenance);

        LedgerItemContribution contribution = LedgerItemContribution.from(audit, key, 0);
        assertTrue(contribution.whyCountedSummary().contains("Loot key · 300 gp manifest · from Rival · pending"));
    }
}
