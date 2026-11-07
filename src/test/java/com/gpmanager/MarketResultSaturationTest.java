package com.gpmanager;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MarketResultSaturationTest {
    @Test public void partialSettlementResultsUseTheCanonicalMoneyBuckets() {
        var parts = new LinkedHashMap<String, Transaction>();
        for (int i = 0; i < 3; i++) {
            var row = new Transaction(ProductionRecipeTest.T0 + i, TransactionType.TRADE, Context.MARKET,
                    "", "", true, List.of(new Flow(4151, "Captured item", 2000000000L,
                            2000000000L, 4000000000000000000L, PriceSource.GRAND_EXCHANGE)),
                    Confidence.CONFIRMED, "", null);
            parts.put(row.id(), row);
        }
        var ids = new ArrayList<>(parts.keySet());
        var offer = new GeOffer(); offer.side = GeOffer.Side.SELL;
        offer.settlementId = ids.get(0); offer.earlierSettlements = " " + ids.get(1) + " " + ids.get(2);
        var result = new MarketRows.Row(offer, parts::get, null);
        assertEquals(Long.MAX_VALUE, result.realizedResultGp);
        assertTrue(result.realizedResultCorrectionAware);
    }
    static Transaction part(long income, long cost) {
        return new Transaction(ProductionRecipeTest.T0, TransactionType.TRADE, Context.MARKET,
                "", "", true, List.of(new Flow(4151, "Captured item", 1, income, income, PriceSource.GRAND_EXCHANGE),
                        new Flow(995, "Coins", -1, cost, -cost, PriceSource.FACE_VALUE)),
                Confidence.CONFIRMED, "", null);
    }

    @Test public void mixedBucketsAreFoldedBeforeSubtractionInEitherSettlementOrder() {
        var rows = List.of(part(8000000000000000000L, 7000000000000000000L),
                part(8000000000000000000L, 1000000000000000000L));
        var parts = new LinkedHashMap<String, Transaction>();
        rows.forEach(row -> parts.put(row.id(), row));
        for (int first : List.of(0, 1)) {
            var offer = new GeOffer(); offer.side = GeOffer.Side.SELL;
            offer.settlementId = rows.get(first).id();
            offer.earlierSettlements = " " + rows.get(1 - first).id();
            var result = new MarketRows.Row(offer, parts::get, null);
            assertEquals(Long.MAX_VALUE - 8000000000000000000L, result.realizedResultGp);
            assertTrue(result.realizedResultCorrectionAware);
        }
    }

    @Test public void missingOrCompactedReceiptsKeepTheCapturedFallback() {
        var receipt = part(300, 100); var offer = new GeOffer(); offer.side = GeOffer.Side.SELL;
        offer.settlementId = receipt.id(); offer.earlierSettlements = " missing";
        offer.realizedResultGp = 127;
        var result = new MarketRows.Row(offer, id -> id.equals(receipt.id()) ? receipt : null, null);
        assertEquals(127, result.realizedResultGp);
        assertFalse(result.realizedResultCorrectionAware);
    }
}
