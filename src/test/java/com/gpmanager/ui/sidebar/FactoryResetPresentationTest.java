package com.gpmanager;

import com.google.gson.Gson;
import java.awt.Component;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * An applied factory reset clears the bound HUD+ trip through the real menu/confirm path; a refused
 * one leaves it exactly as it was.
 */
public class FactoryResetPresentationTest
{
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private final AtomicReference<Dp> panel = new AtomicReference<>();

    @Test
    public void appliedFactoryResetClearsTheBoundHudTrip() throws Exception
    {
        JsonCodec.bind(new Gson());
        GpManagerConfig config = testConfig();
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Am engine = new Am(deltas -> Collections.emptyList(),
            new TransactionClassifier(), config);
        Ei coordinator = new Ei(null,
            IsolatedConfigManager.create("rsprofile.alice"), repository, writer, engine);
        try
        {
            coordinator.start();
            assertTrue(coordinator.ajy(new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID), true));

            long now = System.currentTimeMillis();
            engine.rm(now);
            Cp builder = axn(config, engine, repository, coordinator);
            render(builder, engine, now);
            bookLoot(builder, now + 1L);
            Cb before = render(builder, engine, now + 2L);
            assertFalse("booked loot shows before the reset", before.rows.isEmpty());
            assertTrue("the best drop shows before the reset", hasBestDrop(before));

            onEdt(() ->
            {
                resetThroughTheMenu(panel.get());
                return null;
            });

            Cb after = render(builder, engine, now + 12L);
            assertTrue("the applied reset clears the tray", after.rows.isEmpty());
            assertEquals("the applied reset clears the trip chip", "", after.trip);
            assertFalse("the applied reset clears the best drop", hasBestDrop(after));

            onEdt(() ->
            {
                SidebarPanelProbe.refresh(panel.get());
                return null;
            });
            LivePage live = (LivePage) SidebarPanelProbe.livePage(panel.get());
            assertFalse("after a reset the status gem starts tracking; no Start button",
                LivePageProbe.hasTrackingButton(live.body()));
        }
        finally
        {
            coordinator.shutdown(false);
        }
    }

    @Test
    public void refusedFactoryResetLeavesTheBoundHudTripAlone() throws Exception
    {
        JsonCodec.bind(new Gson());
        GpManagerConfig config = testConfig();
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Am engine = new Am(deltas -> Collections.emptyList(),
            new TransactionClassifier(), config);
        // No bound identity: the coordinator refuses the destructive replace.
        Ei coordinator = new Ei(null, null, repository, writer, engine);
        try
        {
            coordinator.start();
            long now = System.currentTimeMillis();
            engine.rm(now);
            Cp builder = axn(config, engine, repository, coordinator);
            render(builder, engine, now);
            bookLoot(builder, now + 1L);
            assertFalse(render(builder, engine, now + 2L).rows.isEmpty());

            onEdt(() ->
            {
                resetThroughTheMenu(panel.get());
                return null;
            });

            Cb after = render(builder, engine, now + 12L);
            assertFalse("a refused reset keeps the tray", after.rows.isEmpty());
            assertTrue("a refused reset keeps the best drop", hasBestDrop(after));
        }
        finally
        {
            coordinator.shutdown(false);
        }
    }

    private Cp axn(GpManagerConfig config, Am engine, SessionRepository repository,
        Ei coordinator) throws Exception
    {
        return onEdt(() ->
        {
            Dp created = new Dp(engine, config, null, null, repository, coordinator);
            Cp builder = new Cp(config, null);
            created.axn(builder);
            panel.set(created);
            return builder;
        });
    }

    private static void bookLoot(Cp builder, long now)
    {
        for (int k = 0; k < 3; k++)
        {
            builder.tray().kill("Vorkath", now + k, false);
        }
        builder.tray().booked(receipt(now, flow(11286, "Draconic visage", 1L, 2_500_000)), now, false);
    }

    private static Cb render(Cp builder, Am engine, long now) throws Exception
    {
        return onEdt(() -> builder.update(Ca.capture(engine, now, null), f -> true, null, now));
    }

    private static boolean hasBestDrop(Cb snapshot)
    {
        return snapshot.folio.stream().anyMatch(line -> "Best drop".equals(line.label));
    }

    private static void resetThroughTheMenu(Dp panel)
    {
        JMenuItem reset = find(SidebarPanelProbe.dataMenu(panel), "Factory reset current profile");
        assertNotNull("the reset action is present", reset);
        assertTrue("the reset action is enabled", reset.isEnabled());
        reset.doClick();
        assertEquals("Factory reset", ShellProbe.sheetTitle(panel.shell()));
        ShellProbe.pressSheet(panel.shell(), "Reset");
        assertEquals("the sheet closes once the reset ran", "", ShellProbe.sheetTitle(panel.shell()));
    }

    private static JMenuItem find(JPopupMenu menu, String textPrefix)
    {
        return find(menu.getComponents(), textPrefix);
    }

    private static JMenuItem find(Component[] components, String textPrefix)
    {
        for (Component component : components)
        {
            if (component instanceof JMenu)
            {
                JMenuItem nested = find(((JMenu) component).getMenuComponents(), textPrefix);
                if (nested != null)
                {
                    return nested;
                }
            }
            else if (component instanceof JMenuItem && ((JMenuItem) component).getText() != null
                && ((JMenuItem) component).getText().startsWith(textPrefix))
            {
                return (JMenuItem) component;
            }
        }
        return null;
    }

    private static Ac receipt(long at, Ab... flows)
    {
        return new Ac(at, null, Ai.LOOT, Aj.LOOT, "", "Vorkath", true,
            Arrays.asList(flows), Bd.CONFIRMED, "fixture", null);
    }

    private static Ab flow(int id, String name, long quantity, int unit)
    {
        return new Ab(id, name, quantity, unit, quantity * unit, Av.GRAND_EXCHANGE);
    }

    private static GpManagerConfig testConfig()
    {
        return new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 1;
            }
        };
    }

    private static <T> T onEdt(Callable<T> callable) throws Exception
    {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                result.set(callable.call());
            }
            catch (Exception ex)
            {
                failure.set(ex);
            }
        });
        if (failure.get() != null)
        {
            throw failure.get();
        }
        return result.get();
    }
}
