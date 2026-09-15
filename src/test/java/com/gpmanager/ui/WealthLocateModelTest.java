package com.gpmanager.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WealthLocateModelTest
{
    @Test
    public void observeMarksLocateOnlyWithoutInventingNetSemantics()
    {
        WealthLocateModel model = new WealthLocateModel();
        assertEquals(WealthLocateModel.Status.NOT_OBSERVED, find(model, "poh").getStatus());
        model.observe("poh", "costume room opened");
        assertEquals(WealthLocateModel.Status.OBSERVED, find(model, "poh").getStatus());
        assertTrue(find(model, "poh").displayLine().contains("costume room opened"));
        assertEquals(WealthLocateModel.Status.NEUTRAL, find(model, "bank").getStatus());
        model.observe("bank", "should not flip neutral");
        assertEquals(WealthLocateModel.Status.NEUTRAL, find(model, "bank").getStatus());
        model.clearObservations();
        assertEquals(WealthLocateModel.Status.NOT_OBSERVED, find(model, "poh").getStatus());
    }

    private static WealthLocateModel.Slot find(WealthLocateModel model, String id)
    {
        for (WealthLocateModel.Slot slot : model.slots())
        {
            if (id.equals(slot.getId()))
            {
                return slot;
            }
        }
        throw new AssertionError("missing slot " + id);
    }
}
