package com.gpmanager;

import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.config.ConfigPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens RuneLite Configuration filtered to GP Manager. The panel cog tooltip
 * tells the user to click the wrench afterward — sideload installs often cannot
 * open the group editor the same way a built-in plugin list wrench can.
 */
@Singleton
final class ConfigurationPanelOpener
{
    private static final Logger log = LoggerFactory.getLogger(ConfigurationPanelOpener.class);

    private final ClientToolbar clientToolbar;
    private final Provider<ConfigPlugin> configPluginProvider;

    @Inject
    ConfigurationPanelOpener(
        ClientToolbar clientToolbar,
        Provider<ConfigPlugin> configPluginProvider)
    {
        this.clientToolbar = clientToolbar;
        this.configPluginProvider = configPluginProvider;
    }

    void open(Plugin plugin)
    {
        open(plugin, null);
    }

    void open(Plugin plugin, @Nullable Component dialogParent)
    {
        if (plugin == null)
        {
            return;
        }
        SwingUtilities.invokeLater(() -> openOnEdt(plugin, dialogParent));
    }

    private void openOnEdt(Plugin plugin, @Nullable Component dialogParent)
    {
        try
        {
            ConfigPlugin configPlugin = configPluginProvider.get();
            Object topLevel = readField(configPlugin, "topLevelConfigPanel");
            if (topLevel == null)
            {
                @SuppressWarnings("unchecked")
                Provider<Object> topProvider =
                    (Provider<Object>) readField(configPlugin, "topLevelConfigPanelProvider");
                topLevel = topProvider.get();
            }

            NavigationButton nav = (NavigationButton) readField(configPlugin, "navButton");
            if (nav != null)
            {
                clientToolbar.openPanel(nav);
            }

            Object top = topLevel;
            SwingUtilities.invokeLater(() ->
            {
                try
                {
                    invokeAccessible(top, "openWithFilter", new Class<?>[] {String.class}, "GP Manager");
                }
                catch (ReflectiveOperationException | RuntimeException ex)
                {
                    showFailure(dialogParent, ex);
                }
            });
        }
        catch (ReflectiveOperationException | RuntimeException ex)
        {
            showFailure(dialogParent, ex);
        }
    }

    private void showFailure(@Nullable Component dialogParent, Exception ex)
    {
        log.warn("Could not open GP Manager configuration panel", ex);
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root)
        {
            root = root.getCause();
        }
        String detail = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        JOptionPane.showMessageDialog(
            dialogParent,
            "Could not open Configuration.\n\n"
                + detail
                + "\n\nFallback: Configuration sidebar → search \"GP Manager\" → wrench.",
            "GP Manager settings",
            JOptionPane.WARNING_MESSAGE);
    }

    @Nullable
    private static Object readField(Object target, String name) throws ReflectiveOperationException
    {
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            }
            catch (NoSuchFieldException ignored)
            {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void invokeAccessible(Object target, String methodName, Class<?>[] types, Object... args)
        throws ReflectiveOperationException
    {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        method.invoke(target, args);
    }
}
