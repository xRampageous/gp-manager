package com.gpmanager;

import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ItemRuleParserTest
{
    @Test
    public void parsesNonNegativePriceOverrides()
    {
        Map<Integer, Integer> values =
            ItemRuleParser.parsePriceOverrides("995=1, 526=31, bad, 100=-2");
        assertEquals(2, values.size());
        assertEquals(Integer.valueOf(1), values.get(995));
        assertEquals(Integer.valueOf(31), values.get(526));
    }
}
