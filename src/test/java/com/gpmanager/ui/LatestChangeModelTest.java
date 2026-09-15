package com.gpmanager.ui;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LatestChangeModelTest
{
    @Test
    public void singleItemUsesNameQuantityAndNet()
    {
        LatestChangeModel model = new LatestChangeModel();
        ProfitTransaction tx = transaction(
            new ItemFlow(1511, "Oak logs", 2L, 39, 78L));
        model.offer(tx, 1_000L, 2_000L);

        LatestChangeNotice notice = model.current(1_100L);
        assertNotNull(notice);
        assertEquals("Oak logs ×2", notice.compactLabel());
        assertEquals(78L, notice.getValueDelta());
        assertEquals(1511, notice.getItemId());
    }

    @Test
    public void multiItemSummarizesWithoutMisusingFirstItemName()
    {
        LatestChangeModel model = new LatestChangeModel();
        ProfitTransaction tx = transaction(
            new ItemFlow(1511, "Oak logs", 2L, 39, 78L),
            new ItemFlow(1513, "Willow logs", 5L, 40, 200L),
            new ItemFlow(1515, "Maple logs", 1L, 148, 148L));
        model.offer(tx, 1_000L, 2_000L);

        LatestChangeNotice notice = model.current(1_100L);
        assertNotNull(notice);
        assertEquals("3 items", notice.compactLabel());
        assertEquals(426L, notice.getValueDelta());
        assertEquals(0, notice.getItemId());
    }

    @Test
    public void sameItemBurstsCoalesceWhileVisible()
    {
        LatestChangeModel model = new LatestChangeModel();
        model.offer(transaction(new ItemFlow(1511, "Oak logs", 2L, 39, 78L)), 1_000L, 2_000L);
        model.offer(transaction(new ItemFlow(1511, "Oak logs", 1L, 39, 39L)), 1_200L, 2_000L);

        LatestChangeNotice notice = model.current(1_300L);
        assertNotNull(notice);
        assertEquals("Oak logs ×3", notice.compactLabel());
        assertEquals(117L, notice.getValueDelta());
        assertTrue(model.getCoalesceCount() >= 1);
    }

    private static ProfitTransaction transaction(ItemFlow... flows)
    {
        return new ProfitTransaction(
            1_000L,
            0L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "",
            "Woodcutting",
            true,
            Arrays.asList(flows));
    }
}
