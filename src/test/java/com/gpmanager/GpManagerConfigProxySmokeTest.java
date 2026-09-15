package com.gpmanager;

import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.support.RuneLiteStyleConfigProxy;
import com.gpmanager.ui.TrackingDisplayModel;
import com.gpmanager.ui.TrackingDisplaySnapshot;
import java.lang.reflect.Method;
import java.util.Collections;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Annotation-faithful config proxy smoke using {@link RuneLiteStyleConfigProxy}.
 *
 * <p>Catches missing {@link ConfigItem} helpers that anonymous {@code new GpManagerConfig() {}}
 * stubs mask. This is <strong>not</strong> a live RuneLite ConfigManager, Guice, or plugin
 * {@code startUp} integration test — see {@link RuneLiteStyleConfigProxy} class docs.
 */
public class GpManagerConfigProxySmokeTest
{
    @Test
    public void everyAnnotatedAccessorUnboxesThroughProxy() throws Exception
    {
        GpManagerConfig config = RuneLiteStyleConfigProxy.create(GpManagerConfig.class);
        for (Method method : GpManagerConfig.class.getDeclaredMethods())
        {
            if (method.getParameterCount() != 0)
            {
                continue;
            }
            assertNotNull(method.getName() + " must have @ConfigItem",
                method.getAnnotation(ConfigItem.class));
            try
            {
                Object value = method.invoke(config);
                Class<?> type = method.getReturnType();
                if (type.isPrimitive())
                {
                    assertNotNull(method.getName() + " primitive must unbox", value);
                }
                else if (type.isEnum())
                {
                    assertNotNull(method.getName() + " enum must not be null", value);
                }
            }
            catch (Exception ex)
            {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                fail(method.getName() + " failed through config proxy: " + cause);
            }
        }
    }

    @Test
    public void trackingSnapshotSurvivesDefaultProxyConfig()
    {
        GpManagerConfig config = RuneLiteStyleConfigProxy.create(GpManagerConfig.class);
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            config);
        engine.ensureSession(1_000L);
        TrackingDisplayModel model = new TrackingDisplayModel(
            engine, config, new RewardPresentationModel(), null);
        TrackingDisplaySnapshot snapshot = model.snapshot(1_100L);
        assertNotNull(snapshot);
        assertNotNull(snapshot.getStatusLabel());
    }

    @Test
    public void missingConfigItemReturnsNullLikeRuneLite()
    {
        BrokenProbe probe = RuneLiteStyleConfigProxy.create(BrokenProbe.class);
        assertEquals("ok", probe.annotated());
        assertNull(probe.unannotatedObject());
        try
        {
            long ignored = probe.unannotatedLong();
            fail("expected null-unboxing NPE, got " + ignored);
        }
        catch (NullPointerException expected)
        {
            // Same failure mode as the live minimumDisplayedLootGp enable crash.
        }
    }

    /** Local probe — not a production config. */
    public interface BrokenProbe extends Config
    {
        @ConfigItem(
            keyName = "annotated",
            name = "Annotated",
            description = "Has ConfigItem")
        default String annotated()
        {
            return "ok";
        }

        /** Deliberately missing {@link ConfigItem} — proxy must return null. */
        default String unannotatedObject()
        {
            return "should-not-run";
        }

        /** Deliberately missing {@link ConfigItem} — Proxy null-unboxes to NPE. */
        default long unannotatedLong()
        {
            return 42L;
        }
    }
}
