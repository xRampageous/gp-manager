package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.runelite.api.gameval.ItemID;

/**
 * The one authoritative GE tax rule: 2% rounded down per ITEM, capped at 5M per item, integer-only.
 * These are the owner's exact reference/actual rounding cases.
 */
public class GeTaxRuleTest
{
    /** Any taxed (non-exempt) item. */
    private static final int TAXED = ItemID.CHAOSRUNE;

    /** The tax on one item sold at {@code unitPrice}. */
    private static long each(long unitPrice)
    {
        return GeTaxRule.provableTax(TAXED, unitPrice, 1L);
    }

    @Test
    public void perItemTaxFloorsPerItem()
    {
        assertEquals(0L, each(41L));
        assertEquals(0L, each(49L));
        assertEquals(1L, each(50L));
        assertEquals(2L, each(104L));
        assertEquals(3L, each(151L));
        assertEquals(3L, each(157L));
        assertEquals(53L, each(2_669L));
        assertEquals(56L, each(2_802L));
    }

    @Test
    public void stackTaxRoundsPerItemNotOnceOverTheStack()
    {
        assertEquals("41 gp ea is below the tax floor", 0L,
            GeTaxRule.provableTax(TAXED, 41L * 50L, 50L));
        assertEquals(2_000L, GeTaxRule.provableTax(TAXED, 104_000L, 1_000L));
        assertEquals(2_650L, GeTaxRule.provableTax(TAXED, 133_450L, 50L));
        assertEquals(2_800L, GeTaxRule.provableTax(TAXED, 140_100L, 50L));
    }

    @Test
    public void uniformExecutionTaxNeedsAProvableUnitPrice()
    {
        assertEquals(2_000L, GeTaxRule.provableTax(TAXED, 104_000L, 1_000L));
        assertEquals(0L, GeTaxRule.provableTax(TAXED, 2_000L, 50L));
        assertEquals(30L, GeTaxRule.provableTax(TAXED, 1_510L, 10L));
        assertEquals(2_650L, GeTaxRule.provableTax(TAXED, 133_450L, 50L));
        assertEquals("a non-divisible gross cannot prove a per-item price",
            GeTaxRule.UNPROVABLE_TAX, GeTaxRule.provableTax(TAXED, 3_121L, 30L));
    }

    @Test
    public void capAndOverflowStayLongSafe()
    {
        assertEquals(5_000_000L, each(300_000_000L));
        assertEquals(5_000_000L, each(Long.MAX_VALUE));
        assertEquals("the cap keeps even absurd prices bounded",
            10_000_000L, GeTaxRule.provableTax(TAXED, Long.MAX_VALUE - 1L, 2L));
        assertEquals(GeTaxRule.UNPROVABLE_TAX, GeTaxRule.provableTax(TAXED, -5L, 10L));
        assertEquals(GeTaxRule.UNPROVABLE_TAX, GeTaxRule.provableTax(TAXED, 104L, 0L));
    }

    @Test
    public void onlyTheExactRuleGapIsAnAcceptedTax()
    {
        assertEquals(2_000L, GeTaxRule.acceptedTax(TAXED, 104_000L, 1_000L, 102_000L));
        assertEquals("a gap that is not the rule proves nothing",
            0L, GeTaxRule.acceptedTax(TAXED, 104_000L, 1_000L, 102_001L));
        assertEquals("a tax-free stack has no tax to accept",
            0L, GeTaxRule.acceptedTax(TAXED, 2_000L, 50L, 2_000L));
        assertEquals("a mixed-price execution proves nothing",
            0L, GeTaxRule.acceptedTax(TAXED, 3_121L, 30L, 3_061L));
        assertEquals(0L, GeTaxRule.acceptedTax(ItemID.COOKED_CHICKEN, 345L, 5L, 340L));
        assertEquals(0L, GeTaxRule.acceptedTax(TAXED, 104_000L, 1_000L, -1L));
        assertEquals(0L, GeTaxRule.acceptedTax(TAXED, 0L, 1_000L, 0L));
    }

    /**
     * Real-client proof (owner Collect All smoke): the 69 gp cooked chicken paid no tax while a
     * 95 gp steam rune paid the floor. The wiki exemption list is the only thing that reconciles
     * that batch's exact 480 gp gap.
     */
    @Test
    public void wikiExemptionListPaysNothingWhileLookalikesStillPay()
    {
        assertEquals("cooked chicken is exempt at any price",
            0L, GeTaxRule.provableTax(ItemID.COOKED_CHICKEN, 69L, 1L));
        assertTrue(GeTaxRule.EXEMPT_IDS.contains(ItemID.COOKED_CHICKEN));
        assertEquals(0L, GeTaxRule.provableTax(ItemID.COOKED_CHICKEN, 345L, 5L));
        assertEquals("the owner's exact seven-sale batch keeps its real 480 gp tax", 480L,
            GeTaxRule.provableTax(ItemID.DRAGONFRUIT, 3_575L, 5L)
                + GeTaxRule.provableTax(ItemID.PLANK_TEAK, 4_375L, 5L)
                + GeTaxRule.provableTax(ItemID.BOLT_OF_LINEN, 1_915L, 5L)
                + GeTaxRule.provableTax(ItemID.ANGLERFISH, 8_025L, 5L)
                + GeTaxRule.provableTax(ItemID.TBWT_COOKED_KARAMBWAN, 1_995L, 5L)
                + GeTaxRule.provableTax(ItemID.SHARK, 4_800L, 5L)
                + GeTaxRule.provableTax(ItemID.COOKED_CHICKEN, 345L, 5L));
        assertEquals("a low-priced non-exempt consumable still pays the floor",
            1L, GeTaxRule.provableTax(ItemID.STEAMRUNE, 95L, 1L));
        assertFalse(GeTaxRule.EXEMPT_IDS.contains(ItemID.STEAMRUNE));
        assertEquals(200L, GeTaxRule.provableTax(ItemID.STEAMRUNE, 19_000L, 200L));
        assertEquals("an exempt bond stays exempt even at absurd prices",
            0L, GeTaxRule.provableTax(ItemID.OSRS_BOND, 5_000_000_000L, 1L));
        assertEquals("exemption is item identity, never a price threshold",
            2L, GeTaxRule.provableTax(ItemID.CHAOSRUNE, 104L, 1L));
        assertEquals(0L, GeTaxRule.provableTax(ItemID.OAK_LOGS, 41L, 1L));
    }
}
