package com.gpmanager;

import net.runelite.api.Client;

/** Always-ready gate for event fixtures that do not install persistence. */
final class GpManagerPluginProbe extends GpManagerPlugin
{
    @Override
    boolean nh()
    {
        return true;
    }

    static InteractionContextTracker tracker(Client client, Am engine, GpManagerConfig config)
    {
        InteractionContextTracker tracker = new InteractionContextTracker(client, engine, config);
        tracker.mf(() -> true, (name, combat) -> {});
        return tracker;
    }
}
