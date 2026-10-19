package com.gpmanager;

import net.runelite.api.Client;
import java.lang.reflect.Field;

/** Always-ready gate for event fixtures that do not install persistence. */
final class GpManagerPluginProbe extends GpManagerPlugin
{
    @Override
    boolean canIngest()
    {
        return true;
    }

    static InteractionContextTracker tracker(Client client, Engine engine, GpManagerConfig config)
    {
        InteractionContextTracker tracker = new InteractionContextTracker(client, engine, config);
        tracker.bindPresentation(() -> true, (name, combat) -> {});
        return tracker;
    }

    /** Inject a plugin field in offline event fixtures. */
    public static void set(Object target, String name, Object value) throws Exception
    {
        Field field = GpManagerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
