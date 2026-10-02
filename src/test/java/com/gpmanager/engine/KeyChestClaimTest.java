package com.gpmanager;

import com.google.gson.Gson;
import com.gpmanager.SavedState.By;
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
        Am engine = engine(Cc.empty());
        Ac received = settle(engine,
            snapshot(ItemID.KONAR_KEY, 1L), 1_000L);

        assertNotNull(received);
        assertEquals(Ai.TRANSFER, received.getType());
        assertFalse(received.isCounted());
        assertEquals(Au.DEFERRED_CLAIM, received.getActionKind());
        assertEquals("Key held · Brimstone chest", received.getNote());
        assertEquals("Brimstone chest", received.getActivityName());
        assertEquals(Av.DEFERRED_CLAIM, received.getFlows().get(0).getPriceSource());

        engine.abp("Open", "Brimstone chest");
        assertNull("menu click alone is not a claim",
            engine.adj(snapshot(ItemID.KONAR_KEY, 1L), 2_800L));
        By pending = engine.getPendingClaims().get(0);
        assertEquals(received.getId(), pending.getClaimId());
        assertEquals(ItemID.KONAR_KEY, pending.itemOrKeyId);
        assertEquals(1L, pending.getQuantity());

        Map<Integer, Long> afterOpen = new HashMap<>();
        afterOpen.put(CONTENTS_ITEM, 1L);
        Ac contents = settle(engine, new Cc(afterOpen), 3_400L);

        assertNotNull(contents);
        assertEquals(Ai.LOOT, contents.getType());
        assertTrue(contents.isCounted());
        assertEquals("Brimstone chest", contents.getActivityName());
        assertEquals(CONTENTS_PRICE, contents.getNet());
        assertEquals(1, contents.getFlows().size());
        assertEquals(CONTENTS_ITEM, contents.getFlows().get(0).itemId);
        assertEquals("settlement and claim removal happen in the same revision",
            received.getId(), contents.uo());
        assertTrue(engine.getPendingClaims().isEmpty());
        assertEquals(CONTENTS_PRICE, engine.getMetrics(5_200L).net);

        // Replay: a stale duplicate of the settled claim can never book again.
        SavedState saved = engine.qm();
        List<By> stale = new ArrayList<>();
        stale.add(new By(received.getId(), ItemID.KONAR_KEY, 1L,
            1_000L));
        saved.setPendingClaims(stale);
        Am reloaded = engine(Cc.empty());
        reloaded.restore(saved, 6_000L);
        assertTrue("the settled sourceClaimId removes the stale claim on load", reloaded.getPendingClaims().isEmpty());
        assertEquals(CONTENTS_PRICE, reloaded.getMetrics(6_000L).net);
        assertEquals(2, reloaded.getActiveSession().getTransactions().size());
    }

    @Test
    public void observedChestClickAndContentsWithoutKeyLossLeaveClaimPending()
    {
        Am engine = engine(Cc.empty());
        Ac received = settle(engine,
            snapshot(ItemID.KONAR_KEY, 1L), 1_000L);
        engine.abp("Unlock", "Brimstone chest");

        Map<Integer, Long> contentsSnapshot = new HashMap<>();
        contentsSnapshot.put(ItemID.KONAR_KEY, 1L);
        contentsSnapshot.put(CONTENTS_ITEM, 1L);
        Ac contents = settle(engine, new Cc(contentsSnapshot), 3_400L);

        assertNotNull(contents);
        assertEquals(Ai.GAIN, contents.getType());
        assertFalse("unmeasured click cannot relabel an ordinary gain",
            "Brimstone chest".equals(contents.getActivityName()));
        assertTrue("the held key stays a durable unresolved claim", engine.getPendingClaims().size() == 1
            && received.getId().equals(engine.getPendingClaims().get(0).getClaimId()));
        assertTrue(contents.uo().isEmpty());
        assertEquals(CONTENTS_PRICE, engine.getMetrics(5_200L).net);
    }

    @Test
    public void unresolvedClaimSurvivesSaveReloadAndSettlesInTheActiveSession()
    {
        Am engine = engine(Cc.empty());
        Ac received = settle(engine, snapshot(ItemID.KONAR_KEY, 1L), 1_000L);
        Am reloaded = engine(snapshot(ItemID.KONAR_KEY, 1L));
        reloaded.restore(engine.qm(), 2_000L);
        assertEquals("a durable claim does not expire with the runtime window",
            received.getId(), reloaded.getPendingClaims().get(0).getClaimId());
        assertTrue("a recovered owner resumes paused", reloaded.getActiveSession().paused);
        reloaded.togglePause(2_100L);
        reloaded.setBaseline(snapshot(ItemID.KONAR_KEY, 1L));

        reloaded.abp("Open", "Brimstone chest");
        Map<Integer, Long> afterOpen = new HashMap<>();
        afterOpen.put(CONTENTS_ITEM, 1L);
        Ac contents = settle(reloaded, new Cc(afterOpen), 3_400L);

        assertNotNull(contents);
        assertEquals(received.getId(), contents.uo());
        assertTrue(reloaded.getPendingClaims().isEmpty());
        assertEquals(CONTENTS_PRICE, reloaded.getMetrics(5_200L).net);
        assertTrue("settlement lands in the active session",
            reloaded.getActiveSession().getTransactions().contains(contents));
    }

    @Test
    public void crystalKeyUseKeepsGeOpportunityCostAndNeverCreatesDeferredRow()
    {
        Am engine = engine(snapshot(ItemID.CRYSTAL_KEY, 1L));
        engine.abp("Unlock", "Crystal chest");
        Map<Integer, Long> afterOpen = new HashMap<>();
        afterOpen.put(CONTENTS_ITEM, 1L);

        Ac contents = settle(engine, new Cc(afterOpen), 1_600L);

        assertNotNull(contents);
        assertEquals(Ai.LOOT, contents.getType());
        assertEquals("Crystal chest", contents.getActivityName());
        assertEquals(CONTENTS_PRICE - 18_000L, contents.getNet());
        assertEquals(-18_000L, flow(contents, ItemID.CRYSTAL_KEY).valueDelta);
        assertEquals(Av.GRAND_EXCHANGE, flow(contents, ItemID.CRYSTAL_KEY).getPriceSource());
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertTrue(engine.getPendingClaims().isEmpty());
    }

    @Test
    public void tradeableKeyReceiptKeepsGeValueAndHasNoDeferredAuditMetadata()
    {
        Am engine = engine(Cc.empty());

        Ac receipt = settle(engine, snapshot(ItemID.CRYSTAL_KEY, 1L), 1_000L);

        assertNotNull(receipt);
        assertEquals(Ai.GAIN, receipt.getType());
        assertEquals(18_000L, receipt.getNet());
        assertTrue(engine.getPendingClaims().isEmpty());
        assertNull(receipt.getActionKind());
    }

    @Test
    public void untradeableKeyLossOnDeathClosesWithoutBookingKeyCost()
    {
        Am engine = engine(Cc.empty());
        Ac received = settle(engine, snapshot(ItemID.KONAR_KEY, 1L), 1_000L);
        engine.zj();

        Ac closed = settle(engine, Cc.empty(), 2_800L);

        assertNull("the deferred token itself is never booked as a cost", closed);
        assertTrue("a key lost on death closes its claim without a key cost", engine.getPendingClaims().isEmpty());
        assertEquals(received.getId(), engine.getActiveSession().getTransactions().get(0).getId());
        assertEquals(0L, engine.getMetrics(4_600L).net);
    }

    @Test
    public void partialChestSettlementKeepsTheRemainderAcrossRestart()
    {
        Am engine = engine(Cc.empty());
        Ac audit = settle(engine, snapshot(ItemID.KONAR_KEY, 2L), 1_000L);
        assertNotNull(audit);
        By claim = engine.getPendingClaims().get(0);
        assertEquals("one audit row carries the full held quantity", audit.getId(), claim.getClaimId());
        assertEquals(2L, claim.getQuantity());

        engine.abp("Open", "Brimstone chest");
        Map<Integer, Long> afterFirstOpen = new HashMap<>();
        afterFirstOpen.put(ItemID.KONAR_KEY, 1L);
        afterFirstOpen.put(CONTENTS_ITEM, 1L);
        Ac partial = settle(engine, new Cc(afterFirstOpen), 3_400L);

        assertNotNull(partial);
        assertEquals(CONTENTS_PRICE, partial.getNet());
        assertTrue("a partial settlement relates the claim and never proves closure",
            partial.uo().isEmpty());
        By remainder = engine.getPendingClaims().get(0);
        assertEquals(audit.getId(), remainder.getClaimId());
        assertEquals("one key remains unresolved", 1L, remainder.getQuantity());

        Gson gson = new Gson();
        SavedState saved = engine.qm();
        SavedState savedCopy = gson.fromJson(gson.toJson(saved), SavedState.class);

        Am reloaded = engine(Cc.empty());
        reloaded.restore(saved, 4_000L);
        assertEquals("the unresolved remainder survives restart", 1, reloaded.getPendingClaims().size());
        By restored = reloaded.getPendingClaims().get(0);
        assertEquals(audit.getId(), restored.getClaimId());
        assertEquals(1L, restored.getQuantity());
        assertEquals(CONTENTS_PRICE, reloaded.getMetrics(4_600L).net);

        Am reloadedAgain = engine(Cc.empty());
        reloadedAgain.restore(savedCopy, 4_000L);
        assertEquals("repeated restore converges on the same claim", 1, reloadedAgain.getPendingClaims().size());
        assertEquals(1L, reloadedAgain.getPendingClaims().get(0).getQuantity());
        assertEquals(CONTENTS_PRICE, reloadedAgain.getMetrics(4_600L).net);

        reloaded.togglePause(4_100L);
        reloaded.setBaseline(snapshot(ItemID.KONAR_KEY, 1L));
        reloaded.abp("Open", "Brimstone chest");
        Map<Integer, Long> afterSecondOpen = new HashMap<>();
        afterSecondOpen.put(CONTENTS_ITEM, 1L);
        Ac closed = settle(reloaded, new Cc(afterSecondOpen), 5_200L);

        assertNotNull(closed);
        assertEquals("the reaching-zero settlement carries the claim id", audit.getId(), closed.uo());
        assertTrue("the fully settled claim is gone", reloaded.getPendingClaims().isEmpty());
        assertEquals(2L * CONTENTS_PRICE, reloaded.getMetrics(5_800L).net);

        assertNull("replaying the already settled snapshot books nothing",
            settle(reloaded, new Cc(afterSecondOpen), 6_600L));

        SavedState closedCopy = gson.fromJson(gson.toJson(reloaded.qm()), SavedState.class);
        Am afterClose = engine(Cc.empty());
        afterClose.restore(closedCopy, 7_200L);
        assertTrue("a settled claim never comes back", afterClose.getPendingClaims().isEmpty());
        assertEquals(2L * CONTENTS_PRICE, afterClose.getMetrics(7_800L).net);
        assertEquals("reload never books the settlement twice",
            reloaded.getActiveSession().getTransactions().size(),
            afterClose.getActiveSession().getTransactions().size());
    }

    @Test
    public void ambiguousChestUseNeedsAnObservedChestTarget()
    {
        Da lifecycle = new Da();
        Map<Integer, Long> keyLoss = Collections.singletonMap(ItemID.SLAYER_WILDERNESS_KEY, 1L);
        Map<Integer, Long> contents = Collections.singletonMap(CONTENTS_ITEM, 1L);
        assertNull(lifecycle.aaj(keyLoss, contents, null));

        lifecycle.qo("Open", "Larran's big chest");
        Da.Eg match = lifecycle.aaj(keyLoss, contents, null);
        assertNotNull(match);
        assertEquals("Larran's big chest", match.chestName);
    }

    @Test
    public void clickThenMeasuredKeyLossOneTickBeforeContentsUsesClickedLarranChest()
    {
        Da lifecycle = new Da();
        Map<Integer, Long> keyLoss = Collections.singletonMap(ItemID.SLAYER_WILDERNESS_KEY, 1L);
        Map<Integer, Long> contents = Collections.singletonMap(CONTENTS_ITEM, 1L);

        lifecycle.qo("Open", "Larran's big chest");
        assertNull("key loss alone is remembered, not a claim",
            lifecycle.aaj(keyLoss, Collections.emptyMap(), null));
        lifecycle.tick();

        Da.Eg match =
            lifecycle.aaj(Collections.emptyMap(), contents, null);

        assertNotNull(match);
        assertEquals(ItemID.SLAYER_WILDERNESS_KEY, match.keyItemId);
        assertEquals(1L, match.quantity);
        assertEquals("Larran's big chest", match.chestName);
    }

    @Test
    public void uncorroboratedContentsConsumeChestClickWindow()
    {
        Da lifecycle = new Da();
        Map<Integer, Long> keyLoss = Collections.singletonMap(ItemID.SLAYER_WILDERNESS_KEY, 1L);
        Map<Integer, Long> contents = Collections.singletonMap(CONTENTS_ITEM, 1L);

        lifecycle.qo("Open", "Larran's big chest");
        assertNull(lifecycle.aaj(Collections.emptyMap(), contents, null));
        assertNull("the earlier click cannot disambiguate a later unrelated pair",
            lifecycle.aaj(keyLoss, contents, null));
    }

    @Test
    public void chestClickAloneCannotAttributeUnrelatedPickupAndLaterMenuCancelsWindow()
    {
        Da lifecycle = new Da();
        Map<Integer, Long> contents = Collections.singletonMap(CONTENTS_ITEM, 1L);
        lifecycle.qo("Open", "Brimstone chest");
        assertNull("a click without a settled contents gain is not a claim",
            lifecycle.aaj(Collections.emptyMap(), Collections.emptyMap(), null));

        lifecycle.qo("Open", "Larran's big chest");
        assertFalse(lifecycle.qo("Talk-to", "Banker"));
        assertNull("unrelated menu action clears the chest window",
            lifecycle.aaj(Collections.singletonMap(ItemID.SLAYER_WILDERNESS_KEY, 1L),
                contents, null));
    }

    @Test
    public void chestClickCannotOverrideMeasuredDifferentKeyUse()
    {
        Da lifecycle = new Da();
        lifecycle.qo("Unlock", "Brimstone chest");

        assertNull(lifecycle.aaj(Collections.singletonMap(ItemID.CRYSTAL_KEY, 1L),
            Collections.singletonMap(CONTENTS_ITEM, 1L), null));
    }

    @Test
    public void heldKeyRowCarriesNoProvenanceAndTheClaimRoundTripsThroughSavedState()
    {
        Am engine = engine(Cc.empty());
        Ac received = settle(engine, snapshot(ItemID.KONAR_KEY, 1L), 1_000L);
        Gson gson = new Gson();
        String row = gson.toJson(received);
        assertFalse(row.contains("deferredClaimProvenance"));
        assertFalse(row.contains("chestName"));

        SavedState state = gson.fromJson(gson.toJson(engine.qm()), SavedState.class);
        assertEquals(1, state.getPendingClaims().size());
        assertEquals(received.getId(), state.getPendingClaims().get(0).getClaimId());
        assertEquals(ItemID.KONAR_KEY, state.getPendingClaims().get(0).itemOrKeyId);
    }

    private static Am engine(Cc baseline)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        Am engine = new Am(deltas -> {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                boolean deferred = KeyChestCatalogue.wg(id);
                int price = id == CONTENTS_ITEM ? CONTENTS_PRICE
                    : id == ItemID.CRYSTAL_KEY ? 18_000 : (deferred ? 0 : 100);
                String name = id == ItemID.KONAR_KEY ? "Brimstone key"
                    : id == ItemID.CRYSTAL_KEY ? "Crystal key"
                    : id == CONTENTS_ITEM ? "Test chest reward" : "Key";
                flows.add(new Ab(id, name, delta.getValue(), price,
                    delta.getValue() * price,
                    deferred ? Av.DEFERRED_CLAIM : Av.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
        engine.rm(500L);
        engine.setBaseline(baseline);
        return engine;
    }

    private static Ac settle(Am engine, Cc snapshot, long now)
    {
        engine.yz();
        Ac first = engine.adj(snapshot, now);
        Ac settled = engine.adj(snapshot, now + 600L);
        return settled == null ? first : settled;
    }

    private static Cc snapshot(int itemId, long quantity)
    {
        return new Cc(Collections.singletonMap(itemId, quantity));
    }

    private static Ab flow(Ac transaction, int itemId)
    {
        for (Ab flow : transaction.getFlows())
        {
            if (flow.itemId == itemId)
            {
                return flow;
            }
        }
        throw new AssertionError("missing item flow " + itemId);
    }
}
