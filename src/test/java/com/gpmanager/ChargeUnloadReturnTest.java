package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

/** Uncharging is the player's own charge coming back: neutral however long the confirm takes. */
public class ChargeUnloadReturnTest
{
    private static String name(int itemId)
    {
        switch (itemId)
        {
            case ItemID.SNAKEBOSS_SCALE: return "Zulrah's scales";
            case ItemID.DEATHRUNE: return "Death rune";
            case ItemID.BIG_BONES: return "Big bones";
            default: return null;
        }
    }

    private static Map<Integer, Long> counts(int itemId, long quantity)
    {
        return Collections.singletonMap(itemId, quantity);
    }

    @Test
    public void onlyTheWeaponsOwnComponentsCountAsTheReturn()
    {
        assertTrue("blowpipe scales coming back are the return", ChargeIntake.returnLanded(ChargeRead.Variant.V1b,
            counts(ItemID.SNAKEBOSS_SCALE, 10L), counts(ItemID.SNAKEBOSS_SCALE, 2_010L), ChargeUnloadReturnTest::name));
        assertTrue("trident runes coming back are the return", ChargeIntake.returnLanded(ChargeRead.Variant.TRIDENT_SEAS,
            Collections.emptyMap(), counts(ItemID.DEATHRUNE, 500L), ChargeUnloadReturnTest::name));
        assertFalse("loot that is not a load component never counts", ChargeIntake.returnLanded(ChargeRead.Variant.V1b,
            Collections.emptyMap(), counts(ItemID.BIG_BONES, 1L), ChargeUnloadReturnTest::name));
        assertFalse("an unchanged stack is not a return", ChargeIntake.returnLanded(ChargeRead.Variant.V1b,
            counts(ItemID.SNAKEBOSS_SCALE, 10L), counts(ItemID.SNAKEBOSS_SCALE, 10L), ChargeUnloadReturnTest::name));
    }

    @Test
    public void aReturnLandingAfterASlowConfirmBooksAsATransfer()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
        };
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                flows.add(new Flow(delta.getKey(), "Zulrah's scales", delta.getValue(), 150,
                    delta.getValue() * 150L));
            }
            return flows;
        }, new TransactionClassifier(), config);
        long now = 1_000L;
        engine.ensureSession(now);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>()));
        // The confirm took longer than the click's own context: watchReturn marks it on landing.
        engine.markContext(Context.TRANSFER, 4, GameData.msg("jv"));
        engine.markInventoryDirty();
        Map<Integer, Long> scales = new HashMap<>();
        scales.put(ItemID.SNAKEBOSS_SCALE, 2_000L);
        Transaction booked = null;
        for (int tick = 0; tick < 3 && booked == null; tick++)
        {
            booked = engine.processIfDirty(new ContainerSnapshot(scales), now += 600L);
        }
        assertNotNull(booked);
        assertEquals(TransactionType.TRANSFER, booked.getType());
        assertEquals("the returned charge is not income", 0L, engine.getMetrics(now).net);
    }
}
