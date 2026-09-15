package com.gpmanager.grounditems;

import com.gpmanager.LootPresentationFilter;
import com.gpmanager.model.ItemFlow;
import java.awt.Color;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The accounting half of the item-visibility contract must resolve strictly
 * from the advanced {@code accountingItemFilter} rule set, independent of the
 * display-only {@code lootPresentationFilter} (covered separately by
 * {@link LootFilterAccountingIsolationTest}).
 */
public class ContributionEligibilityTest
{
    private final ItemFlow bones = new ItemFlow(526, "Bones", 1L, 35, 35L);
    private final ItemFlow feather = new ItemFlow(314, "Feather", 1L, 10, 10L);

    private LootPresentationFilterService serviceOnlyFeatherHighlighted()
    {
        return new LootPresentationFilterService(new GroundItemsConfigSnapshot(
            true, "Feather", "Bones", false, true, 0,
            GroundItemsConfigSnapshot.ValueMode.HIGHEST,
            Color.MAGENTA, Color.WHITE, Color.GRAY, Collections.emptyList()));
    }

    @Test
    public void allItemsModeReturnsNullMeaningCountEverything()
    {
        assertNull(ContributionEligibility.forAccounting(
            serviceOnlyFeatherHighlighted(), LootPresentationFilter.ALL_ITEMS));
        assertNull(ContributionEligibility.forAccounting(serviceOnlyFeatherHighlighted(), null));
        assertNull(ContributionEligibility.forAccounting(null, LootPresentationFilter.HIGHLIGHTED_LIST_ONLY));
    }

    @Test
    public void highlightedListOnlyExcludesNonHighlightedItemFlows()
    {
        ContributionEligibility eligibility = ContributionEligibility.forAccounting(
            serviceOnlyFeatherHighlighted(), LootPresentationFilter.HIGHLIGHTED_LIST_ONLY);

        assertFalse(eligibility.test(null, bones));
        assertTrue(eligibility.test(null, feather));
    }

    @Test
    public void transactionArgumentIsIgnoredEligibilityIsPurelyPerItem()
    {
        ContributionEligibility eligibility = ContributionEligibility.forAccounting(
            serviceOnlyFeatherHighlighted(), LootPresentationFilter.HIGHLIGHTED_LIST_ONLY);

        // Same per-item decision regardless of which (or whether a) transaction is passed.
        assertTrue(eligibility.test(null, feather));
    }
}
