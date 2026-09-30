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
        assertEquals(0L, GeTaxRule.adr(41L));
        assertEquals(0L, GeTaxRule.adr(49L));
        assertEquals(1L, GeTaxRule.adr(50L));
        assertEquals(2L, GeTaxRule.adr(104L));
        assertEquals(3L, GeTaxRule.adr(151L));
        assertEquals(3L, GeTaxRule.adr(157L));
        assertEquals(53L, GeTaxRule.adr(2_669L));
        assertEquals(56L, GeTaxRule.adr(2_802L));
    }

    @Test
    public void stackTaxRoundsPerItemNotOnceOverTheStack()
    {
        assertEquals("41 gp ea is below the tax floor", 0L, GeTaxRule.axc(41L, 50L));
        assertEquals(2_000L, GeTaxRule.axc(104L, 1_000L));
        assertEquals(2_650L, GeTaxRule.axc(2_669L, 50L));
        assertEquals(2_800L, GeTaxRule.axc(2_802L, 50L));
    }

    @Test
    public void uniformExecutionTaxNeedsAProvableUnitPrice()
    {
        assertEquals(2_000L, GeTaxRule.ake(104_000L, 1_000L));
        assertEquals(0L, GeTaxRule.ake(2_000L, 50L));
        assertEquals(30L, GeTaxRule.ake(1_510L, 10L));
        assertEquals(2_650L, GeTaxRule.ake(133_450L, 50L));
        assertEquals("a non-divisible gross cannot prove a per-item price",
            0L, GeTaxRule.ake(3_121L, 30L));
    }

    @Test
    public void capAndOverflowStayLongSafe()
    {
        assertEquals(GeTaxRule.MAX_TAX_PER_ITEM_GP, GeTaxRule.adr(300_000_000L));
        assertEquals(GeTaxRule.MAX_TAX_PER_ITEM_GP, GeTaxRule.adr(Long.MAX_VALUE));
        assertEquals("the cap keeps even absurd prices bounded",
            10_000_000L, GeTaxRule.axc(Long.MAX_VALUE, 2L));
        assertEquals("an overflowing stack is unpriceable, never guessed",
            0L, GeTaxRule.axc(300_000_000L, Long.MAX_VALUE));
        assertEquals(0L, GeTaxRule.ake(-5L, 10L));
        assertEquals(0L, GeTaxRule.axc(104L, 0L));
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
            0L, GeTaxRule.adr(ItemID.COOKED_CHICKEN, 69L));
        assertEquals(0L, GeTaxRule.axc(ItemID.COOKED_CHICKEN, 69L, 5L));
        assertEquals(0L, GeTaxRule.ake(ItemID.COOKED_CHICKEN, 345L, 5L));
        assertEquals("the owner's exact seven-sale batch keeps its real 480 gp tax", 480L,
            GeTaxRule.ake(ItemID.DRAGONFRUIT, 3_575L, 5L)
                + GeTaxRule.ake(ItemID.PLANK_TEAK, 4_375L, 5L)
                + GeTaxRule.ake(ItemID.BOLT_OF_LINEN, 1_915L, 5L)
                + GeTaxRule.ake(ItemID.ANGLERFISH, 8_025L, 5L)
                + GeTaxRule.ake(ItemID.TBWT_COOKED_KARAMBWAN, 1_995L, 5L)
                + GeTaxRule.ake(ItemID.SHARK, 4_800L, 5L)
                + GeTaxRule.ake(ItemID.COOKED_CHICKEN, 345L, 5L));
        assertEquals("a low-priced non-exempt consumable still pays the floor",
            1L, GeTaxRule.adr(ItemID.STEAMRUNE, 95L));
        assertEquals(200L, GeTaxRule.axc(ItemID.STEAMRUNE, 95L, 200L));
        assertEquals("an exempt bond stays exempt even at absurd prices",
            0L, GeTaxRule.adr(ItemID.OSRS_BOND, 5_000_000_000L));
        assertEquals("exemption is item identity, never a price threshold",
            2L, GeTaxRule.adr(ItemID.CHAOSRUNE, 104L));
        assertEquals(0L, GeTaxRule.adr(ItemID.OAK_LOGS, 41L));
    }
}
