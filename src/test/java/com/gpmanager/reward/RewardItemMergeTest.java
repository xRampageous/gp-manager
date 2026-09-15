package com.gpmanager.reward;

import com.gpmanager.model.ItemPriceSource;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RewardItemMergeTest
{
    @Test
    public void mergeSumsKnownStackValues()
    {
        RewardItem a = new RewardItem(1519, "Willow logs", 1L, 22L, true, ItemPriceSource.GRAND_EXCHANGE);
        RewardItem b = new RewardItem(1519, "Willow logs", 1L, 22L, true, ItemPriceSource.GRAND_EXCHANGE);
        RewardItem m = a.merge(b);
        assertEquals(2L, m.getQuantity());
        assertEquals(44L, m.getRecordedValue());
        assertTrue(m.isValueKnown());
    }

    @Test
    public void mergeExtendsKnownValueWhenOtherUnknown()
    {
        RewardItem known = new RewardItem(1519, "Willow logs", 1L, 22L, true, ItemPriceSource.GRAND_EXCHANGE);
        RewardItem unknown = new RewardItem(1519, "Willow logs", 1L, 0L, false, ItemPriceSource.UNKNOWN);
        RewardItem m = known.merge(unknown);
        assertEquals(2L, m.getQuantity());
        assertEquals(44L, m.getRecordedValue());
        assertTrue(m.isValueKnown());
    }
}
