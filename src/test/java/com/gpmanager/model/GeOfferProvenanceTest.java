package com.gpmanager.model;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

public class GeOfferProvenanceTest
{
    @Test
    public void normalizesSlotQuantityAndOfferStateButPreservesRawSpentDelta()
    {
        GeOfferProvenance provenance = new GeOfferProvenance(-1, -4L, -125L, " sold ");

        assertEquals(0, provenance.getSlot());
        assertEquals(0L, provenance.getQuantityTradedDelta());
        assertEquals(-125L, provenance.getRawGetSpentDelta());
        assertEquals("SOLD", provenance.getOfferState());
        assertEquals("", new GeOfferProvenance(2, 3L, 4L, null).getOfferState());
    }

    @Test
    public void transactionCopiesMetadataAndItDoesNotChangeAccounting()
    {
        ProfitTransaction transaction = transaction();
        transaction.setGeOfferProvenance(new GeOfferProvenance(3, 2L, 900L, "selling"));
        GeOfferProvenance firstRead = transaction.getGeOfferProvenance();
        GeOfferProvenance secondRead = transaction.getGeOfferProvenance();

        assertNotSame(firstRead, secondRead);
        assertEquals(3, firstRead.getSlot());
        assertEquals(2L, firstRead.getQuantityTradedDelta());
        assertEquals(900L, firstRead.getRawGetSpentDelta());
        assertEquals("SELLING", firstRead.getOfferState());
        assertEquals(TransactionType.LOOT, transaction.getAutomaticType());
        assertEquals(true, transaction.isCounted());
        assertEquals(80L, transaction.getNet());
        assertEquals(2, transaction.getFlows().size());

        transaction.setGeOfferProvenance(null);
        assertNull(transaction.getGeOfferProvenance());
    }

    @Test
    public void presentationProjectionPreservesIndependentOfferMetadataCopy()
    {
        ProfitTransaction original = transaction();
        original.setGeOfferProvenance(new GeOfferProvenance(1, 5L, 725L, "bought"));

        ProfitTransaction projection = original.presentationProjection(flow -> flow.getItemId() == 995);

        GeOfferProvenance projected = projection.getGeOfferProvenance();
        assertNotSame(original.getGeOfferProvenance(), projected);
        assertEquals(1, projected.getSlot());
        assertEquals(5L, projected.getQuantityTradedDelta());
        assertEquals(725L, projected.getRawGetSpentDelta());
        assertEquals("BOUGHT", projected.getOfferState());
        assertEquals(TransactionType.LOOT, projection.getAutomaticType());
        assertEquals(true, projection.isCounted());
        assertEquals(1, projection.getFlows().size());
        assertEquals(995, projection.getFlows().get(0).getItemId());
        assertEquals(80L, original.getNet());
    }

    private static ProfitTransaction transaction()
    {
        return new ProfitTransaction(
            100L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "GE fill",
            true,
            Arrays.asList(
                new ItemFlow(995, "Coins", 100L, 1, 100L),
                new ItemFlow(561, "Nature rune", -2L, 10, -20L)));
    }
}
