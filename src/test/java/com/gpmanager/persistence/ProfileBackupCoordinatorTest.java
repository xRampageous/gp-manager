package com.gpmanager.persistence;

import com.google.gson.Gson;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.ReceiptRetentionPeriod;
import com.gpmanager.diagnostics.DebugTrace;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.SessionMode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import static org.junit.Assert.*;

public class ProfileBackupCoordinatorTest
{
    static
    {
        com.gpmanager.persistence.ProfileBackup.bindGson(new com.google.gson.Gson());
    }

    private static final String PROFILE = "rsprofile.backup-test";
    private static final long NOW = 1_800_000_000_000L;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void coordinatorFlushesAndDurablyCommitsImportedProfile() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), directory, null, true);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        GpManagerEngine engine = new GpManagerEngine(deltas -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig()
            {
                @Override
                public ReceiptRetentionPeriod receiptRetentionDays()
                {
                    return ReceiptRetentionPeriod.FOREVER;
                }
            });
        PersistenceCoordinator coordinator = new PersistenceCoordinator(
            clientProxy(), configProxy(), repository, writer, engine, new DebugTrace());
        coordinator.start();
        try
        {
            TrackingIdentity identity = TrackingIdentity.ofRsProfileKey(PROFILE);
            assertTrue(coordinator.trySwitchIdentity(identity, false));
            engine.ensureSession(NOW - 10_000L);
            engine.getGeneralSession().rename("Before import");

            ProfitSession imported = new ProfitSession("Imported profile", NOW - 2_000L, SessionMode.GENERAL);
            imported.close(NOW - 1_000L);
            SavedState importedState = new SavedState(imported, Collections.emptyList());
            importedState.setOwnerKey(PROFILE);
            ProfileBackup backup = ProfileBackup.create(importedState, PROFILE, NOW);

            ProfileBackupReport result = coordinator.restoreProfile(backup.toJson(), NOW);

            assertTrue(result.getRefusalReason(), result.isAccepted());
            assertTrue(result.isApplied());
            assertTrue(result.isPersistenceAttempted());
            assertTrue(result.getPersistenceDetail(), result.isDurablyCommitted());
            SavedState onDisk = repository.load();
            assertEquals(PROFILE, onDisk.getOwnerKey());
            assertEquals("Imported profile", onDisk.getActiveSession().getName());
            assertEquals(SaveStatus.State.OK, writer.getStatus().getState());
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void failedProfileRestoreRollsIdentityAndPersistenceScopeBack() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), directory, null, true)
        {
            @Override
            public synchronized SavedState load()
            {
                SavedState foreign = new SavedState();
                foreign.setOwnerKey("rsprofile.foreign");
                return foreign;
            }
        };
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        GpManagerEngine engine = new GpManagerEngine(deltas -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig()
            {
                @Override
                public ReceiptRetentionPeriod receiptRetentionDays()
                {
                    return ReceiptRetentionPeriod.FOREVER;
                }
            });
        PersistenceCoordinator coordinator = new PersistenceCoordinator(
            clientProxy(), configProxy(), repository, writer, engine, new DebugTrace());
        coordinator.start();
        try
        {
            TrackingIdentity alice = TrackingIdentity.ofRsProfileKey("rsprofile.alice");
            TrackingIdentity bob = TrackingIdentity.ofRsProfileKey("rsprofile.bob");
            assertTrue(coordinator.trySwitchIdentity(alice, false));
            engine.ensureSession(NOW - 10_000L);
            engine.getGeneralSession().rename("Alice profile");

            assertFalse(coordinator.trySwitchIdentity(bob, true));

            assertEquals(alice, coordinator.getActiveIdentity());
            assertEquals(alice, repository.getBoundIdentity());
            assertEquals("Alice profile", engine.getGeneralSession().getName());
            assertEquals(alice.getRsProfileKey(), engine.exportProfile().getProfileId());
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    private static Client clientProxy()
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getAccountHash")) return 77L;
                return defaultValue(method.getReturnType());
            });
    }

    private static ConfigManager configProxy()
    {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try
        {
            Constructor<ConfigManager> constructor = ConfigManager.class.getDeclaredConstructor(
                String.class, ScheduledExecutorService.class,
                Class.forName("net.runelite.client.eventbus.EventBus"), Client.class,
                Gson.class, Class.forName("net.runelite.client.config.ConfigClient"),
                Class.forName("net.runelite.client.config.ProfileManager"),
                Class.forName("net.runelite.client.account.SessionManager"));
            constructor.setAccessible(true);
            ConfigManager manager = constructor.newInstance("test", executor, null, null,
                new Gson(), null, null, null);
            Field profileKey = ConfigManager.class.getDeclaredField("rsProfileKey");
            profileKey.setAccessible(true);
            profileKey.set(manager, PROFILE);
            return manager;
        }
        catch (ReflectiveOperationException ex)
        {
            throw new AssertionError("Could not create isolated ConfigManager fixture", ex);
        }
        finally
        {
            executor.shutdownNow();
        }
    }

    private static Object defaultValue(Class<?> type)
    {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }
}
