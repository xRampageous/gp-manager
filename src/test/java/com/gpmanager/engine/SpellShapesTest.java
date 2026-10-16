package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ItemComposition;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Owner live 2026-10-16: High Level Alchemy and a broken Bones to peaches tablet booked as plain
 * changes, not spells, and a Progress hat or a peach (untradeable, no alch value) showed "?".
 */
public class SpellShapesTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final int NATURE = 561, STEAM = 4694, PLATESKIRT = 1093, COINS = 995;
    private static final int TABLET = 8015, BONES = 526, PEACH = 6883, HAT = 6885, DART = 811, SHARD = 23962;
    private static final Map<Integer, String> NAMES = Map.of(NATURE, "Nature rune", STEAM, "Steam rune",
        PLATESKIRT, "Rune plateskirt", COINS, "Coins", TABLET, "Bones to peaches", BONES, "Bones",
        PEACH, "Peach", HAT, "Progress hat", DART, "Rune dart", SHARD, "Crystal shard");

    @Test
    public void highAlchemyBooksAsItsSpellWithTheCoinsItMade()
    {
        Transaction alch = settle(Map.of(NATURE, 10L, STEAM, 50L, PLATESKIRT, 1L, COINS, 0L),
            Map.of(NATURE, 9L, STEAM, 45L, PLATESKIRT, 0L, COINS, 38_400L),
            PLATESKIRT, ActionKind.CAST, ActionLabel.of("High Level Alchemy"));
        assertSame(ActionKind.CAST, alch.getActionKind());
        assertEquals("High Level Alchemy", alch.spellName());
        assertEquals("the coins stay on the cast", 4, alch.getFlows().size());
    }

    @Test
    public void aBrokenTabletBooksAsTheSpellItHolds()
    {
        assertEquals("the tablet's name spells its spell", "Bones to Peaches", Spells.named("bones to peaches"));
        assertSame("Break is a cast", ActionKind.CAST, ActionReader.verbOf("break"));
        Transaction tablet = settle(Map.of(TABLET, 5L, BONES, 3L, PEACH, 0L),
            Map.of(TABLET, 4L, BONES, 0L, PEACH, 3L),
            TABLET, ActionReader.verbOf("break"), ActionLabel.of(Spells.named("bones to peaches")));
        assertSame(ActionKind.CAST, tablet.getActionKind());
        assertEquals("Bones to Peaches", tablet.spellName());
    }

    /** Owner live 2026-10-16: an Eat clicked two ticks after a Break left the tablet in Review. */
    @Test
    public void aFastSecondClickNeverTakesTheFirstSpendsEvidence()
    {
        Engine engine = engine();
        engine.startCustom("Magic", T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(TABLET, 5L, BONES, 3L, PEACH, 1L))));
        engine.noteConsume(TABLET, 10, false, ActionReader.verbOf("break"), ActionLabel.of(Spells.named("bones to peaches")));
        Map<Integer, Long> broken = Map.of(TABLET, 4L, BONES, 0L, PEACH, 4L);
        engine.markDirty();
        List<Transaction> booked = new ArrayList<>();
        Transaction first = engine.processIfDirty(new ContainerSnapshot(new HashMap<>(broken)), T0 + 600L, booked::add);
        assertNull("the break is still settling when the Eat is clicked", first);
        engine.noteConsume(PEACH, 10, false, ActionKind.EAT, null);
        for (int i = 1; i < 4; i++)
        {
            Transaction settled = engine.processIfDirty(new ContainerSnapshot(new HashMap<>(broken)), T0 + 600L + i * 600L, booked::add);
            if (settled != null) booked.add(settled);
        }
        Transaction tablet = booked.stream().filter(t -> t.getFlows().stream().anyMatch(f -> f.itemId == TABLET))
            .findFirst().orElse(null);
        assertNotNull("the break booked: " + booked, tablet);
        assertSame("the break is still its spell", ActionKind.CAST, tablet.getActionKind());
        assertEquals("Bones to Peaches", tablet.spellName());

        Map<Integer, Long> eaten = Map.of(TABLET, 4L, BONES, 0L, PEACH, 3L);
        engine.markDirty();
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(new ContainerSnapshot(new HashMap<>(eaten)), T0 + 4_000L + i * 600L, booked::add);
            if (settled != null) booked.add(settled);
        }
        Transaction bite = booked.stream().filter(t -> t.getFlows().size() == 1 && t.getFlows().get(0).itemId == PEACH)
            .findFirst().orElse(null);
        assertNotNull("the bite books on its own: " + booked, bite);
        assertSame("and keeps its own verb", ActionKind.EAT, bite.getActionKind());
    }

    /** Owner live 2026-10-16: a spell with a product is one row named by its spell, at its net. */
    @Test
    public void aSpellWithAProductIsOneRow()
    {
        settle(Map.of(TABLET, 5L, BONES, 3L, PEACH, 0L), Map.of(TABLET, 4L, BONES, 0L, PEACH, 3L),
            TABLET, ActionReader.verbOf("break"), ActionLabel.of(Spells.named("bones to peaches")));
        List<LiveSnapshot.Recent> rows = LiveSnapshot.capture(last, T0 + 5_000L, null).recent;
        assertEquals("one row: " + rows.stream().map(r -> r.name).collect(java.util.stream.Collectors.toList()), 1, rows.size());
        assertEquals("Bones to Peaches", rows.get(0).name);
        assertEquals("the tablet and bones it took, the fruit worth nothing", -400L, rows.get(0).value);

        settle(Map.of(NATURE, 10L, STEAM, 50L, PLATESKIRT, 1L, COINS, 0L),
            Map.of(NATURE, 9L, STEAM, 45L, PLATESKIRT, 0L, COINS, 38_400L),
            PLATESKIRT, ActionKind.CAST, ActionLabel.of("High Level Alchemy"));
        rows = LiveSnapshot.capture(last, T0 + 5_000L, null).recent;
        assertEquals(1, rows.size());
        assertEquals("High Level Alchemy", rows.get(0).name);
        assertEquals("coins less the item and runes", 38_400L - 37_000L - 600L, rows.get(0).value);
    }

    /** Owner live 2026-10-16: rows booked before these rules update on load, no reset needed. */
    @Test
    public void oldRowsTakeTheNewRulesOnceAndOnlyWhenAutomatic()
    {
        Engine engine = engine();
        engine.startCustom("Magic", T0);
        Session session = engine.getActiveSession();
        Transaction ate = old(TransactionType.CONSUMPTION, Confidence.LIKELY, true, "",
            new Flow(PEACH, "Peach", -1L, 0, 0L, PriceSource.UNPRICED));
        Transaction shards = old(TransactionType.GAIN, Confidence.LIKELY, true, "",
            new Flow(SHARD, "Crystal shard", 3L, 0, 0L, PriceSource.UNPRICED));
        Transaction review = old(TransactionType.UNCERTAIN, Confidence.UNCERTAIN, false, "",
            new Flow(TABLET, "Bones to peaches", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE),
            new Flow(BONES, "Bones", -3L, 100, -300L, PriceSource.GRAND_EXCHANGE),
            new Flow(PEACH, "Peach", 3L, 0, 0L, PriceSource.UNPRICED));
        Transaction high = alch(38_400L);
        Transaction low = alch(25_600L);
        Transaction dropped = old(TransactionType.CONSUMPTION, Confidence.LIKELY, true, "Dropped Bones to peaches",
            new Flow(TABLET, "Bones to peaches", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE));
        Transaction decided = old(TransactionType.CONSUMPTION, Confidence.LIKELY, true, "",
            new Flow(TABLET, "Bones to peaches", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE));
        decided.applyCorrection(Correction.IGNORE, T0);
        for (Transaction row : List.of(ate, shards, review, high, low, dropped, decided)) session.addTransaction(row, 2_000);

        engine.recheck(id -> id == PLATESKIRT ? 38_400 : 0);

        assertSame("an old peach reads as worth nothing", PriceSource.NO_VALUE, ate.getFlows().get(0).getPriceSource());
        assertSame("an old untradeable takes its alch value", PriceSource.HIGH_ALCHEMY, shards.getFlows().get(0).getPriceSource());
        assertEquals("and the row's total follows", 18_000L, shards.autoNet());
        assertSame(ActionKind.CAST, review.getActionKind());
        assertEquals("Bones to Peaches", review.spellName());
        assertSame("the tablet leaves Review like a fresh break", TransactionType.CONSUMPTION, review.getType());
        assertTrue(review.isCounted());
        assertTrue(!ReviewRules.needsOwnerDecision(review));
        assertEquals("High Level Alchemy", high.spellName());
        assertEquals("coins under the alch price are a low alch", "Low Level Alchemy", low.spellName());
        assertNull("a dropped tablet is no cast", dropped.getActionKind());
        assertNull("an owner decision is never rewritten", decided.getActionKind());

        long revision = engine.getRevision();
        engine.recheck(id -> id == PLATESKIRT ? 38_400 : 0);
        assertEquals("a second pass changes nothing", revision, engine.getRevision());
    }

    private static Transaction alch(long coins)
    {
        return old(TransactionType.CONSUMPTION, Confidence.LIKELY, true, "",
            new Flow(NATURE, "Nature rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE),
            new Flow(STEAM, "Steam rune", -5L, 100, -500L, PriceSource.GRAND_EXCHANGE),
            new Flow(PLATESKIRT, "Rune plateskirt", -1L, 37_000, -37_000L, PriceSource.GRAND_EXCHANGE),
            new Flow(COINS, "Coins", coins, 1, coins, PriceSource.FACE_VALUE));
    }

    private static Transaction old(TransactionType type, Confidence confidence, boolean counted, String note, Flow... flows)
    {
        return new Transaction(T0, type, Context.GENERIC, note, "Magic", counted, List.of(flows), confidence, "old", null);
    }

    /** Owner live 2026-10-16: 39 rune darts picked up with a tablet break rode the spell's row. */
    @Test
    public void aPickupWithASpellBooksOnItsOwn()
    {
        Engine engine = engine();
        engine.startCustom("Magic", T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(Map.of(TABLET, 5L, BONES, 3L, PEACH, 0L, DART, 0L))));
        engine.noteConsume(TABLET, 10, false, ActionReader.verbOf("break"), ActionLabel.of(Spells.named("bones to peaches")));
        engine.markDirty();
        List<Transaction> booked = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(new ContainerSnapshot(new HashMap<>(
                Map.of(TABLET, 4L, BONES, 0L, PEACH, 3L, DART, 39L))), T0 + 600L + i * 600L, booked::add);
            if (settled != null) booked.add(settled);
        }
        Transaction spell = booked.stream().filter(t -> t.getActionKind() == ActionKind.CAST).findFirst().orElse(null);
        assertNotNull("the break books as its spell: " + booked, spell);
        assertTrue("without the darts", spell.getFlows().stream().noneMatch(f -> f.itemId == DART));
        assertTrue("the darts book as their own gain", booked.stream().anyMatch(t -> t != spell
            && t.getFlows().size() == 1 && t.getFlows().get(0).itemId == DART && t.getActionKind() == null));

        // A row already booked with both (a dev build only) stays as item rows, darts apart.
        Transaction old = old(TransactionType.CONSUMPTION, Confidence.LIKELY, true, "",
            new Flow(TABLET, "Bones to peaches", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE),
            new Flow(BONES, "Bones", -3L, 100, -300L, PriceSource.GRAND_EXCHANGE),
            new Flow(PEACH, "Peach", 3L, 0, 0L, PriceSource.NO_VALUE),
            new Flow(DART, "Rune dart", 39L, 100, 3_900L, PriceSource.GRAND_EXCHANGE));
        assertNull("the recheck never names a tablet row that also picked something up",
            Engine.spellOf(old, id -> 0));
        old.setActionKind(ActionKind.CAST);
        old.setActionLabel(ActionLabel.of("Bones to Peaches"));
        List<String> rows = new ArrayList<>();
        for (Semantics.Group group : Semantics.transactionUnits(old, f -> true)) rows.add(group.primaryName + " " + group.value);
        assertTrue("a gain the spell does not make keeps the rows apart: " + rows,
            rows.contains("Rune dart 3900") && rows.size() == 4);
    }

    /** Owner 2026-10-16: a coins row shows its value only, never "×19,911". */
    @Test
    public void coinRowsShowTheirValueOnly()
    {
        Transaction coins = old(TransactionType.GAIN, Confidence.LIKELY, true, "",
            new Flow(COINS, "Coins", 19_911L, 1, 19_911L, PriceSource.FACE_VALUE));
        assertNull(LiveSnapshot.quantityText(Semantics.transactionUnits(coins, f -> true).get(0)));
    }

    @Test
    public void aCastClickNeverNamesABankMove()
    {
        List<Flow> runes = List.of(new Flow(NATURE, "Nature rune", -5L, 100, -500L));
        assertNull(ActionReader.resolve(ActionKind.CAST, runes, TransactionType.TRANSFER, ""));
        assertSame(ActionKind.CAST, ActionReader.resolve(ActionKind.CAST, runes, TransactionType.CONSUMPTION, ""));
    }

    @Test
    public void anUntradeableWithNoAlchValueIsWorthNothingNotUnpriced()
    {
        Valuation valuation = new Valuation(new GpManagerConfig() { }, SpellShapesTest::composition, id -> 0);
        Flow hat = valuation.valueComponent(HAT, 1L, 0L);
        assertSame("a known zero", PriceSource.NO_VALUE, hat.getPriceSource());
        assertEquals(0, hat.unitPrice);
        assertSame("a tradeable item without a quote waits for prices, alch value or not",
            PriceSource.UNPRICED, valuation.valueComponent(BONES, 1L, 0L).getPriceSource());
        assertEquals("rows worth nothing read as a dash", "—", Fmt.worth(0L));
        assertEquals(Fmt.signed(1_200L), Fmt.worth(1_200L));

        Transaction received = settle(Map.of(HAT, 0L), Map.of(HAT, 1L), -1, null, null);
        assertNotNull("receiving it still books", received);
        assertTrue(received.getFlows().get(0).getPriceSource() == PriceSource.NO_VALUE);
    }

    private static Engine last;

    private static Transaction settle(Map<Integer, Long> before, Map<Integer, Long> after, int clicked,
        ActionKind verb, ActionLabel label)
    {
        Engine engine = last = engine();
        engine.startCustom("Magic", T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(before)));
        if (verb != null) engine.noteConsume(clicked, 10, false, verb, label);
        engine.markDirty();
        List<Transaction> booked = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            Transaction settled = engine.processIfDirty(new ContainerSnapshot(new HashMap<>(after)),
                T0 + 600L + i * 600L, booked::add);
            if (settled != null) booked.add(settled);
        }
        return booked.isEmpty() ? null : booked.get(0);
    }

    private static Engine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 1;
            }
        };
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                boolean worthless = id == PEACH || id == HAT;
                int unit = id == COINS ? 1 : worthless ? 0 : id == PLATESKIRT ? 37_000 : id == SHARD ? 6_000 : 100;
                flows.add(new Flow(id, NAMES.get(id), delta.getValue(), unit, delta.getValue() * unit,
                    worthless ? PriceSource.NO_VALUE : id == COINS ? PriceSource.FACE_VALUE
                        : id == SHARD ? PriceSource.HIGH_ALCHEMY : PriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static ItemComposition composition(int id)
    {
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
            new Class<?>[] {ItemComposition.class}, (proxy, method, args) -> {
                if ("getName".equals(method.getName())) return NAMES.getOrDefault(id, "Item");
                if ("isTradeable".equals(method.getName())) return id != HAT;
                if ("getHaPrice".equals(method.getName())) return id == BONES ? 50 : 0;
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                return null;
            });
    }
}
