package com.gpmanager;

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
    };

    private Engine engine()
    {
        Engine engine = new Engine(deltas -> {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new Flow(id, "Item " + id, qty,
                id == 995 ? 1 : 100, qty * (id == 995 ? 1 : 100))));
            return flows;
        }, new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        return engine;
    }

    private Engine orderedKeyEngine(boolean reverse)
    {
        Engine engine = new Engine(deltas -> {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new Flow(id, "Item " + id, qty, 100,
                qty * 100)));
            flows.sort(Comparator.comparingInt((itemData -> itemData.itemId)));
            if (reverse)
            {
                Collections.reverse(flows);
            }
            return flows;
        }, new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        return engine;
    }

    private Transaction settle(Engine engine, int id, long qty, long now)
    {
        engine.markInventoryDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(Collections.singletonMap(id, qty));
        Transaction result = engine.processIfDirty(snapshot, now);
        return result != null ? result : engine.processIfDirty(snapshot, now + 1L);
    }

    private Transaction settleSnapshot(Engine engine, Map<Integer, Long> quantities, long now)
    {
        engine.markInventoryDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(quantities);
        Transaction result = engine.processIfDirty(snapshot, now);
        return result != null ? result : engine.processIfDirty(snapshot, now + 1L);
    }

    @Test
    public void npcAndPlayerLootInOneSnapshotKeepTheirSeparateOwners()
    {
        for (boolean pkFirst : new boolean[] {false, true})
        {
            Engine engine = engine();
            if (pkFirst) engine.markPkLootContext(Collections.singletonMap(4151, 1L), 50, "Player kill", 1_100L);
            engine.markLootContext(Collections.singletonMap(526, 1L), 50, "Loot from Goblin", "Goblin");
            if (!pkFirst) engine.markPkLootContext(Collections.singletonMap(4151, 1L), 50, "Player kill", 1_100L);
            assertNotNull(settleSnapshot(engine, Map.of(526, 1L, 4151, 1L), 1_600L));
            assertEquals("both measured pickups count once", 200L, engine.getMetrics(2_000L).revenue);
            assertEquals("only the player-owned pickup contributes to PK", 100L, engine.getPkMetrics().revenue);
            List<Transaction> rows = engine.getActiveSession().getTransactions();
            assertEquals(2, rows.size());
            Transaction npc = rows.stream().filter(t -> t.getContext() == Context.LOOT).findFirst().get();
            Transaction pk = rows.stream().filter(t -> t.getContext() == Context.PK_LOOT).findFirst().get();
            assertEquals(Map.of(526, 1L), quantities(npc.getFlows()));
            assertEquals(Map.of(4151, 1L), quantities(pk.getFlows()));
            assertEquals("Goblin", npc.getActivityName());
            assertNotNull(pk.getEncounterId());
            assertNull("the same measured snapshot cannot book again",
                settleSnapshot(engine, Map.of(526, 1L, 4151, 1L), 2_200L));
        }
    }

    @Test
    public void sharedItemQuantitiesAndExtraGainsAreSplitWithoutDoubleCounting()
    {
        Engine engine = engine();
        engine.markLootContext(Map.of(526, 2L), 50, "Loot from Goblin", "Goblin");
        engine.markPkLootContext(Map.of(526, 3L), 50, "Player kill", 1_100L);
        List<Transaction> published = new ArrayList<>();
        engine.markInventoryDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(Map.of(526, 8L));
        engine.processIfDirty(snapshot, 1_600L, published::add);
        Transaction remaining = engine.processIfDirty(snapshot, 1_601L, published::add);
        assertNotNull(remaining);
        assertEquals(Context.GENERIC, remaining.getContext());
        assertEquals(Map.of(526, 3L), quantities(remaining.getFlows()));
        assertEquals("both source receipts reach presentation", 2, published.size());
        assertEquals(Map.of(526, 2L), quantities(published.get(0).getFlows()));
        assertEquals(Map.of(526, 3L), quantities(published.get(1).getFlows()));
        assertEquals(800L, engine.getMetrics(2_000L).revenue);
        assertEquals(300L, engine.getPkMetrics().revenue);
        assertEquals(3, engine.getActiveSession().getTransactions().size());
    }

    @Test
    public void twoPlayerPickupsKeepTheirOwnEncountersAndPublishAfterBooking()
    {
        Engine engine = engine();
        engine.markPkLootContext(Map.of(4151, 1L), 50, "Player kill", 1_100L);
        engine.markPkLootContext(Map.of(4151, 1L), 50, "Player kill", 1_200L);
        List<Transaction> published = new ArrayList<>();
        java.util.function.Consumer<Transaction> listener = row ->
        {
            assertEquals("canonical booking is complete before publishing", 200L,
                engine.getMetrics(2_000L).revenue);
            published.add(row);
        };
        engine.markInventoryDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(Map.of(4151, 2L));
        engine.processIfDirty(snapshot, 1_600L, listener);
        Transaction last = engine.processIfDirty(snapshot, 1_601L, listener);
        assertNotNull(last);
        assertEquals(1, published.size());
        assertNotEquals(last.getEncounterId(), published.get(0).getEncounterId());
        assertEquals(100L, last.getAutomaticNet());
        assertEquals(100L, published.get(0).getAutomaticNet());
        assertEquals(200L, engine.getPkMetrics().revenue);
        assertEquals(2, engine.getPkMetrics().kills);
    }

    private Transaction createPendingKeyManifest(Engine engine, long now)
    {
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        engine.markPkLootContext(Collections.singletonMap(keyId, 1L), 50, "Kill: Rival", now);
        assertNotNull(settleSnapshot(engine, Collections.singletonMap(keyId, 1L), now + 500L));

        engine.setLootKeyChestVisible(true);
        engine.observeLootKeyContainer(keyId, Collections.singletonMap(526, 1L));
        engine.observeLootKeyContainer(keyId, Collections.<Integer, Long>emptyMap());
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
        Engine engine = engine();
        engine.markPkLootContext(Collections.singletonMap(526, 3L), 50, "Player kill", 1_100L);
        assertTrue(settle(engine, 526, 1L, 1_600L).isCounted());
        assertTrue("Second actual pickup must count", settle(engine, 526, 2L, 2_200L).isCounted());
        assertTrue(settle(engine, 526, 3L, 2_800L).isCounted());
        assertEquals(300L, engine.getMetrics(3_000L).net);
        assertEquals(1, engine.getPkMetrics().kills);
        assertEquals(300L, engine.getPkMetrics().revenue);
        assertNull("Duplicate snapshot is not another pickup", settle(engine, 526, 3L, 3_400L));
    }

    @Test public void pendingLootChestCannotTurnBankWithdrawalIntoProfit()
    {
        Engine engine = engine();
        engine.setLootKeyChestVisible(true);
        engine.markContext(Context.TRANSFER, 6, "Bank transfer");
        Transaction tx = settle(engine, 995, 10_000L, 1_600L);
        assertFalse(tx.isCounted());
        assertEquals(0L, engine.getMetrics(2_000L).net);
    }

    @Test public void collectWithoutCoinsCannotConsumeSaleOrInventTax()
    {
        Engine engine = engine();
        // A cancelled offer returns items through the same Collect menu: no coin receipt means
        // no sale confirmation and no tax cost, and no synthetic tax path exists any more.
        engine.markContext(Context.MARKET, 10, "Collect");
        Transaction returned = settle(engine, 4151, 1L, 1_600L);
        assertNotNull(returned);
        assertEquals(TransactionType.TRADE, returned.getType());
        for (Flow flow : returned.getFlows())
        {
            assertTrue(flow.itemId != CostKind.GE_TAX_ITEM_ID);
        }
        assertEquals(0L, engine.getMetrics(2_000L).costs);
    }

    @Test public void separateLootChestReceiptsEachCountWithoutReplayingSnapshots()
    {
        Engine engine = engine();
        engine.markLootContext(Collections.singletonMap(995, 1_000L), 50,
            "Loot from Wilderness loot chest", "Wilderness loot chest");
        assertTrue(settle(engine, 995, 500L, 1_600L).isCounted());
        assertTrue(settle(engine, 995, 1_000L, 2_200L).isCounted());
        assertEquals(1_000L, engine.getMetrics(2_800L).net);
        assertNull(settle(engine, 995, 1_000L, 3_400L));
    }

    @Test public void pendingChestDoesNotRelabelUnrelatedHarvestAsPkLoot()
    {
        Engine engine = engine();
        engine.setLootKeyChestVisible(true);
        assertEquals(TransactionType.GAIN, settle(engine, 1942, 1L, 1_600L).getType());
    }

    @Test public void mixedPlayerLootDefersKeyItemButCountsOtherSettledLoot()
    {
        Engine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        Map<Integer, Long> expected = new HashMap<>();
        expected.put(526, 1L);
        expected.put(keyId, 1L);
        engine.markPkLootContext(expected, 50, "Kill: Rival", 1_100L);
        engine.markInventoryDirty();
        ContainerSnapshot gained = new ContainerSnapshot(expected);
        engine.processIfDirty(gained, 1_600L);
        Transaction loot = engine.processIfDirty(gained, 1_601L);

        assertNotNull(loot);
        assertEquals(TransactionType.PK_LOOT, loot.getType());
        assertEquals(Collections.singletonMap(526, 1L), quantities(loot.getFlows()));
        assertEquals(100L, engine.getMetrics(2_000L).net);
        assertEquals("separate, retained, and never-counted key audit plus real loot",
            2, engine.getActiveSession().getTransactions().size());

        Transaction keyAudit = engine.getActiveSession().getTransactions().get(0);
        assertEquals(TransactionType.TRANSFER, keyAudit.getType());
        assertFalse(keyAudit.isCounted());
        SavedState.PendingClaim claim = engine.getPendingClaims().get(0);
        assertEquals("the audit row id is the durable claim id", keyAudit.getId(), claim.getClaimId());
        assertFalse(new com.google.gson.Gson().toJson(keyAudit).contains("Rival"));
    }

    @Test public void keyManifestClaimReceiptIsAttachedToRetainedOrdinarySettlement()
    {
        Engine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        Transaction keyAudit = createPendingKeyManifest(engine, 1_100L);

        Transaction claim = settleSnapshot(engine, heldKeyAndManifestItem(keyId), 2_600L);

        assertNotNull(claim);
        assertEquals(TransactionType.GAIN, claim.getType());
        assertTrue(claim.isCounted());
        assertEquals("the settlement carries the claim id", keyAudit.getId(), claim.getSourceClaimId());
        assertTrue("the consumed key closes the claim in the same revision", engine.getPendingClaims().isEmpty());

        // Replay through the real persistence shape: a stale duplicate of the settled claim is dropped.
        SavedState saved = engine.createSavedState();
        saved.setPendingClaims(Collections.singletonList(new SavedState.PendingClaim(
            keyAudit.getId(), keyId, 1L,
            1_100L)));
        Engine reloaded = engine();
        reloaded.restore(saved, 3_000L);
        assertTrue(reloaded.getPendingClaims().isEmpty());
        assertEquals(engine.getMetrics(3_000L).net, reloaded.getMetrics(3_000L).net);
        assertEquals(engine.getActiveSession().getTransactions().size(),
            reloaded.getActiveSession().getTransactions().size());
    }

    @Test public void partialKeyManifestReceiptKeepsTheClaimAcrossRestart()
    {
        Engine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        engine.markPkLootContext(Collections.singletonMap(keyId, 1L), 50, "Kill: Rival", 1_100L);
        Transaction keyAudit = settleSnapshot(engine, Collections.singletonMap(keyId, 1L), 1_600L);
        engine.setLootKeyChestVisible(true);
        engine.observeLootKeyContainer(keyId, heldNowWithManifest(keyId, 2L, 1L));
        engine.observeLootKeyContainer(keyId, Collections.singletonMap(keyId, 1L));

        Transaction partial = settleSnapshot(engine, heldKeyAndManifestItem(keyId), 2_600L);

        assertNotNull(partial);
        assertTrue(partial.isCounted());
        assertTrue("a partly received manifest relates the claim and never proves closure",
            partial.getSourceClaimId().isEmpty());
        assertEquals(1, engine.getPendingClaims().size());
        assertEquals(1L, engine.getPendingClaims().get(0).getQuantity());
        assertEquals(keyAudit.getId(), engine.getPendingClaims().get(0).getClaimId());
        assertEquals(100L, engine.getMetrics(3_200L).net);

        Engine reloaded = engine();
        reloaded.restore(engine.createSavedState(), 3_400L);
        assertEquals("the partly received claim survives restart untouched",
            1, reloaded.getPendingClaims().size());
        assertEquals(keyAudit.getId(), reloaded.getPendingClaims().get(0).getClaimId());
        assertEquals(1L, reloaded.getPendingClaims().get(0).getQuantity());
        assertEquals(100L, reloaded.getMetrics(4_000L).net);

        // The container-removal evidence is transient by design; the recovered owner rebuilds
        // it and then finishes the already-partly-received manifest.
        reloaded.togglePause(4_100L);
        reloaded.setBaseline(new ContainerSnapshot(heldKeyAndManifestItem(keyId)));
        reloaded.setLootKeyChestVisible(true);
        reloaded.observeLootKeyContainer(keyId, heldNowWithManifest(keyId, 1L, 1L));
        reloaded.observeLootKeyContainer(keyId, Collections.singletonMap(keyId, 1L));
        Map<Integer, Long> fullReceipt = new HashMap<>();
        fullReceipt.put(keyId, 1L);
        fullReceipt.put(526, 2L);
        fullReceipt.put(554, 1L);
        Transaction closed = settleSnapshot(reloaded, fullReceipt, 4_600L);

        assertNotNull(closed);
        assertEquals("the fully received manifest closes exactly once",
            keyAudit.getId(), closed.getSourceClaimId());
        assertTrue(reloaded.getPendingClaims().isEmpty());
        assertEquals(300L, reloaded.getMetrics(5_200L).net);

        Engine afterClose = engine();
        afterClose.restore(reloaded.createSavedState(), 5_800L);
        assertTrue("the settled claim never comes back", afterClose.getPendingClaims().isEmpty());
        assertEquals(300L, afterClose.getMetrics(6_400L).net);
        assertEquals(reloaded.getActiveSession().getTransactions().size(),
            afterClose.getActiveSession().getTransactions().size());
    }

    @Test public void stackedLootKeyClaimSettlesOneKeyPerManifestAcrossRestart()
    {
        Engine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        Map<Integer, Long> threeKeys = Collections.singletonMap(keyId, 3L);
        engine.markPkLootContext(threeKeys, 50, "Kill: Rival", 1_100L);
        Transaction audit = settleSnapshot(engine, threeKeys, 1_600L);
        assertNotNull(audit);
        assertEquals(3L, engine.getPendingClaims().get(0).getQuantity());

        engine.setLootKeyChestVisible(true);
        engine.observeLootKeyContainer(keyId, Collections.singletonMap(526, 1L));
        engine.observeLootKeyContainer(keyId, Collections.<Integer, Long>emptyMap());
        Map<Integer, Long> firstReceipt = new HashMap<>();
        firstReceipt.put(keyId, 2L);
        firstReceipt.put(526, 1L);
        Transaction first = settleSnapshot(engine, firstReceipt, 2_600L);
        assertNotNull(first);
        assertEquals(2L, engine.getPendingClaims().get(0).getQuantity());
        assertEquals(100L, engine.getMetrics(3_200L).net);

        Engine reloaded = engine();
        reloaded.restore(engine.createSavedState(), 3_400L);
        assertEquals(2L, reloaded.getPendingClaims().get(0).getQuantity());
        reloaded.togglePause(3_500L);
        Map<Integer, Long> heldOne = new HashMap<>();
        heldOne.put(keyId, 2L);
        heldOne.put(526, 1L);
        reloaded.setBaseline(new ContainerSnapshot(heldOne));

        reloaded.setLootKeyChestVisible(true);
        reloaded.observeLootKeyContainer(keyId, Collections.singletonMap(526, 1L));
        reloaded.observeLootKeyContainer(keyId, Collections.<Integer, Long>emptyMap());
        Map<Integer, Long> secondReceipt = new HashMap<>();
        secondReceipt.put(keyId, 1L);
        secondReceipt.put(526, 2L);
        Transaction second = settleSnapshot(reloaded, secondReceipt, 4_600L);
        assertNotNull(second);
        assertEquals(1L, reloaded.getPendingClaims().get(0).getQuantity());

        reloaded.setLootKeyChestVisible(true);
        reloaded.observeLootKeyContainer(keyId, Collections.singletonMap(526, 1L));
        reloaded.observeLootKeyContainer(keyId, Collections.<Integer, Long>emptyMap());
        Map<Integer, Long> finalReceipt = new HashMap<>();
        finalReceipt.put(keyId, 0L);
        finalReceipt.put(526, 3L);
        Transaction third = settleSnapshot(reloaded, finalReceipt, 5_600L);
        assertNotNull(third);
        assertEquals(audit.getId(), third.getSourceClaimId());
        assertTrue(reloaded.getPendingClaims().isEmpty());
        assertEquals(300L, reloaded.getMetrics(6_200L).net);
    }

    @Test public void oneAuditRowWithTwoKeyTypesKeepsIndependentClaims()
    {
        Engine engine = engine();
        int firstKey = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        int secondKey = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY1;
        Map<Integer, Long> expected = new LinkedHashMap<>();
        expected.put(firstKey, 1L);
        expected.put(secondKey, 1L);
        engine.markPkLootContext(expected, 50, "Kill: Rival", 1_100L);

        Transaction audit = settleSnapshot(engine, expected, 1_600L);

        assertNotNull(audit);
        assertEquals(TransactionType.TRANSFER, audit.getType());
        assertEquals(2, engine.getPendingClaims().size());
        Map<Integer, String> claimIdsByKey = new HashMap<>();
        for (SavedState.PendingClaim claim : engine.getPendingClaims())
        {
            assertEquals(1L, claim.getQuantity());
            assertNull("each key type owns a distinct claim id",
                claimIdsByKey.put(claim.itemOrKeyId, claim.getClaimId()));
        }
        assertEquals(audit.getId() + "#1", claimIdsByKey.get(firstKey));
        assertEquals(audit.getId() + "#2", claimIdsByKey.get(secondKey));

        engine.beginLootKeyLocalDeathSettle(expected);
        assertNull("the lost key token is never booked",
            settleSnapshot(engine, Collections.singletonMap(secondKey, 1L), 2_200L));

        assertEquals("only the lost key's claim closes", 1, engine.getPendingClaims().size());
        SavedState.PendingClaim survivor = engine.getPendingClaims().get(0);
        assertEquals(secondKey, survivor.itemOrKeyId);
        assertEquals(audit.getId() + "#2", survivor.getClaimId());
        assertEquals(1L, survivor.getQuantity());
        assertEquals(0L, engine.getMetrics(2_800L).net);
    }

    @Test public void multiKeyClaimIdentityDoesNotDependOnFlowOrder()
    {
        int firstKey = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        int secondKey = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY1;
        Map<Integer, Long> expected = new LinkedHashMap<>();
        expected.put(firstKey, 1L);
        expected.put(secondKey, 1L);

        Engine forward = orderedKeyEngine(false);
        forward.markPkLootContext(expected, 50, "Kill: Rival", 1_100L);
        settleSnapshot(forward, expected, 1_600L);

        Engine reverse = orderedKeyEngine(true);
        reverse.markPkLootContext(expected, 50, "Kill: Rival", 1_100L);
        settleSnapshot(reverse, expected, 1_600L);

        Map<Integer, String> forwardIds = claimSuffixes(forward);
        Map<Integer, String> reverseIds = claimSuffixes(reverse);
        assertEquals("the same key keeps the same deterministic suffix", forwardIds, reverseIds);
        assertEquals("#1", forwardIds.get(firstKey));
        assertEquals("#2", forwardIds.get(secondKey));
    }

    private static Map<Integer, String> claimSuffixes(Engine engine)
    {
        Map<Integer, String> ids = new HashMap<>();
        for (SavedState.PendingClaim claim : engine.getPendingClaims())
        {
            String id = claim.getClaimId();
            ids.put(claim.itemOrKeyId, id.substring(id.indexOf('#')));
        }
        return ids;
    }

    private static Map<Integer, Long> heldNowWithManifest(int keyId, long firstItem, long secondItem)
    {
        Map<Integer, Long> quantities = new HashMap<>();
        quantities.put(keyId, 1L);
        quantities.put(526, firstItem);
        quantities.put(554, secondItem);
        return quantities;
    }

    @Test public void transferClassificationCannotCommitKeyManifestClaimMetadata()
    {
        Engine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        Transaction keyAudit = createPendingKeyManifest(engine, 1_100L);
        engine.markBankInterfaceOpen(6);

        Transaction transfer = settleSnapshot(engine, heldKeyAndManifestItem(keyId), 2_600L);

        assertNotNull(transfer);
        assertEquals(TransactionType.TRANSFER, transfer.getType());
        assertFalse(transfer.isCounted());
        assertTrue(transfer.getSourceClaimId().isEmpty());
        assertEquals(1, engine.getPendingClaims().size());
        assertEquals(keyAudit.getId(), engine.getPendingClaims().get(0).getClaimId());
        assertEquals(1L, engine.getPendingClaims().get(0).getQuantity());
    }

    @Test public void queuedLootWithSameItemIdIsNotAttributedToKeyManifest()
    {
        Engine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        Transaction keyAudit = createPendingKeyManifest(engine, 1_100L);
        engine.markLootContext(Collections.singletonMap(526, 1L), 50,
            "Loot from Goblin", "Goblin");

        Transaction loot = settleSnapshot(engine, heldKeyAndManifestItem(keyId), 2_600L);

        assertNotNull(loot);
        assertEquals(Context.LOOT, loot.getContext());
        assertTrue(loot.isCounted());
        assertEquals(Collections.singletonMap(526, 1L), quantities(loot.getFlows()));
        assertTrue(loot.getSourceClaimId().isEmpty());
        assertEquals(1, engine.getPendingClaims().size());
        assertEquals(keyAudit.getId(), engine.getPendingClaims().get(0).getClaimId());
        assertEquals(1L, engine.getPendingClaims().get(0).getQuantity());
    }

    @Test public void oneSourceMatchedItemIsRemovedBeforeResidualKeyClaimMatching()
    {
        Engine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        Transaction keyAudit = createPendingKeyManifest(engine, 1_100L);
        engine.markLootContext(Collections.singletonMap(526, 1L), 50,
            "Loot from Goblin", "Goblin");

        Transaction mixedSource = settleSnapshot(engine, heldKeyAndManifestItems(keyId, 2L), 2_600L);

        assertNotNull(mixedSource);
        assertTrue(mixedSource.isCounted());
        assertEquals(Collections.singletonMap(526, 2L), quantities(mixedSource.getFlows()));
        assertEquals("only the residual unit settled the key claim", keyAudit.getId(), mixedSource.getSourceClaimId());
        assertTrue(engine.getPendingClaims().isEmpty());
    }

    @Test public void keyOnlyBankWithdrawalDoesNotOpenAPendingClaim()
    {
        Engine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        engine.markContext(Context.TRANSFER, 6, "Bank transfer");

        Transaction withdrawal = settleSnapshot(engine, Collections.singletonMap(keyId, 1L), 1_600L);

        assertNull("a key token itself is not valued or given kill provenance on a transfer", withdrawal);
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(0L, engine.getMetrics(2_000L).net);
    }

    @Test public void deferredKeyTokenLossNeverBooksManifestOrTokenCost()
    {
        Engine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        engine.setBaseline(new ContainerSnapshot(Collections.singletonMap(keyId, 1L)));

        Transaction loss = settleSnapshot(engine, Collections.<Integer, Long>emptyMap(), 1_600L);

        assertNull("the deferred token loss must not become a counted cost", loss);
        assertEquals(0L, engine.getMetrics(2_000L).costs);
    }

    @Test public void pendingLocalDeathSnapshotClosesKeyLossAcrossSeparateSettles()
    {
        Engine engine = engine();
        int keyId = net.runelite.api.gameval.ItemID.WILDY_LOOT_KEY0;
        Transaction keyAudit = createPendingKeyManifest(engine, 1_100L);
        engine.beginLootKeyLocalDeathSettle(Collections.singletonMap(keyId, 1L));

        settleSnapshot(engine, heldKeyAndCoins(keyId, 10L), 2_600L);
        Transaction keyLoss = settleSnapshot(engine, Collections.singletonMap(995, 10L), 3_200L);

        assertNull("key-token loss itself remains outside GP accounting", keyLoss);
        assertTrue("the lost key closes its claim", engine.getPendingClaims().isEmpty());
        assertEquals(0L, engine.getMetrics(4_000L).costs);
        assertFalse(keyAudit.isCounted());
    }

    @Test public void quotedReclaimFeeDoesNotBookUnpaidCost() throws Exception
    {
        Engine engine = engine();
        plugin(engine).onChatMessage(chat("Reclaiming your items costs 10,000 coins."));
        assertEquals(0L, engine.getMetrics(2_000L).costs);
    }

    @Test public void reclaimFeeChatAndCoinLossCountOnce() throws Exception
    {
        Engine engine = engine();
        engine.setBaseline(new ContainerSnapshot(Collections.singletonMap(995, 20_000L)));
        GpManagerPlugin plugin = plugin(engine);
        plugin.onChatMessage(chat("You pay a fee of 10,000 coins to reclaim your items."));
        plugin.onChatMessage(chat("You pay a fee of 10,000 coins to reclaim your items."));
        settle(engine, 995, 10_000L, 1_600L);
        assertEquals(10_000L, engine.getMetrics(2_000L).costs);
        assertEquals(1, engine.getActiveSession().getTransactions().size());
    }

    private static Map<Integer, Long> quantities(List<Flow> flows)
    {
        Map<Integer, Long> result = new HashMap<>();
        for (Flow flow : flows)
        {
            result.merge(flow.itemId, flow.quantityDelta, Long::sum);
        }
        return result;
    }

    private GpManagerPlugin plugin(Engine engine) throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        set(plugin, "config", config);
        set(plugin, "engine", engine);
        set(plugin, "charges", new ChargeIntake(null, null, config, engine, null));
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
        Field field = GpManagerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
