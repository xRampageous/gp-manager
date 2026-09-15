package com.gpmanager.ui;

import java.awt.Color;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class HudPlusStatusGemTest
{
    private static final Color ACCENT = new Color(0x46, 0xE6, 0xD6);
    private static final Color CORAL = new Color(0xFF, 0x6B, 0x6B);
    private static final Color MUTED = new Color(0x8B, 0x93, 0xA7);
    private static final Color NEUTRAL = new Color(0xE8, 0xEC, 0xF4);
    private static final Color PROFIT = new Color(0x5C, 0xFF, 0xB0);
    /** Factory default: accent and label are the same grey. */
    private static final Color FACTORY_GREY = new Color(0x8B, 0x93, 0xA7);

    private static TrackingDisplaySnapshot live(String activity)
    {
        return new TrackingDisplaySnapshot(
            0L, 0L, 0L, false, true, activity, "Live", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null);
    }

    @Test
    public void afkIsDistinctFromIdleAndPaused()
    {
        TrackingDisplaySnapshot afk = new TrackingDisplaySnapshot(
            0L, 0L, 0L, true, false, "Woodcutting", "AFK", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null);
        TrackingDisplaySnapshot paused = new TrackingDisplaySnapshot(
            0L, 0L, 0L, true, false, "Woodcutting", "Paused", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null);
        TrackingDisplaySnapshot idle = live("Woodcutting").withCharacterIdle(true);

        assertEquals(HudPlusStatusGem.Kind.AFK, HudPlusStatusGem.resolve(afk, false));
        assertEquals(HudPlusStatusGem.Kind.PAUSED, HudPlusStatusGem.resolve(paused, false));
        assertEquals(HudPlusStatusGem.Kind.IDLE, HudPlusStatusGem.resolve(idle, false));

        Color afkColor = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.AFK, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, true, 0L);
        Color idleColor = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.IDLE, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, true, 0L);
        Color pausedColor = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.PAUSED, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, true, 0L);
        assertEquals(CORAL.getRGB() & 0x00FFFFFF, afkColor.getRGB() & 0x00FFFFFF);
        assertEquals(MUTED, idleColor);
        assertEquals(CORAL, pausedColor);
        assertNotEquals(idleColor, pausedColor);
    }

    @Test
    public void bankingAndIdleUseMutedGrey()
    {
        TrackingDisplaySnapshot banking = live("General").withBankingUiOpen(true);
        TrackingDisplaySnapshot idle = live("General").withCharacterIdle(true);
        assertEquals(HudPlusStatusGem.Kind.BANKING, HudPlusStatusGem.resolve(banking, false));
        assertEquals(HudPlusStatusGem.Kind.IDLE, HudPlusStatusGem.resolve(idle, false));

        Color bankingColor = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.BANKING, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, true, 0L);
        Color idleColor = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.IDLE, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, true, 0L);
        assertEquals(MUTED, bankingColor);
        assertEquals(MUTED, idleColor);
    }

    @Test
    public void waitingGenericLiveIdleAndNamedActivityHaveDistinctGems()
    {
        TrackingDisplaySnapshot tracking = live("General");
        TrackingDisplaySnapshot waiting = live("Waiting").withCharacterIdle(true);
        TrackingDisplaySnapshot idle = live("General").withCharacterIdle(true);
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.selectObject("u1", "oak", "Oak tree", 1_000L);
        TrackingDisplaySnapshot active = live("Woodcutting")
            .withInteraction(ctx.snapshot("u1", 2_000L));

        assertEquals(HudPlusStatusGem.Kind.GENERIC_LIVE, HudPlusStatusGem.resolve(tracking, false));
        assertEquals(HudPlusStatusGem.Kind.WAITING, HudPlusStatusGem.resolve(waiting, false));
        assertEquals(HudPlusStatusGem.Kind.IDLE, HudPlusStatusGem.resolve(idle, false));
        assertEquals(HudPlusStatusGem.Kind.ACTIVE, HudPlusStatusGem.resolve(active, false));

        Color trackingColor = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.GENERIC_LIVE, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, true, 0L);
        Color waitingColor = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.WAITING, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, true, 0L);
        Color idleColor = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.IDLE, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, true, 0L);
        Color activeColor = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.ACTIVE, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, true, 0L);
        assertEquals(ACCENT, trackingColor);
        assertEquals(NEUTRAL, waitingColor);
        assertEquals(MUTED, idleColor);
        assertEquals(ACCENT, activeColor);
        assertNotEquals(waitingColor, trackingColor);
        assertNotEquals(idleColor, trackingColor);
    }

    @Test
    public void factoryGreyAccentSeparatesWaitingIdleFromLive()
    {
        Color banking = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.BANKING, FACTORY_GREY, CORAL, FACTORY_GREY, NEUTRAL, PROFIT, true, 0L);
        Color idle = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.IDLE, FACTORY_GREY, CORAL, FACTORY_GREY, NEUTRAL, PROFIT, true, 0L);
        Color active = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.ACTIVE, FACTORY_GREY, CORAL, FACTORY_GREY, NEUTRAL, PROFIT, true, 0L);
        Color tracking = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.GENERIC_LIVE, FACTORY_GREY, CORAL, FACTORY_GREY, NEUTRAL, PROFIT, true, 0L);
        Color waiting = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.WAITING, FACTORY_GREY, CORAL, FACTORY_GREY, NEUTRAL, PROFIT, true, 0L);
        Color stopped = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.MUTED_STOP, FACTORY_GREY, CORAL, FACTORY_GREY, NEUTRAL, PROFIT, true, 0L);

        assertEquals(FACTORY_GREY, banking);
        assertEquals(FACTORY_GREY, idle);
        assertEquals(HudPlusStatusGem.LIVE_TEAL, active);
        assertEquals(HudPlusStatusGem.LIVE_TEAL, tracking);
        assertEquals(NEUTRAL, waiting);
        assertNotEquals(idle, active);
        assertEquals(active, tracking);
        assertNotEquals(idle, stopped);
    }

    @Test
    public void busyPulseUsesLiveAccentAndStoppedIsDimmed()
    {
        TrackingDisplaySnapshot busy = live("Oak tree").withLootCoalescing(true);
        TrackingDisplaySnapshot locked = live("Oak tree").withLootBatchLocked(true);
        TrackingDisplaySnapshot stopped = new TrackingDisplaySnapshot(
            0L, 0L, 0L, false, false, "General", "Stopped", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null);

        assertEquals(HudPlusStatusGem.Kind.BUSY, HudPlusStatusGem.resolve(busy, false));
        assertEquals(HudPlusStatusGem.Kind.BUSY, HudPlusStatusGem.resolve(locked, false));
        assertEquals(HudPlusStatusGem.Kind.BUSY, HudPlusStatusGem.resolve(live("Oak tree"), true));
        assertEquals(HudPlusStatusGem.Kind.MUTED_STOP, HudPlusStatusGem.resolve(stopped, false));

        Color busySolid = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.BUSY, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, true, 0L);
        Color busyPulse = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.BUSY, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, false, 210L);
        assertEquals(ACCENT, busySolid);
        assertTrue(busyPulse.getAlpha() < 255);
        Color stoppedColor = HudPlusStatusGem.color(
            HudPlusStatusGem.Kind.MUTED_STOP, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, true, 0L);
        assertNotEquals(MUTED, stoppedColor);
        assertTrue(stoppedColor.getRed() < MUTED.getRed());
    }

    @Test
    public void busyBeatsIdleWhenLootTrayBusyOrHold()
    {
        TrackingDisplaySnapshot idleCoalesce = live("Chicken")
            .withCharacterIdle(true)
            .withLootCoalescing(true);
        TrackingDisplaySnapshot idleLocked = live("Chicken")
            .withCharacterIdle(true)
            .withLootBatchLocked(true);
        TrackingDisplaySnapshot idleHold = live("Pending Rewards").withCharacterIdle(true);

        assertEquals(HudPlusStatusGem.Kind.BUSY, HudPlusStatusGem.resolve(idleCoalesce, false));
        assertEquals(HudPlusStatusGem.Kind.BUSY, HudPlusStatusGem.resolve(idleLocked, false));
        assertEquals(HudPlusStatusGem.Kind.BUSY, HudPlusStatusGem.resolve(idleHold, true));
        assertNotEquals(
            HudPlusStatusGem.color(
                HudPlusStatusGem.Kind.BUSY, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, false, 0L).getAlpha(),
            HudPlusStatusGem.color(
                HudPlusStatusGem.Kind.BUSY, ACCENT, CORAL, MUTED, NEUTRAL, PROFIT, false, 210L).getAlpha());
    }

    @Test
    public void afkBeatsBankingAndIdleFlags()
    {
        TrackingDisplaySnapshot afk = new TrackingDisplaySnapshot(
            0L, 0L, 0L, true, false, "Woodcutting", "AFK", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null)
            .withBankingUiOpen(true)
            .withCharacterIdle(true);
        assertEquals(HudPlusStatusGem.Kind.AFK, HudPlusStatusGem.resolve(afk, true));
    }
}
