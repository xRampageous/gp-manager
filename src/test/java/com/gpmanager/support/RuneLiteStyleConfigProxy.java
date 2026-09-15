package com.gpmanager.support;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.runelite.client.config.ConfigItem;

/**
 * Hand-written JDK Proxy that <em>imitates</em> RuneLite {@code ConfigInvocationHandler}
 * for unit tests: methods without {@link ConfigItem} return {@code null} (primitives then
 * null-unbox), while annotated default methods run their interface bodies.
 *
 * <p><strong>Not</strong> a live {@code ConfigManager}, Guice injection, persisted settings
 * parse, or {@code GpManagerPlugin#startUp} integration test. Never reads or writes real
 * {@code ~/.runelite} settings. A TemporaryFolder-backed ConfigManager smoke remains a
 * follow-up improvement.
 */
public final class RuneLiteStyleConfigProxy
{
    private RuneLiteStyleConfigProxy()
    {
    }

    public static <T> T create(Class<T> configInterface)
    {
        if (configInterface == null || !configInterface.isInterface())
        {
            throw new IllegalArgumentException("configInterface must be an interface");
        }
        Object proxy = Proxy.newProxyInstance(
            configInterface.getClassLoader(),
            new Class<?>[] { configInterface },
            new Handler(configInterface));
        return configInterface.cast(proxy);
    }

    private static final class Handler implements InvocationHandler
    {
        private final Class<?> configInterface;

        private Handler(Class<?> configInterface)
        {
            this.configInterface = configInterface;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            if (method.getDeclaringClass() == Object.class)
            {
                return invokeObject(proxy, method, args);
            }
            if (method.getAnnotation(ConfigItem.class) == null)
            {
                // Same as RuneLite: warn + null; callers of primitives NPE on unbox.
                return null;
            }
            if (!method.isDefault())
            {
                return null;
            }
            return invokeDefault(configInterface, proxy, method, args);
        }
    }

    private static Object invokeDefault(
        Class<?> iface,
        Object proxy,
        Method method,
        Object[] args) throws Throwable
    {
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(iface, MethodHandles.lookup());
        MethodHandle handle = lookup.unreflectSpecial(method, iface).bindTo(proxy);
        return handle.invokeWithArguments(args == null ? new Object[0] : args);
    }

    private static Object invokeObject(Object proxy, Method method, Object[] args)
    {
        String name = method.getName();
        if ("toString".equals(name))
        {
            return "RuneLiteStyleConfigProxy(" + proxy.getClass().getInterfaces()[0].getSimpleName() + ")";
        }
        if ("hashCode".equals(name))
        {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(name))
        {
            return proxy == args[0];
        }
        throw new UnsupportedOperationException(name);
    }
}
