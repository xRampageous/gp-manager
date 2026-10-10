package com.gpmanager;

import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;

import java.util.*;

import javax.inject.*;

/**
 * The item lookups accounting needs, in one place: the canonical id, the composition, the active
 * market quote and a name search. Production answers from RuneLite's ItemManager; the rework's
 * replay answers from a recording (docs/REWORK_PLAN.md), so a session books the same both ways.
 */
@Singleton
class ItemLookup {
    ItemManager manager;

    @Inject
    ItemLookup(ItemManager manager) {
        this.manager = manager;
    }

    int canon(int id) {
        return manager == null ? id : manager.canonicalize(id);
    }

    ItemComposition composition(int id) {
        return manager == null ? null : manager.getItemComposition(id);
    }

    List<ItemPrice> find(String name) {
        return manager == null ? Collections.emptyList() : manager.search(name);
    }
}
