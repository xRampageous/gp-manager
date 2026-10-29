package com.gpmanager;

import java.awt.Font;
import java.util.Collections;
import java.util.List;
import net.runelite.client.ui.NavigationButton;

/** Test-only conveniences; production reads the same underlying objects directly. */
public final class FixtureReads {
    private FixtureReads() {}

    public static ContainerSnapshot empty() {
        return new ContainerSnapshot(Collections.emptyMap());
    }

    public static Session currentSession(SavedState state) {
        return state.customSession != null ? state.customSession : state.generalSession;
    }

    public static DeathReclaimStatus deathStatus(Engine engine) {
        synchronized (engine) {
            return new DeathReclaimStatus(engine.death.isAwaitingReclaim(), engine.death.armed(),
                    engine.death.outstanding(), engine.death.age);
        }
    }

    public static int compactOlderThan(Engine engine, int days, long now) {
        synchronized (engine) { return engine.archive.compactOlderThan(days, now); }
    }

    public static boolean automaticMarketQuotesAvailable(Engine engine) {
        return engine.valuation.automaticMarketQuotesAvailable();
    }

    public static boolean has(Inbox inbox, int kind, int item) {
        return inbox.peek(kind, entry -> entry.item == item) != null;
    }

    public static boolean isBound() { return JsonCodec.gson != null; }

    public static List<KeyChestCatalogue.Entry> entries() {
        return Collections.unmodifiableList(KeyChestCatalogue.ENTRIES);
    }

    public static KeyValue clear(KeyValue table) {
        table.body.removeAll();
        table.revalidate();
        table.repaint();
        return table;
    }

    public static NavigationButton button(NavButton navigation) {
        synchronized (navigation) { return navigation.button; }
    }

    public static Font body(HudOverlay overlay) { overlay.fonts(); return overlay.body; }
    public static Font bold(HudOverlay overlay) { overlay.fonts(); return overlay.bold; }

    public static long[] money(RaidHistory history, String id) {
        return history.totals.getOrDefault(id, new long[2]);
    }
}
