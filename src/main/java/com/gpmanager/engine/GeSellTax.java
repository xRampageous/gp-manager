package com.gpmanager.engine;

/**
 * Grand Exchange seller tax: 2% per item, rounded down, capped at 5,000,000 GP
 * per item. Exempt item ids pay 0. Buyers / player trades / shops: not used.
 */
public final class GeSellTax
{
    public static final int RATE_BPS = 200; // 2%
    public static final long CAP_PER_ITEM = 5_000_000L;

    private GeSellTax()
    {
    }

    /**
     * Tax for {@code qty} items sold at {@code unitPrice} each.
     * Returns 0 when disabled math would be zero (price &lt; 50 at 2%) or exempt.
     */
    public static long taxForSale(int unitPrice, long qty, boolean exempt)
    {
        if (exempt || unitPrice <= 0 || qty <= 0)
        {
            return 0L;
        }
        long perItem = Math.min(CAP_PER_ITEM, (unitPrice * (long) RATE_BPS) / 10_000L);
        try
        {
            return Math.multiplyExact(perItem, qty);
        }
        catch (ArithmeticException ex)
        {
            return Long.MAX_VALUE;
        }
    }

    /**
     * When both pre-tax sale total and coins received are known, prefer the
     * observed shortfall when it matches the formula (±1 gp per unit tolerance
     * on the aggregate). Otherwise return {@code formulaTax}.
     */
    public static long reconcileTax(long preTaxSaleTotal, long coinsReceived, long formulaTax)
    {
        if (preTaxSaleTotal <= 0L || coinsReceived < 0L)
        {
            return Math.max(0L, formulaTax);
        }
        long shortfall = preTaxSaleTotal - coinsReceived;
        if (shortfall < 0L)
        {
            return Math.max(0L, formulaTax);
        }
        long delta = Math.abs(shortfall - formulaTax);
        if (delta <= Math.max(1L, formulaTax / 1000L))
        {
            return shortfall;
        }
        return Math.max(0L, formulaTax);
    }
}
