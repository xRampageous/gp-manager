package com.gpmanager;

import org.junit.Test;
import static com.gpmanager.MilestoneTest.onEdt;
import static org.junit.Assert.*;

/** Activity-only presentation must not fold the run's financial history. */
public class ActivityMetadataTest {
    static class CountingEngine extends Engine {
        int folds;
        CountingEngine() { super(d -> java.util.List.of(), new TransactionClassifier(), new GpManagerConfig(){}); }
        @Override synchronized Metrics metrics(long now) { folds++; return super.metrics(now); }
    }
    @Test public void readingTheActivityDoesNotCalculateMoney() throws Exception {
        onEdt(() -> {
            var engine = new CountingEngine(); engine.ensureSession(1_000);
            engine.setDetectedActivity("Vorkath", 2_000);
            var panel = RateRestartTest.panel(engine); engine.folds = 0;
            for (int i = 0; i < 100; i++) assertEquals("Vorkath", panel.activityLabel());
            assertEquals("activity presentation never folds receipt totals", 0, engine.folds);
            return null;
        });
    }
    @Test public void normalizedLabelsKeepSkillsAndNamedActivities() throws Exception {
        onEdt(() -> {
            var engine = new CountingEngine(); var panel = RateRestartTest.panel(engine);
            assertEquals("", panel.activityLabel()); engine.ensureSession(1_000);
            String[] raw = {null, "", " ", "General", "general", "MAGIC", " Magic ", "Mining", " Vorkath ", "Magic trees", "Wilderness Agility Course"};
            String[] expected = {"", "", "", "", "", "", "", "Mining", "Vorkath", "Magic trees", "Wilderness Agility Course"};
            for (int i = 0; i < raw.length; i++) {
                engine.session().activityHint = raw[i];
                assertEquals("label for " + raw[i], expected[i], panel.activityLabel());
            }
            return null;
        });
    }
    @Test public void activityChangesStayLiveWithoutAnotherBooking() throws Exception {
        onEdt(() -> {
            var engine = new CountingEngine(); engine.ensureSession(1_000);
            var panel = RateRestartTest.panel(engine);
            engine.setDetectedActivity("Fishing", 2_000); assertEquals("Fishing", panel.activityLabel());
            engine.setDetectedActivity("Theatre of Blood", 3_000); assertEquals("Theatre of Blood", panel.activityLabel());
            engine.setDetectedActivity("Magic", 4_000); assertEquals("", panel.activityLabel());
            assertTrue(engine.session().transactions().isEmpty());
            return null;
        });
    }
}
