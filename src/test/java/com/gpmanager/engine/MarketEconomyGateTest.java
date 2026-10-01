package com.gpmanager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.EnumSet;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * PRE-R5C.2C.2 world/economy gate: ordinary main-game modifiers keep automatic RuneLite market
 * quotes; special economies and an unknown world fail closed without Review floods, with at most
 * one restrained current-scope notice per surface.
 */
public class MarketEconomyGateTest
{
    private static final long T0 = 1_700_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    // ── decision table ─────────────────────────────────────────────────────────────────────────

    @Test
    public void ordinaryMainGameModifiersAllowAutomaticQuotes()
    {
        assertState(ItemValuationService.EconomyState.NORMAL);
        assertState(ItemValuationService.EconomyState.NORMAL, WorldType.MEMBERS);
        assertState(ItemValuationService.EconomyState.NORMAL, WorldType.PVP);
        assertState(ItemValuationService.EconomyState.NORMAL, WorldType.HIGH_RISK);
        assertState(ItemValuationService.EconomyState.NORMAL, WorldType.BOUNTY);
        assertState(ItemValuationService.EconomyState.NORMAL, WorldType.SKILL_TOTAL);
        assertState(ItemValuationService.EconomyState.NORMAL, WorldType.MEMBERS, WorldType.PVP,
            WorldType.HIGH_RISK, WorldType.BOUNTY, WorldType.SKILL_TOTAL);
        // Owner 2026-09-28: LMS, PvP Arena and every other main-game world share the Grand Exchange.
        for (WorldType shared : new WorldType[] {WorldType.LAST_MAN_STANDING, WorldType.PVP_ARENA,
            WorldType.FRESH_START_WORLD, WorldType.LEGACY_ONLY, WorldType.EOC_ONLY})
        {
            assertState(ItemValuationService.EconomyState.NORMAL, shared);
        }
    }

    @Test
    public void specialEconomiesFailClosed()
    {
        for (WorldType blocked : new WorldType[] {
            WorldType.DEADMAN, WorldType.SEASONAL, WorldType.QUEST_SPEEDRUNNING,
            WorldType.BETA_WORLD, WorldType.NOSAVE_MODE, WorldType.TOURNAMENT_WORLD})
        {
            assertState(ItemValuationService.EconomyState.UNSUPPORTED_SPECIAL, blocked);
        }
        assertState(ItemValuationService.EconomyState.UNSUPPORTED_SPECIAL,
            WorldType.MEMBERS, WorldType.DEADMAN);
    }

    @Test
    public void unknownEconomyFailsClosed()
    {
        assertEquals(ItemValuationService.EconomyState.UNKNOWN,
            ItemValuationService.economyStateFor(null));
        assertEquals(ItemValuationService.EconomyState.UNKNOWN,
            ItemValuationService.economyStateFor(Collections.singletonList(null)));
    }

    // ── the gate on real flows ─────────────────────────────────────────────────────────────────

    @Test
    public void unsupportedWorldLeavesItemsUnpricedWithoutReviewFlood() throws Exception
    {
        Seam seam = new Seam();
        ItemValuationService service = new ItemValuationService(new GpManagerConfig() {}, id -> null,
            (id, active) -> seam.quote, id -> false,
            ItemValuationService.EconomyState.UNSUPPORTED_SPECIAL);
        Engine engine = new Engine(service, new TransactionClassifier(), config());
        engine.ensureSession(T0);

        for (int i = 0; i < 100; i++)
        {
            int itemId = 2_000 + i;
            Flow flow = service.value(Collections.singletonMap(itemId, 1L), T0 + i).get(0);
            assertEquals("special economies keep unknown honest, never zero truth",
                PriceSource.UNPRICED, flow.getPriceSource());
            assertEquals(0L, flow.valueDelta);
            engine.getActiveSession().addTransaction(new Transaction(T0 + i, null,
                TransactionType.GAIN, Context.GENERIC, "", "Item " + itemId, true,
                Collections.singletonList(flow), ClassificationConfidence.CONFIRMED, "", null), 200);
        }

        assertTrue("the gate never queried the automatic market", seam.asked.isEmpty());
        assertEquals("100 unpriced flows are not 100 Review decisions",
            0, engine.getReviewRows(T0 + 200L).size());
        assertEquals(0L, engine.getMetrics(T0 + 200L).net);

        assertEquals(0, LiveSnapshot.capture(engine, T0 + 200L, null).reviewCount);
    }

