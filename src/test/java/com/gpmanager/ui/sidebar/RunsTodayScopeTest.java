package com.gpmanager;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Owner 2026-10-01 (F03): Runs today is whole runs that started today in the profile zone. */
public class RunsTodayScopeTest
{
    @Test
    public void runsTodayIncludesTheCurrentRunAndTodayStartedRunsOnly() throws Exception
    {
        Engine engine = engine("UTC");
        ZoneId zone = ZoneId.of("UTC");
        long midnight = LocalDate.of(2026, 3, 7).atStartOfDay(zone).toInstant().toEpochMilli();
        long now = midnight + 12L * 3_600_000L;

        engine.startCustomSession("Overnight", SessionMode.GENERAL, midnight - 300_000L);
        String overnightId = engine.getActiveSession().getId();
        book(engine, midnight - 299_000L, 1_000L);
        engine.finishCustomSession(midnight + 600_000L);

        engine.startCustomSession("Morning", SessionMode.GENERAL, midnight + 900_000L);
        String morningId = engine.getActiveSession().getId();
        book(engine, midnight + 901_000L, 2_000L);
        engine.finishCustomSession(midnight + 1_800_000L);

        engine.startCustomSession("Live", SessionMode.GENERAL, midnight - 120_000L);
        book(engine, midnight - 119_000L, 3_000L);

        LedgerData.Entry entry = LedgerData.Entry.current().withScope(LedgerData.Scope.TODAY, null, null);
        List<Session> sessions = LedgerData.sessionsFor(engine, entry, now);
        assertEquals("whole runs, not calendar slices", 2, sessions.size());
        assertEquals("the current run is included in full even though it started yesterday",
            engine.getActiveSession().getId(), sessions.get(0).getId());
        assertEquals("a run that started today is included", morningId, sessions.get(1).getId());
        assertTrue("the overnight run is out", sessions.stream()
            .noneMatch(session -> overnightId.equals(session.getId())));

        LedgerData data = LedgerData.capture(engine, now, entry);
        assertEquals("only whole-run totals: the overnight run's money stays out", 5_000L, data.net);
    }

    @Test
    public void midnightBoundaryHoldsAcrossDstDays() throws Exception
    {
        for (String zoneId : new String[] {"UTC", "America/New_York"})
        {
            Engine engine = engine(zoneId);
            ZoneId zone = ZoneId.of(zoneId);
            long midnight = LocalDate.of(2026, 3, 8).atStartOfDay(zone).toInstant().toEpochMilli();
            long now = midnight + 12L * 3_600_000L;

            engine.startCustomSession("JustBefore", SessionMode.GENERAL, midnight - 1L);
            String beforeId = engine.getActiveSession().getId();
            engine.finishCustomSession(midnight + 60_000L);

            engine.startCustomSession("AtMidnight", SessionMode.GENERAL, midnight);

            LedgerData.Entry entry = LedgerData.Entry.current().withScope(LedgerData.Scope.TODAY, null, null);
            List<Session> sessions = LedgerData.sessionsFor(engine, entry, now);
            assertEquals("only the run started at midnight is today", 1, sessions.size());
            assertEquals(engine.getActiveSession().getId(), sessions.get(0).getId());
            assertTrue(sessions.stream().noneMatch(session -> beforeId.equals(session.getId())));
        }
    }

    @Test
    public void theScopeSaysRunsTodayAndDisclosesItsMembership() throws Exception
    {
        Engine engine = engine("UTC");
        long now = System.currentTimeMillis();
        engine.startCustomSession("Live", SessionMode.GENERAL, now);

        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, new GpManagerConfig() {}, null));
        onEdt(() ->
        {
            panel.ledger.apply(LedgerData.capture(engine, now,
                LedgerData.Entry.current().withScope(LedgerData.Scope.TODAY, null, null)));
            return null;
        });

        assertEquals("Runs today ▾", panel.ledger.scope.getText());
        assertTrue("the tooltip names the membership",
            panel.ledger.scope.getToolTipText().contains("runs started today"));
        assertTrue("the note names the membership too",
            containsText(panel.ledger.body, "Runs today"));
    }

    private static boolean containsText(java.awt.Container parent, String needle)
    {
        for (java.awt.Component child : parent.getComponents())
        {
            if (child instanceof JLabel && ((JLabel) child).getText() != null
                && ((JLabel) child).getText().contains(needle))
            {
                return true;
            }
            if (child instanceof java.awt.Container
                && containsText((java.awt.Container) child, needle))
            {
                return true;
            }
        }
        return false;
    }

    private static Transaction book(Engine engine, long at, long value)
    {
        Transaction receipt = new Transaction(at, null, TransactionType.LOOT, Context.LOOT, "", "Vorkath", true,
            Collections.singletonList(new Flow(536, "Dragon bones", 1L, (int) value, value)),
            ClassificationConfidence.CONFIRMED, "fixture", null);
        engine.getActiveSession().addTransaction(receipt, 2_000);
        return receipt;
    }

    private static Engine engine(String zoneId)
    {
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
        engine.profileTimeZoneId = zoneId;
        return engine;
    }

    @SuppressWarnings("unchecked")
    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        Object[] result = new Object[1];
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                result[0] = callable.call();
            }
            catch (Exception ex)
            {
                throw new RuntimeException(ex);
            }
        });
        return (T) result[0];
    }
}
