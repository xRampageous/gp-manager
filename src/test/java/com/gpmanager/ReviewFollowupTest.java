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
        assertEquals(0L, ReviewFixesTest.qty(p, Inbox.BAG_GAIN));
    }

    @Test public void anotherPlayerTakingTheClickedPileMustNotBecomeOurBagLoot() {
        ReviewFixesTest.Plugin p = take();
        // Our Take has not succeeded. Another player removes the clicked pile first.
        // There is no increase in our inventory or confirmation of a bag insertion.
        p.onItemDespawned(new ItemDespawned(tile(10, 10), bones()));
        assertEquals("the matching tile is intent, not proof of our successful pickup", 0L, ReviewFixesTest.qty(p, Inbox.BAG_GAIN));
    }

    @Test public void yourOwnLootVanishingOnYourTakeIsStillABagPickup() {
        ReviewFixesTest.Plugin p = take();
        p.engine.markLoot(Collections.singletonMap(ReviewFixesTest.BONES, 3L), 100, "Loot from Guard", "Guard");
        p.onItemDespawned(new ItemDespawned(tile(10, 10), bones()));
        assertEquals("loot from your own kill is yours to pick up", 3L, ReviewFixesTest.qty(p, Inbox.BAG_GAIN));
    }

    @Test public void anIdentityBoundaryMustClearPendingBagEvidenceImmediately() {
        Engine e = ReviewFixesTest.engine();
        ReviewFixesTest.Plugin p = ReviewFixesTest.plugin(e, new int[] {11});
        p.offers = new OfferSlots();
        p.targets = new InteractionContextTracker(p.client, e, ReviewFixesTest.CONFIG);
        ReviewFixesTest.take(p).item = ReviewFixesTest.BONES;
        ReviewFixesTest.take(p).at = 10;
        ReviewFixesTest.bag(p).at = 10;
        ReviewFixesTest.bag(p).qty = 3;
        p.engine.events.put(Inbox.FEE, -1, 5_964L, 10, null);
        p.engine.events.add(Inbox.CHEST, -1, 1, 10, null).x = InventoryID.RAIDS_REWARDS;
        p.engine.events.add(Inbox.KIT_SNAPSHOT, InventoryID.TACKLE_BOX, 1, Integer.MAX_VALUE, null).data = new Item[0];
        p.beginOwnerSwitch(); // The async bind may finish before the next GameTick sees !nh().
        assertEquals("old-account bag evidence must be cleared at the boundary, not a later tick", 0L, ReviewFixesTest.qty(p, Inbox.BAG_GAIN));
        assertEquals("a waiting reclaim fee never reaches the next account (Codex research 1.2)", 0L, ReviewFixesTest.qty(p, Inbox.FEE));
        assertEquals("nor a chest Bank-all window", null, p.engine.events.peek(Inbox.CHEST));
        assertTrue("nor the last account's kit contents", p.engine.events.peek(Inbox.KIT_SNAPSHOT) == null);
    }

    @Test public void resetAndItsSafetyCopyMustStayWithTheAccountThatWasConfirmed() throws Exception {
        JsonCodec.bind(new Gson());
        Engine e = ReviewFixesTest.engine();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(temporary.newFolder().toPath()), false);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        TrackingIdentity alice = new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID);
        TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);
        AtomicReference<TrackingIdentity> current = new AtomicReference<>(alice);
        AtomicReference<String> backedUpOwner = new AtomicReference<>();
        AtomicReference<String> resetOwner = new AtomicReference<>();
        CountDownLatch backupFinished = new CountDownLatch(1);
        Persistence c = new Persistence(null, null, repository, writer, e) {
            @Override TrackingIdentity resolveCurrentIdentity() { return current.get(); }
            @Override Filepath writeBackup(long now) throws java.io.IOException {
                backedUpOwner.set(account());
                Filepath result = super.writeBackup(now);
                backupFinished.countDown();
                return result;
            }
            @Override synchronized ResetOutcome factoryReset(long now, boolean backup) {
                resetOwner.set(account());
                return super.factoryReset(now, true);
            }
        };
        c.start();
        try {
            assertTrue(c.switchIdentity(alice, true));
            e.ensureSession(System.currentTimeMillis());
            SwingUtilities.invokeAndWait(() -> {
                SidebarPanel p = new SidebarPanel(e, ReviewFixesTest.CONFIG, null, repository, c);
                new GrindsController(p).factoryReset();
                ShellProbe.pressSheet(p.shell, "Back up & delete");
                try {
                    assertTrue("Alice's backup completed", backupFinished.await(5, TimeUnit.SECONDS));
                    // A profile switch finishes while the backup's EDT callback is queued.
                    current.set(bob);
                    assertTrue(c.switchIdentity(bob, true));
                    long now = System.currentTimeMillis();
                    e.ensureSession(now);
                    e.session().addTransaction(new Transaction(now, TransactionType.GAIN, Context.GENERIC, "", "", true,
                        Collections.singletonList(new Flow(526, "Bones", 2L, 100, 200L)),
                        Confidence.CONFIRMED, "fixture", null), 2_000, false);
                    assertEquals("Bob has unrelated history before the callback", 200L, e.metrics(now).net);
                } catch (InterruptedException ex) {
                    throw new AssertionError(ex);
                }
            });
            PersistenceProbe.drain(c, 2);
            assertEquals("the safety backup was Alice's", alice.rsProfileKey, backedUpOwner.get());
            assertEquals("Alice's confirmation must not erase Bob; reset target was " + resetOwner.get(),
                200L, e.metrics(System.currentTimeMillis()).net);
        } finally {
            c.shutdown();
        }
    }
}
