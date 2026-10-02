package com.gpmanager;

import com.google.gson.Gson;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;

/**
 * A real RuneLite {@link ConfigManager} with no client, session or remote behind it: its two
 * property stores point at empty temp files, so tests exercise the production get/set/unset path
 * in memory without touching {@code ~/.runelite}. Test-only reflection.
 */
public final class IsolatedConfigManager
{
    private IsolatedConfigManager()
    {
    }

    public static ConfigManager create(String rsProfileKey)
    {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try
        {
            Constructor<ConfigManager> constructor = ConfigManager.class.getDeclaredConstructor(
                String.class, ScheduledExecutorService.class, EventBus.class, Client.class,
                Gson.class, Class.forName("net.runelite.client.config.ConfigClient"),
                Class.forName("net.runelite.client.config.ProfileManager"),
                Class.forName("net.runelite.client.account.SessionManager"));
            constructor.setAccessible(true);
            ConfigManager manager = constructor.newInstance("test", executor, new EventBus(), null,
                new Gson(), null, null, null);
            set(manager, "configProfile", emptyConfigData());
            set(manager, "rsProfileConfigProfile", emptyConfigData());
            if (rsProfileKey != null)
            {
                set(manager, "rsProfileKey", rsProfileKey);
            }
            return manager;
        }
        catch (ReflectiveOperationException | java.io.IOException ex)
        {
            throw new AssertionError("Could not create isolated ConfigManager fixture", ex);
        }
        finally
        {
            executor.shutdown();
        }
    }

    /** Writes every entry into {@code group} as raw strings for an isolated settings fixture. */
    public static ConfigManager seeded(String rsProfileKey, String group, Map<String, String> raw)
    {
        ConfigManager manager = create(rsProfileKey);
        for (Map.Entry<String, String> entry : raw.entrySet())
        {
            manager.setConfiguration(group, entry.getKey(), entry.getValue());
        }
        return manager;
    }

    private static Object emptyConfigData() throws ReflectiveOperationException, java.io.IOException
    {
        Class<?> type = Class.forName("net.runelite.client.config.ConfigData");
        Constructor<?> constructor = type.getDeclaredConstructor(File.class);
        constructor.setAccessible(true);
        File file = Files.createTempFile("gp-manager-config", ".properties").toFile();
        file.deleteOnExit();
        return constructor.newInstance(file);
    }

    private static void set(ConfigManager manager, String field, Object value) throws ReflectiveOperationException
    {
        Field target = ConfigManager.class.getDeclaredField(field);
        target.setAccessible(true);
        target.set(manager, value);
    }
}
