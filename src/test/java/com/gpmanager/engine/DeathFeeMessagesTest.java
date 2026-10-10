package com.gpmanager;

import java.util.regex.Matcher;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Fees paid from Death's Coffer or the bank, read from the game's own lines (research 1.2; texts
 * as logged by the death-cost-tracker plugin). The plugin lower-cases chat before matching.
 */
public class DeathFeeMessagesTest
{
    private static long amount(String key, String message)
    {
        Matcher m = GameData.pattern(key).matcher(message.toLowerCase());
        assertTrue(message, m.matches());
        return ChargeCheck.parseCount(m.group(1));
    }

    @Test
    public void theWholeReclaimFeeWhateverPaidIt()
    {
        assertEquals(315_000L, amount("death-fee", "Death charges you 315,000 x Coins. You have 1,234 x Coins left in Death's Coffer."));
        assertEquals(42_000L, amount("death-fee", "Death charges you 42,000 x Coins. You have nothing left in Death's Coffer."));
    }

    @Test
    public void bankSharesInBothSpellings()
    {
        assertEquals(180_096L, amount("bank-payment", "Payment has been taken from your bank: 180,096 x Coins"));
        assertEquals(5_964L, amount("bank-payment", "Payment has been taken from your bank: 5,964 coins."));
    }

    @Test
    public void instanceFeesFromTheCoffer()
    {
        assertEquals(25_000L, amount("coffer-payment", "Payment has been taken from your coffer: 25,000 x Coins."));
        assertFalse(GameData.pattern("coffer-payment").matcher("you have 25,000 x coins left in death's coffer.").matches());
    }
}
