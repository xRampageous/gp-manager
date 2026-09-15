package com.gpmanager.engine;

import com.google.gson.Gson;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.AlertEvent;
import com.gpmanager.model.AlertKind;
import com.gpmanager.model.AlertPolicy;
import com.gpmanager.model.GoalDefinition;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.PauseReason;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionEndReason;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.model.WealthLocationSnapshot;
import com.gpmanager.model.WealthLocationsSnapshot;
import com.gpmanager.persistence.SavedState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AlertEventEngineTest
{
    static
    {
        com.gpmanager.persistence.JsonCodec.bind(new com.google.gson.Gson());
    }

    private static final int DROP_ITEM = 40_001;

    @Test
    public void countedNotableDropIsProducedOnceFromTheRetainedReceipt()
    {
        GpManagerEngine engine = engine(1_000, 0, true);
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markLootContext(Collections.singletonMap(DROP_ITEM, 1L), 6,
            "Goblin loot", "Goblin");
        engine.markInventoryDirty();

        ContainerSnapshot after = snapshot(DROP_ITEM, 1L);
        assertNull(engine.processIfDirty(after, 1_600L));
        assertNull(engine.processIfDirty(after, 2_200L));
        ProfitTransaction receipt = engine.processIfDirty(after, 2_800L);

        assertNotNull(receipt);
        assertTrue(receipt.isCounted());
        assertEquals(1, eventsOf(engine, AlertKind.NOTABLE_DROP).size());
        AlertEvent event = eventsOf(engine, AlertKind.NOTABLE_DROP).get(0);
        assertEquals(1_000L, event.getValue());
        assertEquals(DROP_ITEM, event.getItemId());
        engine.processIfDirty(after, 3_400L);
        assertEquals("the same retained receipt cannot alert again", 1,
            eventsOf(engine, AlertKind.NOTABLE_DROP).size());
    }

    @Test
    public void marketProceedsAreNotReportedAsNotableDrops()
    {
        GpManagerEngine engine = engine(1_000, 0, true);
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markContext(TrackingContext.MARKET, 100, "Grand Exchange sale");
        engine.markInventoryDirty();

        ContainerSnapshot proceeds = snapshot(995, 1L);
        assertNull(engine.processIfDirty(proceeds, 1_600L));
        assertNull(engine.processIfDirty(proceeds, 2_200L));
        ProfitTransaction sale = engine.processIfDirty(proceeds, 2_800L);

        assertNotNull(sale);
        assertEquals(TransactionType.TRADE, sale.getAutomaticType());
        assertTrue(sale.isCounted());
        assertTrue("sale proceeds are not a loot drop", eventsOf(engine, AlertKind.NOTABLE_DROP).isEmpty());
    }

    @Test
    public void goalCrossingIsObservedOnMutationAndOncePerSession()
    {
        GpManagerEngine engine = engine(0, 0, true);
        engine.ensureSession(1_000L);
        GoalDefinition goal = new GoalDefinition(GoalDefinition.Kind.KILLS,
            GoalDefinition.Scope.SESSION, 1L, Collections.emptyList());
        engine.setGoalDefinitions(Collections.singletonList(goal));
        engine.processIfDirty(ContainerSnapshot.empty(), 2_000L); // Seed at zero.
        assertTrue(engine.recordObservedEncounter("Goblin", 1L, 0L, false,
            1L, 3_000L));
        assertTrue(engine.recordObservedEncounter("Goblin", 1L, 0L, false,
            2L, 4_000L));
        assertEquals(1, eventsOf(engine, AlertKind.GOAL_REACHED).size());

        engine.startCustomSession("Second activity", SessionMode.AUTO, 5_000L);
        engine.processIfDirty(ContainerSnapshot.empty(), 6_000L); // New owner seeds at zero.
        assertTrue(engine.recordObservedEncounter("Goblin", 1L, 0L, false,
            1L, 7_000L));
        assertEquals("the same configured goal may alert once for each owner session", 2,
            eventsOf(engine, AlertKind.GOAL_REACHED).size());
    }

    @Test
    public void gpPerHourGoalIsCheckedByTicksWithoutAGetterRead()
    {
        GpManagerEngine engine = engine(0, 0, true);
        long started = 1_000L;
        engine.ensureSession(started);
        engine.setGoalDefinitions(Collections.singletonList(new GoalDefinition(
            GoalDefinition.Kind.GP_PER_HOUR, GoalDefinition.Scope.SESSION, 50L,
            Collections.emptyList())));
        ContainerSnapshot empty = ContainerSnapshot.empty();
        engine.processIfDirty(empty, started + 3_600_000L); // 0 GP/h baseline.
        assertTrue(eventsOf(engine, AlertKind.GOAL_REACHED).isEmpty());

        ProfitSession owner = engine.getActiveSession();
        owner.addTransaction(new ProfitTransaction(started + 3_600_000L,
            TransactionType.GAIN, TrackingContext.LOOT, "Measured test gain", true,
            Collections.singletonList(new ItemFlow(1, "Coins", 100L, 1, 100L,
                ItemPriceSource.FACE_VALUE))), 100);
        engine.processIfDirty(empty, started + 3_600_000L);

        assertEquals(1, eventsOf(engine, AlertKind.GOAL_REACHED).size());
    }

    @Test
    public void wealthMilestonesRequireCompleteReadsAndCanCrossAgainAfterADecrease()
    {
        GpManagerEngine engine = engine(0, 100, true);
        engine.recordWealthSnapshot(wealth(1_000L, 50L), 1_000L);
        engine.recordWealthSnapshot(wealth(2_000L, 250L), 2_000L);
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(3_000L,
            Collections.singletonList(new WealthLocationSnapshot("bank", "Bank",
                WealthLocationSnapshot.Status.AVAILABLE, 10_000L, 3_000L,
                Collections.emptyList(), "partial read"))), 3_000L);
        engine.recordWealthSnapshot(wealth(4_000L, 150L), 4_000L);
        engine.recordWealthSnapshot(wealth(5_000L, 210L), 5_000L);
        engine.recordWealthSnapshot(wealth(6_000L, 210L), 6_000L);

        List<AlertEvent> events = eventsOf(engine, AlertKind.WEALTH_MILESTONE);
        assertEquals(3, events.size());
        assertEquals(100L, events.get(0).getValue());
        assertEquals(200L, events.get(1).getValue());
        assertEquals("a later upward recross is a new crossing", 200L, events.get(2).getValue());
    }

    @Test
    public void largestRepresentableWealthCrossingTerminates()
    {
        GpManagerEngine engine = engine(0, 1, true);
        engine.recordWealthSnapshot(wealth(1_000L, Long.MAX_VALUE - 1L), 1_000L);
        engine.recordWealthSnapshot(wealth(2_000L, Long.MAX_VALUE), 2_000L);

        List<AlertEvent> events = eventsOf(engine, AlertKind.WEALTH_MILESTONE);
        assertEquals(1, events.size());
        assertEquals(Long.MAX_VALUE, events.get(0).getValue());
    }

    @Test
    public void idleAutoEndClosesAtIdleStartPersistsReasonAndNeverClosesFreePlay()
    {
        GpManagerEngine engine = engine(0, 0, true);
        engine.ensureSession(1_000L);
        engine.startCustomSession("Custom", SessionMode.AUTO, 2_000L);
        String customId = engine.getActiveSession().getId();
        engine.setSessionIdleAutoEnd(2);
        engine.pauseForIdle(100_000L, 40_000L);
        assertEquals(PauseReason.IDLE, engine.getActiveSession().getPauseReason());
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), 159_999L));
        assertEquals(customId, engine.getActiveSession().getId());
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), 160_000L));

        ProfitSession closed = engine.getHistory().get(0);
        assertEquals(customId, closed.getId());
        assertEquals(40_000L, closed.getEndedAtEpochMillis());
        assertEquals(SessionEndReason.IDLE, closed.getEndReason());
        assertEquals(1, eventsOf(engine, AlertKind.SESSION_AUTO_ENDED).size());
        engine.processIfDirty(ContainerSnapshot.empty(), 160_600L);
        assertEquals(1, eventsOf(engine, AlertKind.SESSION_AUTO_ENDED).size());

        GpManagerEngine freePlay = engine(0, 0, true);
        freePlay.ensureSession(1_000L);
        String freePlayId = freePlay.getActiveSession().getId();
        freePlay.setSessionIdleAutoEnd(1);
        freePlay.pauseForIdle(10_000L, 2_000L);
        freePlay.processIfDirty(ContainerSnapshot.empty(), 62_000L);
        assertEquals(freePlayId, freePlay.getActiveSession().getId());
        assertFalse(freePlay.getActiveSession().isClosed());
        assertTrue(eventsOf(freePlay, AlertKind.SESSION_AUTO_ENDED).isEmpty());
    }

    @Test
    public void manualAndBoundaryEndReasonsSurviveSavedStateAndPolicyCanSuppressEvents()
    {
        GpManagerEngine manual = engine(0, 0, true);
        manual.ensureSession(500L);
        manual.startCustomSession("Manual", SessionMode.AUTO, 1_000L);
        assertTrue(manual.finishCustomSession(2_000L));
        assertEquals(SessionEndReason.MANUAL, manual.getHistory().get(0).getEndReason());

        GpManagerEngine engine = engine(0, 0, true);
        engine.ensureSession(1_000L);
        engine.startCustomSession("Boundary", SessionMode.AUTO, 2_000L);
        engine.setActiveSessionEndReason(SessionEndReason.BOUNDARY);
        assertTrue(engine.finishCustomSession(3_000L));
        assertEquals(SessionEndReason.BOUNDARY, engine.getHistory().get(0).getEndReason());

        String json = new Gson().toJson(engine.createSavedState());
        SavedState saved = new Gson().fromJson(json, SavedState.class);
        GpManagerEngine restored = engine(0, 0, true);
        restored.restore(saved, 4_000L);
        assertEquals(SavedState.CURRENT_SCHEMA_VERSION, saved.getSchemaVersion());
        assertEquals(SessionEndReason.BOUNDARY, restored.getHistory().get(0).getEndReason());
        assertTrue("transient alerts do not survive profile restore",
            restored.getRecentAlerts(10).isEmpty());

        List<AlertEvent> delivered = new ArrayList<>();
        restored.addAlertListener(event -> { throw new IllegalStateException("presentation fault"); });
        restored.addAlertListener(delivered::add);
        restored.setAlertPolicy(AlertPolicy.allDisabled());
        assertFalse(restored.recordAlert(new AlertEvent(AlertKind.SUPPLIES_LOW, 5_000L,
            null, "Supplies low", "Sharks below target", 1L, 385)));
        assertTrue(delivered.isEmpty());

        restored.setAlertPolicy(AlertPolicy.allEnabled());
        assertTrue(restored.recordAlert(new AlertEvent(AlertKind.SUPPLIES_LOW, 6_000L,
            null, "Supplies low", "Sharks below target", 1L, 385)));
        assertEquals(1, delivered.size());
        assertEquals(1L, delivered.get(0).getSequenceId());
        assertEquals(1, restored.getRecentAlerts(1).size());

        GpManagerEngine boundaryGeneral = engine(0, 0, true);
        boundaryGeneral.ensureSession(1_000L);
        boundaryGeneral.setActiveSessionEndReason(SessionEndReason.BOUNDARY);
        assertTrue(boundaryGeneral.restartGeneral(2_000L, true, false));
        assertEquals(SessionEndReason.BOUNDARY,
            boundaryGeneral.getHistory().get(0).getEndReason());
    }

    @Test
    public void installingAnotherProfileClearsAlertsAndKeepsEventIdsMonotonic()
    {
        GpManagerEngine engine = engine(0, 100, true);
        engine.restoreForProfile("owner-a", new SavedState(), 1_000L);
        engine.recordWealthSnapshot(wealth(1_100L, 50L), 1_100L);
        assertTrue(engine.recordAlert(new AlertEvent(AlertKind.RECLAIM_EXPIRED, 1_100L,
            null, "Old profile", "old profile alert", 0L, -1)));
        long firstSequenceId = engine.getRecentAlerts(1).get(0).getSequenceId();

        engine.restoreForProfile("owner-b", new SavedState(), 2_000L);
        assertTrue("alerts belong to the previous profile", engine.getRecentAlerts(50).isEmpty());
        engine.recordWealthSnapshot(wealth(2_100L, 150L), 2_100L);
        assertTrue("the new profile's first wealth read seeds silently",
            eventsOf(engine, AlertKind.WEALTH_MILESTONE).isEmpty());

        assertTrue(engine.recordAlert(new AlertEvent(AlertKind.RECLAIM_EXPIRED, 2_200L,
            null, "New profile", "new profile alert", 0L, -1)));
        assertEquals("clearing profile-scoped alerts must not reuse process ids",
            firstSequenceId + 1L, engine.getRecentAlerts(1).get(0).getSequenceId());
    }

    @Test
    public void alertRingIsBoundedNewestFirstAndNonConsuming()
    {
        GpManagerEngine engine = engine(0, 0, true);
        for (int i = 1; i <= 55; i++)
        {
            assertTrue(engine.recordAlert(new AlertEvent(AlertKind.SUPPLIES_LOW, i,
                null, "Supply", "Low", i, -1)));
        }
        List<AlertEvent> recent = engine.getRecentAlerts(60);
        assertEquals(50, recent.size());
        assertEquals(55L, recent.get(0).getAtEpochMillis());
        assertEquals(6L, recent.get(49).getAtEpochMillis());
        assertEquals(50, engine.getRecentAlerts(60).size());
    }

    private static GpManagerEngine engine(int notableThreshold, int wealthMilestone,
        boolean wealthHistoryEnabled)
    {
        FlowValuator valuator = deltas ->
        {
            List<ItemFlow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : deltas.entrySet())
            {
                flows.add(new ItemFlow(entry.getKey(), "Goblin drops", entry.getValue(),
                    1_000, entry.getValue() * 1_000L, ItemPriceSource.GRAND_EXCHANGE));
            }
            return flows;
        };
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public boolean wealthHistoryEnabled() { return wealthHistoryEnabled; }
            @Override public int notableDropThresholdGp() { return notableThreshold; }
            @Override public int wealthMilestoneGp() { return wealthMilestone; }
            @Override public int stabilizationTicks() { return 2; }
        };
        return new GpManagerEngine(valuator, new TransactionClassifier(), config);
    }

    private static ContainerSnapshot snapshot(int itemId, long quantity)
    {
        return new ContainerSnapshot(Collections.singletonMap(itemId, quantity));
    }

    private static List<AlertEvent> eventsOf(GpManagerEngine engine, AlertKind kind)
    {
        List<AlertEvent> found = new ArrayList<>();
        for (AlertEvent event : engine.getRecentAlerts(100))
        {
            if (event.getKind() == kind) found.add(event);
        }
        Collections.reverse(found);
        return found;
    }

    private static WealthLocationsSnapshot wealth(long at, long total)
    {
        long bank = total / 4L;
        long worn = total / 8L;
        long offers = total / 8L;
        long collection = total / 8L;
        long inventory = total / 8L;
        long pouch = total / 8L;
        long coffers = total - bank - worn - offers - collection - inventory - pouch;
        return new WealthLocationsSnapshot(at, Arrays.asList(
            wealthLocation(at, "bank", bank),
            wealthLocation(at, "worn", worn),
            wealthLocation(at, "ge_offers", offers),
            wealthLocation(at, "ge_collection", collection),
            wealthLocation(at, "inventory", inventory),
            wealthLocation(at, "rune_pouch", pouch),
            wealthLocation(at, "coffers", coffers)));
    }

    private static WealthLocationSnapshot wealthLocation(long at, String id, long value)
    {
        return new WealthLocationSnapshot(id, id, WealthLocationSnapshot.Status.AVAILABLE,
            value, at, Collections.emptyList(), "fixed test read");
    }
}
