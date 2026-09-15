package com.gpmanager.persistence;

import com.google.gson.Gson;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.WealthLocationSnapshot;
import com.gpmanager.model.WealthLocationsSnapshot;
import com.gpmanager.model.WealthSnapshotHistory;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WealthSnapshotHistoryPersistenceTest
{
    @Test
    public void wealthHistoryRoundTripsThroughSavedStateJson()
    {
        long capturedAt = 1_000_000L;
        WealthLocationSnapshot.Item item = new WealthLocationSnapshot.Item(0, 995, "Coins", 25,
            1, ItemPriceSource.FACE_VALUE, capturedAt);
        WealthLocationSnapshot location = new WealthLocationSnapshot("bank", "Bank",
            WealthLocationSnapshot.Status.AVAILABLE, 25L, capturedAt,
            Collections.singletonList(item), "");
        WealthSnapshotHistory history = WealthSnapshotHistory.empty().append(
            new WealthLocationsSnapshot(capturedAt, Collections.singletonList(location)), capturedAt);

        SavedState original = new SavedState();
        original.setWealthSnapshotHistory(history);
        Gson gson = new Gson();
        SavedState restored = gson.fromJson(gson.toJson(original), SavedState.class);

        assertEquals(SavedState.CURRENT_SCHEMA_VERSION, restored.getSchemaVersion());
        assertEquals(1, restored.getWealthSnapshotHistory().getSnapshots().size());
        WealthSnapshotHistory.Location restoredBank = restored.getWealthSnapshotHistory()
            .getLatest().getLocation("bank");
        assertNotNull(restoredBank);
        assertEquals(25L, restoredBank.getValueGp());
        assertEquals(995, restoredBank.getHoldings().get(0).getItemId());
        assertEquals(ItemPriceSource.FACE_VALUE, restoredBank.getHoldings().get(0).getPriceSource());
    }

    @Test
    public void legacySavedStateWithoutWealthHistoryLoadsEmptyHistory()
    {
        SavedState legacy = new Gson().fromJson("{}", SavedState.class);
        assertNotNull(legacy.getWealthSnapshotHistory());
        assertTrue(legacy.getWealthSnapshotHistory().getSnapshots().isEmpty());
    }

    @Test
    public void savedStateKeepsAnIndependentImmutableHistoryCopy()
    {
        long now = 2_000L;
        WealthSnapshotHistory source = WealthSnapshotHistory.empty().append(
            new WealthLocationsSnapshot(now, Collections.emptyList()), now);
        SavedState state = new SavedState();
        state.setWealthSnapshotHistory(source);

        WealthSnapshotHistory next = source.append(
            new WealthLocationsSnapshot(now + 1L, Collections.emptyList()), now + 1L);
        assertEquals(1, state.getWealthSnapshotHistory().getSnapshots().size());
        assertEquals(2, next.getSnapshots().size());
        assertEquals(1, state.getWealthSnapshotHistory().copyForPersistence().getSnapshots().size());
    }
}
