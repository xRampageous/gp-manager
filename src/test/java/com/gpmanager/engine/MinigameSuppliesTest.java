package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Supplies a minigame hands out and takes back inside its own area never book (owner 2026-11-07:
 * "while woodcutting etc while in wintertodt it shouldn't book anything"): Wintertodt's bruma
 * items, Zalcano's tephra and the Mage Training Arena's room items. Each is priced here so a
 * booking would show.
 */
public class MinigameSuppliesTest
{
    private static final int ROOT = ItemID.WINT_BRUMA_ROOT, KINDLING = ItemID.WINT_BRUMA_KINDLING,
        HERB = ItemID.WINT_HERB, UNF = ItemID.WINT_VIAL, POTION = ItemID.WINT_POTION4,
        TEPHRA = ItemID.ZALCANO_ORE, REFINED = ItemID.ZALCANO_REFINED_ORE, IMBUED = ItemID.ZALCANO_IMBUED_ORE,
        BOOTS = ItemID.MAGICTRAINING_LEATHER_BOOTS, ROOM_COINS = ItemID.MAGICTRAINING_COINS,
        CUBE = ItemID.MAGICTRAINING_ENCHAN_CUBE, BONES = ItemID.MAGICTRAINING_BONES1,
        MAGIC_LOGS = ItemID.MAGIC_LOGS;

    private static Engine settle(Map<Integer, Long> before, Map<Integer, Long> after)
    {
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, "Item " + id, quantity, 100, quantity * 100)));
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig()
        {
        });
        engine.ensureSession(1_000_000L);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(before)));
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(after)), 1_000_000L + tick * 600L, null);
        }
        return engine;
    }

    private static void booksNothing(Map<Integer, Long> before, Map<Integer, Long> after)
    {
        Engine engine = settle(before, after);
        assertTrue(engine.session().transactions().toString(), engine.session().transactions().isEmpty());
        assertEquals(0L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void wintertodtChoppingFletchingBrewingAndLeavingBookNothing()
    {
        booksNothing(Map.of(), Map.of(ROOT, 10L));
        booksNothing(Map.of(ROOT, 10L), Map.of(KINDLING, 10L));
        booksNothing(Map.of(UNF, 1L, HERB, 1L), Map.of(POTION, 1L));
        booksNothing(Map.of(KINDLING, 4L, POTION, 1L), Map.of());
    }

    @Test
    public void zalcanoTephraBooksNothing()
    {
        booksNothing(Map.of(), Map.of(TEPHRA, 5L));
        booksNothing(Map.of(TEPHRA, 5L), Map.of(REFINED, 5L));
        booksNothing(Map.of(REFINED, 5L), Map.of(IMBUED, 5L));
        booksNothing(Map.of(IMBUED, 5L), Map.of());
    }

    @Test
    public void mageTrainingArenaRoomItemsBookNothing()
    {
        booksNothing(Map.of(BOOTS, 1L), Map.of(ROOM_COINS, 30L));
        booksNothing(Map.of(), Map.of(CUBE, 3L));
        booksNothing(Map.of(BONES, 4L), Map.of());
    }

    @Test
    public void realLootBesideMinigameSuppliesStillBooks()
    {
        Engine engine = settle(Map.of(KINDLING, 4L), Map.of(MAGIC_LOGS, 20L));
        List<Transaction> rows = engine.session().transactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals("only the magic logs count", 2_000L, engine.metrics(1_010_000L).net);
    }
}
