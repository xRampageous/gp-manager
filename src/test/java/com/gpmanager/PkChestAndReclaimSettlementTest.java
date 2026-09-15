package com.gpmanager;

import com.gpmanager.engine.*;
import com.gpmanager.model.*;
import java.lang.reflect.Field;
import java.util.*;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real settlement/callback boundaries missed by the isolated accounting helpers. */
/**
 * Partial PK pickups, pending loot chests, reclaim fees and coinless GE Collect settle
 * through inventory evidence only. Review regressions (September 2026) kept under the
 * subject they test.
 */
public class PkChestAndReclaimSettlementTest
{
    private final GpManagerConfig config = new GpManagerConfig() {
        public int stabilizationTicks() { return 0; }
        public boolean keepTransferAuditRows() { return true; }
        public TrackingDisplay trackingDisplay() { return TrackingDisplay.OFF; }
    };

    private GpManagerEngine engine()
    {
        GpManagerEngine engine = new GpManagerEngine(deltas -> {
            List<ItemFlow> flows = new ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new ItemFlow(id, "Item " + id, qty,
                id == 995 ? 1 : 100, qty * (id == 995 ? 1 : 100))));
            return flows;
        }, new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        return engine;
    }

    private ProfitTransaction settle(GpManagerEngine engine, int id, long qty, long now)
    {
        engine.markInventoryDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(Collections.singletonMap(id, qty));
        ProfitTransaction result = engine.processIfDirty(snapshot, now);
        return result != null ? result : engine.processIfDirty(snapshot, now + 1L);
    }

    private ProfitTransaction settleSnapshot(GpManagerEngine engine, Map<Integer, Long> quantities, long now)
    {
        engine.markInventoryDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(quantities);
        ProfitTransaction result = engine.processIfDirty(snapshot, now);
        return result != null ? result : engine.processIfDirty(snapshot, now + 1L);
    }

    private ProfitTransaction createPendingKeyManifest(GpManagerEngine engine, long now)
    {
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        engine.markPkLootContext(Collections.singletonMap(keyId, 1L), 50, "Kill: Rival", now);
        assertNotNull(settleSnapshot(engine, Collections.singletonMap(keyId, 1L), now + 500L));

        engine.setLootKeyChestVisible(true);
        engine.observeLootKeyContainer(keyId, Collections.singletonMap(526, 1L),
            Collections.singletonMap(526, 100L), 100L, true, now + 1_000L);
        engine.observeLootKeyContainer(keyId, Collections.<Integer, Long>emptyMap(),
            Collections.<Integer, Long>emptyMap(), 0L, true, now + 1_100L);
        return engine.getActiveSession().getTransactions().get(0);
    }

    private static Map<Integer, Long> heldKeyAndManifestItem(int keyId)
    {
        return heldKeyAndManifestItems(keyId, 1L);
    }

    private static Map<Integer, Long> heldKeyAndManifestItems(int keyId, long manifestQuantity)
    {
        Map<Integer, Long> quantities = new HashMap<>();
        quantities.put(keyId, 1L);
        quantities.put(526, manifestQuantity);
        return quantities;
    }

    private static Map<Integer, Long> heldKeyAndCoins(int keyId, long coinQuantity)
    {
        Map<Integer, Long> quantities = new HashMap<>();
        quantities.put(keyId, 1L);
        quantities.put(995, coinQuantity);
        return quantities;
    }

    @Test public void partialPkPickupsAllCountWithOneEncounter()
    {
        GpManagerEngine engine = engine();
        engine.markPkLootContext(Collections.singletonMap(526, 3L), 50, "Player kill", 1_100L);
        assertTrue(settle(engine, 526, 1L, 1_600L).isCounted());
        assertTrue("Second actual pickup must count", settle(engine, 526, 2L, 2_200L).isCounted());
        assertTrue(settle(engine, 526, 3L, 2_800L).isCounted());
        assertEquals(300L, engine.getMetrics(3_000L).getNet());
        assertEquals(1, engine.getPkMetrics().getKills());
        assertEquals(300L, engine.getPkMetrics().getRevenue());
        assertNull("Duplicate snapshot is not another pickup", settle(engine, 526, 3L, 3_400L));
    }

    @Test public void pendingLootChestCannotTurnBankWithdrawalIntoProfit()
    {
        GpManagerEngine engine = engine();
        engine.setLootKeyChestVisible(true);
        engine.markContext(TrackingContext.TRANSFER, 6, "Bank transfer");
        ProfitTransaction tx = settle(engine, 995, 10_000L, 1_600L);
        assertFalse(tx.isCounted());
        assertEquals(0L, engine.getMetrics(2_000L).getNet());
    }

    @Test public void collectWithoutCoinsCannotConsumeSaleOrInventTax()
    {
        List<GeSellTaxBooking.PendingSale> pending = new ArrayList<>();
        pending.add(new GeSellTaxBooking.PendingSale(4151, 1, 1_000, false));
        GeSellTaxBooking.Result result = GeSellTaxBooking.applyOnCollect(
            Collections.singletonList(new ItemFlow(4151, "Returned item", 1L, 1_000, 1_000)),
            "Collect", true, pending);
        assertEquals(0L, result.taxBooked);
        assertEquals(1, pending.size());
        assertEquals(1, result.flows.size());
    }

    @Test public void separateLootChestReceiptsEachCountWithoutReplayingSnapshots()
    {
        GpManagerEngine engine = engine();
        engine.markLootContext(Collections.singletonMap(995, 1_000L), 50,
            "Loot from Wilderness loot chest", "Wilderness loot chest");
        assertTrue(settle(engine, 995, 500L, 1_600L).isCounted());
        assertTrue(settle(engine, 995, 1_000L, 2_200L).isCounted());
        assertEquals(1_000L, engine.getMetrics(2_800L).getNet());
        assertNull(settle(engine, 995, 1_000L, 3_400L));
    }

    @Test public void pendingChestDoesNotRelabelUnrelatedHarvestAsPkLoot()
    {
        GpManagerEngine engine = engine();
        engine.setLootKeyChestVisible(true);
        assertEquals(TransactionType.GAIN, settle(engine, 1942, 1L, 1_600L).getType());
    }

    @Test public void mixedPlayerLootDefersKeyItemButCountsOtherSettledLoot()
    {
        GpManagerEngine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        Map<Integer, Long> expected = new HashMap<>();
        expected.put(526, 1L);
        expected.put(keyId, 1L);
        engine.markPkLootContext(expected, 50, "Kill: Rival", 1_100L);
        engine.markInventoryDirty();
        ContainerSnapshot gained = new ContainerSnapshot(expected);
        engine.processIfDirty(gained, 1_600L);
        ProfitTransaction loot = engine.processIfDirty(gained, 1_601L);

        assertNotNull(loot);
        assertEquals(TransactionType.PK_LOOT, loot.getType());
        assertEquals(Collections.singletonMap(526, 1L), quantities(loot.getFlows()));
        assertEquals(100L, engine.getMetrics(2_000L).getNet());
        assertEquals("separate, retained, and never-counted key audit plus real loot",
            2, engine.getActiveSession().getTransactions().size());

        ProfitTransaction keyAudit = engine.getActiveSession().getTransactions().get(0);
        assertEquals(TransactionType.TRANSFER, keyAudit.getType());
        assertFalse(keyAudit.isCounted());
        assertEquals("Rival", keyAudit.getLootKeyProvenance().get(0).getVictimName());
    }

    @Test public void keyManifestClaimReceiptIsAttachedToRetainedOrdinarySettlement()
    {
        GpManagerEngine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        ProfitTransaction keyAudit = createPendingKeyManifest(engine, 1_100L);

        ProfitTransaction claim = settleSnapshot(engine, heldKeyAndManifestItem(keyId), 2_600L);

        assertNotNull(claim);
        assertEquals(TransactionType.GAIN, claim.getType());
        assertTrue(claim.isCounted());
        assertEquals(1, claim.getLootKeyProvenance().size());
        assertTrue(claim.getLootKeyProvenance().get(0).isClaimReceipt());
        assertTrue(claim.getLootKeyProvenance().get(0).ledgerSummary().contains("claimed"));
        assertEquals(LootKeyProvenance.Status.CLAIMED,
            keyAudit.getLootKeyProvenance().get(0).getStatus());
    }

    @Test public void transferClassificationCannotCommitKeyManifestClaimMetadata()
    {
        GpManagerEngine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        ProfitTransaction keyAudit = createPendingKeyManifest(engine, 1_100L);
        engine.markBankInterfaceOpen(6);

        ProfitTransaction transfer = settleSnapshot(engine, heldKeyAndManifestItem(keyId), 2_600L);

        assertNotNull(transfer);
        assertEquals(TransactionType.TRANSFER, transfer.getType());
        assertFalse(transfer.isCounted());
        assertTrue(transfer.getLootKeyProvenance().isEmpty());
        LootKeyProvenance pending = keyAudit.getLootKeyProvenance().get(0);
        assertEquals(LootKeyProvenance.Status.PENDING, pending.getStatus());
        assertEquals(Collections.singletonMap(526, 1L), pending.getRemainingManifestQuantities());
        assertTrue(pending.getClaimedQuantities().isEmpty());
    }

    @Test public void queuedLootWithSameItemIdIsNotAttributedToKeyManifest()
    {
        GpManagerEngine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        ProfitTransaction keyAudit = createPendingKeyManifest(engine, 1_100L);
        engine.markLootContext(Collections.singletonMap(526, 1L), 50,
            "Loot from Goblin", "Goblin");

        ProfitTransaction loot = settleSnapshot(engine, heldKeyAndManifestItem(keyId), 2_600L);

        assertNotNull(loot);
        assertEquals(TrackingContext.LOOT, loot.getContext());
        assertTrue(loot.isCounted());
        assertEquals(Collections.singletonMap(526, 1L), quantities(loot.getFlows()));
        assertTrue(loot.getLootKeyProvenance().isEmpty());
        LootKeyProvenance pending = keyAudit.getLootKeyProvenance().get(0);
        assertEquals(LootKeyProvenance.Status.PENDING, pending.getStatus());
        assertEquals(Collections.singletonMap(526, 1L), pending.getRemainingManifestQuantities());
        assertTrue(pending.getClaimedQuantities().isEmpty());
    }

    @Test public void oneSourceMatchedItemIsRemovedBeforeResidualKeyClaimMatching()
    {
        GpManagerEngine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        ProfitTransaction keyAudit = createPendingKeyManifest(engine, 1_100L);
        engine.markLootContext(Collections.singletonMap(526, 1L), 50,
            "Loot from Goblin", "Goblin");

        ProfitTransaction mixedSource = settleSnapshot(engine, heldKeyAndManifestItems(keyId, 2L), 2_600L);

        assertNotNull(mixedSource);
        assertTrue(mixedSource.isCounted());
        assertEquals(Collections.singletonMap(526, 2L), quantities(mixedSource.getFlows()));
        assertEquals(1, mixedSource.getLootKeyProvenance().size());
        assertEquals(Collections.singletonMap(526, 1L),
            mixedSource.getLootKeyProvenance().get(0).getClaimedQuantities());
        LootKeyProvenance claimed = keyAudit.getLootKeyProvenance().get(0);
        assertEquals(Collections.singletonMap(526, 1L), claimed.getClaimedQuantities());
        assertTrue(claimed.getRemainingManifestQuantities().isEmpty());
    }

    @Test public void keyOnlyBankWithdrawalDoesNotCreatePendingLootKeyProvenance()
    {
        GpManagerEngine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        engine.markContext(TrackingContext.TRANSFER, 6, "Bank transfer");

        ProfitTransaction withdrawal = settleSnapshot(engine, Collections.singletonMap(keyId, 1L), 1_600L);

        assertNull("a key token itself is not valued or given kill provenance on a transfer", withdrawal);
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(0L, engine.getMetrics(2_000L).getNet());
    }

    @Test public void deferredKeyTokenLossNeverBooksManifestOrTokenCost()
    {
        GpManagerEngine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        engine.setBaseline(new ContainerSnapshot(Collections.singletonMap(keyId, 1L)));

        ProfitTransaction loss = settleSnapshot(engine, Collections.<Integer, Long>emptyMap(), 1_600L);

        assertNull("the deferred token loss must not become a counted cost", loss);
        assertEquals(0L, engine.getMetrics(2_000L).getCosts());
    }

    @Test public void pendingLocalDeathSnapshotClosesKeyLossAcrossSeparateSettles()
    {
        GpManagerEngine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        ProfitTransaction keyAudit = createPendingKeyManifest(engine, 1_100L);
        engine.beginLootKeyLocalDeathSettle(Collections.singletonMap(keyId, 1L));

        settleSnapshot(engine, heldKeyAndCoins(keyId, 10L), 2_600L);
        ProfitTransaction keyLoss = settleSnapshot(engine, Collections.singletonMap(995, 10L), 3_200L);

        assertNull("key-token loss itself remains outside GP accounting", keyLoss);
        LootKeyProvenance lost = keyAudit.getLootKeyProvenance().get(0);
        assertEquals(LootKeyProvenance.Status.LOST_ON_DEATH, lost.getStatus());
        assertTrue(lost.ledgerSummary().contains("Key lost on death"));
        assertEquals(0L, engine.getMetrics(4_000L).getCosts());
        assertEquals(100L, lost.getManifestValueGp());
    }

    @Test public void quotedReclaimFeeDoesNotBookUnpaidCost() throws Exception
    {
        GpManagerEngine engine = engine();
        plugin(engine).onChatMessage(chat("Reclaiming your items costs 10,000 coins."));
        assertEquals(0L, engine.getMetrics(2_000L).getCosts());
    }

    @Test public void reclaimFeeChatAndCoinLossCountOnce() throws Exception
    {
        GpManagerEngine engine = engine();
        engine.setBaseline(new ContainerSnapshot(Collections.singletonMap(995, 20_000L)));
        GpManagerPlugin plugin = plugin(engine);
        plugin.onChatMessage(chat("You pay a fee of 10,000 coins to reclaim your items."));
        plugin.onChatMessage(chat("You pay a fee of 10,000 coins to reclaim your items."));
        settle(engine, 995, 10_000L, 1_600L);
        assertEquals(10_000L, engine.getMetrics(2_000L).getCosts());
        assertEquals(1, engine.getActiveSession().getTransactions().size());
    }

    private static Map<Integer, Long> quantities(List<ItemFlow> flows)
    {
        Map<Integer, Long> result = new HashMap<>();
        for (ItemFlow flow : flows)
        {
            result.merge(flow.getItemId(), flow.getQuantityDelta(), Long::sum);
        }
        return result;
    }

    private GpManagerPlugin plugin(GpManagerEngine engine) throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPlugin();
        set(plugin, "config", config);
        set(plugin, "engine", engine);
        set(plugin, "ingestion", new GameplayIngestionFacade(() -> true, () -> true));
        return plugin;
    }

    private static ChatMessage chat(String text)
    {
        ChatMessage message = new ChatMessage();
        message.setType(ChatMessageType.GAMEMESSAGE);
        message.setMessage(text);
        return message;
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
