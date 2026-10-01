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
        Am engine = engine("UTC");
        ZoneId zone = ZoneId.of("UTC");
        long midnight = LocalDate.of(2026, 3, 7).atStartOfDay(zone).toInstant().toEpochMilli();
        long now = midnight + 12L * 3_600_000L;

        engine.ajl("Overnight", Cx.GENERAL, midnight - 300_000L);
        String overnightId = engine.getActiveSession().getId();
        book(engine, midnight - 299_000L, 1_000L);
        engine.sx(midnight + 600_000L);

        engine.ajl("Morning", Cx.GENERAL, midnight + 900_000L);
        String morningId = engine.getActiveSession().getId();
        book(engine, midnight + 901_000L, 2_000L);
        engine.sx(midnight + 1_800_000L);

        engine.ajl("Live", Cx.GENERAL, midnight - 120_000L);
        book(engine, midnight - 119_000L, 3_000L);

        Ao.Entry entry = Ao.Entry.current().withScope(Ao.Scope.TODAY, null, null);
        List<Ad> sessions = Ao.aig(engine, entry, now);
        assertEquals("whole runs, not calendar slices", 2, sessions.size());
        assertEquals("the current run is included in full even though it started yesterday",
            engine.getActiveSession().getId(), sessions.get(0).getId());
        assertEquals("a run that started today is included", morningId, sessions.get(1).getId());
        assertTrue("the overnight run is out", sessions.stream()
            .noneMatch(session -> overnightId.equals(session.getId())));

        Ao data = Ao.capture(engine, now, entry);
        assertEquals("only whole-run totals: the overnight run's money stays out", 5_000L, data.net);
    }

    @Test
    public void midnightBoundaryHoldsAcrossDstDays() throws Exception
    {
        for (String zoneId : new String[] {"UTC", "America/New_York"})
        {
            Am engine = engine(zoneId);
            ZoneId zone = ZoneId.of(zoneId);
            long midnight = LocalDate.of(2026, 3, 8).atStartOfDay(zone).toInstant().toEpochMilli();
            long now = midnight + 12L * 3_600_000L;

            engine.ajl("JustBefore", Cx.GENERAL, midnight - 1L);
            String beforeId = engine.getActiveSession().getId();
            engine.sx(midnight + 60_000L);

            engine.ajl("AtMidnight", Cx.GENERAL, midnight);

            Ao.Entry entry = Ao.Entry.current().withScope(Ao.Scope.TODAY, null, null);
            List<Ad> sessions = Ao.aig(engine, entry, now);
            assertEquals("only the run started at midnight is today", 1, sessions.size());
            assertEquals(engine.getActiveSession().getId(), sessions.get(0).getId());
            assertTrue(sessions.stream().noneMatch(session -> beforeId.equals(session.getId())));
        }
    }

    @Test
    public void theScopeSaysRunsTodayAndDisclosesItsMembership() throws Exception
    {
        Am engine = engine("UTC");
        long now = System.currentTimeMillis();
        engine.ajl("Live", Cx.GENERAL, now);

        Dp panel = onEdt(() -> new Dp(engine, new GpManagerConfig() {}, null));
        onEdt(() ->
        {
            panel.ledger.apply(Ao.capture(engine, now,
                Ao.Entry.current().withScope(Ao.Scope.TODAY, null, null)));
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

    private static Ac book(Am engine, long at, long value)
    {
        Ac receipt = new Ac(at, null, Ai.LOOT, Aj.LOOT, "", "Vorkath", true,
            Collections.singletonList(new Ab(536, "Dragon bones", 1L, (int) value, value)),
            Bd.CONFIRMED, "fixture", null);
        engine.getActiveSession().kf(receipt, 2_000);
        return receipt;
    }

    private static Am engine(String zoneId)
    {
        Am engine = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(),
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
