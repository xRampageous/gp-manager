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

        detector.observe("Woodcutting", true);
        assertEquals("Woodcutting", engine.getMetrics(1_100L).activityHint);

        detector.observe("NPC loot", true);
        assertEquals("a fallback is not an activity", "Woodcutting", engine.getMetrics(1_200L).activityHint);
    }

    @Test
    public void aGatherClickNamesNothingUntilItsSkillsFirstXp()
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

        // Clicking a tree with no axe, or before reaching it.
        detector.gatherSkill = ActionSignals.gatherActivity("chop down");
        assertEquals("General", engine.getMetrics(1_100L).activityHint);
        // Other XP still needs its second drop.
        detector.observeXp("Mining", null, false, 1_200L);
        assertEquals("General", engine.getMetrics(1_300L).activityHint);
        // The first log's XP names the run at once.
        detector.observeXp("Woodcutting", null, false, 1_400L);
        assertEquals("Woodcutting", engine.getMetrics(1_500L).activityHint);
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

        detector.armProduction("Smelting", now);
        engine.inbox.drop(Inbox.CLOCK); // two failed smelts: the six-tick window has lapsed
        detector.observeXp("Smithing", null, false, now + 12_000L);
        assertEquals(Context.PRODUCTION, engine.liveClue().context);
        assertTrue("Smithing XP re-arms the run", engine.clockLeft() > 0);

        engine.inbox.drop(Inbox.CLOCK);
        detector.observeXp("Attack", null, false, now + 17_000L);
        assertEquals("combat XP never opens production", 0, engine.clockLeft());

        detector.observeXp("Smithing", null, false, now + 12_000L + 31_000L);
        assertEquals("past the run window XP no longer re-arms", 0, engine.clockLeft());
    }
}
