package com.gpmanager.model;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WealthSnapshotHistoryTest
{
    private static final long DAY = 24L * 60L * 60L * 1000L;

    @Test
    public void retentionKeepsEveryRecentVisitDailySnapshotsAndWeeklySnapshots()
    {
        long now = 2_000L * DAY;
        WealthSnapshotHistory history = WealthSnapshotHistory.empty();

        history = history.append(snapshot(now - 10L * DAY), now);
        history = history.append(snapshot(now - 9L * DAY), now);

        long dailyDay = now - 45L * DAY;
        history = history.append(snapshot(dailyDay + 1_000L), now);
        history = history.append(snapshot(dailyDay + 2_000L), now);
        history = history.append(snapshot(dailyDay + DAY + 1_000L), now);

        LocalDate weeklyDate = LocalDate.ofEpochDay(now / DAY).minusDays(400L)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long weekStart = weeklyDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        history = history.append(snapshot(weekStart + 1_000L), now);
        history = history.append(snapshot(weekStart + DAY + 1_000L), now);
        history = history.append(snapshot(weekStart + 8L * DAY + 1_000L), now);

        List<WealthSnapshotHistory.Snapshot> values = history.getSnapshots();
        assertEquals(6, values.size());
        assertEquals(weekStart + DAY + 1_000L, values.get(0).getCapturedAtEpochMillis());
        assertEquals(weekStart + 8L * DAY + 1_000L, values.get(1).getCapturedAtEpochMillis());
        assertEquals(dailyDay + 2_000L, values.get(2).getCapturedAtEpochMillis());
        assertEquals(dailyDay + DAY + 1_000L, values.get(3).getCapturedAtEpochMillis());
        assertEquals(now - 10L * DAY, values.get(4).getCapturedAtEpochMillis());
        assertEquals(now - 9L * DAY, values.get(5).getCapturedAtEpochMillis());
        assertEquals(values.get(5).getCapturedAtEpochMillis(), history.getLatest().getCapturedAtEpochMillis());
        assertEquals(2, history.getTimeline(20, now).size());
    }

    @Test
    public void locationRetainsTopHundredItemsAndAggregatesTheRemainder()
    {
        long now = 10_000L;
        List<WealthLocationSnapshot.Item> items = new ArrayList<>();
        long total = 0;
        for (int id = 1; id <= 102; id++)
        {
            items.add(new WealthLocationSnapshot.Item(id, id, "Item " + id, 1L, id,
                ItemPriceSource.GRAND_EXCHANGE, now));
            total += id;
        }
        WealthLocationSnapshot location = new WealthLocationSnapshot("bank", "Bank",
            WealthLocationSnapshot.Status.AVAILABLE, total, now, items, "");

        WealthSnapshotHistory history = WealthSnapshotHistory.empty().append(
            new WealthLocationsSnapshot(now, Collections.singletonList(location)), now);
        WealthSnapshotHistory.Location retained = history.getLatest().getLocation("bank");

        assertEquals(101, retained.getHoldings().size());
        assertEquals(102, retained.getHoldings().get(0).getItemId());
        WealthSnapshotHistory.Holding remainder = retained.getHoldings().get(100);
        assertTrue(remainder.isRemainder());
        assertEquals(2L, remainder.getRemainderItemCount());
        assertEquals(3L, remainder.getValueGp());
        assertEquals(total, retained.getValueGp());
        assertEquals(102, retained.toWealthLocationSnapshot().getItems().size() + 2);
    }

    @Test
    public void hardSnapshotCapIsExposedAndDropsOldestRows()
    {
        long now = 90L * DAY;
        WealthSnapshotHistory history = WealthSnapshotHistory.empty();
        for (int i = 0; i <= WealthSnapshotHistory.MAX_SNAPSHOTS; i++)
        {
            history = history.append(snapshot(now - DAY + i), now);
        }
        assertEquals(WealthSnapshotHistory.MAX_SNAPSHOTS, history.getSnapshotCap());
        assertEquals(WealthSnapshotHistory.MAX_SNAPSHOTS, history.getSnapshots().size());
        assertTrue(history.isCapped());
        assertEquals(now - DAY + 1L, history.getSnapshots().get(0).getCapturedAtEpochMillis());
    }

    @Test
    public void publicCollectionsAreImmutableAndReturnedRowsAreCopies()
    {
        long now = 50_000L;
        WealthSnapshotHistory history = WealthSnapshotHistory.empty().append(
            new WealthLocationsSnapshot(now, Collections.singletonList(location(now))), now);
        WealthSnapshotHistory.Snapshot first = history.getLatest();
        try
        {
            history.getSnapshots().clear();
            fail("snapshot history list must be immutable");
        }
        catch (UnsupportedOperationException expected)
        {
            assertNotNull(expected);
        }
        try
        {
            first.getLocation("bank").getHoldings().clear();
            fail("holding list must be immutable");
        }
        catch (UnsupportedOperationException expected)
        {
            assertNotNull(expected);
        }
        WealthSnapshotHistory appended = history.append(snapshot(now + 1L), now + 1L);
        assertEquals(1, history.getSnapshots().size());
        assertEquals(2, appended.getSnapshots().size());
        assertNull(history.getLatest().getLocation("missing"));
        assertFalse(history.isCapped());
    }

    private static WealthLocationsSnapshot snapshot(long capturedAt)
    {
        return new WealthLocationsSnapshot(capturedAt,
            Collections.singletonList(location(capturedAt)));
    }

    private static WealthLocationSnapshot location(long capturedAt)
    {
        WealthLocationSnapshot.Item item = new WealthLocationSnapshot.Item(0, 995, "Coins", 5,
            1, ItemPriceSource.FACE_VALUE, capturedAt);
        return new WealthLocationSnapshot("bank", "Bank", WealthLocationSnapshot.Status.AVAILABLE,
            5L, capturedAt, Collections.singletonList(item), "");
    }
}
