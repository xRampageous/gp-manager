package com.gpmanager.engine;

import com.google.gson.Gson;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ActionKind;
import com.gpmanager.model.DeferredClaimProvenance;
import com.gpmanager.model.KeyChestCatalogue;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
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

public class DeferredChestClaimLifecycleTest
{
    private static final int CONTENTS_ITEM = 10_001;
    private static final int CONTENTS_PRICE = 25_000;

    @Test
    public void brimstoneClaimUsesOnlySettledInventoryContentsAndClosesAuditRow()
    {
        GpManagerEngine engine = engine(ContainerSnapshot.empty());
        ProfitTransaction received = settle(engine,
            snapshot(ItemID.KONAR_KEY, 1L), 1_000L);

        assertNotNull(received);
        assertEquals(TransactionType.TRANSFER, received.getType());
        assertFalse(received.isCounted());
        assertEquals(ActionKind.DEFERRED_CLAIM, received.getActionKind());
        assertEquals("Key held · Brimstone chest", received.getNote());
        assertEquals("Brimstone chest", received.getActivityName());
        assertEquals(ItemPriceSource.DEFERRED_CLAIM, received.getFlows().get(0).getPriceSource());

        engine.observeKeyChestInteraction("Open", "Brimstone chest");
        assertNull("menu click alone is not a claim",
            engine.processIfDirty(snapshot(ItemID.KONAR_KEY, 1L), 2_800L));
        assertEquals(DeferredClaimProvenance.Status.PENDING,
            received.getDeferredClaimProvenance().getStatus());

        Map<Integer, Long> afterOpen = new HashMap<>();
        afterOpen.put(CONTENTS_ITEM, 1L);
        ProfitTransaction contents = settle(engine, new ContainerSnapshot(afterOpen), 3_400L);

        assertNotNull(contents);
        assertEquals(TransactionType.LOOT, contents.getType());
        assertTrue(contents.isCounted());
        assertEquals("Brimstone chest", contents.getActivityName());
        assertEquals(CONTENTS_PRICE, contents.getNet());
        assertEquals(1, contents.getFlows().size());
        assertEquals(CONTENTS_ITEM, contents.getFlows().get(0).getItemId());
        assertEquals(DeferredClaimProvenance.Status.CLAIMED,
            received.getDeferredClaimProvenance().getStatus());
        assertEquals("Key claimed · Brimstone chest", received.getNote());
        assertEquals(CONTENTS_PRICE, engine.getMetrics(5_200L).getNet());
    }

    @Test
    public void observedChestClickAndContentsWithoutKeyLossLeaveClaimPending()
    {
        GpManagerEngine engine = engine(ContainerSnapshot.empty());
        ProfitTransaction received = settle(engine,
            snapshot(ItemID.KONAR_KEY, 1L), 1_000L);
        engine.observeKeyChestInteraction("Unlock", "Brimstone chest");

        Map<Integer, Long> contentsSnapshot = new HashMap<>();
        contentsSnapshot.put(ItemID.KONAR_KEY, 1L);
        contentsSnapshot.put(CONTENTS_ITEM, 1L);
        ProfitTransaction contents = settle(engine, new ContainerSnapshot(contentsSnapshot), 3_400L);

        assertNotNull(contents);
        assertEquals(TransactionType.GAIN, contents.getType());
        assertFalse("unmeasured click cannot relabel an ordinary gain",
            "Brimstone chest".equals(contents.getActivityName()));
        assertEquals(DeferredClaimProvenance.Status.PENDING,
            received.getDeferredClaimProvenance().getStatus());
        assertEquals(CONTENTS_PRICE, engine.getMetrics(5_200L).getNet());
    }

