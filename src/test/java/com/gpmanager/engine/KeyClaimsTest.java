package com.gpmanager;

import com.google.gson.Gson;
import com.gpmanager.SavedState.PendingClaim;
import java.lang.reflect.Proxy;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Loot-key runtime evidence is transient (container removal window); only the
 * minimal {@link PendingClaim} survives restart. Tests cover settlement, death and idempotency.
 */
public class KeyClaimsTest
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
            assertEquals(index, KeyClaims.keyIndexForItemId(keyIds[index]));
            assertEquals(keyIds[index], KeyClaims.keyItemIdForIndex(index));
            assertEquals(containerIds[index], KeyClaims.keyContainerIdForIndex(index));
            assertTrue(KeyClaims.isLootKeyItem(keyIds[index]));
        }
        assertEquals(-1, KeyClaims.keyIndexForItemId(-1));
        assertEquals(-1, KeyClaims.keyItemIdForIndex(5));
        assertEquals(-1, KeyClaims.keyContainerIdForIndex(5));

        Map<Integer, Long> heldKeys = KeyClaims.keyQuantities(itemContainer(
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

        Map<Integer, Long> manifest = KeyClaims.manifestFromContainer(container, id -> id == CRATE_ITEM);

        Map<Integer, Long> expected = new LinkedHashMap<>();
        expected.put(MANIFEST_ITEM, 14L);
        expected.put(SECOND_MANIFEST_ITEM, 3L);
        assertEquals(expected, manifest);
        assertFalse("returned manifests cannot be changed by callers", isMutable(manifest));
    }

    @Test
    public void emptyOrInvalidContainerContentsDoNotBecomeEvidence()
    {
        assertTrue(KeyClaims.manifestFromContainer(null, id -> false).isEmpty());
        assertTrue(KeyClaims.manifestFromContainer(itemContainer((Item[]) null), id -> false).isEmpty());
        assertTrue(KeyClaims.manifestFromContainer(
            itemContainer(new Item(-1, 1), new Item(MANIFEST_ITEM, 0), new Item(CRATE_ITEM, 1)),
            id -> id == CRATE_ITEM).isEmpty());

    }

    @Test
    public void pendingClaimOpensOnceCarriesTheCharterFieldsAndRoundTripsThroughSavedState()
    {
        KeyClaims claims = new KeyClaims();
        claims.open("audit-1", ItemID.WILDY_LOOT_KEY2, 1L, 5_000L);
        claims.open("audit-1", ItemID.WILDY_LOOT_KEY2, 1L, 5_000L);
        claims.open("", ItemID.WILDY_LOOT_KEY2, 1L, 5_000L);
        claims.open("bad-qty", ItemID.WILDY_LOOT_KEY2, 0L, 5_000L);
        assertEquals("duplicate and invalid opens are ignored", 1, claims.all().size());

        PendingClaim claim = find(claims, "audit-1");
        assertNotNull(claim);
        assertEquals(ItemID.WILDY_LOOT_KEY2, claim.itemOrKeyId);
        assertEquals(1L, claim.getQuantity());
        assertEquals(5_000L, claim.getCreatedAtEpochMillis());

        SavedState state = new SavedState();
        claims.writeTo(state);
        Gson gson = new Gson();
        SavedState reloaded = gson.fromJson(gson.toJson(state), SavedState.class);
        KeyClaims restored = new KeyClaims();
        restored.restore(reloaded, Collections.emptyList());
        PendingClaim persisted = find(restored, "audit-1");
        assertNotNull("a durable claim never expires on its own", persisted);
        assertEquals(claim.itemOrKeyId, persisted.itemOrKeyId);
        assertEquals(claim.getCreatedAtEpochMillis(), persisted.getCreatedAtEpochMillis());
        assertEquals(Arrays.asList("claimId", "itemOrKeyId", "quantity",
            "createdAtEpochMillis"),
            new java.util.ArrayList<>(gson.toJsonTree(claim).getAsJsonObject().keySet()));
    }

    @Test
    public void unrelatedPendingManifestDoesNotBlockUniqueCompatibleClaim()
    {
        KeyClaims claims = new KeyClaims();
        claims.open("compatible", ItemID.WILDY_LOOT_KEY0, 1L, 2_000L);
        claims.open("unrelated", ItemID.WILDY_LOOT_KEY1, 1L, 2_000L);

        claims.setLootChestVisible(true);
        claims.observeKeyContainerContents(ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 2L));
        claims.observeKeyContainerContents(ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 1L));

        assertEquals("the unrelated manifest is not a second candidate", "compatible",
            claims.settleLootKey(Collections.singletonMap(MANIFEST_ITEM, 1L), Collections.emptyMap()));
        assertNotNull(find(claims, "unrelated"));
    }

    @Test
    public void ambiguousCompatibleClaimCandidatesSettleNothing()
    {
        KeyClaims claims = new KeyClaims();
        claims.open("first", ItemID.WILDY_LOOT_KEY0, 1L, 2_000L);
        claims.open("second", ItemID.WILDY_LOOT_KEY1, 1L, 2_000L);

        claims.setLootChestVisible(true);
        claims.observeKeyContainerContents(ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 3L));
        claims.observeKeyContainerContents(ItemID.WILDY_LOOT_KEY1,
            Collections.singletonMap(MANIFEST_ITEM, 7L));
        claims.observeKeyContainerContents(ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 2L));
        claims.observeKeyContainerContents(ItemID.WILDY_LOOT_KEY1,
            Collections.singletonMap(MANIFEST_ITEM, 6L));

        assertNull("the settled gain has two compatible key candidates",
            claims.settleLootKey(Collections.singletonMap(MANIFEST_ITEM, 1L), Collections.emptyMap()));
        assertEquals(1L, find(claims, "first").getQuantity());
        assertEquals(1L, find(claims, "second").getQuantity());
    }

    @Test
    public void partialSettlementKeepsAStackedClaimOpenUntilTheKeyIsConsumed()
    {
        KeyClaims claims = new KeyClaims();
        claims.open("k4", ItemID.WILDY_LOOT_KEY4, 2L, 100_000L);
        Map<Integer, Long> manifest = new LinkedHashMap<>();
        manifest.put(MANIFEST_ITEM, 5L);
        manifest.put(SECOND_MANIFEST_ITEM, 3L);

        claims.setLootChestVisible(true);
        claims.observeKeyContainerContents(ItemID.WILDY_LOOT_KEY4, manifest);
        Map<Integer, Long> remainingContainer = new LinkedHashMap<>(manifest);
        remainingContainer.put(MANIFEST_ITEM, 3L);
        claims.observeKeyContainerContents(ItemID.WILDY_LOOT_KEY4, remainingContainer);

        assertNull("one matched manifest settles one key of the stack",
            claims.settleLootKey(Collections.singletonMap(MANIFEST_ITEM, 2L), Collections.emptyMap()));
        assertEquals("a partially settled claim keeps its remainder", 1L, find(claims, "k4").getQuantity());

        claims.observeKeyContainerContents(ItemID.WILDY_LOOT_KEY4, Collections.<Integer, Long>emptyMap());
        assertEquals("the consumed key closes the claim", "k4", claims.settleLootKey(remainingContainer,
            Collections.singletonMap(ItemID.WILDY_LOOT_KEY4, 1L)));
        assertTrue(claims.all().isEmpty());
    }

    @Test
    public void emptyContainerWithdrawalAndConsumedKeySettleOnce()
    {
        KeyClaims claims = new KeyClaims();
        claims.open("k0", ItemID.WILDY_LOOT_KEY0, 1L, 2_000L);
        claims.setLootChestVisible(true);
        claims.observeKeyContainerContents(ItemID.WILDY_LOOT_KEY0,
            Collections.singletonMap(MANIFEST_ITEM, 1L));
        claims.observeKeyContainerContents(ItemID.WILDY_LOOT_KEY0,
            Collections.<Integer, Long>emptyMap());

        assertEquals("k0", claims.settleLootKey(
            Collections.singletonMap(MANIFEST_ITEM, 1L), Collections.singletonMap(ItemID.WILDY_LOOT_KEY0, 1L)));
        assertNull(find(claims, "k0"));
        assertNull("the same gain can never settle twice", claims.settleLootKey(
            Collections.singletonMap(MANIFEST_ITEM, 1L), Collections.emptyMap()));
    }

    @Test
    public void aGainWithoutObservedContainerRemovalSettlesNothing()
    {
        KeyClaims claims = new KeyClaims();
        claims.open("k1", ItemID.WILDY_LOOT_KEY1, 1L, 2_000L);
        claims.setLootChestVisible(true);
        claims.observeKeyContainerContents(ItemID.WILDY_LOOT_KEY1,
            Collections.singletonMap(MANIFEST_ITEM, 1L));

        assertNull(claims.settleLootKey(Collections.singletonMap(MANIFEST_ITEM, 1L), Collections.emptyMap()));
        assertNotNull(find(claims, "k1"));
    }

    @Test
    public void localDeathRequiresBothMatchingKeyLossFlowAndInventoryDecrease()
    {
        assertDeathRemainsPending(true, 1L);
        assertDeathRemainsPending(false, 0L);
    }

    @Test
    public void localDeathClosesOnlyMatchingKeyAndNeverBooksKeyAsLoss()
    {
        Session session = new Session("PK", 1_000L);
        Transaction row = keyAuditRow(ItemID.WILDY_LOOT_KEY0, 1L, session);
        KeyClaims claims = new KeyClaims();
        claims.open(row.getId(), ItemID.WILDY_LOOT_KEY0, 1L, 2_000L);
        claims.open("other", ItemID.WILDY_LOOT_KEY3, 1L, 2_000L);
        long beforeCosts = row.getCosts();
        long beforeRevenue = row.getRevenue();
        List<Flow> originalAuditFlows = new java.util.ArrayList<>(row.getFlows());
        claims.beginLocalDeath(Collections.singletonMap(ItemID.WILDY_LOOT_KEY0, 1L));

        claims.recordDeathLosses(
            Collections.singletonList(new Flow(ItemID.WILDY_LOOT_KEY0, "Loot key", -1L, 0, 0L)),
            Collections.<Integer, Long>emptyMap());

        assertNull("the lost key's claim is closed", find(claims, row.getId()));
        assertNotNull("an unrelated key stays pending", find(claims, "other"));
        assertEquals("closing the claim never adds item flows", originalAuditFlows, row.getFlows());
        assertEquals(beforeRevenue, row.getRevenue());
        assertEquals(beforeCosts, row.getCosts());
        assertEquals(1, session.getTransactions().size());
    }

    @Test
    public void unrelatedFirstDeathSettlementKeepsKeyEvidenceForLaterBatch()
    {
        KeyClaims claims = new KeyClaims();
        claims.open("k2", ItemID.WILDY_LOOT_KEY2, 1L, 2_000L);
        claims.beginLocalDeath(Collections.singletonMap(ItemID.WILDY_LOOT_KEY2, 1L));

        claims.recordDeathLosses(
            Collections.singletonList(new Flow(995, "Coins", -1L, 1, -1L)),
            Collections.singletonMap(ItemID.WILDY_LOOT_KEY2, 1L));
        assertTrue("first unrelated inventory/equipment settle must not consume the bounded death snapshot",
            claims.isAwaitingLocalDeathSettle());
        assertNotNull(find(claims, "k2"));

        claims.recordDeathLosses(
            Collections.singletonList(new Flow(ItemID.WILDY_LOOT_KEY2, "Loot key", -1L, 0, 0L)),
            Collections.<Integer, Long>emptyMap());

        assertNull(find(claims, "k2"));
        assertFalse(claims.isAwaitingLocalDeathSettle());
    }

    @Test
    public void keyReceiptAuditNoteRemainsValueDeferred()
    {
        assertEquals("Loot key received - value deferred", KeyClaims.LOOT_KEY_NOTE);
    }

    private static PendingClaim find(KeyClaims claims, String claimId)
    {
        for (PendingClaim claim : claims.all())
        {
            if (claim.getClaimId().equals(claimId))
            {
                return claim;
            }
        }
        return null;
    }

    private static Transaction keyAuditRow(int keyItemId, long quantity, Session session)
    {
        Transaction row = new Transaction(2_000L, null, TransactionType.ADJUSTMENT,
            Context.PK_LOOT, KeyClaims.LOOT_KEY_NOTE, "PKing", false,
            Collections.singletonList(new Flow(keyItemId, "Loot key", quantity, 0, 0L)),
            ClassificationConfidence.LIKELY, "Deferred loot-key value", null);
        session.addTransaction(row, 2_000);
        return row;
    }

    private static void assertDeathRemainsPending(boolean hasNegativeFlow, long currentQuantity)
    {
        KeyClaims claims = new KeyClaims();
        claims.open("k1", ItemID.WILDY_LOOT_KEY1, 1L, 2_000L);
        claims.beginLocalDeath(Collections.singletonMap(ItemID.WILDY_LOOT_KEY1, 1L));
        List<Flow> flows = hasNegativeFlow
            ? Collections.singletonList(new Flow(ItemID.WILDY_LOOT_KEY1, "Loot key", -1L, 0, 0L))
            : Collections.emptyList();

        claims.recordDeathLosses(flows, currentQuantity > 0L
            ? Collections.singletonMap(ItemID.WILDY_LOOT_KEY1, currentQuantity)
            : Collections.<Integer, Long>emptyMap());

        assertEquals(1L, find(claims, "k1").getQuantity());
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
