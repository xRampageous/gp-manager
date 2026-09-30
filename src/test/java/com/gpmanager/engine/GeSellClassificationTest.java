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
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L);
        for (int rune : RUNES)
        {
            inventory.put(rune, 5L);
        }
        engine.setBaseline(new Cc(inventory));

        Bj ledger = new Bj();
        for (int i = 0; i < RUNES.length; i++)
        {
            int rune = RUNES[i];
            noteGe(engine, ledger, i, SELLING, rune, 5, 0, 200, 0, now);
            inventory.put(rune, inventory.get(rune) - 1L);
            Ac sale = settleStable(engine, inventory, now);
            assertNotNull("sale settles: " + name(rune), sale);
            assertEquals("a proven GE sale placement is ownership-neutral custody: " + name(rune),
                Ai.TRANSFER, sale.getType());
            assertEquals(Aj.TRANSFER, sale.getContext());
            assertFalse("custody is never counted money", sale.isCounted());
            assertNotEquals("a sold rune is never stamped as a cast: " + name(rune),
                Au.CAST, sale.getActionKind());
            assertEquals("the observed sold value is untouched: " + name(rune),
                -200L, sale.getFlows().get(0).valueDelta);
            now += 10_000L;
        }
        assertEquals("pending custody never changes the observed Net",
            0L, engine.getMetrics(now).net);
        assertEquals("no placement fell through to CAST", 0,
            engine.getActiveSession().getTransactions().stream()
                .filter(t -> t != null && Au.CAST == t.getActionKind()).count());
    }

    @Test
    public void coalescedRuneLossesSettleOnceAsNeutralCustody()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L, ItemID.FIRERUNE, 5L);
        engine.setBaseline(new Cc(inventory));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        noteGe(engine, ledger, 1, SELLING, ItemID.FIRERUNE, 5, 0, 100, 0, now);
        inventory.put(ItemID.NATURERUNE, 4L);
        inventory.put(ItemID.FIRERUNE, 4L);

        Ac sale = settleStable(engine, inventory, now);
        assertNotNull(sale);
        assertEquals(Ai.TRANSFER, sale.getType());
        assertFalse(sale.isCounted());
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertTrue(sale.getFlows().size() >= 2);
        assertEquals(0L, engine.getMetrics(now).net);
    }

    @Test
    public void offerEvidenceDuringTheDirtyWindowOwnsTheLossAsCustody()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new Cc(inventory));

        // The inventory loss goes dirty first; the sell placement evidence arrives before the
        // stable settlement commits.
        inventory.put(ItemID.NATURERUNE, 4L);
        Cc snapshot = new Cc(inventory);
        engine.yz();
        engine.adj(snapshot, now);

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now + 100L);

        Ac settled = engine.adj(snapshot, now + 600L);
        Ac finalSettle = engine.adj(snapshot, now + 1_200L);
        Ac sale = finalSettle == null ? settled : finalSettle;
        assertNotNull(sale);
        assertEquals(Ai.TRANSFER, sale.getType());
        assertEquals(Aj.TRANSFER, sale.getContext());
        assertFalse(sale.isCounted());
    }

    @Test
    public void claimedSellPlacementCannotOwnALaterUnrelatedLoss()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new Cc(inventory));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        inventory.put(ItemID.NATURERUNE, 4L);
        Ac sale = settleStable(engine, inventory, now);
        assertNotNull(sale);
        assertEquals("the placement principal is custody", Ai.TRANSFER, sale.getType());
        assertFalse(sale.isCounted());

        // A later unrelated same-item loss inside the placement window must not reuse the
        // already-captured placement evidence.
        inventory.put(ItemID.NATURERUNE, 3L);
        Ac later = settleStable(engine, inventory, now + 5_000L);
        assertNotNull(later);
        assertEquals("a spent placement never owns a later unrelated loss",
            Ai.CONSUMPTION, later.getType());
    }

    @Test
    public void stalePlacementEvidenceNeverStealsAnExplicitCast()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new Cc(inventory));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        engine.noteConsumptionIntent(ItemID.NATURERUNE, 18, false, Au.CAST);
        inventory.put(ItemID.NATURERUNE, 4L);

        Ac cast = settleStable(engine, inventory, now);
        assertNotNull(cast);
        assertEquals(Ai.CONSUMPTION, cast.getType());
        assertEquals("Cast", Br.verbOf(cast));
    }

    @Test
    public void soldRunePlacementIsNeutralCustodyAndCastCostIsSupplies()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new Cc(inventory));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        inventory.put(ItemID.NATURERUNE, 4L);
        Ac sale = settleStable(engine, inventory, now);
        assertNotNull(sale);
        assertEquals("a sold rune placement is ownership-neutral custody, never a cost",
            CostKind.NONE, CostKind.of(sale, sale.getFlows().get(0)));

        engine.noteConsumptionIntent(ItemID.NATURERUNE, 18, false, Au.CAST);
        inventory.put(ItemID.NATURERUNE, 3L);
        Ac cast = settleStable(engine, inventory, now + 10_000L);
        assertNotNull(cast);
        assertEquals(Ai.CONSUMPTION, cast.getType());
        assertEquals("a genuine cast stays a supply cost",
            CostKind.SUPPLIES, CostKind.of(cast, cast.getFlows().get(0)));
        assertEquals("only the cast remains counted", -200L, engine.getMetrics(now + 10_000L).net);
    }

    @Test
    public void lateOfferEvidenceNeverRewritesABookedSettle()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new Cc(inventory));

        // The loss settles before any evidence exists: book once, conservatively.
        inventory.put(ItemID.NATURERUNE, 4L);
        Ac settled = settleStable(engine, inventory, now);
        assertNotNull(settled);
        assertEquals(Ai.CONSUMPTION, settled.getType());
        assertEquals(1, engine.getActiveSession().getTransactions().size());

        // Evidence arriving afterwards must not rewrite the booked row (no retroactive
        // reconciliation), and a later cast must still be a cast.
        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now + 1_000L);
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertEquals(Ai.CONSUMPTION, engine.getActiveSession().getTransactions().get(0).getType());

        engine.noteConsumptionIntent(ItemID.NATURERUNE, 18, false, Au.CAST);
        inventory.put(ItemID.NATURERUNE, 3L);
        Ac cast = settleStable(engine, inventory, now + 10_000L);
        assertNotNull(cast);
        assertEquals(Ai.CONSUMPTION, cast.getType());
        assertEquals("Cast", Br.verbOf(cast));
    }

    @Test
    public void exactSpellNameAttachesOnlyToItsMatchedConsumptionIntent()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Vorkath", Cx.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new Cc(inventory));

        int spellbookGroup = net.runelite.api.widgets.WidgetID.SPELLBOOK_GROUP_ID;
        Bb iceBurst = Bb.tv("Cast",
            spellbookGroup << 16 | 14, spellbookGroup, "<col=ff9040>Ice Burst</col>", "Cast");
        assertNotNull(iceBurst);
        engine.noteConsumptionIntent(ItemID.NATURERUNE, 18, false, Au.CAST, iceBurst);
        inventory.put(ItemID.NATURERUNE, 4L);
        Ac cast = settleStable(engine, inventory, now);
        assertNotNull(cast);
        assertEquals("Ice Burst", cast.uc().value());

        inventory.put(ItemID.NATURERUNE, 3L);
        Ac laterLoss = settleStable(engine, inventory, now + 5_000L);
        assertNotNull(laterLoss);
        assertEquals("one matched intent cannot label later rune loss", null,
            laterLoss.uc());
    }

    @Test
    public void unresolvedCastClickCannotRewriteAnArmedSupplyVerb()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Vorkath", Cx.AUTO, now);
        int dragonBones = 536;
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, dragonBones, 5L);
        engine.setBaseline(new Cc(inventory));

        engine.noteConsumptionIntent(dragonBones, 18, false, Au.BURY, null);
        // A spellbook Cast click can resolve no item id. The open intent must keep the
        // resolved item's proven verb; the exact spell name is a hint, not a re-verb.
        engine.noteConsumptionIntent(-1, 18, false, Au.CAST,
            Bb.of("Ice Burst"));
        inventory.put(dragonBones, 4L);
        Ac buried = settleStable(engine, inventory, now);
        assertNotNull(buried);
        assertEquals("a later unresolved Cast click cannot rewrite the buried verb",
            "Buried", Br.verbOf(buried));
        assertEquals("buried bones stay a supply cost, not a loss",
            CostKind.SUPPLIES, CostKind.of(buried, buried.getFlows().get(0)));
        assertNull("a Cast click that did not cast cannot name the spell",
            buried.uc());
    }

    @Test
    public void hardTransferEvidenceWinsOverStaleSellPlacement()
    {
        Am engine = engine();
        long now = T0;
        engine.ajl("Trading", Cx.AUTO, now);
        Map<Integer, Long> inventory = inventory(ItemID.COINS, 100_000L, ItemID.NATURERUNE, 5L);
        engine.setBaseline(new Cc(inventory));

        Bj ledger = new Bj();
        noteGe(engine, ledger, 0, SELLING, ItemID.NATURERUNE, 5, 0, 200, 0, now);
        engine.markContext(Aj.TRANSFER, 6, "Bank container transfer");
        inventory.put(ItemID.NATURERUNE, 4L);

        Ac deposit = settleStable(engine, inventory, now);
        assertNotNull(deposit);
        assertEquals("hard transfer evidence keeps its own delta", Ai.TRANSFER, deposit.getType());
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void noteGe(
        Am engine,
        Bj ledger,
        int slot,
        GrandExchangeOfferState state,
        int itemId,
        int totalQuantity,
        int quantityTraded,
        int price,
        int spent,
        long at)
    {
        Bj.Transition transition = ledger.observe(
            new Bj.Snapshot(slot, state, itemId, totalQuantity, quantityTraded, price, spent))
            .orElse(null);
        if (transition != null)
        {
            engine.abh(transition, name(itemId), at);
        }
    }

    /** Production stabilization: a dirty change commits only after the configured quiet ticks. */
    private static Ac settleStable(Am engine, Map<Integer, Long> next, long now)
    {
        engine.yz();
        Cc snapshot = new Cc(next);
        Ac result = null;
        for (int i = 0; i < 3; i++)
        {
            Ac settled = engine.adj(snapshot, now + i * 600L);
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

    private static Am engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 2; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                flows.add(new Ab(id, name(id), delta.getValue(), price(id), delta.getValue() * price(id),
                    id == ItemID.COINS ? Av.FACE_VALUE : Av.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