    @Test
    public void belowMinimumChestContentsStillCloseDeferredAuditRow()
    {
        GpManagerEngine engine = engine(ContainerSnapshot.empty(), 50_000);
        ProfitTransaction received = settle(engine,
            snapshot(ItemID.KONAR_KEY, 1L), 1_000L);
        engine.observeKeyChestInteraction("Open", "Brimstone chest");

        Map<Integer, Long> contentsSnapshot = new HashMap<>();
        contentsSnapshot.put(CONTENTS_ITEM, 1L);
        ProfitTransaction auditUpdate = settle(engine, new ContainerSnapshot(contentsSnapshot), 3_400L);

        assertNotNull(auditUpdate);
        assertEquals(ActionKind.DEFERRED_CLAIM, auditUpdate.getActionKind());
        assertEquals(DeferredClaimProvenance.Status.CLAIMED,
            received.getDeferredClaimProvenance().getStatus());
        assertEquals(0L, engine.getMetrics(5_200L).getNet());
        assertEquals(1, engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void crystalKeyUseKeepsGeOpportunityCostAndNeverCreatesDeferredRow()
    {
        GpManagerEngine engine = engine(snapshot(ItemID.CRYSTAL_KEY, 1L));
        engine.observeKeyChestInteraction("Unlock", "Crystal chest");
        Map<Integer, Long> afterOpen = new HashMap<>();
        afterOpen.put(CONTENTS_ITEM, 1L);

        ProfitTransaction contents = settle(engine, new ContainerSnapshot(afterOpen), 1_600L);

        assertNotNull(contents);
        assertEquals(TransactionType.LOOT, contents.getType());
        assertEquals("Crystal chest", contents.getActivityName());
        assertEquals(CONTENTS_PRICE - 18_000L, contents.getNet());
        assertEquals(-18_000L, flow(contents, ItemID.CRYSTAL_KEY).getValueDelta());
        assertEquals(ItemPriceSource.GRAND_EXCHANGE, flow(contents, ItemID.CRYSTAL_KEY).getPriceSource());
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertNull(engine.getActiveSession().getTransactions().get(0).getDeferredClaimProvenance());
    }

    @Test
    public void tradeableKeyReceiptKeepsGeValueAndHasNoDeferredAuditMetadata()
    {
        GpManagerEngine engine = engine(ContainerSnapshot.empty());

        ProfitTransaction receipt = settle(engine, snapshot(ItemID.CRYSTAL_KEY, 1L), 1_000L);

        assertNotNull(receipt);
        assertEquals(TransactionType.GAIN, receipt.getType());
        assertEquals(18_000L, receipt.getNet());
        assertNull(receipt.getDeferredClaimProvenance());
        assertNull(receipt.getActionKind());
    }

    @Test
    public void untradeableKeyLossOnDeathClosesWithoutBookingKeyCost()
    {
        GpManagerEngine engine = engine(ContainerSnapshot.empty());
        ProfitTransaction received = settle(engine, snapshot(ItemID.KONAR_KEY, 1L), 1_000L);
        engine.markUnclassifiedLocalDeath();

        ProfitTransaction closed = settle(engine, ContainerSnapshot.empty(), 2_800L);

        assertNotNull(closed);
        assertEquals("Key lost on death · Brimstone chest", received.getNote());
        assertEquals(DeferredClaimProvenance.Status.LOST_ON_DEATH,
            received.getDeferredClaimProvenance().getStatus());
        assertEquals(0L, engine.getMetrics(4_600L).getNet());
    }

    @Test
    public void ambiguousChestUseNeedsAnObservedChestTarget()
    {
        DeferredChestClaimLifecycle lifecycle = new DeferredChestClaimLifecycle();
        Map<Integer, Long> keyLoss = Collections.singletonMap(ItemID.SLAYER_WILDERNESS_KEY, 1L);
        Map<Integer, Long> contents = Collections.singletonMap(CONTENTS_ITEM, 1L);
        assertNull(lifecycle.matchContents(keyLoss, contents, null));

        lifecycle.observeMenuOption("Open", "Larran's big chest");
        DeferredChestClaimLifecycle.Match match = lifecycle.matchContents(keyLoss, contents, null);
        assertNotNull(match);
        assertEquals("Larran's big chest", match.getChestName());
    }

    @Test
    public void clickThenMeasuredKeyLossOneTickBeforeContentsUsesClickedLarranChest()
    {
        DeferredChestClaimLifecycle lifecycle = new DeferredChestClaimLifecycle();
        Map<Integer, Long> keyLoss = Collections.singletonMap(ItemID.SLAYER_WILDERNESS_KEY, 1L);
        Map<Integer, Long> contents = Collections.singletonMap(CONTENTS_ITEM, 1L);

        lifecycle.observeMenuOption("Open", "Larran's big chest");
        assertNull("key loss alone is remembered, not a claim",
            lifecycle.matchContents(keyLoss, Collections.emptyMap(), null));
        lifecycle.tick();

        DeferredChestClaimLifecycle.Match match =
            lifecycle.matchContents(Collections.emptyMap(), contents, null);

        assertNotNull(match);
        assertEquals(ItemID.SLAYER_WILDERNESS_KEY, match.getKeyItemId());
        assertEquals(1L, match.getQuantity());
        assertEquals("Larran's big chest", match.getChestName());
    }

    @Test
    public void uncorroboratedContentsConsumeChestClickWindow()
    {
        DeferredChestClaimLifecycle lifecycle = new DeferredChestClaimLifecycle();
        Map<Integer, Long> keyLoss = Collections.singletonMap(ItemID.SLAYER_WILDERNESS_KEY, 1L);
        Map<Integer, Long> contents = Collections.singletonMap(CONTENTS_ITEM, 1L);

        lifecycle.observeMenuOption("Open", "Larran's big chest");
        assertNull(lifecycle.matchContents(Collections.emptyMap(), contents, null));
        assertNull("the earlier click cannot disambiguate a later unrelated pair",
            lifecycle.matchContents(keyLoss, contents, null));
    }

    @Test
    public void chestClickAloneCannotAttributeUnrelatedPickupAndLaterMenuCancelsWindow()
    {
        DeferredChestClaimLifecycle lifecycle = new DeferredChestClaimLifecycle();
        Map<Integer, Long> contents = Collections.singletonMap(CONTENTS_ITEM, 1L);
        lifecycle.observeMenuOption("Open", "Brimstone chest");
        assertNull("a click without a settled contents gain is not a claim",
            lifecycle.matchContents(Collections.emptyMap(), Collections.emptyMap(), null));

        lifecycle.observeMenuOption("Open", "Larran's big chest");
        assertFalse(lifecycle.observeMenuOption("Talk-to", "Banker"));
        assertNull("unrelated menu action clears the chest window",
            lifecycle.matchContents(Collections.singletonMap(ItemID.SLAYER_WILDERNESS_KEY, 1L),
                contents, null));
    }

    @Test
    public void chestClickCannotOverrideMeasuredDifferentKeyUse()
    {
        DeferredChestClaimLifecycle lifecycle = new DeferredChestClaimLifecycle();
        lifecycle.observeMenuOption("Unlock", "Brimstone chest");

        assertNull(lifecycle.matchContents(Collections.singletonMap(ItemID.CRYSTAL_KEY, 1L),
            Collections.singletonMap(CONTENTS_ITEM, 1L), null));
    }

    @Test
    public void deferredClaimMetadataSurvivesProjectionAndSessionSerialization()
    {
        ProfitTransaction row = new ProfitTransaction(
            1_000L, null, TransactionType.TRANSFER, TrackingContext.TRANSFER,
            "Key held · Brimstone chest", "Brimstone chest", false,
            java.util.Arrays.asList(
                new ItemFlow(ItemID.KONAR_KEY, "Brimstone key", 1L, 0, 0L,
                    ItemPriceSource.DEFERRED_CLAIM),
                new ItemFlow(CONTENTS_ITEM, "Reward", 1L, CONTENTS_PRICE, CONTENTS_PRICE)));
        row.setDeferredClaimProvenance(DeferredChestClaimLifecycle.receivedEntry(
            ItemID.KONAR_KEY, "Brimstone key", "Brimstone chest", 1L, 1_000L));

        ProfitTransaction projection = row.presentationProjection(
            flow -> flow.getItemId() == ItemID.KONAR_KEY);
        ProfitTransaction restored = new Gson().fromJson(new Gson().toJson(row), ProfitTransaction.class);

        assertNotNull(projection.getDeferredClaimProvenance());
        assertEquals(DeferredClaimProvenance.Status.PENDING,
            projection.getDeferredClaimProvenance().getStatus());
        assertEquals(DeferredClaimProvenance.Status.PENDING,
            restored.getDeferredClaimProvenance().getStatus());
        assertEquals("Brimstone chest", restored.getDeferredClaimProvenance().getChestName());
    }

    private static GpManagerEngine engine(ContainerSnapshot baseline)
    {
        return engine(baseline, 1);
    }

    private static GpManagerEngine engine(ContainerSnapshot baseline, int minimumTransactionValue)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public int minimumTransactionValue() { return minimumTransactionValue; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        GpManagerEngine engine = new GpManagerEngine(deltas -> {
            List<ItemFlow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                boolean deferred = KeyChestCatalogue.isDeferredClaimKey(id);
                int price = id == CONTENTS_ITEM ? CONTENTS_PRICE
                    : id == ItemID.CRYSTAL_KEY ? 18_000 : (deferred ? 0 : 100);
                String name = id == ItemID.KONAR_KEY ? "Brimstone key"
                    : id == ItemID.CRYSTAL_KEY ? "Crystal key"
                    : id == CONTENTS_ITEM ? "Test chest reward" : "Key";
                flows.add(new ItemFlow(id, name, delta.getValue(), price,
                    delta.getValue() * price,
                    deferred ? ItemPriceSource.DEFERRED_CLAIM : ItemPriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
        engine.ensureSession(500L);
        engine.setBaseline(baseline);
        return engine;
    }

    private static ProfitTransaction settle(GpManagerEngine engine, ContainerSnapshot snapshot, long now)
    {
        engine.markInventoryDirty();
        ProfitTransaction first = engine.processIfDirty(snapshot, now);
        ProfitTransaction settled = engine.processIfDirty(snapshot, now + 600L);
        return settled == null ? first : settled;
    }

    private static ContainerSnapshot snapshot(int itemId, long quantity)
    {
        return new ContainerSnapshot(Collections.singletonMap(itemId, quantity));
    }

    private static ItemFlow flow(ProfitTransaction transaction, int itemId)
    {
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow.getItemId() == itemId)
            {
                return flow;
            }
        }
        throw new AssertionError("missing item flow " + itemId);
    }
}
