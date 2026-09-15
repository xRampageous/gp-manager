package com.gpmanager.ui.ledger;

import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class LedgerItemGroupingTest
{
    @Test
    public void mergesSameItemAcrossSingleAndMultiItemTransactions()
    {
        ProfitTransaction single = txn(100L, flow(526, "Bones", 1L, 50, 50L));
        ProfitTransaction multi = txn(200L,
            flow(526, "Bones", 1L, 50, 50L),
            flow(2138, "Raw chicken", 1L, 60, 60L));

        List<LedgerItemGroup> groups = LedgerItemGrouping.group(
            Arrays.asList(single, multi), contribution -> true);

        LedgerItemGroup bones = findByName(groups, "Bones");
        assertEquals(2, bones.contributionCount());
        assertEquals(2L, bones.getReceivedQuantity());
        assertEquals(0L, bones.getUsedLostQuantity());
        assertEquals(100L, bones.getTotalValue());
        assertTrue(bones.hasMultiItemParents());
        assertEquals("Bones ×2", bones.displayName());
    }

    @Test
    public void keepsDuplicateFlowsWithinOneTransactionAsSeparateContributions()
    {
        ProfitTransaction duplicate = txn(50L,
            flow(995, "Coins", 10L, 1, 10L),
            flow(995, "Coins", 5L, 1, 5L));

        List<LedgerItemGroup> groups = LedgerItemGrouping.group(
            Collections.singletonList(duplicate), contribution -> true);

        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).contributionCount());
        assertEquals(15L, groups.get(0).getTotalQuantityAbs());
        assertEquals(15L, groups.get(0).getTotalValue());
    }

    @Test
    public void keepsReceivedAndUsedLostUnderOneItemParent()
    {
        ProfitTransaction mixed = txn(10L,
            flow(526, "Bones", 1L, 50, 50L),
            flow(526, "Bones", -1L, 50, -50L));

        List<LedgerItemGroup> groups = LedgerItemGrouping.group(
            Collections.singletonList(mixed), contribution -> true);

        assertEquals(1, groups.size());
        LedgerItemGroup bones = findByName(groups, "Bones");
        assertEquals(1L, bones.getReceivedQuantity());
        assertEquals(1L, bones.getUsedLostQuantity());
        assertEquals(0L, bones.getTotalValue());
        assertEquals("+1 / −1", bones.quantitySummary());
        assertEquals("Bones +1 / −1", bones.displayName());
    }

    @Test
    public void mergesDifferentRecordedPricesAndSumsContributionValues()
    {
        ProfitTransaction cheap = txn(1L, flow(526, "Bones", 1L, 30, 30L, ItemPriceSource.GRAND_EXCHANGE));
        ProfitTransaction dear = txn(2L, flow(526, "Bones", 1L, 90, 90L, ItemPriceSource.GRAND_EXCHANGE));

        LedgerItemContribution cheapContribution = LedgerItemContribution.from(cheap, cheap.getFlows().get(0), 0);
        LedgerItemContribution dearContribution = LedgerItemContribution.from(dear, dear.getFlows().get(0), 0);
        assertEquals(cheapContribution.groupKey(), dearContribution.groupKey());

        List<LedgerItemGroup> groups = LedgerItemGrouping.group(
            Arrays.asList(cheap, dear), contribution -> true);

        assertEquals(1, groups.size());
        assertEquals(120L, groups.get(0).getTotalValue());
        assertEquals(2L, groups.get(0).getTotalQuantityAbs());
    }

    @Test
    public void keepsUnpricedExcludedAndReviewAsChildContributions()
    {
        ProfitTransaction priced = txn(1L, flow(526, "Bones", 1L, 50, 50L, ItemPriceSource.GRAND_EXCHANGE));
        ProfitTransaction unpriced = txn(2L, flow(526, "Bones", 1L, 0, 0L, ItemPriceSource.UNPRICED));
        ProfitTransaction excluded = new ProfitTransaction(3L, TransactionType.LOOT, TrackingContext.LOOT,
            "loot", false, Collections.singletonList(
                flow(526, "Bones", 1L, 50, 50L, ItemPriceSource.GRAND_EXCHANGE)));
        ProfitTransaction uncertain = new ProfitTransaction(4L, null, TransactionType.LOOT, TrackingContext.LOOT,
            "loot", "General", true,
            Collections.singletonList(flow(526, "Bones", 1L, 50, 50L, ItemPriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.UNCERTAIN, "uncertain", null);

        List<LedgerItemGroup> groups = LedgerItemGrouping.group(
            Arrays.asList(priced, unpriced, excluded, uncertain), contribution -> true);

        assertEquals(1, groups.size());
        assertEquals(4, groups.get(0).contributionCount());
    }

    @Test
    public void keepsStableGroupIdWhenNewerContributionArrives()
    {
        ProfitTransaction first = txn(10L, flow(526, "Bones", 1L, 50, 50L, ItemPriceSource.GRAND_EXCHANGE));
        String groupId = LedgerItemGrouping.group(Collections.singletonList(first), c -> true)
            .get(0).getGroupId();

        ProfitTransaction newer = txn(20L, flow(526, "Bones", 1L, 50, 50L, ItemPriceSource.GRAND_EXCHANGE));
        LedgerItemGroup merged = LedgerItemGrouping.group(Arrays.asList(first, newer), c -> true).get(0);

        assertEquals(groupId, merged.getGroupId());
        assertEquals(20L, merged.newestTimestamp());
        assertEquals(2, merged.contributionCount());
    }

    @Test
    public void sortsGroupsByItemNameAndContributionsNewestFirst()
    {
        ProfitTransaction olderBones = txn(10L, flow(526, "Bones", 1L, 50, 50L, ItemPriceSource.GRAND_EXCHANGE));
        ProfitTransaction newerFeather = txn(30L, flow(314, "Feather", 5L, 3, 15L, ItemPriceSource.GRAND_EXCHANGE));
        ProfitTransaction midBones = txn(20L, flow(526, "Bones", 1L, 50, 50L, ItemPriceSource.GRAND_EXCHANGE));

        List<LedgerItemGroup> groups = LedgerItemGrouping.group(
            Arrays.asList(olderBones, newerFeather, midBones), contribution -> true);

        assertEquals("Bones", groups.get(0).newest().getItemName());
        assertEquals("Feather", groups.get(1).newest().getItemName());
        assertEquals(20L, groups.get(0).getContributions().get(0).getTimestampEpochMillis());
        assertEquals(10L, groups.get(0).getContributions().get(1).getTimestampEpochMillis());
    }

    @Test
    public void sumsPerItemValuesWithoutDuplicatingTransactionNet()
    {
        // Net would be 110; per-item grouping must keep Bones=50 and chicken=60 separately.
        ProfitTransaction multi = txn(100L,
            flow(526, "Bones", 1L, 50, 50L, ItemPriceSource.GRAND_EXCHANGE),
            flow(2138, "Raw chicken", 1L, 60, 60L, ItemPriceSource.GRAND_EXCHANGE));

        List<LedgerItemGroup> groups = LedgerItemGrouping.group(
            Collections.singletonList(multi), contribution -> true);

        assertEquals(2, groups.size());
        assertEquals(50L, findByName(groups, "Bones").getTotalValue());
        assertEquals(60L, findByName(groups, "Raw chicken").getTotalValue());
        long summed = findByName(groups, "Bones").getTotalValue()
            + findByName(groups, "Raw chicken").getTotalValue();
        assertEquals(multi.getNet(), summed);
        assertFalse(findByName(groups, "Bones").getTotalValue() == multi.getNet());
    }

    @Test
    public void gainFilterKeepsGainContributionsFromMixedTransactions()
    {
        ProfitTransaction mixed = txn(1L,
            flow(526, "Bones", 1L, 50, 50L, ItemPriceSource.GRAND_EXCHANGE),
            flow(229, "Vial", -1L, 2, -2L, ItemPriceSource.GRAND_EXCHANGE));

        List<LedgerItemGroup> gains = LedgerItemGrouping.group(
            Collections.singletonList(mixed), LedgerItemGrouping::matchesGainFilter);
        List<LedgerItemGroup> costs = LedgerItemGrouping.group(
            Collections.singletonList(mixed), LedgerItemGrouping::matchesCostFilter);

        assertEquals(1, gains.size());
        assertEquals("Bones", gains.get(0).newest().getItemName());
        assertEquals(1, costs.size());
        assertEquals("Vial", costs.get(0).newest().getItemName());
    }

    private static LedgerItemGroup findByName(List<LedgerItemGroup> groups, String name)
    {
        for (LedgerItemGroup group : groups)
        {
            if (group.newest() != null && name.equals(group.newest().getItemName()))
            {
                return group;
            }
        }
        throw new AssertionError("Missing group for " + name);
    }

    private static ProfitTransaction txn(long timestamp, ItemFlow... flows)
    {
        return new ProfitTransaction(timestamp, TransactionType.LOOT, TrackingContext.LOOT, "loot", true,
            Arrays.asList(flows));
    }

    private static ItemFlow flow(int itemId, String name, long qty, int unitPrice, long value)
    {
        return flow(itemId, name, qty, unitPrice, value, ItemPriceSource.GRAND_EXCHANGE);
    }

    private static ItemFlow flow(int itemId, String name, long qty, int unitPrice, long value,
        ItemPriceSource source)
    {
        return new ItemFlow(itemId, name, qty, unitPrice, value, source);
    }
}
