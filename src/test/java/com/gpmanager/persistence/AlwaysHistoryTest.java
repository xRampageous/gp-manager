package com.gpmanager;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import net.runelite.api.Client;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static com.gpmanager.GpManagerPluginProbe.set;
import static com.gpmanager.SwingTestSupport.onEdt;
import static org.junit.Assert.*;

/** A stored false preference cannot hide history or suppress any production save path. */
public class AlwaysHistoryTest {
    private static final TrackingIdentity ALICE = new TrackingIdentity("rsprofile.always-history", 123L);
    private static final long NOW = 1_700_000_000_000L;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void saveHistorySettingIsRemoved() {
        for (var method : GpManagerConfig.class.getDeclaredMethods()) assertNotEquals("persistHistory", method.getName());
    }

    @Test public void legacyOffStillLoadsSavedHistoryOnTheAccountEvent() throws Exception {
        try (Harness h = new Harness(temporary.newFolder().toPath())) {
            h.plugin.onAccountHashChanged(null);
            assertNotNull("stored history must be loaded despite the old off preference", h.engine.session());
            assertEquals("Saved earlier", h.engine.session().name());
            assertEquals(500, h.engine.metrics(NOW).net);
            assertEquals(ALICE, h.disk.activeIdentity);
            assertTrue(h.disk.isTrackingReady());
        }
    }

    @Test public void legacyOffStillSavesGameplayAndSidebarEdits() throws Exception {
        try (Harness h = new Harness(temporary.newFolder().toPath())) {
            h.plugin.onAccountHashChanged(null);
            TargetFreshStartTest.coins(h.engine, NOW + 1, 200);
            h.plugin.persist();
            assertTrue(h.writer.flush(Duration.ofSeconds(5)));
            assertEquals(700, FixtureReads.currentSession(h.repository.load()).metrics(NOW + 2).net);
            SidebarPanel panel = onEdt(() -> new SidebarPanel(h.engine, h.config, null, h.repository, h.disk));
            onEdt(() -> { panel.mutate(() -> h.engine.session().rename("Edited here")); return null; });
            assertTrue(h.writer.flush(Duration.ofSeconds(5)));
            assertEquals("Edited here", FixtureReads.currentSession(h.repository.load()).name());
            assertEquals(700, FixtureReads.currentSession(h.repository.load()).metrics(NOW + 2).net);
        }
    }

    @Test public void deleteProfileStillRemovesThePersistedHistory() throws Exception {
        try (Harness h = new Harness(temporary.newFolder().toPath())) {
            h.plugin.onAccountHashChanged(null);
            assertTrue(h.disk.factoryReset(NOW, false).applied);
            assertEquals(0, h.engine.metrics(NOW).net);
            assertNull("Delete Profile leaves no stored current session", FixtureReads.currentSession(h.repository.load()));
            assertTrue(h.repository.load().history().isEmpty());
        }
    }

    private static class Harness implements AutoCloseable {
        final Engine engine = TargetFreshStartTest.engine();
        final SessionRepository repository;
        final OrderedPersistenceWriter writer;
        final Persistence disk;
        final GpManagerPlugin plugin;
        final GpManagerConfig config;
        Harness(Path root) throws Exception {
            Gson gson = new Gson(); JsonCodec.bind(gson);
            GpManagerConfig defaults = new GpManagerConfig() {};
            config = (GpManagerConfig) Proxy.newProxyInstance(GpManagerConfig.class.getClassLoader(),
                    new Class<?>[] {GpManagerConfig.class}, (proxy, method, args) ->
                            method.getName().equals("persistHistory") ? false : method.invoke(defaults, args));
            repository = new SessionRepository(gson, FilepathTestSupport.root(root), true);
            repository.bindIdentity(ALICE);
            Engine seed = TargetFreshStartTest.engine();
            seed.session().rename("Saved earlier");
            TargetFreshStartTest.coins(seed, NOW, 500);
            assertTrue(repository.save(new WriteIntent(ALICE, repository.generation, 0, seed.exportState(), null)));
            writer = new OrderedPersistenceWriter(repository);
            disk = new Persistence(null, null, repository, writer, engine) {
                @Override TrackingIdentity resolveCurrentIdentity() { return ALICE; }
                @Override void syncIdentityAsync() {
                    assertTrue(switchIdentity(ALICE, true));
                }
            };
            plugin = new GpManagerPlugin() {
                @Override void beginOwnerSwitch() {}
                @Override void pulse() {}
            };
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
                    (proxy, method, args) -> method.getName().equals("getAccountHash") ? 123L : null);
            set(plugin, "config", config); set(plugin, "storage", disk); set(plugin, "engine", engine); set(plugin, "client", client);
        }
        public void close() { writer.shutdown(Duration.ofSeconds(5)); }
    }
}
