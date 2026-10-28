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
        engine.startCustom("Trading", now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L);
        for (int rune : RUNES)
        {
            inventory.put(rune, 5L);
        }
        engine.setBaseline(new ContainerSnapshot(inventory));

        OfferSlots ledger = new OfferSlots();
        for (int i = 0; i < RUNES.length; i++)
        {
            int rune = RUNES[i];
            noteGe(engine, ledger, i, SELLING, rune, 5, 0, 200, 0, now);
            inventory.put(rune, inventory.get(rune) - 1L);
            Transaction sale = settleStable(engine, inventory, now);
            assertNotNull("sale settles: " + name(rune), sale);
            assertEquals("a proven GE sale placement is ownership-neutral custody: " + name(rune),
                TransactionType.TRANSFER, sale.type());
            assertEquals(Context.TRANSFER, sale.context());
            assertFalse("custody is never counted money", sale.counts());
            assertNotEquals("a sold rune is never stamped as a cast: " + name(rune),
                ActionKind.CAST, sale.action());
            assertEquals("the observed sold value is untouched: " + name(rune),
                -200L, sale.flows().get(0).value);
            now += 10_000L;
        }
        assertEquals("pending custody never changes the observed Net",
            0L, engine.metrics(now).net);
        assertEquals("no placement fell through to CAST", 0,
            engine.session().transactions().stream()
                .filter(t -> t != null && ActionKind.CAST == t.action()).count());
    }

    @Test
    public void coalescedRuneLossesSettleOnceAsNeutralCustody()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustom("Trading", now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L, ItemID.FIRERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        OfferSlots ledger = new OfferSlots();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        noteGe(engine, ledger, 1, SELLING, ItemID.FIRERUNE, 5, 0, 100, 0, now);
        inventory.put(ItemID.NATURERUNE, 4L);
        inventory.put(ItemID.FIRERUNE, 4L);

        Transaction sale = settleStable(engine, inventory, now);
        assertNotNull(sale);
        assertEquals(TransactionType.TRANSFER, sale.type());
        assertFalse(sale.counts());
        assertEquals(1, engine.session().transactions().size());
        assertTrue(sale.flows().size() >= 2);
        assertEquals(0L, engine.metrics(now).net);
    }

    @Test
    public void offerEvidenceDuringTheDirtyWindowOwnsTheLossAsCustody()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustom("Trading", now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        // The inventory loss goes dirty first; the sell placement evidence arrives before the
        // stable settlement commits.
        inventory.put(ItemID.NATURERUNE, 4L);
        ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
        engine.markDirty();
        engine.processIfDirty(snapshot, now, null);

        OfferSlots ledger = new OfferSlots();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now + 100L);

        Transaction settled = engine.processIfDirty(snapshot, now + 600L, null);
        Transaction finalSettle = engine.processIfDirty(snapshot, now + 1_200L, null);
        Transaction sale = finalSettle == null ? settled : finalSettle;
        assertNotNull(sale);
        assertEquals(TransactionType.TRANSFER, sale.type());
        assertEquals(Context.TRANSFER, sale.context());
        assertFalse(sale.counts());
    }

    @Test
    public void claimedSellPlacementCannotOwnALaterUnrelatedLoss()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustom("Trading", now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        OfferSlots ledger = new OfferSlots();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        inventory.put(ItemID.NATURERUNE, 4L);
        Transaction sale = settleStable(engine, inventory, now);
        assertNotNull(sale);
        assertEquals("the placement principal is custody", TransactionType.TRANSFER, sale.type());
        assertFalse(sale.counts());

        // A later unrelated same-item loss inside the placement window must not reuse the
        // already-captured placement evidence.
        inventory.put(ItemID.NATURERUNE, 3L);
        Transaction later = settleStable(engine, inventory, now + 5_000L);
        assertNotNull(later);
        assertEquals("a spent placement never owns a later unrelated loss",
            TransactionType.CONSUMPTION, later.type());
    }

    @Test
    public void stalePlacementEvidenceNeverStealsAnExplicitCast()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustom("Trading", now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        OfferSlots ledger = new OfferSlots();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        engine.noteConsume(ItemID.NATURERUNE, 18, false, ActionKind.CAST, null);
        inventory.put(ItemID.NATURERUNE, 4L);

        Transaction cast = settleStable(engine, inventory, now);
        assertNotNull(cast);
        assertEquals(TransactionType.CONSUMPTION, cast.type());
        assertEquals("Cast", Semantics.verbOf(cast));
    }

    @Test
    public void soldRunePlacementIsNeutralCustodyAndCastCostIsSupplies()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustom("Trading", now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        OfferSlots ledger = new OfferSlots();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        inventory.put(ItemID.NATURERUNE, 4L);
        Transaction sale = settleStable(engine, inventory, now);
        assertNotNull(sale);
        assertEquals("a sold rune placement is ownership-neutral custody, never a cost",
            CostKind.NONE, CostKind.of(sale, sale.flows().get(0)));

        engine.noteConsume(ItemID.NATURERUNE, 18, false, ActionKind.CAST, null);
        inventory.put(ItemID.NATURERUNE, 3L);
        Transaction cast = settleStable(engine, inventory, now + 10_000L);
        assertNotNull(cast);
        assertEquals(TransactionType.CONSUMPTION, cast.type());
        assertEquals("a genuine cast stays a supply cost",
            CostKind.SUPPLIES, CostKind.of(cast, cast.flows().get(0)));
        assertEquals("only the cast remains counted", -200L, engine.metrics(now + 10_000L).net);
    }

    @Test
    public void lateOfferEvidenceNeverRewritesABookedSettle()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustom("Trading", now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        // The loss settles before any evidence exists: book once, conservatively.
        inventory.put(ItemID.NATURERUNE, 4L);
        Transaction settled = settleStable(engine, inventory, now);
        assertNotNull(settled);
        assertEquals(TransactionType.CONSUMPTION, settled.type());
        assertEquals(1, engine.session().transactions().size());

        // Evidence arriving afterwards must not rewrite the booked row (no retroactive
        // reconciliation), and a later cast must still be a cast.
        OfferSlots ledger = new OfferSlots();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now + 1_000L);
        assertEquals(1, engine.session().transactions().size());
        assertEquals(TransactionType.CONSUMPTION, engine.session().transactions().get(0).type());

        engine.noteConsume(ItemID.NATURERUNE, 18, false, ActionKind.CAST, null);
        inventory.put(ItemID.NATURERUNE, 3L);
        Transaction cast = settleStable(engine, inventory, now + 10_000L);
        assertNotNull(cast);
        assertEquals(TransactionType.CONSUMPTION, cast.type());
        assertEquals("Cast", Semantics.verbOf(cast));
    }

    @Test
    public void exactSpellNameAttachesOnlyToItsMatchedConsumptionIntent()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustom("Vorkath", now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        int spellbookGroup = net.runelite.api.gameval.InterfaceID.MAGIC_SPELLBOOK;
        ActionLabel iceBurst = ActionLabel.fromSpellMenu("Cast",
            spellbookGroup << 16 | 14, spellbookGroup, "<col=ff9040>Ice Burst</col>", "Cast");
        assertNotNull(iceBurst);
        engine.noteConsume(ItemID.NATURERUNE, 18, false, ActionKind.CAST, iceBurst);
        inventory.put(ItemID.NATURERUNE, 4L);
        Transaction cast = settleStable(engine, inventory, now);
        assertNotNull(cast);
        assertEquals("Ice Burst", cast.actionLabel().value);

        inventory.put(ItemID.NATURERUNE, 3L);
        Transaction laterLoss = settleStable(engine, inventory, now + 5_000L);
        assertNotNull(laterLoss);
        assertEquals("one matched intent cannot label later rune loss", null,
            laterLoss.actionLabel());
    }

    @Test
    public void unresolvedCastClickCannotRewriteAnArmedSupplyVerb()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustom("Vorkath", now);
        int dragonBones = 536;
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, dragonBones, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        engine.noteConsume(dragonBones, 18, false, ActionKind.BURY, null);
        // A spellbook Cast click can resolve no item id. The open intent must keep the
        // resolved item's proven verb; the exact spell name is a hint, not a re-verb.
        engine.noteConsume(-1, 18, false, ActionKind.CAST,
            ActionLabel.of("Ice Burst"));
        inventory.put(dragonBones, 4L);
        Transaction buried = settleStable(engine, inventory, now);
        assertNotNull(buried);
        assertEquals("a later unresolved Cast click cannot rewrite the buried verb",
            "Buried", Semantics.verbOf(buried));
        assertEquals("buried bones stay a supply cost, not a loss",
            CostKind.SUPPLIES, CostKind.of(buried, buried.flows().get(0)));
        assertNull("a Cast click that did not cast cannot name the spell",
            buried.actionLabel());
    }

    @Test
    public void hardTransferEvidenceWinsOverStaleSellPlacement()
    {
        Engine engine = engine();
        long now = T0;
        engine.startCustom("Trading", now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new ContainerSnapshot(inventory));

        OfferSlots ledger = new OfferSlots();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        inventory.put(ItemID.NATURERUNE, 4L);

        Transaction deposit = settleStable(engine, inventory, now);
        assertNotNull(deposit);
        assertEquals("hard transfer evidence keeps its own delta", TransactionType.TRANSFER, deposit.type());
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void noteGe(
        Engine engine,
        OfferSlots ledger,
        int slot,
        GrandExchangeOfferState state,
        int itemId,
        int totalQuantity,
        int quantityTraded,
        int price,
        int spent,
        long at)
    {
        OfferSlots.Transition transition = ledger.observe(
            new OfferSlots.Snapshot(slot, state, itemId, totalQuantity, quantityTraded, price, spent))
            .orElse(null);
        if (transition != null)
        {
            engine.noteGeOffer(transition, name(itemId), at);
        }
    }

    /** Production stabilization: a dirty change commits only after the configured quiet ticks. */
    private static Transaction settleStable(Engine engine, Map<Integer, Long> next, long now)
    {
        engine.markDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(next);
        Transaction result = null;
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(snapshot, now + i * 600L, null);
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
