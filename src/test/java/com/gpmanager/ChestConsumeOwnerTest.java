package com.gpmanager;

import com.google.gson.Gson;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 1.7.0: a reward chest's measured Bank-all decrease is checked, spent and re-armed in one
 * engine-then-inbox hold, and the production owner fence takes the same order. A fence that
 * arrives while a chest is being consumed lands after it: no foreign receipt, no stale re-arm for
 * the next owner and no lock inversion.
 */
public class ChestConsumeOwnerTest
{
    private static final long T0 = 1_700_000_000_000L;

    private static final class Rig
    {
        final int[] tick = {100};
        final Item[][] chest = {{new Item(ItemID.TWISTED_BOW, 1), new Item(ItemID.DRAGON_ARROW, 50)}};
        final AtomicReference<Runnable> onValue = new AtomicReference<>();
        final Engine engine;
        final Persistence persistence;
        final GpManagerPlugin plugin = new GpManagerPlugin();
        final ItemContainer rewards;

        Rig()
        {
            JsonCodec.bind(new Gson());
            engine = new Engine(deltas ->
            {
                Runnable hook = onValue.getAndSet(null);
                if (hook != null) hook.run();
                List<Flow> result = new ArrayList<>();
                deltas.forEach((id, q) -> result.add(new Flow(id, "Item " + id, q, 100, q * 100)));
                return result;
            }, new TransactionClassifier(), new GpManagerConfig() {});
            engine.ensureSession(T0);
            engine.setBaseline(ContainerSnapshot.empty());
            persistence = new Persistence(null, null, null, null, engine);
            persistence.activeIdentity = new TrackingIdentity("rsprofile.alice", 1L);
            persistence.fence();
            rewards = (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
                new Class<?>[]{ItemContainer.class},
                (proxy, method, args) -> "getItems".equals(method.getName()) ? chest[0] : null);
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, args) ->
                {
                    if ("getTickCount".equals(method.getName())) return tick[0];
                    if ("getItemContainer".equals(method.getName()))
                        return (int) args[0] == InventoryID.RAIDS_REWARDS ? rewards : null;
                    return null;
                });
            plugin.engine = engine;
            plugin.client = client;
            plugin.containers = new ContainerSnapshotFactory(client, null);
        }

        void bankAll()
        {
            plugin.bankAll(InterfaceID.RAIDS_REWARDS);
        }

        void changed()
        {
            plugin.chestChanged(InventoryID.RAIDS_REWARDS, rewards);
        }

        Observation chestProof()
        {
            return engine.events.peek(Inbox.CHEST, v -> true);
        }
    }

    @Test
    public void aFenceDuringConsumptionLandsAfterItWithNoStaleReArm() throws Exception
    {
        Rig rig = new Rig();
        rig.bankAll();
        rig.chest[0] = new Item[]{new Item(ItemID.DRAGON_ARROW, 20)};
        rig.tick[0]++;
        AtomicBoolean fenced = new AtomicBoolean();
        Thread[] fence = new Thread[1];
        rig.onValue.set(() ->
        {
            // An account switch fences the owner while the first portion is being booked.
            fence[0] = new Thread(() ->
            {
                rig.persistence.activeIdentity = new TrackingIdentity("rsprofile.bob", 2L);
                rig.persistence.fence();
                fenced.set(true);
            });
            fence[0].start();
            try
            {
                fence[0].join(300L);
            }
            catch (InterruptedException ex)
            {
                throw new AssertionError(ex);
            }
        });

        rig.changed();
        fence[0].join(5_000L);
        assertTrue("the fence completes once the chest hold ends", fenced.get());
        assertEquals("the whole decrease belongs to the owner that sampled it", 1L,
            RewardChestBankAllTest.received(rig.engine, ItemID.TWISTED_BOW));
        assertEquals(30L, RewardChestBankAllTest.received(rig.engine, ItemID.DRAGON_ARROW));
        assertNull("no chest proof is re-armed for the next owner", rig.chestProof());
    }

    @Test
    public void anAccountRoundTripNeverReusesTheOldChestProof()
    {
        Rig rig = new Rig();
        rig.bankAll();
        rig.persistence.activeIdentity = new TrackingIdentity("rsprofile.bob", 2L);
        rig.persistence.fence();
        rig.persistence.activeIdentity = new TrackingIdentity("rsprofile.alice", 1L);
        rig.persistence.fence();
        rig.chest[0] = new Item[0];
        rig.tick[0]++;
        rig.changed();
        assertEquals("A -> B -> A: the earlier click proves nothing", 0L,
            RewardChestBankAllTest.received(rig.engine, ItemID.TWISTED_BOW));
        assertNull(rig.chestProof());
    }

    @Test
    public void partialRepeatedAndExpiredDecreasesStayExactlyOnce()
    {
        Rig rig = new Rig();
        rig.bankAll();
        rig.chest[0] = new Item[]{new Item(ItemID.TWISTED_BOW, 1), new Item(ItemID.DRAGON_ARROW, 40)};
        rig.tick[0]++;
        rig.changed();
        rig.changed();
        assertEquals("a repeated callback moves nothing", 10L,
            RewardChestBankAllTest.received(rig.engine, ItemID.DRAGON_ARROW));
        rig.chest[0] = new Item[]{new Item(ItemID.DRAGON_ARROW, 40)};
        rig.tick[0]++;
        rig.changed();
        assertEquals(1L, RewardChestBankAllTest.received(rig.engine, ItemID.TWISTED_BOW));
        rig.plugin.chestChanged(InventoryID.BANK, rig.rewards);
        rig.chest[0] = new Item[0];
        rig.tick[0] += 11;
        rig.changed();
        assertEquals("after the window nothing more is proof", 10L,
            RewardChestBankAllTest.received(rig.engine, ItemID.DRAGON_ARROW));
    }

    @Test
    public void chestConsumptionAndOwnerFencesNeverDeadlock() throws Exception
    {
        Rig rig = new Rig();
        int rounds = 200;
        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread client = new Thread(() ->
        {
            try
            {
                start.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < rounds; i++)
                {
                    rig.chest[0] = new Item[]{new Item(ItemID.TWISTED_BOW, 1), new Item(ItemID.DRAGON_ARROW, 50)};
                    rig.bankAll();
                    rig.chest[0] = new Item[]{new Item(ItemID.DRAGON_ARROW, 25)};
                    rig.changed();
                }
            }
            catch (Throwable ex)
            {
                failure.compareAndSet(null, ex);
            }
            finally
            {
                done.countDown();
            }
        });
        Thread lifecycle = new Thread(() ->
        {
            try
            {
                start.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < rounds; i++) rig.persistence.fence();
            }
            catch (Throwable ex)
            {
                failure.compareAndSet(null, ex);
            }
            finally
            {
                done.countDown();
            }
        });
        client.start();
        lifecycle.start();
        boolean finished = done.await(20, TimeUnit.SECONDS);
        long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        assertNull("no lock inversion", deadlocked);
        assertTrue(finished);
        assertNull(failure.get());
        assertFalse(client.isAlive() && lifecycle.isAlive());
    }
}
