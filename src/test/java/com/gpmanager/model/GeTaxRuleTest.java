package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

import net.runelite.api.gameval.ItemID;

/**
 * The one authoritative GE tax rule: 2% rounded down per ITEM, capped at 5M per item, integer-only.
 * These are the owner's exact reference/actual rounding cases.
 */
public class GeTaxRuleTest
{
    @Test
    public void perItemTaxFloorsPerItem()
    {
        assertEquals(0L, GeTaxRule.perItemTax(41L));
        assertEquals(0L, GeTaxRule.perItemTax(49L));
        assertEquals(1L, GeTaxRule.perItemTax(50L));
        assertEquals(2L, GeTaxRule.perItemTax(104L));
        assertEquals(3L, GeTaxRule.perItemTax(151L));
        assertEquals(3L, GeTaxRule.perItemTax(157L));
        assertEquals(53L, GeTaxRule.perItemTax(2_669L));
        assertEquals(56L, GeTaxRule.perItemTax(2_802L));
    }

    @Test
    public void stackTaxRoundsPerItemNotOnceOverTheStack()
    {
        assertEquals("41 gp ea is below the tax floor", 0L, GeTaxRule.stackTax(41L, 50L));
        assertEquals(2_000L, GeTaxRule.stackTax(104L, 1_000L));
        assertEquals(2_650L, GeTaxRule.stackTax(2_669L, 50L));
        assertEquals(2_800L, GeTaxRule.stackTax(2_802L, 50L));
    }

    @Test
    public void uniformExecutionTaxNeedsAProvableUnitPrice()
    {
        assertEquals(2_000L, GeTaxRule.uniformStackTax(104_000L, 1_000L));
        assertEquals(0L, GeTaxRule.uniformStackTax(2_000L, 50L));
        assertEquals(30L, GeTaxRule.uniformStackTax(1_510L, 10L));
        assertEquals(2_650L, GeTaxRule.uniformStackTax(133_450L, 50L));
        assertEquals("a non-divisible gross cannot prove a per-item price",
            0L, GeTaxRule.uniformStackTax(3_121L, 30L));
    }

    @Test
    public void capAndOverflowStayLongSafe()
    {
        assertEquals(GeTaxRule.MAX_TAX_PER_ITEM_GP, GeTaxRule.perItemTax(300_000_000L));
        assertEquals(GeTaxRule.MAX_TAX_PER_ITEM_GP, GeTaxRule.perItemTax(Long.MAX_VALUE));
        assertEquals("the cap keeps even absurd prices bounded",
            10_000_000L, GeTaxRule.stackTax(Long.MAX_VALUE, 2L));
        assertEquals("an overflowing stack is unpriceable, never guessed",
            0L, GeTaxRule.stackTax(300_000_000L, Long.MAX_VALUE));
        assertEquals(0L, GeTaxRule.uniformStackTax(-5L, 10L));
        assertEquals(0L, GeTaxRule.stackTax(104L, 0L));
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
            0L, GeTaxRule.perItemTax(ItemID.COOKED_CHICKEN, 69L));
        assertEquals(0L, GeTaxRule.stackTax(ItemID.COOKED_CHICKEN, 69L, 5L));
        assertEquals(0L, GeTaxRule.uniformStackTax(ItemID.COOKED_CHICKEN, 345L, 5L));
        assertEquals("the owner's exact seven-sale batch keeps its real 480 gp tax", 480L,
            GeTaxRule.uniformStackTax(ItemID.DRAGONFRUIT, 3_575L, 5L)
                + GeTaxRule.uniformStackTax(ItemID.PLANK_TEAK, 4_375L, 5L)
                + GeTaxRule.uniformStackTax(ItemID.BOLT_OF_LINEN, 1_915L, 5L)
                + GeTaxRule.uniformStackTax(ItemID.ANGLERFISH, 8_025L, 5L)
                + GeTaxRule.uniformStackTax(ItemID.TBWT_COOKED_KARAMBWAN, 1_995L, 5L)
                + GeTaxRule.uniformStackTax(ItemID.SHARK, 4_800L, 5L)
                + GeTaxRule.uniformStackTax(ItemID.COOKED_CHICKEN, 345L, 5L));
        assertEquals("a low-priced non-exempt consumable still pays the floor",
            1L, GeTaxRule.perItemTax(ItemID.STEAMRUNE, 95L));
        assertEquals(200L, GeTaxRule.stackTax(ItemID.STEAMRUNE, 95L, 200L));
        assertEquals("an exempt bond stays exempt even at absurd prices",
            0L, GeTaxRule.perItemTax(ItemID.OSRS_BOND, 5_000_000_000L));
        assertEquals("exemption is item identity, never a price threshold",
            2L, GeTaxRule.perItemTax(ItemID.CHAOSRUNE, 104L));
        assertEquals(0L, GeTaxRule.perItemTax(ItemID.OAK_LOGS, 41L));
    }
}
