package com.gpmanager;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.runelite.api.events.ItemDespawned;
import org.junit.Test;

import static com.gpmanager.ReviewFixesTest.*;
import static org.junit.Assert.*;

/** Real monetary paths retain their receipts while proof is reserved, exhausted or fenced. */
public class InboxMoneyTest
{
    @Test
    public void logoutDuringABindCompletesItsNewGeneration() throws Exception
    {
        Persistence persistence = new Persistence(null, null, null, null, engine());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try
        {
            persistence.runLifecycle(false, "test bind", () ->
            {
                entered.countDown();
                try
                {
                    release.await(3, TimeUnit.SECONDS);
                }
                catch (InterruptedException ex)
                {
                    throw new AssertionError(ex);
                }
                return true;
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            long generation = persistence.request.get();
            persistence.onLogout();
            release.countDown();
            persistence.worker.shutdown();
            assertTrue(persistence.worker.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(generation + 1, persistence.request.get());
            assertFalse(persistence.loading);
            assertFalse(persistence.consumeReady());
        }
        finally
        {
            release.countDown();
            persistence.worker.shutdownNow();
        }
    }

    @Test
    public void ownerFenceRetainsDurableGraveContinuityAndClearsTheInteraction()
    {
        Engine engine = engine();
        engine.death.onPvmDeath(Map.of(BONES, 2L));
        engine.death.ticks = 10;
        engine.stored.put(BONES, 1L);
        engine.events.owner(1001L);
        assertTrue(engine.death.isAwaitingReclaim());
        assertEquals(Long.valueOf(2L), engine.death.held.get(BONES));
        assertEquals(0, engine.death.ticks);
        assertTrue(engine.stored.isEmpty());
    }

    @Test
    public void existingCoordinatorGenerationFencesAnAccountCycle()
    {
        Engine engine = engine();
        Persistence persistence = new Persistence(null, null, null, null, engine);
        persistence.activeIdentity = new TrackingIdentity("A", 1);
        persistence.fence();
        Observation fee = engine.events.add(Inbox.FEE, -1, 500L, 10, "Reclaim fee (bank)");
        persistence.activeIdentity = new TrackingIdentity("B", 2);
        persistence.fence();
        persistence.activeIdentity = new TrackingIdentity("A", 1);
        persistence.fence();
        assertEquals(3L, persistence.request.get());
        assertEquals(1003L, engine.events.owner);
        engine.events.open.add(fee);
        engine.fee(fee, T0);
        assertEquals(0L, engine.metrics(T0).cost);
    }

    @Test
    public void bagBooksOnlyThePrivateQuantityAndNeverTwice()
    {
        Engine engine = engine();
        engine.markLoot(Map.of(BONES, 2L), 50, "Loot from Guard", "Guard");
        int[] tick = {11};
        Plugin plugin = plugin(engine, tick);
        Observation click = take(plugin);
        click.item = BONES;
        click.at = 10;
        click.x = 10;
        click.y = 10;
        plugin.onItemDespawned(new ItemDespawned(ReviewFollowupTest.tile(10, 10), ReviewFollowupTest.bones()));
        assertEquals(2L, qty(plugin, Inbox.BAG_GAIN));
        assertFalse(FixtureReads.has(engine.events, Inbox.PRIVATE_LOOT, BONES));
        plugin.onItemDespawned(new ItemDespawned(ReviewFollowupTest.tile(10, 10), ReviewFollowupTest.bones()));
        tick[0]++;
        plugin.bagSettle();
        engine.processIfDirty(FixtureReads.empty(), T0 + 600, null);
        engine.processIfDirty(FixtureReads.empty(), T0 + 1200, null);
        assertEquals(200L, engine.metrics(T0 + 1200).income);
        plugin.bagSettle();
        engine.processIfDirty(FixtureReads.empty(), T0 + 1800, null);
        assertEquals(200L, engine.metrics(T0 + 1800).income);
    }

    @Test
    public void reservationKeepsTheFirstEncounterAheadOfTheSecond()
    {
        Engine engine = engine();
        engine.loot(Map.of(BONES, 1L), 50, "first", "Guard", Context.LOOT, "one");
        engine.loot(Map.of(BONES, 1L), 50, "second", "Goblin", Context.LOOT, "two");
        assertEquals(1L, engine.events.reserve(Inbox.PRIVATE_LOOT, BONES, 1L, Inbox.LOOT_RESERVE));
        LootMatch source = engine.consumeQueuedLoot(List.of(new Flow(BONES, "Bones", 1L, 100, 100L)));
        assertEquals("first", source.note);
        assertEquals("one", source.fight);
        assertEquals(1L, engine.events.peek(Inbox.PRIVATE_LOOT).qty);
    }

    @Test
    public void ordinaryPickupAlsoExhaustsPrivateProof()
    {
        Engine engine = engine();
        engine.markLoot(Map.of(BONES, 1L), 6, "Loot from Guard", "Guard");
        engine.consumeQueuedLoot(List.of(new Flow(BONES, "Bones", 1L, 100, 100L)));
        assertFalse(FixtureReads.has(engine.events, Inbox.PRIVATE_LOOT, BONES));
        assertEquals(0L, engine.events.reserve(Inbox.PRIVATE_LOOT, BONES, 1L, Inbox.LOOT_RESERVE));
    }
}
