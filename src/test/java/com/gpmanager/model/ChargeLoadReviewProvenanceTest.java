package com.gpmanager.model;

import com.google.gson.Gson;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

public class ChargeLoadReviewProvenanceTest
{
    @Test
    public void normalizesVariantAndInvalidNumericValues()
    {
        ChargeLoadReviewProvenance provenance = new ChargeLoadReviewProvenance(
            "  TOXIC_BLOWPIPE ", -12934, "  inventory:slot:3  ", -1L);

        assertEquals("toxic_blowpipe", provenance.getVariantWireName());
        assertEquals(0, provenance.getSelectedComponentId());
        assertEquals("inventory:slot:3", provenance.getMatchedTargetIdentity());
        assertEquals(0L, provenance.getObservedAtEpochMillis());
    }

    @Test
    public void transactionCopiesProvenanceOnSetAndGetAndClearsOnNull()
    {
        ProfitTransaction transaction = transaction();
        ChargeLoadReviewProvenance supplied = new ChargeLoadReviewProvenance(
            "trident_swamp", 560, "widget:149:2:item:12899", 1234L);

        transaction.setChargeLoadReviewProvenance(supplied);

        ChargeLoadReviewProvenance firstRead = transaction.getChargeLoadReviewProvenance();
        ChargeLoadReviewProvenance secondRead = transaction.getChargeLoadReviewProvenance();
        assertNotSame(supplied, firstRead);
        assertNotSame(firstRead, secondRead);
        assertEquals("trident_swamp", firstRead.getVariantWireName());
        assertEquals(560, firstRead.getSelectedComponentId());
        assertEquals("widget:149:2:item:12899", firstRead.getMatchedTargetIdentity());
        assertEquals(1234L, firstRead.getObservedAtEpochMillis());

        transaction.setChargeLoadReviewProvenance(null);
        assertNull(transaction.getChargeLoadReviewProvenance());
    }

    @Test
    public void metadataRoundTripsInTransactionSerialization()
    {
        ProfitTransaction original = transaction();
        original.setChargeLoadReviewProvenance(new ChargeLoadReviewProvenance(
            "  blowpipe ", 12934, "inventory:toxic blowpipe", 987654321L));

        ProfitTransaction restored = new Gson().fromJson(
            new Gson().toJson(original), ProfitTransaction.class);

        ChargeLoadReviewProvenance provenance = restored.getChargeLoadReviewProvenance();
        assertEquals("blowpipe", provenance.getVariantWireName());
        assertEquals(12934, provenance.getSelectedComponentId());
        assertEquals("inventory:toxic blowpipe", provenance.getMatchedTargetIdentity());
        assertEquals(987654321L, provenance.getObservedAtEpochMillis());
    }

    @Test
    public void presentationProjectionRetainsIndependentProvenanceCopy()
    {
        ProfitTransaction original = transaction();
        original.setChargeLoadReviewProvenance(new ChargeLoadReviewProvenance(
            "trident_seas", 560, "equipment:trident", 4567L));

        ProfitTransaction projection = original.presentationProjection(flow -> flow.getItemId() == 560);

        ChargeLoadReviewProvenance projected = projection.getChargeLoadReviewProvenance();
        assertNotSame(original.getChargeLoadReviewProvenance(), projected);
        assertEquals("trident_seas", projected.getVariantWireName());
        assertEquals(560, projected.getSelectedComponentId());
        assertEquals("equipment:trident", projected.getMatchedTargetIdentity());
        assertEquals(4567L, projected.getObservedAtEpochMillis());
        assertEquals(1, projection.getFlows().size());
    }

    private static ProfitTransaction transaction()
    {
        return new ProfitTransaction(
            100L,
            TransactionType.UNCERTAIN,
            TrackingContext.GENERIC,
            "Charge load",
            false,
            Arrays.asList(
                new ItemFlow(560, "Death rune", -1L, 200, -200L),
                new ItemFlow(995, "Coins", -1L, 1, -1L)));
    }
}
