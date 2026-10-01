package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pre-R5 GE sale classification: a genuine sell placement must own its settled rune loss as
 * TRADE/MARKET across the supported event orderings, while genuine casting stays
 * CONSUMPTION/CAST and a spent placement can never own a later unrelated loss.
 */
public class GeSellClassificationTest
{
    private static final int[] RUNES = {
        ItemID.AIRRUNE, ItemID.NATURERUNE, ItemID.FIRERUNE, ItemID.ASTRALRUNE, ItemID.LAVARUNE, ItemID.STEAMRUNE
    };
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void everySequentialRuneSaleSettlesAsNeutralCustodyNotCast()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L);
        for (int rune : RUNES)
        {
            inventory.put(rune, 5L);
        }
        engine.setBaseline(new ContainerSnapshot(inventory));

        OfferLedger ledger = new OfferLedger();
        for (int i = 0; i < RUNES.length; i++)
        {
            int rune = RUNES[i];
            noteGe(engine, ledger, i, SELLING, rune, 5, 0, 200, 0, now);
            inventory.put(rune, inventory.get(rune) - 1L);
            Transaction sale = settleStable(engine, inventory, now);
            assertNotNull("sale settles: " + name(rune), sale);
            assertEquals("a proven GE sale placement is ownership-neutral custody: " + name(rune),
                TransactionType.TRANSFER, sale.getType());
            assertEquals(Context.TRANSFER, sale.getContext());
            assertFalse("custody is never counted money", sale.isCounted());
            assertNotEquals("a sold rune is never stamped as a cast: " + name(rune),
                ActionKind.CAST, sale.getActionKind());
            assertEquals("the observed sold value is untouched: " + name(rune),
                -200L, sale.getFlows().get(0).valueDelta);
            now += 10_000L;
        }
        assertEquals("pending custody never changes the observed Net",
            0L, engine.getMetrics(now).net);
        assertEquals("no placement fell through to CAST", 0,
            engine.getActiveSession().getTransactions().stream()
                .filter(t -> t != null && ActionKind.CAST == t.getActionKind()).count());
    }

    @Test
    public void coalescedRuneLossesSettleOnceAsNeutralCustody()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L, ItemID.FIRERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        noteGe(engine, ledger, 1, SELLING, ItemID.FIRERUNE, 5, 0, 100, 0, now);
        inventory.put(ItemID.NATURERUNE, 4L);
        inventory.put(ItemID.FIRERUNE, 4L);

        Transaction sale = settleStable(engine, inventory, now);
        assertNotNull(sale);
        assertEquals(TransactionType.TRANSFER, sale.getType());
        assertFalse(sale.isCounted());
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertTrue(sale.getFlows().size() >= 2);
        assertEquals(0L, engine.getMetrics(now).net);
    }

    @Test
    public void offerEvidenceDuringTheDirtyWindowOwnsTheLossAsCustody()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        // The inventory loss goes dirty first; the sell placement evidence arrives before the
        // stable settlement commits.
        inventory.put(ItemID.NATURERUNE, 4L);
        ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
        engine.markInventoryDirty();
        engine.processIfDirty(snapshot, now);

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now + 100L);

        Transaction settled = engine.processIfDirty(snapshot, now + 600L);
        Transaction finalSettle = engine.processIfDirty(snapshot, now + 1_200L);
        Transaction sale = finalSettle == null ? settled : finalSettle;
        assertNotNull(sale);
        assertEquals(TransactionType.TRANSFER, sale.getType());
        assertEquals(Context.TRANSFER, sale.getContext());
        assertFalse(sale.isCounted());
    }

    @Test
    public void claimedSellPlacementCannotOwnALaterUnrelatedLoss()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        inventory.put(ItemID.NATURERUNE, 4L);
        Transaction sale = settleStable(engine, inventory, now);
        assertNotNull(sale);
        assertEquals("the placement principal is custody", TransactionType.TRANSFER, sale.getType());
        assertFalse(sale.isCounted());

        // A later unrelated same-item loss inside the placement window must not reuse the
        // already-captured placement evidence.
        inventory.put(ItemID.NATURERUNE, 3L);
        Transaction later = settleStable(engine, inventory, now + 5_000L);
        assertNotNull(later);
        assertEquals("a spent placement never owns a later unrelated loss",
            TransactionType.CONSUMPTION, later.getType());
    }

    @Test
    public void stalePlacementEvidenceNeverStealsAnExplicitCast()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        engine.noteConsumptionIntent(ItemID.NATURERUNE, 18, false, ActionKind.CAST);
        inventory.put(ItemID.NATURERUNE, 4L);

        Transaction cast = settleStable(engine, inventory, now);
        assertNotNull(cast);
        assertEquals(TransactionType.CONSUMPTION, cast.getType());
        assertEquals("Cast", SemanticFinancialProjection.verbOf(cast));
    }

    @Test
    public void soldRunePlacementIsNeutralCustodyAndCastCostIsSupplies()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        inventory.put(ItemID.NATURERUNE, 4L);
        Transaction sale = settleStable(engine, inventory, now);
        assertNotNull(sale);
        assertEquals("a sold rune placement is ownership-neutral custody, never a cost",
            CostKind.NONE, CostKind.of(sale, sale.getFlows().get(0)));

        engine.noteConsumptionIntent(ItemID.NATURERUNE, 18, false, ActionKind.CAST);
        inventory.put(ItemID.NATURERUNE, 3L);
        Transaction cast = settleStable(engine, inventory, now + 10_000L);
        assertNotNull(cast);
        assertEquals(TransactionType.CONSUMPTION, cast.getType());
        assertEquals("a genuine cast stays a supply cost",
            CostKind.SUPPLIES, CostKind.of(cast, cast.getFlows().get(0)));
        assertEquals("only the cast remains counted", -200L, engine.getMetrics(now + 10_000L).net);
    }

    @Test
    public void lateOfferEvidenceNeverRewritesABookedSettle()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        // The loss settles before any evidence exists: book once, conservatively.
        inventory.put(ItemID.NATURERUNE, 4L);
        Transaction settled = settleStable(engine, inventory, now);
        assertNotNull(settled);
        assertEquals(TransactionType.CONSUMPTION, settled.getType());
        assertEquals(1, engine.getActiveSession().getTransactions().size());

        // Evidence arriving afterwards must not rewrite the booked row (no retroactive
        // reconciliation), and a later cast must still be a cast.
        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now + 1_000L);
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertEquals(TransactionType.CONSUMPTION, engine.getActiveSession().getTransactions().get(0).getType());

        engine.noteConsumptionIntent(ItemID.NATURERUNE, 18, false, ActionKind.CAST);
        inventory.put(ItemID.NATURERUNE, 3L);
        Transaction cast = settleStable(engine, inventory, now + 10_000L);
        assertNotNull(cast);
        assertEquals(TransactionType.CONSUMPTION, cast.getType());
        assertEquals("Cast", SemanticFinancialProjection.verbOf(cast));
    }

    @Test
    public void exactSpellNameAttachesOnlyToItsMatchedConsumptionIntent()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Vorkath", SessionMode.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        int spellbookGroup = net.runelite.api.gameval.InterfaceID.MAGIC_SPELLBOOK;
        ActionLabel iceBurst = ActionLabel.fromSpellMenu("Cast",
            spellbookGroup << 16 | 14, spellbookGroup, "<col=ff9040>Ice Burst</col>", "Cast");
        assertNotNull(iceBurst);
        engine.noteConsumptionIntent(ItemID.NATURERUNE, 18, false, ActionKind.CAST, iceBurst);
        inventory.put(ItemID.NATURERUNE, 4L);
        Transaction cast = settleStable(engine, inventory, now);
        assertNotNull(cast);
        assertEquals("Ice Burst", cast.getObservedActionLabel().value());

        inventory.put(ItemID.NATURERUNE, 3L);
        Transaction laterLoss = settleStable(engine, inventory, now + 5_000L);
        assertNotNull(laterLoss);
        assertEquals("one matched intent cannot label later rune loss", null,
            laterLoss.getObservedActionLabel());
    }

    @Test
    public void unresolvedCastClickCannotRewriteAnArmedSupplyVerb()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Vorkath", SessionMode.AUTO, now);
        int dragonBones = 536;
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, dragonBones, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        engine.noteConsumptionIntent(dragonBones, 18, false, ActionKind.BURY, null);
        // A spellbook Cast click can resolve no item id. The open intent must keep the
        // resolved item's proven verb; the exact spell name is a hint, not a re-verb.
        engine.noteConsumptionIntent(-1, 18, false, ActionKind.CAST,
            ActionLabel.of("Ice Burst"));
        inventory.put(dragonBones, 4L);
        Transaction buried = settleStable(engine, inventory, now);
        assertNotNull(buried);
        assertEquals("a later unresolved Cast click cannot rewrite the buried verb",
            "Buried", SemanticFinancialProjection.verbOf(buried));
        assertEquals("buried bones stay a supply cost, not a loss",
            CostKind.SUPPLIES, CostKind.of(buried, buried.getFlows().get(0)));
        assertNull("a Cast click that did not cast cannot name the spell",
            buried.getObservedActionLabel());
    }

    @Test
    public void hardTransferEvidenceWinsOverStaleSellPlacement()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustomSession("Trading", SessionMode.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        OfferLedger ledger = new OfferLedger();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        inventory.put(ItemID.NATURERUNE, 4L);

        Transaction deposit = settleStable(engine, inventory, now);
        assertNotNull(deposit);
        assertEquals("hard transfer evidence keeps its own delta", TransactionType.TRANSFER, deposit.getType());
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void noteGe(
        Engine engine,
        OfferLedger ledger,
        int slot,
        GrandExchangeOfferState state,
        int itemId,
        int totalQuantity,
        int quantityTraded,
        int price,
        int spent,
        long at)
    {
        OfferLedger.Transition transition = ledger.observe(
            new OfferLedger.Snapshot(slot, state, itemId, totalQuantity, quantityTraded, price, spent))
            .orElse(null);
        if (transition != null)
        {
            engine.noteGeOfferObservation(transition, name(itemId), at);
        }
    }

    /** Production stabilization: a dirty change commits only after the configured quiet ticks. */
    private static Transaction settleStable(Engine engine, Map<Integer, Long> next, long now)
    {
        engine.markInventoryDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(next);
        Transaction result = null;
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(snapshot, now + i * 600L);
            if (settled != null)
            {
                result = settled;
            }
        }
        return result;
    }

    private static Map<Integer, Long> inventory(Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((Integer) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }

    private static String name(int id)
    {
        if (id == ItemID.COINS)
        {
            return "Coins";
        }
        if (id == ItemID.AIRRUNE)
        {
            return "Air rune";
        }
        if (id == ItemID.NATURERUNE)
        {
            return "Nature rune";
        }
        if (id == ItemID.FIRERUNE)
        {
            return "Fire rune";
        }
        if (id == ItemID.ASTRALRUNE)
        {
            return "Astral rune";
        }
        if (id == ItemID.LAVARUNE)
        {
            return "Lava rune";
        }
        if (id == ItemID.STEAMRUNE)
        {
            return "Steam rune";
        }
        return "Item " + id;
    }

    private static int price(int id)
    {
        return id == ItemID.COINS ? 1 : 200;
    }

    private static Engine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 2; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                flows.add(new Flow(id, name(id), delta.getValue(), price(id), delta.getValue() * price(id),
                    id == ItemID.COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
