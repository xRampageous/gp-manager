package com.gpmanager;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReadModelTrimContractTest {
    @Test public void parsedRulesKeepOrderThresholdsWildcardsAndCacheIdentity() {
        LootPresentationFilterService.RULES.clear();
        var empty = LootPresentationFilterService.rules(null);
        assertTrue(empty.isEmpty());
        assertSame(empty, LootPresentationFilterService.rules(""));
        String csv = "Iron dagger > 2,Bones < 5,Rune*, ,Broken > z";
        var rules = LootPresentationFilterService.rules(csv);
        assertEquals(4, rules.size());
        assertSame(rules, LootPresentationFilterService.rules(csv));
        assertEquals("Iron dagger", rules.get(0).name);
        assertEquals(2, rules.get(0).quantity);
        assertFalse(rules.get(0).matches("Iron dagger", 2));
        assertTrue(rules.get(0).matches("Iron dagger", 3));
        assertTrue(rules.get(1).lessThan);
        assertTrue(rules.get(1).matches("Bones", 4));
        assertFalse(rules.get(1).matches("Bones", 5));
        assertTrue(rules.get(2).wildcard);
        assertTrue(rules.get(2).matches("Rune sword", 1));
        assertEquals("Broken > z", rules.get(3).name);
        assertEquals(0, rules.get(3).quantity);
        rules.add(LootPresentationFilterService.Rule.parse("Coins"));
        assertEquals(5, LootPresentationFilterService.rules(csv).size());
    }

    @Test public void everyDisplayModeRetainsTheVisibilityTruthTable() {
        var modes = new GpManagerConfig.LootFilter[]{null, GpManagerConfig.LootFilter.ALL_ITEMS,
                GpManagerConfig.LootFilter.FOLLOW_GROUND_ITEMS, GpManagerConfig.LootFilter.HIGHLIGHTED_LIST_ONLY};
        for (boolean enabled : new boolean[]{false, true})
            for (boolean only : new boolean[]{false, true})
                for (var mode : modes) {
                    var service = new LootPresentationFilterService(new GroundItemsPrefs(enabled, "Iron dagger", "Bones", only));
                    assertTrue(service.isFlowIncluded(null, mode));
                    for (String name : List.of("Iron dagger", "Bones", "Onion seed")) {
                        boolean expected = !enabled || mode == null || mode == GpManagerConfig.LootFilter.ALL_ITEMS
                                || name.equals("Iron dagger") || name.equals("Onion seed")
                                && mode == GpManagerConfig.LootFilter.FOLLOW_GROUND_ITEMS && !only;
                        assertEquals(enabled + "/" + only + "/" + mode + "/" + name, expected,
                                service.isFlowIncluded(new Flow(1203, name, 1, 100, 100), mode));
                    }
                }
        var overlapping = new LootPresentationFilterService(new GroundItemsPrefs(true, "Iron dagger", "Iron dagger", true));
        assertTrue(overlapping.isFlowIncluded(new Flow(1203, "Iron dagger", 1, 100, 100),
                GpManagerConfig.LootFilter.HIGHLIGHTED_LIST_ONLY));
    }

    @Test public void capturedReceiptMappingRetainsMoneyReviewCountAndDetachedFlows() {
        var engine = PreEpochReceiptRestoreTest.engine();
        var gain = Tx.of(1_700_000_000_001L, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(1203, "Iron dagger", 1, 100, 100, PriceSource.GRAND_EXCHANGE)));
        engine.session().addTransaction(gain, 2000, false);
        var review = new Transaction(1_700_000_000_002L, TransactionType.UNCERTAIN, Context.GENERIC, "", "Audit", false,
                List.of(new Flow(882, "Bronze arrow", 1, 200, 200, PriceSource.MANUAL_OVERRIDE)),
                Confidence.UNCERTAIN, "Audit", null);
        engine.session().addTransaction(review, 2000, false);
        var facts = new MoneySnapshot.SessionFacts(engine.session());
        assertEquals(100, facts.money.net);
        assertEquals(1, facts.review);
        assertEquals(gain.id(), facts.transactions.get(0).id());
        assertEquals(review.id(), facts.transactions.get(1).id());
        assertNotSame(gain, facts.transactions.get(0));
        assertNotSame(gain.flows().get(0), facts.transactions.get(0).flows().get(0));
        gain.applyCorrection(Correction.COST, "Later edit");
        review.applyCorrection(Correction.REVENUE, "Later edit");
        assertEquals(Correction.AUTO, facts.transactions.get(0).correction());
        assertEquals(Correction.AUTO, facts.transactions.get(1).correction());
        assertEquals(100, facts.money.net);
        assertEquals(1, facts.review);
        try {
            facts.transactions.clear();
            fail("captured receipt lists remain immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(2, facts.transactions.size());
        }
    }

    @Test public void marketFillStateKeepsCompletedPartialAndLargeCounts() {
        var row = new MarketRows.Row(new GeOffer(), null, null);
        for (long offered : new long[]{0, 1, 1000, Long.MAX_VALUE})
            for (long filled : new long[]{0, 1, 1000, Long.MAX_VALUE})
                for (long collected : new long[]{0, 1, 1000, Long.MAX_VALUE}) {
                    row.offeredQty = offered;
                    row.filledQty = filled;
                    row.settledQty = collected;
                    String expected = offered <= 0 || filled == collected && collected == offered ? ""
                            : filled == collected ? "Filled " + Fmt.exact(collected) + " / " + Fmt.exact(offered)
                            : "Filled " + Fmt.exact(filled) + " / " + Fmt.exact(offered) + " · collected " + Fmt.exact(collected);
                    assertEquals(expected, MarketText.fillStateText(row));
                }
    }
}
