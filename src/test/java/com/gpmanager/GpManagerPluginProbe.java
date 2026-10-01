package com.gpmanager;

import net.runelite.api.Client;

/** Always-ready gate for event fixtures that do not install persistence. */
final class GpManagerPluginProbe extends GpManagerPlugin
{
    @Override
    boolean canIngestGameplay()
    {
        return true;
    }

    static InteractionContextTracker tracker(Client client, Engine engine, GpManagerConfig config)
    {
        InteractionContextTracker tracker = new InteractionContextTracker(client, engine, config);
        tracker.bindPresentation(() -> true, (name, combat) -> {});
        return tracker;
    }
}
