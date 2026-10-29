package com.gpmanager;

import com.google.gson.Gson;
import com.gpmanager.SavedState.PendingClaim;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KeyChestClaimTest
{
    private static final int CONTENTS_ITEM = 10_001;
    private static final int CONTENTS_PRICE = 25_000;

    @Test
    public void brimstoneClaimUsesOnlySettledInventoryContentsAndClosesAuditRow()
    {
        Engine engine = engine(FixtureReads.empty());
        Transaction received = settle(engine,
            snapshot(ItemID.KONAR_KEY, 1L), 1_000L);

        assertNotNull(received);
        assertEquals(TransactionType.TRANSFER, received.type());
        assertFalse(received.counts());
        assertEquals(ActionKind.DEFERRED_CLAIM, received.action());
        assertEquals("Key held · Brimstone chest", received.note());
        assertEquals("Brimstone chest", received.activityName());
        assertEquals(PriceSource.DEFERRED_CLAIM, received.flows().get(0).source());

        engine.observeChest("Open", "Brimstone chest");
        assertNull("menu click alone is not a claim",
            engine.processIfDirty(snapshot(ItemID.KONAR_KEY, 1L), 2_800L, null));
        PendingClaim pending = engine.pendingClaims().get(0);
        assertEquals(received.id(), pending.claimId());
        assertEquals(ItemID.KONAR_KEY, pending.itemOrKeyId);
        assertEquals(1L, pending.quantity());

        Map<Integer, Long> afterOpen = new HashMap<>();
        afterOpen.put(CONTENTS_ITEM, 1L);
        Transaction contents = settle(engine, new ContainerSnapshot(afterOpen), 3_400L);

        assertNotNull(contents);
        assertEquals(TransactionType.LOOT, contents.type());
        assertTrue(contents.counts());
        assertEquals("Brimstone chest", contents.activityName());
        assertEquals(CONTENTS_PRICE, contents.net());
        assertEquals(1, contents.flows().size());
        assertEquals(CONTENTS_ITEM, contents.flows().get(0).item);
        assertEquals("settlement and claim removal happen in the same revision",
            received.id(), contents.claimId());
        assertTrue(engine.pendingClaims().isEmpty());
        assertEquals(CONTENTS_PRICE, engine.metrics(5_200L).net);

        // Replay: a stale duplicate of the settled claim can never book again.
        SavedState saved = engine.savedState();
        List<PendingClaim> stale = new ArrayList<>();
        stale.add(new PendingClaim(received.id(), ItemID.KONAR_KEY, 1L,
            1_000L));
        saved.setPendingClaims(stale);
        Engine reloaded = engine(FixtureReads.empty());
        reloaded.restore(saved, 6_000L);
        assertTrue("the settled sourceClaimId removes the stale claim on load", reloaded.pendingClaims().isEmpty());
        assertEquals(CONTENTS_PRICE, reloaded.metrics(6_000L).net);
        assertEquals(2, reloaded.session().transactions().size());
    }

    @Test
    public void observedChestClickAndContentsWithoutKeyLossLeaveClaimPending()
    {
        Engine engine = engine(FixtureReads.empty());
        Transaction received = settle(engine,
            snapshot(ItemID.KONAR_KEY, 1L), 1_000L);
        engine.observeChest("Unlock", "Brimstone chest");

        Map<Integer, Long> contentsSnapshot = new HashMap<>();
        contentsSnapshot.put(ItemID.KONAR_KEY, 1L);
        contentsSnapshot.put(CONTENTS_ITEM, 1L);
        Transaction contents = settle(engine, new ContainerSnapshot(contentsSnapshot), 3_400L);

        assertNotNull(contents);
        assertEquals(TransactionType.GAIN, contents.type());
        assertFalse("unmeasured click cannot relabel an ordinary gain",
            "Brimstone chest".equals(contents.activityName()));
        assertTrue("the held key stays a durable unresolved claim", engine.pendingClaims().size() == 1
            && received.id().equals(engine.pendingClaims().get(0).claimId()));
        assertTrue(contents.claimId().isEmpty());
        assertEquals(CONTENTS_PRICE, engine.metrics(5_200L).net);
    }

    @Test
    public void unresolvedClaimSurvivesSaveReloadAndSettlesInTheActiveSession()
    {
        Engine engine = engine(FixtureReads.empty());
        Transaction received = settle(engine, snapshot(ItemID.KONAR_KEY, 1L), 1_000L);
        Engine reloaded = engine(snapshot(ItemID.KONAR_KEY, 1L));
        reloaded.restore(engine.savedState(), 2_000L);
        assertEquals("a durable claim does not expire with the runtime window",
            received.id(), reloaded.pendingClaims().get(0).claimId());
        assertTrue("a recovered owner resumes paused", reloaded.session().paused);
        reloaded.togglePause(2_100L);
        reloaded.setBaseline(snapshot(ItemID.KONAR_KEY, 1L));

        reloaded.observeChest("Open", "Brimstone chest");
        Map<Integer, Long> afterOpen = new HashMap<>();
        afterOpen.put(CONTENTS_ITEM, 1L);
        Transaction contents = settle(reloaded, new ContainerSnapshot(afterOpen), 3_400L);

        assertNotNull(contents);
        assertEquals(received.id(), contents.claimId());
        assertTrue(reloaded.pendingClaims().isEmpty());
        assertEquals(CONTENTS_PRICE, reloaded.metrics(5_200L).net);
        assertTrue("settlement lands in the active session",
            reloaded.session().transactions().contains(contents));
    }

    @Test
    public void crystalKeyUseKeepsGeOpportunityCostAndNeverCreatesDeferredRow()
    {
        Engine engine = engine(snapshot(ItemID.CRYSTAL_KEY, 1L));
        engine.observeChest("Unlock", "Crystal chest");
        Map<Integer, Long> afterOpen = new HashMap<>();
        afterOpen.put(CONTENTS_ITEM, 1L);

        Transaction contents = settle(engine, new ContainerSnapshot(afterOpen), 1_600L);

        assertNotNull(contents);
        assertEquals(TransactionType.LOOT, contents.type());
        assertEquals("Crystal chest", contents.activityName());
        assertEquals(CONTENTS_PRICE - 18_000L, contents.net());
        assertEquals(-18_000L, flow(contents, ItemID.CRYSTAL_KEY).value);
        assertEquals(PriceSource.GRAND_EXCHANGE, flow(contents, ItemID.CRYSTAL_KEY).source());
        assertEquals(1, engine.session().transactions().size());
        assertTrue(engine.pendingClaims().isEmpty());
    }

    @Test
    public void tradeableKeyReceiptKeepsGeValueAndHasNoDeferredAuditMetadata()
    {
        Engine engine = engine(FixtureReads.empty());

        Transaction receipt = settle(engine, snapshot(ItemID.CRYSTAL_KEY, 1L), 1_000L);

        assertNotNull(receipt);
        assertEquals(TransactionType.GAIN, receipt.type());
        assertEquals(18_000L, receipt.net());
        assertTrue(engine.pendingClaims().isEmpty());
        assertNull(receipt.action());
    }

    @Test
    public void untradeableKeyLossOnDeathClosesWithoutBookingKeyCost()
    {
        Engine engine = engine(FixtureReads.empty());
        Transaction received = settle(engine, snapshot(ItemID.KONAR_KEY, 1L), 1_000L);
        engine.markUnknownDeath();

        Transaction closed = settle(engine, FixtureReads.empty(), 2_800L);

        assertNull("the deferred token itself is never booked as a cost", closed);
        assertTrue("a key lost on death closes its claim without a key cost", engine.pendingClaims().isEmpty());
        assertEquals(received.id(), engine.session().transactions().get(0).id());
        assertEquals(0L, engine.metrics(4_600L).net);
    }

    @Test
    public void partialChestSettlementKeepsTheRemainderAcrossRestart()
    {
        Engine engine = engine(FixtureReads.empty());
        Transaction audit = settle(engine, snapshot(ItemID.KONAR_KEY, 2L), 1_000L);
        assertNotNull(audit);
        PendingClaim claim = engine.pendingClaims().get(0);
        assertEquals("one audit row carries the full held quantity", audit.id(), claim.claimId());
        assertEquals(2L, claim.quantity());

        engine.observeChest("Open", "Brimstone chest");
        Map<Integer, Long> afterFirstOpen = new HashMap<>();
        afterFirstOpen.put(ItemID.KONAR_KEY, 1L);
        afterFirstOpen.put(CONTENTS_ITEM, 1L);
        Transaction partial = settle(engine, new ContainerSnapshot(afterFirstOpen), 3_400L);

        assertNotNull(partial);
        assertEquals(CONTENTS_PRICE, partial.net());
        assertTrue("a partial settlement relates the claim and never proves closure",
            partial.claimId().isEmpty());
        PendingClaim remainder = engine.pendingClaims().get(0);
        assertEquals(audit.id(), remainder.claimId());
        assertEquals("one key remains unresolved", 1L, remainder.quantity());

        Gson gson = new Gson();
        SavedState saved = engine.savedState();
        SavedState savedCopy = gson.fromJson(gson.toJson(saved), SavedState.class);

        Engine reloaded = engine(FixtureReads.empty());
        reloaded.restore(saved, 4_000L);
        assertEquals("the unresolved remainder survives restart", 1, reloaded.pendingClaims().size());
        PendingClaim restored = reloaded.pendingClaims().get(0);
        assertEquals(audit.id(), restored.claimId());
        assertEquals(1L, restored.quantity());
        assertEquals(CONTENTS_PRICE, reloaded.metrics(4_600L).net);

        Engine reloadedAgain = engine(FixtureReads.empty());
        reloadedAgain.restore(savedCopy, 4_000L);
        assertEquals("repeated restore converges on the same claim", 1, reloadedAgain.pendingClaims().size());
        assertEquals(1L, reloadedAgain.pendingClaims().get(0).quantity());
        assertEquals(CONTENTS_PRICE, reloadedAgain.metrics(4_600L).net);

        reloaded.togglePause(4_100L);
        reloaded.setBaseline(snapshot(ItemID.KONAR_KEY, 1L));
        reloaded.observeChest("Open", "Brimstone chest");
        Map<Integer, Long> afterSecondOpen = new HashMap<>();
        afterSecondOpen.put(CONTENTS_ITEM, 1L);
        Transaction closed = settle(reloaded, new ContainerSnapshot(afterSecondOpen), 5_200L);

        assertNotNull(closed);
        assertEquals("the reaching-zero settlement carries the claim id", audit.id(), closed.claimId());
        assertTrue("the fully settled claim is gone", reloaded.pendingClaims().isEmpty());
        assertEquals(2L * CONTENTS_PRICE, reloaded.metrics(5_800L).net);

        assertNull("replaying the already settled snapshot books nothing",
            settle(reloaded, new ContainerSnapshot(afterSecondOpen), 6_600L));

        SavedState closedCopy = gson.fromJson(gson.toJson(reloaded.savedState()), SavedState.class);
        Engine afterClose = engine(FixtureReads.empty());
        afterClose.restore(closedCopy, 7_200L);
        assertTrue("a settled claim never comes back", afterClose.pendingClaims().isEmpty());
        assertEquals(2L * CONTENTS_PRICE, afterClose.metrics(7_800L).net);
        assertEquals("reload never books the settlement twice",
            reloaded.session().transactions().size(),
            afterClose.session().transactions().size());
    }

    @Test
    public void ambiguousChestUseNeedsAnObservedChestTarget()
    {
        KeyClaims lifecycle = new KeyClaims();
        Map<Integer, Long> keyLoss = Collections.singletonMap(ItemID.SLAYER_WILDERNESS_KEY, 1L);
        Map<Integer, Long> contents = Collections.singletonMap(CONTENTS_ITEM, 1L);
        assertNull(lifecycle.matchChest(keyLoss, contents, null));

        lifecycle.chestClick("Open", "Larran's big chest");
        KeyClaims.ChestOpen match = lifecycle.matchChest(keyLoss, contents, null);
        assertNotNull(match);
        assertEquals("Larran's big chest", match.chest);
    }

    @Test
    public void clickThenMeasuredKeyLossOneTickBeforeContentsUsesClickedLarranChest()
    {
        KeyClaims lifecycle = new KeyClaims();
        Map<Integer, Long> keyLoss = Collections.singletonMap(ItemID.SLAYER_WILDERNESS_KEY, 1L);
        Map<Integer, Long> contents = Collections.singletonMap(CONTENTS_ITEM, 1L);

        lifecycle.chestClick("Open", "Larran's big chest");
        assertNull("key loss alone is remembered, not a claim",
            lifecycle.matchChest(keyLoss, Collections.emptyMap(), null));
        lifecycle.tick();

        KeyClaims.ChestOpen match =
            lifecycle.matchChest(Collections.emptyMap(), contents, null);

        assertNotNull(match);
        assertEquals(ItemID.SLAYER_WILDERNESS_KEY, match.key);
        assertEquals(1L, match.quantity);
        assertEquals("Larran's big chest", match.chest);
    }

    @Test
    public void uncorroboratedContentsConsumeChestClickWindow()
    {
        KeyClaims lifecycle = new KeyClaims();
        Map<Integer, Long> keyLoss = Collections.singletonMap(ItemID.SLAYER_WILDERNESS_KEY, 1L);
        Map<Integer, Long> contents = Collections.singletonMap(CONTENTS_ITEM, 1L);

        lifecycle.chestClick("Open", "Larran's big chest");
        assertNull(lifecycle.matchChest(Collections.emptyMap(), contents, null));
        assertNull("the earlier click cannot disambiguate a later unrelated pair",
            lifecycle.matchChest(keyLoss, contents, null));
    }

    @Test
    public void chestClickAloneCannotAttributeUnrelatedPickupAndLaterMenuCancelsWindow()
    {
        KeyClaims lifecycle = new KeyClaims();
        Map<Integer, Long> contents = Collections.singletonMap(CONTENTS_ITEM, 1L);
        lifecycle.chestClick("Open", "Brimstone chest");
        assertNull("a click without a settled contents gain is not a claim",
            lifecycle.matchChest(Collections.emptyMap(), Collections.emptyMap(), null));

        lifecycle.chestClick("Open", "Larran's big chest");
        assertFalse(lifecycle.chestClick("Talk-to", "Banker"));
        assertNull("unrelated menu action clears the chest window",
            lifecycle.matchChest(Collections.singletonMap(ItemID.SLAYER_WILDERNESS_KEY, 1L),
                contents, null));
    }

    @Test
    public void chestClickCannotOverrideMeasuredDifferentKeyUse()
    {
        KeyClaims lifecycle = new KeyClaims();
        lifecycle.chestClick("Unlock", "Brimstone chest");

        assertNull(lifecycle.matchChest(Collections.singletonMap(ItemID.CRYSTAL_KEY, 1L),
            Collections.singletonMap(CONTENTS_ITEM, 1L), null));
    }

    @Test
    public void heldKeyRowCarriesNoProvenanceAndTheClaimRoundTripsThroughSavedState()
    {
        Engine engine = engine(FixtureReads.empty());
        Transaction received = settle(engine, snapshot(ItemID.KONAR_KEY, 1L), 1_000L);
        Gson gson = new Gson();
        String row = gson.toJson(received);
        assertFalse(row.contains("deferredClaimProvenance"));
        assertFalse(row.contains("chestName"));

        SavedState state = gson.fromJson(gson.toJson(engine.savedState()), SavedState.class);
        assertEquals(1, state.pendingClaims().size());
        assertEquals(received.id(), state.pendingClaims().get(0).claimId());
        assertEquals(ItemID.KONAR_KEY, state.pendingClaims().get(0).itemOrKeyId);
    }

    private static Engine engine(ContainerSnapshot baseline)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        Engine engine = new Engine(deltas -> {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                boolean deferred = KeyChestCatalogue.isDeferredKey(id);
                int price = id == CONTENTS_ITEM ? CONTENTS_PRICE
                    : id == ItemID.CRYSTAL_KEY ? 18_000 : (deferred ? 0 : 100);
                String name = id == ItemID.KONAR_KEY ? "Brimstone key"
                    : id == ItemID.CRYSTAL_KEY ? "Crystal key"
                    : id == CONTENTS_ITEM ? "Test chest reward" : "Key";
                flows.add(new Flow(id, name, delta.getValue(), price,
                    delta.getValue() * price,
                    deferred ? PriceSource.DEFERRED_CLAIM : PriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
        engine.ensureSession(500L);
        engine.setBaseline(baseline);
        return engine;
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long now)
    {
        engine.markDirty();
        Transaction first = engine.processIfDirty(snapshot, now, null);
        Transaction settled = engine.processIfDirty(snapshot, now + 600L, null);
        return settled == null ? first : settled;
    }

    private static ContainerSnapshot snapshot(int itemId, long quantity)
    {
        return new ContainerSnapshot(Collections.singletonMap(itemId, quantity));
    }

    private static Flow flow(Transaction transaction, int itemId)
    {
        for (Flow flow : transaction.flows())
        {
            if (flow.item == itemId)
            {
                return flow;
            }
        }
        throw new AssertionError("missing item flow " + itemId);
    }
}
