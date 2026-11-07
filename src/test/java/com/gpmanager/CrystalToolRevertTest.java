package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.MenuAction;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A crystal tool's Revert asks twice ("When you dismantle this…", then "Yes, dismantle it.")
 * before it comes apart, so the click's evidence can lapse; the game's "You take apart your …"
 * line arrives with the parts (owner recording 20261107-071643: Revert on tick 553, parts on 558).
 */
public class CrystalToolRevertTest {
    static final int AXE = ItemID.CRYSTAL_AXE_2H, SEED = ItemID.PRIF_TOOL_SEED, DRAGON = ItemID.DRAGON_AXE_2H,
        SHARD = ItemID.PRIF_CRYSTAL_SHARD;
    java.util.function.LongSupplier previous;

    @Before public void clock() {
        previous = SafeMath.clock; SafeMath.clock = () -> ProductionRecipeTest.T0;
    }

    @After public void restore() {
        SafeMath.clock = previous;
    }

    /** Recorded prices: RuneLite prices the axe as its seed plus the dragon felling axe. */
    static ProductionRecentTest.Fixture fixture() {
        var f = new ProductionRecentTest.Fixture(new HashMap<>(Map.of(AXE, 1L)));
        f.engine.valuation = deltas -> {
            var flows = new ArrayList<Flow>();
            deltas.forEach((id, quantity) -> {
                long price = id == AXE ? 17_599_599 : id == SEED ? 15_850_000 : id == DRAGON ? 1_749_599 : 2_000;
                String name = id == AXE ? "Crystal felling axe" : id == SEED ? "Crystal tool seed"
                    : id == DRAGON ? "Dragon felling axe" : "Crystal shard";
                flows.add(new Flow(id, name, quantity, price, SafeMath.multiply(quantity, price), PriceSource.GRAND_EXCHANGE));
            });
            return flows;
        };
        return f;
    }

    /** Revert on the axe, then the two dialogs outlast the click's few ticks. */
    static void revertSlowly(ProductionRecentTest.Fixture f) {
        Widget slot = LootingBagMovementTest.proxy(Widget.class, (method, args) -> method.equals("getItemId") ? AXE : null);
        f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Revert", MenuAction.CC_OP_LOW_PRIORITY, slot,
            "<col=ff9040>Crystal felling axe</col>"));
        f.engine.events.tick(f.engine.events.now + 10);
    }

    static void line(ProductionRecentTest.Fixture f, String text) {
        var message = new ChatMessage(); message.setType(ChatMessageType.MESBOX); message.setMessage(text);
        f.plugin.onChatMessage(message);
    }

    static boolean reviewed(ProductionRecentTest.Fixture f) {
        return f.engine.session().transactions().stream().anyMatch(ReviewRules::needsReview);
    }

    @Test public void aConfirmedRevertCostsOnlyTheShardsHoweverLongTheDialogsTook() {
        var f = fixture(); revertSlowly(f);
        line(f, "You take apart your crystal felling axe.");
        f.become(Map.of(SEED, 1L, DRAGON, 1L));
        assertFalse(f.engine.session().transactions().toString(), reviewed(f));
        assertEquals("only the 120 shards used to make it are lost", -240_000L, f.live().net);
        assertTrue(f.engine.session().transactions().stream()
            .anyMatch(row -> "Dismantled Crystal felling axe".equals(row.note())));
    }

    @Test public void theSameChangeAfterALapsedRevertWithoutTheGameLineStaysReview() {
        var f = fixture(); revertSlowly(f);
        line(f, "When you dismantle this, you will receive a dragon felling axe and crystal equipment seed but "
            + "<col=ff0000>no crystal shards</col>.");
        f.become(Map.of(SEED, 1L, DRAGON, 1L));
        assertTrue(reviewed(f));
    }

    @Test public void theGameLineAloneNeverProvesARevertNobodyClicked() {
        var f = fixture(); line(f, "You take apart your crystal felling axe.");
        f.become(Map.of(SEED, 1L, DRAGON, 1L));
        assertTrue(reviewed(f));
    }
}
