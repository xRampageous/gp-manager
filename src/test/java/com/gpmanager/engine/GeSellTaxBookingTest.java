package com.gpmanager.engine;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeSellTaxBookingTest
{
    @Test
    public void collectWithPriorCountedSellAnnotatesOnly()
    {
        List<GeSellTaxBooking.PendingSale> pending = new ArrayList<>();
        pending.add(new GeSellTaxBooking.PendingSale(4151, 1L, 1_000, true));
        List<ItemFlow> flows = new ArrayList<>();
        flows.add(new ItemFlow(995, "Coins", 980L, 1, 980L, ItemPriceSource.GRAND_EXCHANGE));

        GeSellTaxBooking.Result result = GeSellTaxBooking.applyOnCollect(
            flows, "Collect", true, pending);

        assertEquals(20L, result.taxBooked);
        assertFalse(result.explicitRow);
        assertEquals(1, result.flows.size());
        assertEquals(GeSellTaxBooking.WHY, result.explanationSuffix);
        assertTrue(pending.isEmpty());
    }

    @Test
    public void collectWithoutPriorCountedSellBooksExplicitTax()
    {
        List<GeSellTaxBooking.PendingSale> pending = new ArrayList<>();
        pending.add(new GeSellTaxBooking.PendingSale(4151, 1L, 1_000, false));
        List<ItemFlow> flows = new ArrayList<>();
        flows.add(new ItemFlow(995, "Coins", 980L, 1, 980L, ItemPriceSource.GRAND_EXCHANGE));

        GeSellTaxBooking.Result result = GeSellTaxBooking.applyOnCollect(
            flows, "Collect-X", true, pending);

        assertEquals(20L, result.taxBooked);
        assertTrue(result.explicitRow);
        assertEquals(2, result.flows.size());
        assertTrue(GeSellTaxBooking.isTaxFlow(result.flows.get(1)));
        assertEquals(-20L, result.flows.get(1).getValueDelta());
    }

    @Test
    public void buyAndPlayerTradeNeverTax()
    {
        List<GeSellTaxBooking.PendingSale> pending = new ArrayList<>();
        pending.add(new GeSellTaxBooking.PendingSale(4151, 1L, 1_000, false));
        List<ItemFlow> flows = new ArrayList<>();
        flows.add(new ItemFlow(995, "Coins", 980L, 1, 980L, ItemPriceSource.GRAND_EXCHANGE));

        assertEquals(0L, GeSellTaxBooking.applyOnCollect(
            flows, "Buy-1 Shark", true, pending).taxBooked);
        assertEquals(0L, GeSellTaxBooking.applyOnCollect(
            flows, "Player trade", true, pending).taxBooked);
    }

    @Test
    public void disabledConfigSkipsTax()
    {
        List<GeSellTaxBooking.PendingSale> pending = new ArrayList<>();
        pending.add(new GeSellTaxBooking.PendingSale(4151, 1L, 1_000, false));
        List<ItemFlow> flows = new ArrayList<>();
        flows.add(new ItemFlow(995, "Coins", 980L, 1, 980L, ItemPriceSource.GRAND_EXCHANGE));

        assertEquals(0L, GeSellTaxBooking.applyOnCollect(
            flows, "Collect", false, pending).taxBooked);
        assertEquals(1, pending.size());
    }

    @Test
    public void collectPrefersCoinMatchedSaleOverFifo()
    {
        // Two pending sells; Collect coins match the second sale's post-tax total.
        List<GeSellTaxBooking.PendingSale> pending = new ArrayList<>();
        pending.add(new GeSellTaxBooking.PendingSale(4151, 1L, 10_000, false)); // ~9800 coins
        pending.add(new GeSellTaxBooking.PendingSale(11802, 1L, 1_000, false)); // ~980 coins
        List<ItemFlow> flows = new ArrayList<>();
        flows.add(new ItemFlow(995, "Coins", 980L, 1, 980L, ItemPriceSource.GRAND_EXCHANGE));

        GeSellTaxBooking.Result result = GeSellTaxBooking.applyOnCollect(
            flows, "Collect", true, pending);

        assertEquals(20L, result.taxBooked);
        assertEquals(1, pending.size());
        assertEquals(4151, pending.get(0).itemId);
    }
}
