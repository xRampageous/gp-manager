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
        assertState(Bl.Bk.NORMAL);
        assertState(Bl.Bk.NORMAL, WorldType.MEMBERS);
        assertState(Bl.Bk.NORMAL, WorldType.PVP);
        assertState(Bl.Bk.NORMAL, WorldType.HIGH_RISK);
        assertState(Bl.Bk.NORMAL, WorldType.BOUNTY);
        assertState(Bl.Bk.NORMAL, WorldType.SKILL_TOTAL);
        assertState(Bl.Bk.NORMAL, WorldType.MEMBERS, WorldType.PVP,
            WorldType.HIGH_RISK, WorldType.BOUNTY, WorldType.SKILL_TOTAL);
        // Owner 2026-09-28: LMS, PvP Arena and every other main-game world share the Grand Exchange.
        for (WorldType shared : new WorldType[] {WorldType.LAST_MAN_STANDING, WorldType.PVP_ARENA,
            WorldType.FRESH_START_WORLD, WorldType.LEGACY_ONLY, WorldType.EOC_ONLY})
        {
            assertState(Bl.Bk.NORMAL, shared);
        }
    }

    @Test
    public void specialEconomiesFailClosed()
    {
        for (WorldType blocked : new WorldType[] {
            WorldType.DEADMAN, WorldType.SEASONAL, WorldType.QUEST_SPEEDRUNNING,
            WorldType.BETA_WORLD, WorldType.NOSAVE_MODE, WorldType.TOURNAMENT_WORLD})
        {
            assertState(Bl.Bk.UNSUPPORTED_SPECIAL, blocked);
        }
        assertState(Bl.Bk.UNSUPPORTED_SPECIAL,
            WorldType.MEMBERS, WorldType.DEADMAN);
    }

    @Test
    public void unknownEconomyFailsClosed()
    {
        assertEquals(Bl.Bk.UNKNOWN,
            Bl.rh(null));
        assertEquals(Bl.Bk.UNKNOWN,
            Bl.rh(Collections.singletonList(null)));
    }

    // ── the gate on real flows ─────────────────────────────────────────────────────────────────

    @Test
    public void unsupportedWorldLeavesItemsUnpricedWithoutReviewFlood() throws Exception
    {
        Seam seam = new Seam();
        Bl service = new Bl(new GpManagerConfig() {}, id -> null,
            (id, active) -> seam.quote, id -> false,
            Bl.Bk.UNSUPPORTED_SPECIAL);
        Am engine = new Am(service, new TransactionClassifier(), config());
        engine.rm(T0);

        for (int i = 0; i < 100; i++)
        {
            int itemId = 2_000 + i;
            Ab flow = service.value(Collections.singletonMap(itemId, 1L), T0 + i).get(0);
            assertEquals("special economies keep unknown honest, never zero truth",
                Av.UNPRICED, flow.getPriceSource());
            assertEquals(0L, flow.valueDelta);
            engine.getActiveSession().kf(new Ac(T0 + i, null,
                Ai.GAIN, Aj.GENERIC, "", "Item " + itemId, true,
                Collections.singletonList(flow), Bd.CONFIRMED, "", null), 200);
        }

        assertTrue("the gate never queried the automatic market", seam.asked.isEmpty());
        assertEquals("100 unpriced flows are not 100 Review decisions",
            0, engine.getReviewRows(T0 + 200L).size());
        assertEquals(0L, engine.getMetrics(T0 + 200L).net);

        assertEquals(0, Ca.capture(engine, T0 + 200L, null).reviewCount);
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
        Bl service = new Bl(config, id -> null,
            (id, active) -> 500, id -> false,
            Bl.Bk.UNSUPPORTED_SPECIAL);

        Ab overridden = service.value(Collections.singletonMap(560, 2L), T0).get(0);
        assertEquals(Av.MANUAL_OVERRIDE, overridden.getPriceSource());
        assertEquals(300L, overridden.valueDelta);

        Ab coins = service.value(Collections.singletonMap(995, 5L), T0).get(0);
        assertEquals(Av.FACE_VALUE, coins.getPriceSource());
        assertEquals(5L, coins.valueDelta);

        Ab ordinary = service.value(Collections.singletonMap(561, 1L), T0).get(0);
        assertEquals(Av.UNPRICED, ordinary.getPriceSource());
        assertEquals("unknown on a special world is not zero truth", 0L, ordinary.valueDelta);
        assertFalse(service.automaticMarketQuotesAvailable());
    }

    @Test
    public void unknownEconomyAlsoFailsClosedUntilTheWorldIsKnown()
    {
        Bl service = new Bl(new GpManagerConfig() {}, id -> null,
            (id, active) -> 100, id -> false, Bl.Bk.UNKNOWN);
        Am engine = new Am(service, new TransactionClassifier(), config());
        engine.rm(T0);
        assertEquals(Av.UNPRICED,
            service.value(Collections.singletonMap(560, 1L), T0).get(0).getPriceSource());

        service.setEconomyState(Bl.Bk.NORMAL);
        assertTrue(service.automaticMarketQuotesAvailable());
        assertEquals(Av.GRAND_EXCHANGE,
            service.value(Collections.singletonMap(560, 1L), T0).get(0).getPriceSource());
    }

    // ── plugin lifecycle seam ──────────────────────────────────────────────────────────────────

    @Test
    public void pluginRefreshSeamTracksLoginWorldHopAndLogout() throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPlugin();
        Bl service = new Bl(new GpManagerConfig() {}, id -> null,
            (id, active) -> 100, id -> false, Bl.Bk.UNKNOWN);
        set(plugin, "valuation", service);

        set(plugin, "client", client(GameState.LOGGED_IN, EnumSet.of(WorldType.DEADMAN)));
        refresh(plugin);
        assertEquals(Bl.Bk.UNSUPPORTED_SPECIAL, service.getEconomyState());

        set(plugin, "client", client(GameState.LOGGED_IN, EnumSet.of(WorldType.MEMBERS)));
        refresh(plugin);
        assertEquals(Bl.Bk.NORMAL, service.getEconomyState());

        set(plugin, "client", client(GameState.LOGIN_SCREEN, EnumSet.noneOf(WorldType.class)));
        refresh(plugin);
        assertEquals("logout clears the snapshot to UNKNOWN", Bl.Bk.UNKNOWN,
            service.getEconomyState());

        set(plugin, "client", client(GameState.HOPPING, EnumSet.of(WorldType.DEADMAN)));
        refresh(plugin);
        assertEquals("a hop has no world identity until login",
            Bl.Bk.UNKNOWN, service.getEconomyState());
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static final class Seam
    {
        final java.util.List<Integer> asked = new java.util.ArrayList<>();
        int quote = 100;
    }

    private static void assertState(Bl.Bk expected, WorldType... types)
    {
        assertEquals(expected, Bl.rh(
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
