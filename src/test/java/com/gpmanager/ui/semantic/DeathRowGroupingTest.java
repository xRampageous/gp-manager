package com.gpmanager;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** A death's bag and measured gear remain separate facts behind one presentation row. */
public class DeathRowGroupingTest {
    static Transaction loss(String fight, boolean bag, Flow... flows) {
        return new Transaction(bag ? 1000 : 1600, TransactionType.PK_DEATH_LOSS, Context.PK_DEATH,
                bag ? GameData.msg("bag-loss") : "PK death loss", "PKing", true, Arrays.asList(flows),
                Confidence.LIKELY, bag ? GameData.msg("bag-loss-proof") : "Measured gear loss", fight);
    }
    static Transaction gear(String fight) {
        return loss(fight, false, new Flow(861, "Magic shortbow", -1, 715, -715),
                new Flow(11941, "Looting bag", -1, 6, -6));
    }
    static Transaction bag(String fight) {
        return loss(fight, true, new Flow(1079, "Rune platelegs", -2, 37872, -75744),
                new Flow(995, "Coins", -693, 1, -693));
    }
    static Semantics.Result capture(Transaction... rows) {
        return SemanticProbe.capture(Arrays.asList(rows), List.of(), "", null);
    }

    @Test public void oneDeathRowKeepsEveryCanonicalReceiptAndItsValue() {
        var bag = bag("death:1"); var gear = gear("death:1");
        var projection = capture(bag, gear);
        assertEquals(1, projection.groups.size());
        var group = projection.groups.get(0);
        assertEquals("Death: PvP", group.name);
        assertEquals(-77158, group.value);
        assertEquals(Set.of(bag.id(), gear.id()), group.ids);
        assertEquals(4, group.entries.size());
        assertTrue(group.matchesText("Rune platelegs"));
        assertTrue(group.matchesText("Magic shortbow"));
        assertFalse("two receipts must not display as two deaths", group.actionGroup());
        assertEquals(-1, group.quantity);
        assertEquals(1, LiveProbe.recent(List.of(bag, gear), LiveContext.NONE).size());
    }

    @Test public void separateAndLegacyUnlinkedDeathsNeverMerge() {
        assertEquals(2, capture(gear("death:1"), gear("death:2")).groups.size());
        assertEquals(2, capture(gear(null), gear(null)).groups.size());
    }

    @Test public void partialUnknownStaysOneHonestIncompleteDeath() {
        var unknown = loss("death:1", false, new Flow(999999, "Unknown", -1, 0, 0, PriceSource.UNPRICED));
        var groups = capture(bag("death:1"), unknown).groups;
        assertEquals(1, groups.size());
        assertTrue(groups.get(0).incomplete());
        assertEquals(-76437, groups.get(0).value);
    }

    @Test public void correctionsRemainExactAndCannotHideTheOtherReceipt() {
        var bag = bag("death:1"); var gear = gear("death:1");
        gear.applyCorrection(Correction.IGNORE, "Manual correction");
        var groups = capture(bag, gear).groups;
        assertEquals(2, groups.size());
        assertEquals(1, groups.stream().filter(group -> group.corrected).count());
        assertEquals(-76437, Semantics.summarize(groups).cost);
    }

    @Test public void graveMovementsNeverBecomeRows() {
        var row = new Transaction(1000, TransactionType.TRANSFER, Context.TRANSFER,
                GameData.msg("death-items"), "Death reclaim", false,
                List.of(new Flow(10499, "Ava's accumulator", -1, 600, -600),
                        new Flow(19564, "Royal seed pod", -1, 12, -12)), Confidence.LIKELY, "Owned items", null);
        assertTrue(capture(row).groups.isEmpty());
        assertEquals(0, capture(row).groups.size());
    }
}
