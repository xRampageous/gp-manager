package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.CoinStore;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.WealthAnchor;
import com.gpmanager.model.WealthBreakdown;
import com.gpmanager.model.WealthChangeBreakdown;
import com.gpmanager.model.WealthChangeFacts;
import com.gpmanager.model.WealthLocationSnapshot;
import com.gpmanager.model.WealthLocationsSnapshot;
import com.gpmanager.model.WealthSnapshotHistory;
import com.gpmanager.model.WealthTopMover;
import com.gpmanager.model.WealthTrendPoint;
import com.gpmanager.model.DailyRollup;
import com.gpmanager.persistence.SavedState;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WealthHistoryEngineTest
{
    @Test
    public void observedCoinStoresAreWealthOnlyAndExpire()
    {
        GpManagerEngine engine = engine(true);
        engine.observeCoinStore(CoinStore.NMZ_COFFER, 777L, 10_000L);
        engine.recordWealthSnapshot(snapshot(10_000L, 100), 10_000L);
        WealthSnapshotHistory.Snapshot saved = engine.getWealthTimeline(1, 10_000L).get(0);
        assertEquals(777L, saved.getLocation(CoinStore.NMZ_COFFER.getLocationId()).getValueGp());
        engine.recordWealthSnapshot(snapshot(10_000L + GpManagerEngine.COIN_STORE_FRESHNESS_MILLIS + 1L, 100),
            10_000L + GpManagerEngine.COIN_STORE_FRESHNESS_MILLIS + 1L);
        assertTrue(engine.getWealthTimeline(400, 10_000L + GpManagerEngine.COIN_STORE_FRESHNESS_MILLIS + 1L)
            .get(1).getLocation(CoinStore.NMZ_COFFER.getLocationId()) == null);
        assertEquals(0L, engine.getMetrics(10_000L).getNet());
    }
    @Test
    public void coinStoresDeriveTheCoffersSourceOncePlacedAndSurviveASave()
    {
        long now = 10_000L;
        GpManagerEngine engine = engine(true);
        List<WealthLocationSnapshot> sources = completeWealthLocations(now);
        sources.removeIf(location -> "coffers".equals(location.getId()));

        // One store fresh, two never seen: Other stays unavailable and names what is missing.
        engine.observeCoinStore(CoinStore.NMZ_COFFER, 500L, now);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(now, sources), now);
        assertFalse(engine.getWealthBreakdown(now).getGroup(WealthBreakdown.Group.OTHER).isValueAvailable());
        assertEquals(Arrays.asList(CoinStore.BLAST_FURNACE_COFFER, CoinStore.SERVANT_MONEYBAG), engine.getCoinStoreGaps(now));

        // The owner never uses a servant; the furnace gets read once: the derived source is complete.
        engine.markCoinStoreUnused(CoinStore.SERVANT_MONEYBAG, true);
        engine.observeCoinStore(CoinStore.BLAST_FURNACE_COFFER, 250L, now + 1L);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(now + 2L, sources), now + 2L);
        assertTrue(engine.getCoinStoreGaps(now + 2L).isEmpty());
        WealthBreakdown breakdown = engine.getWealthBreakdown(now + 2L);
        WealthBreakdown.GroupValue other = breakdown.getGroup(WealthBreakdown.Group.OTHER);
        assertTrue(other.isValueAvailable());
        assertTrue(breakdown.isTotalAvailable());
        WealthSnapshotHistory.Snapshot latest = engine.getWealthTimeline(1, now + 2L).get(engine.getWealthTimeline(1, now + 2L).size() - 1);
        assertEquals(750L, latest.getLocation("coffers").getValueGp());
        assertEquals("Coffers", latest.getLocation("coffers").getTitle());
        assertEquals("NMZ coffer", latest.getLocation(CoinStore.NMZ_COFFER.getLocationId()).getTitle());

        // Observations and the unused mark survive a save and restore; a fresh read lifts the mark.
        SavedState state = engine.createSavedState();
        GpManagerEngine restored = engine(true);
        restored.restore(state, now + 3L);
        assertTrue(restored.isCoinStoreUnused(CoinStore.SERVANT_MONEYBAG));
        assertTrue(restored.getCoinStoreGaps(now + 3L).isEmpty());
        restored.observeCoinStore(CoinStore.SERVANT_MONEYBAG, 10L, now + 4L);
        assertFalse(restored.isCoinStoreUnused(CoinStore.SERVANT_MONEYBAG));
        assertEquals(0L, restored.getMetrics(now + 4L).getNet());
    }

    @Test
    public void bankVisitHistoryProvidesAgedLatestTimelineAndMarketBreakdownWithoutNetSideEffects()
    {
        GpManagerEngine engine = engine(true);
        engine.recordWealthSnapshot(snapshot(10_000L, 100), 10_000L);
        engine.recordWealthSnapshot(snapshot(20_000L, 150), 20_000L);

        assertEquals(2, engine.getWealthTimeline(1, 20_000L).size());
        assertEquals(10_000L, engine.getLatestWealth(30_000L).getAgeMillis());
        assertEquals(WealthSnapshotHistory.MAX_SNAPSHOTS, engine.getWealthSnapshotCap());

        WealthChangeBreakdown change = engine.getWealthChangeSince(WealthAnchor.LAST_BANK_VISIT, 20_000L);
        assertTrue(change.isAvailable());
        assertEquals(0L, change.getEarnedGp());
        assertEquals(50L, change.getMarketGp());
        assertEquals(0L, change.getUnexplainedGp());
        assertEquals(change.getEarnedGp() + change.getMarketGp() + change.getUnexplainedGp(),
            50L);

        assertEquals(0L, engine.getMetrics(20_000L).getNet());
        assertEquals(1, engine.getWealthTopMovers(WealthAnchor.LAST_BANK_VISIT, 5, 20_000L).size());
        assertEquals(WealthTopMover.Reason.MARKET,
            engine.getWealthTopMovers(WealthAnchor.LAST_BANK_VISIT, 5, 20_000L).get(0).getReason());
    }

    @Test
    public void trackingOffLeavesDurableHistoryUntouched()
    {
        GpManagerEngine engine = engine(false);
        engine.recordWealthSnapshot(snapshot(10_000L, 100), 10_000L);
        assertFalse(engine.getLatestWealth(10_000L).isAvailable());
    }

    @Test
    public void explicitTrackingResetClearsWealthHistoryToo()
    {
        GpManagerEngine engine = engine(true);
        engine.recordWealthSnapshot(snapshot(10_000L, 100), 10_000L);
        engine.resetTrackingData(20_000L);
        assertFalse(engine.getLatestWealth(20_000L).isAvailable());
    }

    @Test
    public void historyComparisonRequiresComparableLocations()
    {
        GpManagerEngine engine = engine(true);
        engine.recordWealthSnapshot(snapshot(10_000L, 100), 10_000L);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(20_000L,
            Collections.singletonList(location(20_000L, "bank", 150))), 20_000L);

        assertFalse(engine.getWealthChangeSince(WealthAnchor.LAST_BANK_VISIT, 20_000L).isAvailable());
    }

    @Test
    public void itemRemainderMakesDerivedComparisonAndMoversUnavailable()
    {
        GpManagerEngine engine = engine(true);
        engine.recordWealthSnapshot(snapshotWithManyItems(10_000L, 100), 10_000L);
        engine.recordWealthSnapshot(snapshotWithManyItems(20_000L, 125), 20_000L);

        assertFalse(engine.getWealthChangeSince(WealthAnchor.LAST_BANK_VISIT, 20_000L).isAvailable());
        assertTrue(engine.getWealthTopMovers(WealthAnchor.LAST_BANK_VISIT, 5, 20_000L).isEmpty());
    }

    @Test
    public void missingRollupForAnOverlappingProfileSessionFailsClosed()
    {
        GpManagerEngine engine = engine(true);
        engine.ensureSession(5_000L);
        engine.recordWealthSnapshot(snapshot(10_000L, 100), 10_000L);
        engine.recordWealthSnapshot(snapshot(20_000L, 125), 20_000L);

        assertFalse(engine.getWealthChangeSince(WealthAnchor.LAST_BANK_VISIT, 20_000L).isAvailable());
    }

    @Test
    public void calendarAnchorsUseTheNearestPriorReadAndFailWhenNoPriorReadExists()
    {
        long now = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        ZoneId zone = ZoneId.systemDefault();
        long todayStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli();
        long olderRead = todayStart - 5L * 24L * 60L * 60L * 1000L;

        GpManagerEngine withOlderBaseline = engine(true);
        withOlderBaseline.recordWealthSnapshot(snapshot(olderRead, 100), olderRead);
        withOlderBaseline.recordWealthSnapshot(snapshot(now, 125), now);
        WealthChangeBreakdown sinceTodayAnchor = withOlderBaseline.getWealthChangeSince(
            WealthAnchor.TODAY, now);
        assertTrue(sinceTodayAnchor.isAvailable());
        assertEquals(25L, sinceTodayAnchor.getMarketGp());

        GpManagerEngine withoutPriorBaseline = engine(true);
        withoutPriorBaseline.recordWealthSnapshot(snapshot(now, 125), now);
        assertFalse(withoutPriorBaseline.getWealthChangeSince(WealthAnchor.TODAY, now).isAvailable());
        assertFalse(withoutPriorBaseline.getWealthChangeSince(WealthAnchor.THIRTY_DAYS, now).isAvailable());
    }

    @Test
    public void sevenDayAnchorUsesLatestCaptureAtOrBeforeElapsedCutoff()
    {
        long now = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        long cutoff = now - 7L * 24L * 60L * 60L * 1000L;
        GpManagerEngine engine = engine(true);
        engine.recordWealthSnapshot(snapshot(cutoff - 1L, 100), cutoff - 1L);
        engine.recordWealthSnapshot(snapshot(cutoff, 200), cutoff);
        engine.recordWealthSnapshot(snapshot(cutoff + 1L, 300), cutoff + 1L);
        engine.recordWealthSnapshot(snapshot(now, 400), now);
        engine.recordWealthSnapshot(snapshot(now + 1_000L, 900), now + 1_000L);

        WealthChangeBreakdown change = engine.getWealthChangeSince(WealthAnchor.SEVEN_DAYS, now);
        assertTrue(change.isAvailable());
        assertEquals(200L, change.getMarketGp());
    }

    @Test
    public void breakdownGroupsAllRequiredSourcesAndFlagsTopHundredRemainder()
    {
        long now = 10_000L;
        GpManagerEngine engine = engine(true);
        List<WealthLocationSnapshot> sources = completeWealthLocations(now);
        List<WealthLocationSnapshot.Item> bankItems = new java.util.ArrayList<>();
        long bankTotal = 0L;
        for (int itemId = 1; itemId <= WealthSnapshotHistory.MAX_ITEMS_PER_LOCATION + 1; itemId++)
        {
            bankItems.add(new WealthLocationSnapshot.Item(itemId, itemId, "Bank item " + itemId,
                1L, 10, ItemPriceSource.GRAND_EXCHANGE, now));
            bankTotal += 10L;
        }
        sources.set(0, new WealthLocationSnapshot("bank", "Bank",
            WealthLocationSnapshot.Status.AVAILABLE, bankTotal, now, bankItems, "read"));
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(now, sources), now);

        WealthBreakdown breakdown = engine.getWealthBreakdown(now);
        assertTrue(breakdown.isTotalAvailable());
        assertEquals(bankTotal + 75L,
            breakdown.getTotalGp().longValue());
        assertEquals(bankTotal, breakdown.getGroup(WealthBreakdown.Group.BANK).getValueGp().longValue());
        assertEquals(12L, breakdown.getGroup(WealthBreakdown.Group.EQUIPPED).getValueGp().longValue());
        assertEquals(24L, breakdown.getGroup(WealthBreakdown.Group.GRAND_EXCHANGE).getValueGp().longValue());
        WealthBreakdown.GroupValue other = breakdown.getGroup(WealthBreakdown.Group.OTHER);
        assertEquals(39L, other.getValueGp().longValue());
        assertTrue(other.getSourceLocationIds().contains("rune_pouch"));
        assertTrue(other.getSourceLocationIds().contains("coffers"));
        assertTrue(breakdown.getGroup(WealthBreakdown.Group.BANK).hasRemainder());
        assertTrue(breakdown.isShareAvailable(WealthBreakdown.Group.BANK));
    }

    @Test
    public void breakdownMissingOrUnpricedSourceIsUnavailableRatherThanZero()
    {
        long now = 10_000L;
        GpManagerEngine engine = engine(true);
        List<WealthLocationSnapshot> sources = completeWealthLocations(now);
        sources.removeIf(location -> "coffers".equals(location.getId()));
        sources.set(0, new WealthLocationSnapshot("bank", "Bank",
            WealthLocationSnapshot.Status.UNPRICED, 0L, now,
            Collections.emptyList(), "unpriced"));
        List<WealthLocationSnapshot.Item> inventoryItems = new java.util.ArrayList<>();
        for (int itemId = 1; itemId <= WealthSnapshotHistory.MAX_ITEMS_PER_LOCATION + 1; itemId++)
        {
            inventoryItems.add(new WealthLocationSnapshot.Item(itemId, itemId,
                "Inventory item " + itemId, 1L, 10, ItemPriceSource.GRAND_EXCHANGE, now));
        }
        sources.set(4, new WealthLocationSnapshot("inventory", "Inventory",
            WealthLocationSnapshot.Status.AVAILABLE,
            (long) inventoryItems.size() * 10L, now, inventoryItems, "read"));
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(now, sources), now);

        WealthBreakdown breakdown = engine.getWealthBreakdown(now);
        WealthBreakdown.GroupValue bank = breakdown.getGroup(WealthBreakdown.Group.BANK);
        WealthBreakdown.GroupValue other = breakdown.getGroup(WealthBreakdown.Group.OTHER);
        assertFalse(bank.isValueAvailable());
        assertEquals(null, bank.getValueGp());
        assertTrue(bank.getUnavailableLocationIds().contains("bank"));
        assertFalse(other.isValueAvailable());
        assertTrue(other.getMissingLocationIds().contains("coffers"));
        assertTrue("Observed remainder must survive other missing-source failures", other.hasRemainder());
        assertFalse(breakdown.isTotalAvailable());
        assertFalse(breakdown.isShareAvailable(WealthBreakdown.Group.BANK));
    }

    @Test
    public void breakdownIgnoresCapturesLaterThanItsAsOfClock()
    {
        long now = 10_000L;
        GpManagerEngine engine = engine(true);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(now,
            completeWealthLocations(now, 100L)), now);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(now + 1L,
            completeWealthLocations(now + 1L, 200L)), now + 1L);

        WealthBreakdown breakdown = engine.getWealthBreakdown(now);
        assertEquals(now, breakdown.getCapturedAtEpochMillis());
        assertEquals(25L, breakdown.getGroup(WealthBreakdown.Group.BANK).getValueGp().longValue());
    }

    @Test
    public void wealthTrendUsesProfileLocalDaysSelectsLatestReadAndLeavesGapsAndFutureReadsEmpty()
    {
        ZoneId zone = ZoneId.of("Australia/Sydney");
        long firstRead = java.time.ZonedDateTime.of(2026, 10, 3, 12, 0, 0, 0, zone)
            .toInstant().toEpochMilli();
        long laterSameDay = java.time.ZonedDateTime.of(2026, 10, 3, 23, 30, 0, 0, zone)
            .toInstant().toEpochMilli();
        long lastRead = java.time.ZonedDateTime.of(2026, 10, 5, 12, 0, 0, 0, zone)
            .toInstant().toEpochMilli();
        long now = java.time.ZonedDateTime.of(2026, 10, 5, 13, 0, 0, 0, zone)
            .toInstant().toEpochMilli();

        SavedState state = new SavedState();
        state.setProfileTimeZoneId(zone.getId());
        GpManagerEngine engine = engine(true);
        engine.restore(state, now);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(firstRead,
            completeWealthLocations(firstRead)), firstRead);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(laterSameDay,
            completeWealthLocations(laterSameDay)), laterSameDay);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(lastRead,
            completeWealthLocations(lastRead)), lastRead);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(lastRead,
            completeWealthLocations(lastRead, 240L)), lastRead);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(now + 1_000L,
            completeWealthLocations(now + 1_000L)), now + 1_000L);

        List<WealthTrendPoint> trend = engine.getWealthTrend(3, now);
        assertEquals(3, trend.size());
        assertEquals(LocalDate.of(2026, 10, 3), trend.get(0).getDate());
        assertTrue(trend.get(0).isCaptured());
        assertEquals(laterSameDay, trend.get(0).getBreakdown().getCapturedAtEpochMillis());
        assertEquals(LocalDate.of(2026, 10, 4), trend.get(1).getDate());
        assertFalse(trend.get(1).isCaptured());
        assertEquals(null, trend.get(1).getBreakdown());
        assertEquals(LocalDate.of(2026, 10, 5), trend.get(2).getDate());
        assertTrue(trend.get(2).isCaptured());
        assertEquals(lastRead, trend.get(2).getBreakdown().getCapturedAtEpochMillis());
        assertEquals("A timestamp tie selects the last retained as-of read", 60L,
            trend.get(2).getBreakdown().getGroup(WealthBreakdown.Group.BANK)
                .getValueGp().longValue());
    }

    @Test
    public void wealthChangeFactsExposeSnapshotDeltaPercentAndBankShareWithoutNetAttribution()
    {
        long now = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        GpManagerEngine engine = engine(true);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(now - 30L * 86_400_000L,
            completeWealthLocations(now - 30L * 86_400_000L, 100L)), now - 30L * 86_400_000L);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(now - 7L * 86_400_000L,
            completeWealthLocations(now - 7L * 86_400_000L, 80L)), now - 7L * 86_400_000L);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(now,
            completeWealthLocations(now, 160L)), now);

        WealthChangeFacts facts = engine.getWealthChangeFacts(now);
        assertEquals(60L, facts.getThirtyDays().getChangeGp().longValue());
        assertEquals(60.0d, facts.getThirtyDays().getChangePercent(), 0.0001d);
        assertEquals(80L, facts.getSevenDays().getChangeGp().longValue());
        assertEquals(100.0d, facts.getSevenDays().getChangePercent(), 0.0001d);
        assertEquals(25.0d, facts.getLatestBankSharePercent(), 0.0001d);
    }

    @Test
    public void zeroWealthBaselineKeepsAbsoluteFactButHasNoPercent()
    {
        long now = 20L * 86_400_000L;
        GpManagerEngine engine = engine(true);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(now - 7L * 86_400_000L,
            completeWealthLocations(now - 7L * 86_400_000L, 0L)), now - 7L * 86_400_000L);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(now,
            completeWealthLocations(now, 100L)), now);

        WealthChangeFacts.WindowChange change = engine.getWealthChangeFacts(now).getSevenDays();
        assertEquals(100L, change.getChangeGp().longValue());
        assertFalse(change.isPercentAvailable());
        assertEquals(null, change.getChangePercent());
    }

    @Test
    public void recordingWealthDoesNotChangeAnExistingCountedNet()
    {
        FlowValuator valuator = deltas ->
        {
            java.util.List<ItemFlow> flows = new java.util.ArrayList<>();
            for (Map.Entry<Integer, Long> entry : deltas.entrySet())
            {
                flows.add(new ItemFlow(entry.getKey(), "Loot", entry.getValue(), 5,
                    entry.getValue() * 5L, ItemPriceSource.GRAND_EXCHANGE, 1_000L));
            }
            return flows;
        };
        GpManagerEngine engine = engine(true, valuator);
        long start = 1_000L;
        engine.ensureSession(start);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markInventoryDirty();
        Map<Integer, Long> gained = new HashMap<>();
        gained.put(1001, 2L);
        ContainerSnapshot after = new ContainerSnapshot(gained);
        engine.processIfDirty(after, start + 600L);
        engine.processIfDirty(after, start + 1_200L);
        engine.processIfDirty(after, start + 1_800L);
        long netBefore = engine.getMetrics(start + 1_800L).getNet();
        assertEquals(10L, netBefore);

        engine.recordWealthSnapshot(snapshot(2_000L, 100), 2_000L);
        engine.recordWealthSnapshot(snapshot(3_000L, 100), 3_000L);

        assertEquals(netBefore, engine.getMetrics(3_000L).getNet());
    }

    @Test
    public void partialDayCountedNetIsProratedAcrossFourHourRollupBuckets()
    {
        ZoneId zone = ZoneId.of("UTC");
        LocalDate day = LocalDate.of(2026, 9, 14);
        long start = day.atTime(2, 0).atZone(zone).toInstant().toEpochMilli();
        long end = day.atTime(6, 0).atZone(zone).toInstant().toEpochMilli();
        long[] net = { 400L, 600L, 0L, 0L, 0L, 0L };
        long[] active = { 1L, 1L, 0L, 0L, 0L, 0L };
        DailyRollup rollup = DailyRollup.builder(day, zone)
            .addAccounting(1_000L, 0L)
            .addFourHourBuckets(net, active)
            .build();
        SavedState saved = new SavedState();
        saved.setProfileTimeZoneId(zone.getId());
        saved.setDailyRollups(Collections.singletonList(rollup));

        GpManagerEngine engine = engine(true);
        engine.restore(saved, end + 1L);
        engine.recordWealthSnapshot(snapshot(start, 100), start);
        engine.recordWealthSnapshot(snapshotWithExtraCoins(end), end);

        WealthChangeBreakdown change = engine.getWealthChangeSince(
            WealthAnchor.LAST_BANK_VISIT, end + 1L);
        assertTrue(change.isAvailable());
        assertEquals(500L, change.getEarnedGp());
        assertEquals(0L, change.getMarketGp());
        assertEquals(0L, change.getUnexplainedGp());
    }

    private static GpManagerEngine engine(boolean enabled)
    {
        return engine(enabled, deltas -> Collections.emptyList());
    }

    private static GpManagerEngine engine(boolean enabled, FlowValuator valuator)
    {
        return new GpManagerEngine(valuator, new TransactionClassifier(),
            new GpManagerConfig()
            {
                @Override public boolean wealthHistoryEnabled() { return enabled; }
            });
    }

    private static WealthLocationsSnapshot snapshot(long capturedAt, int price)
    {
        return new WealthLocationsSnapshot(capturedAt, Arrays.asList(
            location(capturedAt, "bank", price),
            new WealthLocationSnapshot("inventory", "Inventory", WealthLocationSnapshot.Status.AVAILABLE,
                0L, capturedAt, Collections.emptyList(), "empty")));
    }

    private static List<WealthLocationSnapshot> completeWealthLocations(long capturedAt)
    {
        return completeWealthLocations(capturedAt, 100L);
    }

    private static List<WealthLocationSnapshot> completeWealthLocations(long capturedAt,
        long total)
    {
        long bank = total / 4L;
        long worn = total / 8L;
        long offers = total / 8L;
        long collection = total / 8L;
        long inventory = total / 8L;
        long pouch = total / 8L;
        long coffer = total - bank - worn - offers - collection - inventory - pouch;
        return new java.util.ArrayList<>(Arrays.asList(
            valuedLocation(capturedAt, "bank", bank),
            valuedLocation(capturedAt, "worn", worn),
            valuedLocation(capturedAt, "ge_offers", offers),
            valuedLocation(capturedAt, "ge_collection", collection),
            valuedLocation(capturedAt, "inventory", inventory),
            valuedLocation(capturedAt, "rune_pouch", pouch),
            valuedLocation(capturedAt, "coffers", coffer)));
    }

    private static WealthLocationSnapshot valuedLocation(long capturedAt, String id, long value)
    {
        return new WealthLocationSnapshot(id, id,
            WealthLocationSnapshot.Status.AVAILABLE, value, capturedAt,
            Collections.emptyList(), "read");
    }

    private static WealthLocationSnapshot location(long capturedAt, String id, int price)
    {
        WealthLocationSnapshot.Item item = new WealthLocationSnapshot.Item(0, 1001, "Item",
            1L, price, ItemPriceSource.GRAND_EXCHANGE, capturedAt);
        return new WealthLocationSnapshot(id, id, WealthLocationSnapshot.Status.AVAILABLE,
            price, capturedAt, Collections.singletonList(item), "read");
    }

    private static WealthLocationsSnapshot snapshotWithExtraCoins(long capturedAt)
    {
        WealthLocationSnapshot bank = location(capturedAt, "bank", 100);
        WealthLocationSnapshot inventory = new WealthLocationSnapshot("inventory", "Inventory",
            WealthLocationSnapshot.Status.AVAILABLE, 500L, capturedAt,
            Collections.singletonList(new WealthLocationSnapshot.Item(0, 995, "Coins", 500L,
                1, ItemPriceSource.FACE_VALUE, capturedAt)), "read");
        return new WealthLocationsSnapshot(capturedAt, Arrays.asList(bank, inventory));
    }

    private static WealthLocationsSnapshot snapshotWithManyItems(long capturedAt, int unitPrice)
    {
        java.util.List<WealthLocationSnapshot.Item> items = new java.util.ArrayList<>();
        long total = 0L;
        for (int itemId = 1; itemId <= WealthSnapshotHistory.MAX_ITEMS_PER_LOCATION + 1; itemId++)
        {
            items.add(new WealthLocationSnapshot.Item(itemId, itemId, "Item " + itemId,
                1L, unitPrice, ItemPriceSource.GRAND_EXCHANGE, capturedAt));
            total += unitPrice;
        }
        WealthLocationSnapshot bank = new WealthLocationSnapshot("bank", "Bank",
            WealthLocationSnapshot.Status.AVAILABLE, total, capturedAt, items, "read");
        WealthLocationSnapshot inventory = new WealthLocationSnapshot("inventory", "Inventory",
            WealthLocationSnapshot.Status.AVAILABLE, 0L, capturedAt,
            Collections.emptyList(), "empty");
        return new WealthLocationsSnapshot(capturedAt, Arrays.asList(bank, inventory));
    }
}
