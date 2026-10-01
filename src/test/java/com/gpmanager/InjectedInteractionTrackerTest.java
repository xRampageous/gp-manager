package com.gpmanager;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.InteractingChanged;
import org.junit.Test;

import static org.junit.Assert.*;

/** An internal injected tracker must still publish client evidence through its callback. */
public class InjectedInteractionTrackerTest
{
    @Test
    public void guiceBuildsAndReusesTheTrackerThatPublishesCombatTargets()
    {
        GpManagerConfig config = new GpManagerConfig() {};
        Engine engine = new Engine(deltas -> Collections.emptyList(),
            new TransactionClassifier(), config);
        engine.ensureSession(1L);
        Player local = proxy(Player.class, method -> null);
        Client client = proxy(Client.class, method -> "getLocalPlayer".equals(method) ? local : null);
        NPC target = proxy(NPC.class, method -> "getName".equals(method) ? "Goblin"
            : "getCombatLevel".equals(method) ? 2 : null);
        Injector injector = Guice.createInjector(new AbstractModule()
        {
            @Override
            protected void configure()
            {
                bind(Client.class).toInstance(client);
                bind(Engine.class).toProvider(() -> engine);
                bind(GpManagerConfig.class).toInstance(config);
            }
        });
        InteractionContextTracker tracker = injector.getInstance(InteractionContextTracker.class);
        assertSame(tracker, injector.getInstance(InteractionContextTracker.class));
        String[] published = {""};
        tracker.bindPresentation(() -> true, (name, combat) -> published[0] = name);
        tracker.onInteractingChanged(new InteractingChanged(local, target));
        assertEquals("Goblin", published[0]);
        assertTrue(tracker.hasPvmContext());
    }

    private static <T> T proxy(Class<T> type, Function<String, Object> values)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (object, method, args) ->
            {
                Object value = values.apply(method.getName());
                if (value != null || !method.getReturnType().isPrimitive()) return value;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == long.class) return 0L;
                return 0;
            }));
    }
}