    @Test
    public void unsupportedWorldStillAllowsManualOverrideAndFaceValue()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public String manualPriceOverrides()
            {
                return "560=150";
            }
        };
        ItemValuationService service = new ItemValuationService(config, id -> null,
            (id, active) -> 500, id -> false,
            ItemValuationService.EconomyState.UNSUPPORTED_SPECIAL);

        Flow overridden = service.value(Collections.singletonMap(560, 2L), T0).get(0);
        assertEquals(PriceSource.MANUAL_OVERRIDE, overridden.getPriceSource());
        assertEquals(300L, overridden.valueDelta);

        Flow coins = service.value(Collections.singletonMap(995, 5L), T0).get(0);
        assertEquals(PriceSource.FACE_VALUE, coins.getPriceSource());
        assertEquals(5L, coins.valueDelta);

        Flow ordinary = service.value(Collections.singletonMap(561, 1L), T0).get(0);
        assertEquals(PriceSource.UNPRICED, ordinary.getPriceSource());
        assertEquals("unknown on a special world is not zero truth", 0L, ordinary.valueDelta);
        assertFalse(service.automaticMarketQuotesAvailable());
    }

    @Test
    public void unknownEconomyAlsoFailsClosedUntilTheWorldIsKnown()
    {
        ItemValuationService service = new ItemValuationService(new GpManagerConfig() {}, id -> null,
            (id, active) -> 100, id -> false, ItemValuationService.EconomyState.UNKNOWN);
        Engine engine = new Engine(service, new TransactionClassifier(), config());
        engine.ensureSession(T0);
        assertEquals(PriceSource.UNPRICED,
            service.value(Collections.singletonMap(560, 1L), T0).get(0).getPriceSource());

        service.setEconomyState(ItemValuationService.EconomyState.NORMAL);
        assertTrue(service.automaticMarketQuotesAvailable());
        assertEquals(PriceSource.GRAND_EXCHANGE,
            service.value(Collections.singletonMap(560, 1L), T0).get(0).getPriceSource());
    }

    // ── plugin lifecycle seam ──────────────────────────────────────────────────────────────────

    @Test
    public void pluginRefreshSeamTracksLoginWorldHopAndLogout() throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPlugin();
        ItemValuationService service = new ItemValuationService(new GpManagerConfig() {}, id -> null,
            (id, active) -> 100, id -> false, ItemValuationService.EconomyState.UNKNOWN);
        set(plugin, "valuation", service);

        set(plugin, "client", client(GameState.LOGGED_IN, EnumSet.of(WorldType.DEADMAN)));
        refresh(plugin);
        assertEquals(ItemValuationService.EconomyState.UNSUPPORTED_SPECIAL, service.getEconomyState());

        set(plugin, "client", client(GameState.LOGGED_IN, EnumSet.of(WorldType.MEMBERS)));
        refresh(plugin);
        assertEquals(ItemValuationService.EconomyState.NORMAL, service.getEconomyState());

        set(plugin, "client", client(GameState.LOGIN_SCREEN, EnumSet.noneOf(WorldType.class)));
        refresh(plugin);
        assertEquals("logout clears the snapshot to UNKNOWN", ItemValuationService.EconomyState.UNKNOWN,
            service.getEconomyState());

        set(plugin, "client", client(GameState.HOPPING, EnumSet.of(WorldType.DEADMAN)));
        refresh(plugin);
        assertEquals("a hop has no world identity until login",
            ItemValuationService.EconomyState.UNKNOWN, service.getEconomyState());
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static final class Seam
    {
        final java.util.List<Integer> asked = new java.util.ArrayList<>();
        int quote = 100;
    }

    private static void assertState(ItemValuationService.EconomyState expected, WorldType... types)
    {
        assertEquals(expected, ItemValuationService.economyStateFor(
            types.length == 0 ? EnumSet.noneOf(WorldType.class) : EnumSet.copyOf(
                java.util.Arrays.asList(types))));
    }

    private static GpManagerConfig config()
    {
        return new GpManagerConfig()
        {
            @Override public int stabilizationTicks()
            {
                return 0;
            }
        };
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void refresh(GpManagerPlugin plugin) throws Exception
    {
        Method method = GpManagerPlugin.class.getDeclaredMethod("refreshEconomyState");
        method.setAccessible(true);
        method.invoke(plugin);
    }

    private static Client client(GameState state, EnumSet<WorldType> types)
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[] {Client.class}, (proxy, method, args) ->
            {
                if ("getGameState".equals(method.getName()))
                {
                    return state;
                }
                if ("getWorldType".equals(method.getName()))
                {
                    return types;
                }
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == byte.class) return (byte) 0;
                if (type == short.class) return (short) 0;
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                if (type == float.class) return 0F;
                if (type == double.class) return 0D;
                if (type == char.class) return '\0';
                return null;
            });
    }
}
