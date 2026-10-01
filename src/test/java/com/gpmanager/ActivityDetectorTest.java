package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Generic fallbacks are not activities: they never become the session's activity label. */
public class ActivityDetectorTest
{
    @Test
    public void npcLootFallbackNeverBecomesTheActivity()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 0;
            }
        };
        Engine engine = new Engine(deltas -> Collections.emptyList(),
            new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        ActivityDetector detector = new ActivityDetector(config, engine);

        detector.observeActivity("Woodcutting", true);
        assertEquals("Woodcutting", engine.getMetrics(1_100L).activityHint);

        detector.observeActivity("NPC loot", true);
        assertEquals("a fallback is not an activity", "Woodcutting", engine.getMetrics(1_200L).activityHint);
    }

    /**
     * Owner report 2026-09-28: iron smelting fails about half the time with no XP, so the six-tick
     * window lapsed between successes and the next ore-to-bar change became a Review row. A
     * production skill's XP keeps the run in PRODUCTION for 30 s after the last one.
     */
    @Test
    public void aSmeltingRunSurvivesFailedSmeltsWithoutXp()
    {
        GpManagerConfig config = new GpManagerConfig() {};
        Engine engine = new Engine(deltas -> Collections.emptyList(),
            new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        ActivityDetector detector = new ActivityDetector(config, engine);
        long now = 1_000_000L;

        detector.armProductionContext("Smelting", now);
        engine.contextTicks = 0; // two failed smelts: the six-tick window has lapsed
        detector.observeSkillXp("Smithing", null, now + 12_000L);
        assertEquals(Context.PRODUCTION, engine.active.context);
        assertTrue("Smithing XP re-arms the run", engine.contextTicks > 0);

        engine.contextTicks = 0;
        detector.observeSkillXp("Attack", null, now + 17_000L);
        assertEquals("combat XP never opens production", 0, engine.contextTicks);

        detector.observeSkillXp("Smithing", null, now + 12_000L + 31_000L);
        assertEquals("past the run window XP no longer re-arms", 0, engine.contextTicks);
    }
}
