package com.gpmanager;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.client.ui.NavigationButton;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** One NavigationButton owner: no duplicates across disable/enable, and never a silent missing icon. */
public class PresentationLifecycleTest
{
    private final RecordingNavigation navigation = new RecordingNavigation();
    private final Dp panel = new Dp(engine(), config(), null);
    private final BufferedImage icon = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    @Test
    public void realSidebarIconLoadsAndIsCarriedByTheButton()
    {
        BufferedImage loaded = Ec.awn();
        assertNotNull(loaded);
        assertTrue("the real bundled icon must load, not the placeholder",
            loaded.getWidth() > 16 && loaded.getHeight() > 16);

        Ec lifecycle = new Ec();
        assertTrue(lifecycle.install(navigation, panel, loaded));
        NavigationButton button = lifecycle.button();
        assertNotNull(button);
        assertNotNull("the button must carry the icon", button.getIcon());
        assertNotNull("the button must carry the panel", button.getPanel());
        assertEquals("GP Manager", button.getTooltip());
        assertEquals(5, button.getPriority());
    }

    @Test
    public void installIsIdempotentWhileInstalled()
    {
        Ec lifecycle = new Ec();
        assertTrue(lifecycle.install(navigation, panel, icon));
        assertNotNull(lifecycle.button());
        assertFalse("second install must not register a duplicate",
            lifecycle.install(navigation, panel, icon));
        assertEquals(1, navigation.adds.size());
    }

    @Test
    public void disableAndReenableNeverDuplicatesTheButton()
    {
        Ec lifecycle = new Ec();
        assertTrue(lifecycle.install(navigation, panel, icon));
        NavigationButton first = lifecycle.button();

        lifecycle.uninstall(navigation);
        assertNull(lifecycle.button());
        assertEquals(first, navigation.removes.get(0));

        assertTrue("re-enable installs exactly one fresh button",
            lifecycle.install(navigation, panel, icon));
        assertNotNull(lifecycle.button());
        assertEquals(2, navigation.adds.size());
        assertEquals(1, navigation.removes.size());
        lifecycle.uninstall(navigation);
        assertEquals(2, navigation.removes.size());
        assertNull(lifecycle.button());
    }

    @Test
    public void missingHostPanelOrIconDegradeWithoutRegistering()
    {
        Ec lifecycle = new Ec();
        assertFalse(lifecycle.install(null, panel, icon));
        assertFalse(lifecycle.install(navigation, null, icon));
        assertFalse(lifecycle.install(navigation, panel, null));
        assertNull(lifecycle.button());
        assertEquals(0, navigation.adds.size());
    }

    @Test
    public void toolbarAdapterIsNullSafe()
    {
        assertNull(Ec.akh(null));
    }

    static GpManagerConfig config()
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

    static Am engine()
    {
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Ab(id, "Item " + id, quantity, 50, quantity * 50)));
            return flows;
        }, new TransactionClassifier(), config());
    }

    static final class RecordingNavigation implements Ec.NavigationHost
    {
        final List<NavigationButton> adds = new ArrayList<>();
        final List<NavigationButton> removes = new ArrayList<>();

        @Override
        public void add(NavigationButton button)
        {
            adds.add(button);
        }

        @Override
        public void remove(NavigationButton button)
        {
            removes.add(button);
        }
    }

    static List<Ab> flow(int itemId, String name, long quantity, int unitPrice, long value)
    {
        return Collections.singletonList(new Ab(itemId, name, quantity, unitPrice, value));
    }
}
