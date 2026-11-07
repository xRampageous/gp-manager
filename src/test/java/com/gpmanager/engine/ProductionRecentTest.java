package com.gpmanager;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.*;

/** Owner 2026-11-04: no-XP dough, pizza and a cold ore-on-furnace start. */
public class ProductionRecentTest
{
    static final long T0 = 1_700_000_000_000L;
    static final Map<Integer, String> NAMES = Map.ofEntries(
        Map.entry(1933, "Pot of flour"), Map.entry(1937, "Jug of water"), Map.entry(1935, "Jug"),
        Map.entry(1931, "Pot"), Map.entry(2307, "Bread dough"), Map.entry(2309, "Bread"),
        Map.entry(2311, "Burnt bread"), Map.entry(2283, "Pizza base"), Map.entry(1982, "Tomato"),
        Map.entry(2285, "Incomplete pizza"), Map.entry(1985, "Cheese"), Map.entry(2287, "Uncooked pizza"),
        Map.entry(2289, "Plain pizza"), Map.entry(2305, "Burnt pizza"), Map.entry(319, "Anchovies"),
        Map.entry(2297, "Anchovy pizza"), Map.entry(436, "Copper ore"), Map.entry(438, "Tin ore"),
        Map.entry(2349, "Bronze bar"), Map.entry(1953, "Pastry dough"), Map.entry(1863, "Pitta dough"),
        Map.entry(1865, "Pitta bread"), Map.entry(1867, "Burnt pitta bread"), Map.entry(1929, "Bucket of water"),
        Map.entry(1925, "Bucket"), Map.entry(1921, "Bowl of water"), Map.entry(1923, "Bowl"),
        Map.entry(2142, "Cooked meat"), Map.entry(2293, "Meat pizza"), Map.entry(2116, "Pineapple chunks"),
        Map.entry(2301, "Pineapple pizza"));

