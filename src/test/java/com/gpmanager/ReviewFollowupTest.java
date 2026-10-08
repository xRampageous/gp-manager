package com.gpmanager;

import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.events.ItemDespawned;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * The Codex follow-up review of GP Manager 1.2 (2026-10-05), kept as regression tests, plus the
 * positive control: your own loot vanishing on your Take still counts as a bag pickup.
 */
public class ReviewFollowupTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    static Tile tile(int sceneX, int sceneY) {
        Map<String, Object> values = new HashMap<>();
        values.put("getWorldLocation", new WorldPoint(100, 100, 0));
        values.put("getSceneLocation", new Point(sceneX, sceneY));
        return ReviewFixesTest.proxy(Tile.class, values);
    }

    static TileItem bones() {
        Map<String, Object> values = new HashMap<>();
        values.put("getId", ReviewFixesTest.BONES);
        values.put("getQuantity", 3);
        // The game marks a pile dropped for you as yours (Codex research 1.2).
        values.put("getOwnership", TileItem.OWNERSHIP_SELF);
        return ReviewFixesTest.proxy(TileItem.class, values);
    }

    static ReviewFixesTest.Plugin take() {
        ReviewFixesTest.Plugin p = ReviewFixesTest.plugin(ReviewFixesTest.engine(), new int[] {11});
        ReviewFixesTest.take(p).item = ReviewFixesTest.BONES;
        ReviewFixesTest.take(p).at = 10;
        ReviewFixesTest.take(p).x = 10;
        ReviewFixesTest.take(p).y = 10;
        return p;
    }

    @Test public void aDifferentSceneTileIsRejectedWithValidTileMetadata() {
        ReviewFixesTest.Plugin p = take();
        p.onItemDespawned(new ItemDespawned(tile(11, 10), bones()));
        assertEquals(0L, ReviewFixesTest.qty(p, Eb.BG));
    }

    @Test public void anotherPlayerTakingTheClickedPileMustNotBecomeOurBagLoot() {
        ReviewFixesTest.Plugin p = take();
        // Our Take has not succeeded. Another player removes the clicked pile first.
        // There is no increase in our inventory or confirmation of a bag insertion.
        p.onItemDespawned(new ItemDespawned(tile(10, 10), bones()));
        assertEquals("the matching tile is intent, not proof of our successful pickup", 0L, ReviewFixesTest.qty(p, Eb.BG));
    }

    @Test public void yourOwnLootVanishingOnYourTakeIsStillABagPickup() {
        ReviewFixesTest.Plugin p = take();
        p.engine.zk(Collections.singletonMap(ReviewFixesTest.BONES, 3L), 100, "Loot from Guard", "Guard");
        p.onItemDespawned(new ItemDespawned(tile(10, 10), bones()));
        assertEquals("loot from your own kill is yours to pick up", 3L, ReviewFixesTest.qty(p, Eb.BG));
    }

    @Test public void anIdentityBoundaryMustClearPendingBagEvidenceImmediately() {
        Am e = ReviewFixesTest.engine();
        ReviewFixesTest.Plugin p = ReviewFixesTest.plugin(e, new int[] {11});
        p.geOfferLedger = new Bj();
        p.interactionContextTracker = new InteractionContextTracker(p.client, e, ReviewFixesTest.CONFIG);
        ReviewFixesTest.take(p).item = ReviewFixesTest.BONES;
        ReviewFixesTest.take(p).at = 10;
        ReviewFixesTest.bag(p).at = 10;
        ReviewFixesTest.bag(p).qty = 3;
        p.engine.eb.put(Eb.FE, -1, 5_964L, 10, null);
        p.engine.eb.add(Eb.CH, -1, 1, 10, null).x = InventoryID.RAIDS_REWARDS;
        p.engine.eb.add(Eb.KT, InventoryID.TACKLE_BOX, 1, Integer.MAX_VALUE, null).data = new Item[0];
        p.lx(); // The async bind may finish before the next GameTick sees !nh().
        assertEquals("old-account bag evidence must be cleared at the boundary, not a later tick", 0L, ReviewFixesTest.qty(p, Eb.BG));
        assertEquals("a waiting reclaim fee never reaches the next account (Codex research 1.2)", 0L, ReviewFixesTest.qty(p, Eb.FE));
        assertEquals("nor a chest Bank-all window", null, p.engine.eb.peek(Eb.CH));
        assertTrue("nor the last account's kit contents", p.engine.eb.peek(Eb.KT) == null);
    }

    @Test public void reopeningAnUnchangedBankMustRefreshTheLastSeenTime() throws Exception {
        Am e = ReviewFixesTest.engine();
        e.bank(1_000_000L, System.currentTimeMillis() - 86_400_000L);
        e.carried(2_000_000L);
        SwingUtilities.invokeAndWait(() -> {
            Dp p = new Dp(e, ReviewFixesTest.CONFIG, null);
            p.active = true;
            p.shell.show(Shell.GRINDS);
            p.avz();
            assertTrue(p.grinds.data.allTime.bankAge > 80_000_000L);
            e.bank(1_000_000L, System.currentTimeMillis());
            // Keep the genuine current cache format in this minute; only bankAt changed.
            p.pageKeys.put(Shell.GRINDS, e.getRevision() + ":" + System.currentTimeMillis() / 60_000L
                + ":" + e.bankValue + ":" + e.carriedValue);
            p.pageForced = false;
            p.avz();
            assertTrue("the bank was just checked, not one day ago: " + p.grinds.data.allTime.bankAge,
                p.grinds.data.allTime.bankAge < 5_000L);
        });
    }

    @Test public void backupThenResetMustStayWithTheAccountThatWasConfirmed() throws Exception {
        JsonCodec.bind(new Gson());
        Am e = ReviewFixesTest.engine();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(temporary.newFolder().toPath()));
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        TrackingIdentity alice = new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID);
        TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);
        AtomicReference<TrackingIdentity> current = new AtomicReference<>(alice);
        AtomicReference<String> backedUpOwner = new AtomicReference<>();
        AtomicReference<String> resetOwner = new AtomicReference<>();
        CountDownLatch backupFinished = new CountDownLatch(1);
        Ei c = new Ei(null, null, repository, writer, e) {
            @Override TrackingIdentity resolveCurrentIdentity() { return current.get(); }
            @Override Filepath akt(long now) throws java.io.IOException {
                backedUpOwner.set(account());
                Filepath result = super.akt(now);
                backupFinished.countDown();
                return result;
            }
            @Override synchronized Ds sj(long now) {
                resetOwner.set(account());
                return super.sj(now);
            }
        };
        c.start();
        try {
            assertTrue(c.ajy(alice, true));
            e.rm(System.currentTimeMillis());
            SwingUtilities.invokeAndWait(() -> {
                Dp p = new Dp(e, ReviewFixesTest.CONFIG, null, repository, c);
                new GrindsController(p).ti();
                ShellProbe.pressSheet(p.shell, "Back up first");
                try {
                    assertTrue("Alice's backup completed", backupFinished.await(5, TimeUnit.SECONDS));
                    // A profile switch finishes while the backup's EDT callback is queued.
                    current.set(bob);
                    assertTrue(c.ajy(bob, false));
                    long now = System.currentTimeMillis();
                    e.rm(now);
                    e.getActiveSession().kf(new Ac(now, null, Ai.GAIN, Aj.GENERIC, "", "", true,
                        Collections.singletonList(new Ab(526, "Bones", 2L, 100, 200L)),
                        Bd.CONFIRMED, "fixture", null), 2_000);
                    assertEquals("Bob has unrelated history before the callback", 200L, e.getMetrics(now).net);
                } catch (InterruptedException ex) {
                    throw new AssertionError(ex);
                }
            });
            PersistenceProbe.drain(c, 2);
            assertEquals("the safety backup was Alice's", alice.rsProfileKey, backedUpOwner.get());
            assertEquals("Alice's confirmation must not erase Bob; reset target was " + resetOwner.get(),
                200L, e.getMetrics(System.currentTimeMillis()).net);
        } finally {
            c.shutdown(false);
        }
    }
}
