package com.gpmanager.engine;

import com.google.gson.Gson;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.LootKeyProvenance;
import com.gpmanager.model.PkEncounter;
import com.gpmanager.model.PkEncounterType;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LootKeyLifecycleTest
{
    private static final int MANIFEST_ITEM = 995;
    private static final int SECOND_MANIFEST_ITEM = 554;
    private static final int CRATE_ITEM = 20_001;

    @Test
    public void allFiveKeyVariantsMapToTheirOwnManifestContainers()
    {
        int[] keyIds = {
            ItemID.WILDY_LOOT_KEY0,
            ItemID.WILDY_LOOT_KEY1,
            ItemID.WILDY_LOOT_KEY2,
            ItemID.WILDY_LOOT_KEY3,
            ItemID.WILDY_LOOT_KEY4
        };
        int[] containerIds = {
            InventoryID.DEADMAN_LOOT_INV0,
            InventoryID.DEADMAN_LOOT_INV1,
            InventoryID.DEADMAN_LOOT_INV2,
            InventoryID.DEADMAN_LOOT_INV3,
            InventoryID.DEADMAN_LOOT_INV4
        };

        for (int index = 0; index < keyIds.length; index++)
        {
            assertEquals(index, LootKeyLifecycle.keyIndexForItemId(keyIds[index]));
            assertEquals(index, LootKeyLifecycle.keyIndexForContainerId(containerIds[index]));
            assertEquals(keyIds[index], LootKeyLifecycle.keyItemIdForIndex(index));
            assertEquals(containerIds[index], LootKeyLifecycle.keyContainerIdForIndex(index));
            assertTrue(LootKeyLifecycle.isLootKeyItem(keyIds[index]));
            assertTrue(LootKeyLifecycle.shouldDeferKeyValue(keyIds[index]));
        }
        assertEquals(-1, LootKeyLifecycle.keyIndexForItemId(-1));
        assertEquals(-1, LootKeyLifecycle.keyIndexForContainerId(-1));
        assertEquals(-1, LootKeyLifecycle.keyItemIdForIndex(5));
        assertEquals(-1, LootKeyLifecycle.keyContainerIdForIndex(5));
        assertFalse(LootKeyLifecycle.shouldDeferKeyValue(MANIFEST_ITEM));

        Map<Integer, Long> heldKeys = LootKeyLifecycle.keyQuantities(itemContainer(
            new Item(keyIds[0], 1), new Item(keyIds[1], 2), new Item(keyIds[2], 1),
            new Item(keyIds[3], 1), new Item(keyIds[4], 1), new Item(MANIFEST_ITEM, 10)));
        assertEquals(5, heldKeys.size());
        assertEquals(Long.valueOf(2L), heldKeys.get(keyIds[1]));
        assertFalse(heldKeys.containsKey(MANIFEST_ITEM));
    }

    @Test
    public void manifestExtractionReadsAProxiedContainerAndSkipsCrates()
    {
        ItemContainer container = itemContainer(
            new Item(MANIFEST_ITEM, 12),
            new Item(SECOND_MANIFEST_ITEM, 3),
            new Item(MANIFEST_ITEM, 2),
            new Item(CRATE_ITEM, 1));

        Map<Integer, Long> manifest = LootKeyLifecycle.manifestFromContainer(container, id -> id == CRATE_ITEM);

        Map<Integer, Long> expected = new LinkedHashMap<>();
        expected.put(MANIFEST_ITEM, 14L);
        expected.put(SECOND_MANIFEST_ITEM, 3L);
        assertEquals(expected, manifest);
        assertFalse("returned manifests cannot be changed by callers", isMutable(manifest));
    }

    @Test
    public void emptyOrInvalidContainerContentsDoNotBecomeAManifest()
    {
        assertTrue(LootKeyLifecycle.manifestFromContainer(null, id -> false).isEmpty());
        assertTrue(LootKeyLifecycle.manifestFromContainer(itemContainer((Item[]) null), id -> false).isEmpty());
        assertTrue(LootKeyLifecycle.manifestFromContainer(
            itemContainer(new Item(-1, 1), new Item(MANIFEST_ITEM, 0), new Item(CRATE_ITEM, 1)),
            id -> id == CRATE_ITEM).isEmpty());

        ProfitSession session = new ProfitSession("PK", 1_000L);
        ProfitTransaction row = pendingKeyRow(ItemID.WILDY_LOOT_KEY0, 1L, null, session);
        assertFalse(LootKeyLifecycle.captureManifest(session, ItemID.WILDY_LOOT_KEY0,
            Collections.emptyMap(), 0L, false, 2_000L));
        assertFalse(LootKeyLifecycle.captureManifest(session, ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 0L), 0L, false, 2_000L));
        assertFalse(LootKeyLifecycle.captureManifest(session, -1,
            Collections.singletonMap(MANIFEST_ITEM, 1L), 1L, true, 2_000L));
        assertFalse(row.getLootKeyProvenance().get(0).hasManifest());
    }

    @Test
    public void receivedEntryUsesTheMatchingKillForVictimAndTimestamp()
    {
        ProfitSession session = new ProfitSession("PK", 1_000L);
        PkEncounter kill = session.addPkEncounter(PkEncounterType.KILL, 2_000L, "Kill: Rival Name",
            ClassificationConfidence.CONFIRMED, "RuneLite player loot");

        LootKeyProvenance entry = LootKeyLifecycle.receivedEntry(
            ItemID.WILDY_LOOT_KEY2, 1L, kill.getId(), session, 5_000L);

        assertEquals("Rival Name", entry.getVictimName());
        assertEquals(kill.getId(), entry.getEncounterId());
        assertEquals(2_000L, entry.getKilledAtEpochMillis());
        assertEquals(5_000L, entry.getReceivedAtEpochMillis());
        assertEquals(LootKeyProvenance.Status.PENDING, entry.getStatus());
    }

    @Test
    public void capturesManifestOnExactlyOnePendingKeyAuditRow()
    {
        ProfitSession session = new ProfitSession("PK", 1_000L);
        ProfitTransaction row = pendingKeyRow(ItemID.WILDY_LOOT_KEY1, 1L, null, session);
        Map<Integer, Long> manifest = Collections.singletonMap(MANIFEST_ITEM, 5L);

        assertTrue(LootKeyLifecycle.captureManifest(session, ItemID.WILDY_LOOT_KEY1,
            manifest, 4_000L, true, 3_000L));

        LootKeyProvenance attached = row.getLootKeyProvenance().get(0);
        assertEquals(manifest, attached.getManifestQuantities());
        assertEquals(manifest, attached.getRemainingManifestQuantities());
        assertEquals(4_000L, attached.getManifestValueGp());
        assertTrue(attached.isManifestValueComplete());
        assertEquals(3_000L, attached.getManifestCapturedAtEpochMillis());
    }

    @Test
    public void ambiguousPendingKeyRowsDoNotReceiveAGuessedManifest()
    {
        ProfitSession firstSession = new ProfitSession("First", 1_000L);
        ProfitSession secondSession = new ProfitSession("Second", 1_000L);
        ProfitTransaction first = pendingKeyRow(ItemID.WILDY_LOOT_KEY3, 1L, null, firstSession);
        ProfitTransaction second = pendingKeyRow(ItemID.WILDY_LOOT_KEY3, 1L, null, secondSession);

        assertFalse(LootKeyLifecycle.captureManifest(Arrays.asList(firstSession, secondSession),
            ItemID.WILDY_LOOT_KEY3, Collections.singletonMap(MANIFEST_ITEM, 5L), 5_000L, true, 3_000L));

        assertFalse(first.getLootKeyProvenance().get(0).hasManifest());
        assertFalse(second.getLootKeyProvenance().get(0).hasManifest());
    }

    @Test
    public void unrelatedPendingManifestDoesNotBlockUniqueCompatibleClaim()
    {
        ProfitSession session = new ProfitSession("PK", 1_000L);
        ProfitTransaction compatible = pendingKeyRow(ItemID.WILDY_LOOT_KEY0, 1L, null, session);
        ProfitTransaction unrelated = pendingKeyRow(ItemID.WILDY_LOOT_KEY1, 1L, null, session);
        assertTrue(LootKeyLifecycle.captureManifest(session, ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 2L), 2_000L, true, 2_000L));
        assertTrue(LootKeyLifecycle.captureManifest(session, ItemID.WILDY_LOOT_KEY1,
            Collections.singletonMap(SECOND_MANIFEST_ITEM, 9L), 9_000L, true, 2_000L));

        LootKeyLifecycle lifecycle = new LootKeyLifecycle();
        lifecycle.setLootChestVisible(true);
        lifecycle.observeKeyContainerContents(Collections.singletonList(session), ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 2L), Collections.emptyMap(), 2_000L, true, 2_500L);
        lifecycle.observeKeyContainerContents(Collections.singletonList(session), ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 1L), Collections.emptyMap(), 1_000L, true, 2_600L);

        List<LootKeyProvenance> receipts = lifecycle.recordClaim(
            Collections.singletonList(session), Collections.singletonMap(MANIFEST_ITEM, 1L), 3_000L);

        assertEquals("the unrelated manifest is not a second candidate", 1, receipts.size());
        assertEquals(Collections.singletonMap(MANIFEST_ITEM, 1L), receipts.get(0).getClaimedQuantities());
        assertEquals(LootKeyProvenance.Status.PENDING, unrelated.getLootKeyProvenance().get(0).getStatus());
        assertEquals(1L, compatible.getLootKeyProvenance().get(0).getRemainingManifestQuantities()
            .get(MANIFEST_ITEM).longValue());
    }

    @Test
    public void ambiguousCompatibleClaimCandidatesDoNotReceiveAReceipt()
    {
        ProfitSession firstSession = new ProfitSession("First", 1_000L);
        ProfitSession secondSession = new ProfitSession("Second", 1_000L);
        ProfitTransaction first = pendingKeyRow(ItemID.WILDY_LOOT_KEY0, 1L, null, firstSession);
        ProfitTransaction second = pendingKeyRow(ItemID.WILDY_LOOT_KEY1, 1L, null, secondSession);
        assertTrue(LootKeyLifecycle.captureManifest(firstSession, ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 3L), 3_000L, true, 2_000L));
        assertTrue(LootKeyLifecycle.captureManifest(secondSession, ItemID.WILDY_LOOT_KEY1,
            Collections.singletonMap(MANIFEST_ITEM, 7L), 7_000L, true, 2_000L));

        LootKeyLifecycle lifecycle = new LootKeyLifecycle();
        lifecycle.setLootChestVisible(true);
        lifecycle.observeKeyContainerContents(Arrays.asList(firstSession, secondSession), ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 3L), Collections.emptyMap(), 3_000L, true, 2_500L);
        lifecycle.observeKeyContainerContents(Arrays.asList(firstSession, secondSession), ItemID.WILDY_LOOT_KEY1,
            Collections.singletonMap(MANIFEST_ITEM, 7L), Collections.emptyMap(), 7_000L, true, 2_500L);
        lifecycle.observeKeyContainerContents(Arrays.asList(firstSession, secondSession), ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 2L), Collections.emptyMap(), 2_000L, true, 2_600L);
        lifecycle.observeKeyContainerContents(Arrays.asList(firstSession, secondSession), ItemID.WILDY_LOOT_KEY1,
            Collections.singletonMap(MANIFEST_ITEM, 6L), Collections.emptyMap(), 6_000L, true, 2_600L);

        List<LootKeyProvenance> receipts = lifecycle.recordClaim(
            Arrays.asList(firstSession, secondSession), Collections.singletonMap(MANIFEST_ITEM, 1L), 3_000L);

        assertTrue("the settled gain fits both manifests, so its key is ambiguous", receipts.isEmpty());
        assertEquals(3L, first.getLootKeyProvenance().get(0).getRemainingManifestQuantities()
            .get(MANIFEST_ITEM).longValue());
        assertEquals(7L, second.getLootKeyProvenance().get(0).getRemainingManifestQuantities()
            .get(MANIFEST_ITEM).longValue());
    }

    @Test
    public void partialSettledClaimReceiptKeepsClaimedQuantityDistinctFromManifestTotal()
    {
        ProfitSession session = new ProfitSession("PK", 1_000L);
        PkEncounter kill = session.addPkEncounter(PkEncounterType.KILL, 100_000L, "Kill: Rival Name",
            ClassificationConfidence.CONFIRMED, "RuneLite player loot");
        ProfitTransaction row = pendingKeyRow(ItemID.WILDY_LOOT_KEY4, 1L, kill.getId(), session);
        Map<Integer, Long> manifest = new LinkedHashMap<>();
        manifest.put(MANIFEST_ITEM, 5L);
        manifest.put(SECOND_MANIFEST_ITEM, 3L);
        Map<Integer, Long> unitPrices = new LinkedHashMap<>();
        unitPrices.put(MANIFEST_ITEM, 200L);
        unitPrices.put(SECOND_MANIFEST_ITEM, 100L);
        assertTrue(LootKeyLifecycle.captureManifest(Collections.singletonList(session),
            ItemID.WILDY_LOOT_KEY4, manifest, unitPrices, 1_300L, true, 110_000L));

        LootKeyLifecycle lifecycle = new LootKeyLifecycle();
        lifecycle.setLootChestVisible(true);
        lifecycle.observeKeyContainerContents(Collections.singletonList(session), ItemID.WILDY_LOOT_KEY4,
            manifest, Collections.emptyMap(), 1_300L, true, 120_000L);
        Map<Integer, Long> remainingContainer = new LinkedHashMap<>(manifest);
        remainingContainer.put(MANIFEST_ITEM, 3L);
        lifecycle.observeKeyContainerContents(Collections.singletonList(session), ItemID.WILDY_LOOT_KEY4,
            remainingContainer, Collections.emptyMap(), 1_100L, true, 121_000L);

        Map<Integer, Long> settledGain = Collections.singletonMap(MANIFEST_ITEM, 2L);
        List<LootKeyProvenance> receipts = lifecycle.recordClaim(
            Collections.singletonList(session), settledGain, 160_000L);

        assertEquals(1, receipts.size());
        LootKeyProvenance receipt = receipts.get(0);
        assertTrue(receipt.isClaimReceipt());
        assertEquals(LootKeyProvenance.Status.CLAIMED, receipt.getStatus());
        assertEquals(settledGain, receipt.getClaimedQuantities());
        assertFalse("the receipt describes only the settled subset", receipt.getClaimedQuantities().equals(manifest));
        assertEquals("receipt keeps the source manifest total as manifest context", manifest,
            receipt.getManifestQuantities());
        assertEquals(1_300L, receipt.getManifestValueGp());
        assertEquals(160_000L, receipt.getClaimedAtEpochMillis());
        assertTrue(receipt.ledgerSummary().contains("1,300 gp manifest"));
        assertTrue(receipt.ledgerSummary().contains("from Rival Name"));
        assertTrue(receipt.ledgerSummary().contains("claimed 400 gp"));
        assertTrue(receipt.ledgerSummary().contains("claimed 01:00 after kill"));
        assertFalse("the complete manifest price is not presented as a claimed value",
            receipt.ledgerSummary().contains("claimed 1,300 gp"));

        LootKeyProvenance pending = row.getLootKeyProvenance().get(0);
        assertEquals(3L, pending.getRemainingManifestQuantities().get(MANIFEST_ITEM).longValue());
        assertEquals(3L, pending.getRemainingManifestQuantities().get(SECOND_MANIFEST_ITEM).longValue());
        assertEquals(settledGain, pending.getClaimedQuantities());
        assertEquals(LootKeyProvenance.Status.PENDING, pending.getStatus());
        assertFalse("a receipt copy cannot become a second candidate for later partial claims",
            receipt.hasUnclaimedManifest());

        lifecycle.observeKeyContainerContents(Collections.singletonList(session), ItemID.WILDY_LOOT_KEY4,
            remainingContainer, Collections.emptyMap(), 1_100L, true, 161_000L);
        lifecycle.observeKeyContainerContents(Collections.singletonList(session), ItemID.WILDY_LOOT_KEY4,
            Collections.<Integer, Long>emptyMap(), Collections.emptyMap(), 0L, true, 162_000L);
        List<LootKeyProvenance> finalReceipts = lifecycle.recordClaim(
            Collections.singletonList(session), remainingContainer, 163_000L);
        assertEquals(1, finalReceipts.size());
        assertEquals(900L, finalReceipts.get(0).getClaimedValueGp());
        assertEquals(LootKeyProvenance.Status.CLAIMED, row.getLootKeyProvenance().get(0).getStatus());
    }

    @Test
    public void emptyContainerWithdrawalAndConsumedKeyProduceOneEvidenceBackedReceipt()
    {
        ProfitSession session = new ProfitSession("PK", 1_000L);
        ProfitTransaction row = pendingKeyRow(ItemID.WILDY_LOOT_KEY0, 1L, null, session);
        LootKeyLifecycle lifecycle = new LootKeyLifecycle();
        lifecycle.setLootChestVisible(true);
        lifecycle.observeKeyContainerContents(Collections.singletonList(session), ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 1L), Collections.singletonMap(MANIFEST_ITEM, 50L),
            50L, true, 2_500L);
        lifecycle.observeKeyContainerContents(Collections.singletonList(session), ItemID.WILDY_LOOT_KEY0,
            Collections.<Integer, Long>emptyMap(), Collections.emptyMap(), 0L, true, 2_600L);

        List<LootKeyProvenance> receipts = lifecycle.recordClaim(
            Collections.singletonList(session),
            Collections.singletonMap(MANIFEST_ITEM, 1L),
            Collections.singletonMap(ItemID.WILDY_LOOT_KEY0, 1L),
            3_000L);

        assertEquals(1, receipts.size());
        assertEquals(50L, receipts.get(0).getClaimedValueGp());
        assertEquals(0L, receipts.get(0).getRemainingKeyQuantity());
        assertEquals(LootKeyProvenance.Status.CLAIMED, row.getLootKeyProvenance().get(0).getStatus());
        assertTrue(lifecycle.recordClaim(Collections.singletonList(session),
            Collections.singletonMap(MANIFEST_ITEM, 1L), 3_100L).isEmpty());
    }

    @Test
    public void aGainMatchingAManifestWithoutObservedContainerRemovalGetsNoReceipt()
    {
        ProfitSession session = new ProfitSession("PK", 1_000L);
        ProfitTransaction row = pendingKeyRow(ItemID.WILDY_LOOT_KEY1, 1L, null, session);
        assertTrue(LootKeyLifecycle.captureManifest(session, ItemID.WILDY_LOOT_KEY1,
            Collections.singletonMap(MANIFEST_ITEM, 1L), 50L, true, 2_000L));

        LootKeyLifecycle lifecycle = new LootKeyLifecycle();
        lifecycle.setLootChestVisible(true);
        lifecycle.observeKeyContainerContents(Collections.singletonList(session), ItemID.WILDY_LOOT_KEY1,
            Collections.singletonMap(MANIFEST_ITEM, 1L), Collections.emptyMap(), 50L, true, 2_500L);

        assertTrue(lifecycle.recordClaim(Collections.singletonList(session),
            Collections.singletonMap(MANIFEST_ITEM, 1L), 3_000L).isEmpty());
        assertEquals(LootKeyProvenance.Status.PENDING, row.getLootKeyProvenance().get(0).getStatus());
    }

    @Test
    public void localDeathRequiresBothMatchingKeyLossFlowAndInventoryDecrease()
    {
        assertDeathRemainsPending(true, 1L);
        assertDeathRemainsPending(false, 0L);
    }

    @Test
    public void localDeathClosesOnlyMatchingKeyAndNeverBooksManifestAsLoss()
    {
        ProfitSession session = new ProfitSession("PK", 1_000L);
        ProfitTransaction row = pendingKeyRow(ItemID.WILDY_LOOT_KEY0, 1L, null, session);
        LootKeyLifecycle.captureManifest(session, ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 8L), 8_000L, true, 2_000L);
        long beforeCosts = row.getCosts();
        long beforeRevenue = row.getRevenue();
        List<ItemFlow> originalAuditFlows = new ArrayList<>(row.getFlows());
        LootKeyLifecycle lifecycle = new LootKeyLifecycle();
        lifecycle.beginLocalDeath(Collections.singletonMap(ItemID.WILDY_LOOT_KEY0, 1L));

        lifecycle.recordDeathLosses(Collections.singletonList(session),
            Collections.singletonList(new ItemFlow(ItemID.WILDY_LOOT_KEY0, "Loot key", -1L, 0, 0L)),
            Collections.<Integer, Long>emptyMap(), 3_000L);

        LootKeyProvenance lost = row.getLootKeyProvenance().get(0);
        assertEquals(LootKeyProvenance.Status.LOST_ON_DEATH, lost.getStatus());
        assertEquals(1L, lost.getLostKeyQuantity());
        assertEquals(0L, lost.getRemainingKeyQuantity());
        assertTrue(lost.ledgerSummary().contains("Key lost on death"));
        assertEquals("metadata closes the pending audit row, but never adds manifest item flows",
            originalAuditFlows, row.getFlows());
        assertEquals(beforeRevenue, row.getRevenue());
        assertEquals(beforeCosts, row.getCosts());
        assertEquals(1, session.getTransactions().size());
    }

    @Test
    public void unrelatedFirstDeathSettlementKeepsKeyEvidenceForLaterBatch()
    {
        ProfitSession session = new ProfitSession("PK", 1_000L);
        ProfitTransaction row = pendingKeyRow(ItemID.WILDY_LOOT_KEY2, 1L, null, session);
        LootKeyLifecycle lifecycle = new LootKeyLifecycle();
        lifecycle.beginLocalDeath(Collections.singletonMap(ItemID.WILDY_LOOT_KEY2, 1L));

        lifecycle.recordDeathLosses(Collections.singletonList(session),
            Collections.singletonList(new ItemFlow(995, "Coins", -1L, 1, -1L)),
            Collections.singletonMap(ItemID.WILDY_LOOT_KEY2, 1L), 3_000L);
        assertTrue("first unrelated inventory/equipment settle must not consume the bounded death snapshot",
            lifecycle.isAwaitingLocalDeathSettle());

        Map<Integer, Long> lost = lifecycle.recordDeathLosses(Collections.singletonList(session),
            Collections.singletonList(new ItemFlow(ItemID.WILDY_LOOT_KEY2, "Loot key", -1L, 0, 0L)),
            Collections.<Integer, Long>emptyMap(), 3_100L);

        assertEquals(Long.valueOf(1L), lost.get(ItemID.WILDY_LOOT_KEY2));
        assertEquals(LootKeyProvenance.Status.LOST_ON_DEATH, row.getLootKeyProvenance().get(0).getStatus());
        assertFalse(lifecycle.isAwaitingLocalDeathSettle());
    }

    @Test
    public void provenanceSurvivesDetachedCopiesPresentationProjectionAndGsonRoundTrip()
    {
        ProfitSession session = new ProfitSession("PK", 1_000L);
        ProfitTransaction row = pendingKeyRow(ItemID.WILDY_LOOT_KEY2, 1L, null, session);
        LootKeyLifecycle.captureManifest(session, ItemID.WILDY_LOOT_KEY2,
            Collections.singletonMap(MANIFEST_ITEM, 4L), 4_000L, false, 2_000L);

        LootKeyProvenance detached = row.getLootKeyProvenance().get(0);
        detached.noteLostOnDeath(1L);
        assertEquals("read access returns a detached metadata copy", LootKeyProvenance.Status.PENDING,
            row.getLootKeyProvenance().get(0).getStatus());

        ProfitTransaction projection = row.presentationProjection(flow -> false);
        assertNotNull(projection);
        assertTrue(projection.getFlows().isEmpty());
        assertEquals(row.getLootKeyProvenance().get(0).getManifestQuantities(),
            projection.getLootKeyProvenance().get(0).getManifestQuantities());

        Gson gson = new Gson();
        ProfitTransaction restored = gson.fromJson(gson.toJson(row), ProfitTransaction.class);
        LootKeyProvenance persisted = restored.getLootKeyProvenance().get(0);
        assertEquals(row.getLootKeyProvenance().get(0).getManifestQuantities(), persisted.getManifestQuantities());
        assertEquals(4_000L, persisted.getManifestValueGp());
        assertFalse(persisted.isManifestValueComplete());
        assertEquals(LootKeyProvenance.Status.PENDING, persisted.getStatus());
    }

    @Test
    public void chestUiDetectionStillRequiresARecognisableLootChestName()
    {
        assertTrue(LootKeyLifecycle.looksLikeLootChestUi("Wilderness loot chest"));
        assertFalse(LootKeyLifecycle.looksLikeLootChestUi("Bank chest"));
        assertFalse(LootKeyLifecycle.looksLikeLootChestUi(null));
    }

    @Test
    public void keyReceiptAndClaimAuditNotesRemainValueDeferred()
    {
        assertEquals("Loot key received - value deferred", LootKeyLifecycle.keyReceivedNote());
        assertEquals("Loot key consumed - value already deferred", LootKeyLifecycle.keyConsumedNote());
        assertEquals("Loot key destroyed - contents unreachable", LootKeyLifecycle.keyDestroyedNote());
    }

    private static ProfitTransaction pendingKeyRow(
        int keyItemId,
        long quantity,
        String encounterId,
        ProfitSession session)
    {
        LootKeyProvenance entry = LootKeyLifecycle.receivedEntry(keyItemId, quantity,
            encounterId, session, 2_000L);
        ProfitTransaction row = new ProfitTransaction(2_000L, null, TransactionType.ADJUSTMENT,
            TrackingContext.PK_LOOT, LootKeyLifecycle.keyReceivedNote(), "PKing", false,
            Collections.singletonList(new ItemFlow(keyItemId, "Loot key", quantity, 0, 0L)),
            ClassificationConfidence.LIKELY, "Deferred loot-key value", encounterId);
        row.addLootKeyProvenance(entry);
        session.addTransaction(row, 2_000);
        return row;
    }

    private static void assertDeathRemainsPending(boolean hasNegativeFlow, long currentQuantity)
    {
        ProfitSession session = new ProfitSession("PK", 1_000L);
        ProfitTransaction row = pendingKeyRow(ItemID.WILDY_LOOT_KEY1, 1L, null, session);
        LootKeyLifecycle lifecycle = new LootKeyLifecycle();
        lifecycle.beginLocalDeath(Collections.singletonMap(ItemID.WILDY_LOOT_KEY1, 1L));
        List<ItemFlow> flows = hasNegativeFlow
            ? Collections.singletonList(new ItemFlow(ItemID.WILDY_LOOT_KEY1, "Loot key", -1L, 0, 0L))
            : Collections.emptyList();

        lifecycle.recordDeathLosses(Collections.singletonList(session), flows,
            currentQuantity > 0L
                ? Collections.singletonMap(ItemID.WILDY_LOOT_KEY1, currentQuantity)
                : Collections.<Integer, Long>emptyMap(), 3_000L);

        assertEquals(LootKeyProvenance.Status.PENDING, row.getLootKeyProvenance().get(0).getStatus());
        assertEquals(0L, row.getLootKeyProvenance().get(0).getLostKeyQuantity());
    }

    private static ItemContainer itemContainer(Item... items)
    {
        return proxy(ItemContainer.class, (method, args) -> "getItems".equals(method) ? items : null);
    }

    private static boolean isMutable(Map<Integer, Long> map)
    {
        try
        {
            map.put(-999, 1L);
            map.remove(-999);
            return true;
        }
        catch (UnsupportedOperationException ignored)
        {
            return false;
        }
    }

    private interface Answer
    {
        Object answer(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Answer answer)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (obj, method, args) ->
        {
            Object value = answer.answer(method.getName(), args);
            if (value != null || !method.getReturnType().isPrimitive())
            {
                return value;
            }
            if (method.getReturnType() == boolean.class)
            {
                return false;
            }
            if (method.getReturnType() == long.class)
            {
                return 0L;
            }
            return 0;
        }));
    }
}