    static class Fixture
    {
        final Engine engine;
        final GpManagerPluginProbe plugin = new GpManagerPluginProbe();
        long at = T0;
        Fixture(Map<Integer, Long> before)
        {
            JsonCodec.bind(new Gson());
            engine = new Engine(deltas -> {
                List<Flow> flows = new ArrayList<>();
                deltas.forEach((id, qty) -> flows.add(new Flow(id, NAMES.getOrDefault(id, "Other"),
                    qty, price(id), qty * price(id), PriceSource.GRAND_EXCHANGE)));
                return flows;
            }, new TransactionClassifier(), new GpManagerConfig() {
                @Override public int stabilizationTicks() { return 0; }
            });
            engine.ensureSession(at);
            engine.setBaseline(new ContainerSnapshot(new HashMap<>(before)));
            plugin.client = LootingBagMovementTest.proxy(Client.class, (method, args) -> {
                if (method.equals("getGameState")) return GameState.LOGGED_IN;
                if (method.equals("getTickCount")) return 100;
                return null;
            });
            plugin.engine = engine;
            plugin.config = new GpManagerConfig() {};
            plugin.containers = new ContainerSnapshotFactory(plugin.client, null);
            plugin.targets = GpManagerPluginProbe.tracker(plugin.client, engine, plugin.config);
            plugin.charges = new ChargeIntake(plugin.client, null, plugin.config, engine, null);
            plugin.activity = new ActivityDetector(plugin.config, engine);
            plugin.idle = new CharacterIdleTracker(null, new CharacterIdleModel());
        }
        void use(boolean furnace)
        {
            plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Use",
                furnace ? MenuAction.WIDGET_TARGET_ON_GAME_OBJECT : MenuAction.WIDGET_TARGET_ON_WIDGET,
                null, furnace ? "Copper ore -> Furnace" : "Pot of flour -> Jug of water"));
        }
        Transaction become(Map<Integer, Long> after)
        {
            engine.markDirty();
            Transaction result = null;
            for (int i = 0; i < 3; i++)
            {
                Transaction row = engine.processIfDirty(new ContainerSnapshot(new HashMap<>(after)), at += 600, null);
                if (row != null) result = row;
            }
            return result;
        }
        LiveSnapshot live() { return LiveSnapshot.capture(engine, at + 1, LiveContext.NONE); }
    }

    static int price(int id) { return id % 97 + 1; }
    static long net(Map<Integer, Long> before, Map<Integer, Long> after)
    {
        return after.entrySet().stream().mapToLong(e -> e.getValue() * price(e.getKey())).sum()
            - before.entrySet().stream().mapToLong(e -> e.getValue() * price(e.getKey())).sum();
    }

    @Test public void noXpDoughIsOneNetRowWithEveryIngredientInDetails()
    {
        Map<Integer, Long> before = Map.of(1933, 1L, 1937, 1L);
        Map<Integer, Long> after = Map.of(2307, 1L, 1931, 1L, 1935, 1L);
        Fixture f = new Fixture(before);
        f.use(false);
        Transaction made = f.become(after);
        assertEquals(TransactionType.PROCESSING, made.type());
        assertFalse(ReviewRules.needsReview(made));
        assertEquals("Cooking", made.activityName());
        LiveSnapshot live = f.live();
        assertEquals(1, live.recent.size());
        LiveSnapshot.Recent row = live.recent.get(0);
        assertEquals("Bread dough", row.name);
        assertEquals(ItemID.BREAD_DOUGH, row.item);
        assertEquals(net(before, after), row.value);
        assertEquals(row.value, live.net);
        assertEquals(1, row.receipts.size());
        LedgerData data = LedgerData.capture(f.engine, f.at + 1, LivePage.entryFor(row));
        Semantics.Group group = data.detail.group;
        assertNotNull(group);
        assertEquals(5, group.entries.size());
        assertTrue(group.entries.stream().anyMatch(e -> e.name.equals("Pot of flour")));
        assertTrue(group.entries.stream().anyMatch(e -> e.name.equals("Jug of water")));
        assertEquals("a read model cannot rewrite the receipt", 5, made.flows().size());
    }

    @Test public void pizzaStagesAndToppingsEachHaveOneProductionRow()
    {
        for (int[] recipe : new int[][] {{2283,1982,2285}, {2285,1985,2287},
            {2289,319,2297}, {2289,2142,2293}, {2289,2116,2301}})
        {
            Fixture f = new Fixture(Map.of(recipe[0], 1L, recipe[1], 1L));
            f.use(false);
            Transaction made = f.become(Map.of(recipe[2], 1L));
            assertEquals(NAMES.get(recipe[2]), TransactionType.PROCESSING, made.type());
            assertEquals(1, f.live().recent.size());
            assertEquals(NAMES.get(recipe[2]), f.live().recent.get(0).name);
            assertEquals(net(Map.of(recipe[0], 1L, recipe[1], 1L), Map.of(recipe[2], 1L)),
                f.live().recent.get(0).value);
        }
    }

    @Test public void allFourDoughsKeepTheirReturnedContainers()
    {
        for (int product : new int[] {2307, 1953, 2283, 1863})
            for (int[] vessel : new int[][] {{1937,1935},{1929,1925},{1921,1923}})
            {
                Fixture f = new Fixture(Map.of(1933, 2L, vessel[0], 2L));
                f.use(false);
                f.become(Map.of(product, 2L, 1931, 2L, vessel[1], 2L));
                assertEquals(NAMES.get(product), 1, f.live().recent.size());
                assertEquals(NAMES.get(product), f.live().recent.get(0).name);
                assertEquals(2, f.live().recent.get(0).quantity);
            }
    }

    @Test public void cookingAndBurningDoughOrPizzaGroupTheirInputs()
    {
        for (int[] recipe : new int[][] {{2307,2309},{2307,2311},{2287,2289},
            {2287,2305},{1863,1865},{1863,1867}})
        {
            Fixture f = new Fixture(Map.of(recipe[0], 2L));
            f.engine.markContext(Context.PRODUCTION, 6, "Cooking");
            Transaction made = f.become(Map.of(recipe[1], 2L));
            assertEquals(ActionReader.resolve(null, made.flows(), made.type(), made.activityName()), made.action());
            assertEquals(1, f.live().recent.size());
            assertEquals(NAMES.get(recipe[1]), f.live().recent.get(0).name);
            assertEquals(2, f.live().recent.get(0).quantity);
        }
    }

    @Test public void repeatedDoughGroupsAndSurvivesReloadWithoutChangingNet()
    {
        Fixture f = new Fixture(Map.of(1933, 2L, 1937, 2L));
        f.use(false);
        f.become(Map.of(1933, 1L, 1937, 1L, 2307, 1L, 1931, 1L, 1935, 1L));
        f.become(Map.of(2307, 2L, 1931, 2L, 1935, 2L));
        LiveSnapshot live = f.live();
        assertEquals(1, live.recent.size());
        assertEquals(2, live.recent.get(0).entries);
        assertEquals(2, live.recent.get(0).quantity);
        assertEquals("\u00d72", live.recent.get(0).qty);
        assertEquals(2, live.recent.get(0).receipts.size());
        Session restored = new Gson().fromJson(new Gson().toJson(f.engine.session()), Session.class);
        assertEquals(f.engine.session().transactions().size(), restored.transactions().size());
        assertEquals(f.engine.session().metrics(f.at).income, restored.metrics(f.at).income);
        assertEquals(f.engine.session().metrics(f.at).cost, restored.metrics(f.at).cost);
    }

    @Test public void coldOreOnFurnaceSmeltsBronzeWithoutReview()
    {
        Fixture f = new Fixture(Map.of(436, 1L, 438, 1L));
        f.use(true);
        Transaction made = f.become(Map.of(2349, 1L));
        assertEquals(TransactionType.PROCESSING, made.type());
        assertEquals("Smithing", made.activityName());
        assertFalse(ReviewRules.needsReview(made));
        assertEquals(net(Map.of(436, 1L, 438, 1L), Map.of(2349, 1L)), f.live().net);
    }

    @Test public void absentOrExpiredUseDoesNotInferProduction()
    {
        for (boolean expired : new boolean[] {false, true})
        {
            Fixture f = new Fixture(Map.of(1933, 1L, 1937, 1L));
            if (expired) { f.use(false); f.engine.events.tick(200); }
            assertEquals(TransactionType.UNCERTAIN,
                f.become(Map.of(2307, 1L, 1931, 1L, 1935, 1L)).type());
        }
    }

    @Test public void partialWrongRatioExtraAndReverseRecipesStayReview()
    {
        for (Map<Integer, Long> after : List.of(Map.of(1937,1L,2307,1L),
            Map.of(2307,2L,1931,1L,1935,1L), Map.of(2307,1L,1931,1L,1935,1L,560,1L)))
        {
            Fixture f = new Fixture(Map.of(1933,1L,1937,1L)); f.use(false);
            assertEquals(TransactionType.UNCERTAIN, f.become(after).type());
        }
        Fixture reverse = new Fixture(Map.of(2307,1L,1931,1L,1935,1L)); reverse.use(false);
        assertEquals(TransactionType.UNCERTAIN, reverse.become(Map.of(1933,1L,1937,1L)).type());
    }

    @Test public void strongerTransferAndLootEvidenceKeepsOwnership()
    {
        for (Context context : new Context[] {Context.TRANSFER, Context.LOOT, Context.MARKET})
        {
            Fixture f = new Fixture(Map.of(1933,1L,1937,1L)); f.use(false);
            f.engine.markContext(context, 6, context == Context.MARKET ? "Grand Exchange" : "fixture");
            Transaction result = f.become(Map.of(2307,1L,1931,1L,1935,1L));
            assertNotEquals(context.name(), TransactionType.PROCESSING, result.type());
        }
    }

    @Test public void productionEvidenceCanArriveOnEitherSideOfTheFirstInventoryCallback()
    {
        for (boolean inventoryFirst : new boolean[] {false, true})
        {
            Fixture f = new Fixture(Map.of(1933,1L,1937,1L,946,1L));
            Map<Integer,Long> after=Map.of(2307,1L,1931,1L,1935,1L,946,1L);
            if (inventoryFirst)
            {
                f.engine.markDirty();
                assertNull(f.engine.processIfDirty(new ContainerSnapshot(after),f.at+=600,null));
            }
            f.use(false);
            Transaction result=f.become(after);
            assertEquals(TransactionType.PROCESSING,result.type());
            assertEquals(1,f.live().recent.size());
            assertTrue("retained knife is no ingredient",result.flows().stream().noneMatch(flow->flow.item==946));
        }
    }

    @Test public void explicitConsumeKeepsItsOwnReceiptEvenBesideARecipe()
    {
        Fixture f = new Fixture(Map.of(2289,1L,319,1L));
        f.use(false);
        f.engine.noteConsume(2289,6,false,ActionKind.EAT,null);
        Transaction result=f.become(Map.of(2297,1L));
        assertEquals(TransactionType.CONSUMPTION,result.type());
        assertTrue(f.live().recent.size()>1);
    }

    @Test public void preparationCannotCrossTrackingProfileOrOwnerFences()
    {
        for(String boundary:List.of("tracking","priming","action","owner"))
        {
            Map<Integer,Long> before=Map.of(1933,1L,1937,1L);
            Fixture f=new Fixture(before);
            f.use(false);
            switch(boundary)
            {
                case "tracking": f.engine.resetTracking(false); break;
                case "priming": f.engine.beginPriming(); break;
                case "action": f.engine.clearActionEvidence(); break;
                default: f.engine.events.owner(1);
            }
            f.engine.setBaseline(new ContainerSnapshot(before));
            assertEquals(boundary,TransactionType.UNCERTAIN,
                f.become(Map.of(2307,1L,1931,1L,1935,1L)).type());
        }
    }
}
