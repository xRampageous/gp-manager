package com.gpmanager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.*;

/** Plugin presentation wiring against the concrete sidebar and its read models. */
public class PresentationWiringTest
{
    @Test
    public void missingToolbarDegradesToNoPresentationWithoutThrowing() throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPlugin();
        set(plugin, "clientToolbar", null);
        set(plugin, "lootPresentationFilter",
            new LootPresentationFilterService(
                Bc.disabled()));
        invoke(plugin, "installPresentation", new Class<?>[0]);
        assertNull("a missing toolbar leaves no panel bound", get(plugin, "activityPanel"));
    }

    @Test
    public void ownerScopeHeldClearsAndResumesTheConcreteSidebar() throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPlugin();
        Dp panel = panel(plugin);
        SwingUtilities.invokeAndWait(() -> SidebarPanelProbe.refresh(panel));
        assertNotNull(SidebarPanelProbe.live(panel));
        invoke(plugin, "ownerScopeChanged", new Class<?>[] {String.class}, "held");
        SwingUtilities.invokeAndWait(() -> SidebarPanelProbe.refresh(panel));
        assertNull("held presentation cannot render the previous owner", SidebarPanelProbe.live(panel));
        invoke(plugin, "ownerScopeChanged", new Class<?>[] {String.class}, "switching");
        assertNull(SidebarPanelProbe.live(panel));
        invoke(plugin, "ownerScopeChanged", new Class<?>[] {String.class}, "12345");
        assertNotNull("ready presentation resumes its facts", SidebarPanelProbe.live(panel));
    }

    @Test
    public void presentationSignalsRefreshWithoutBookingMoney() throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPlugin();
        Dp panel = panel(plugin);
        Am engine = (Am) get(plugin, "engine");
        engine.rm(1L);
        Ad session = engine.getActiveSession();
        long revision = engine.getRevision();
        invoke(plugin, "pulse", new Class<?>[0]);
        invoke(plugin, "transactionBooked", new Class<?>[] {Ac.class, long.class},
            Tx.of(1L, null, Ai.GAIN, Aj.GENERIC, "", "Test", true,
                Collections.emptyList()), 1L);
        invoke(plugin, "lootObserved", new Class<?>[] {boolean.class, String.class,
            java.util.Collection.class, int.class, long.class}, false, "Test", Collections.emptyList(), 1, 1L);
        invoke(plugin, "refreshPresentation", new Class<?>[0]);
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> {});
        assertNotNull("signals reach the sidebar read model", SidebarPanelProbe.live(panel));
        assertSame(session, engine.getActiveSession());
        assertTrue(session.getTransactions().isEmpty());
        assertEquals(revision, engine.getRevision());
        assertEquals(0L, engine.getMetrics(1L).net);
    }

    @Test
    public void detachedPresentationAcceptsEverySignal() throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPlugin();
        invoke(plugin, "pulse", new Class<?>[0]);
        invoke(plugin, "refreshPresentation", new Class<?>[0]);
        invoke(plugin, "ownerScopeChanged", new Class<?>[] {String.class}, (Object) null);
        invoke(plugin, "interactionChanged", new Class<?>[] {String.class, boolean.class}, "Goblin", true);
        invoke(plugin, "transactionBooked", new Class<?>[] {Ac.class, long.class}, null, 1L);
        invoke(plugin, "lootObserved", new Class<?>[] {boolean.class, String.class,
            java.util.Collection.class, int.class, long.class}, true, "Test", null, 1, 1L);
    }

    private static Dp panel(GpManagerPlugin plugin) throws Exception
    {
        GpManagerConfig config = new GpManagerConfig() {};
        Am engine = new Am(deltas -> Collections.emptyList(),
            new TransactionClassifier(), config);
        Dp[] panel = new Dp[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new Dp(engine, config, null));
        set(plugin, "engine", engine);
        set(plugin, "activityPanel", panel[0]);
        return panel[0];
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception
    {
        Method method = GpManagerPlugin.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static Object get(Object target, String name) throws Exception
    {
        Field field = GpManagerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = GpManagerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
