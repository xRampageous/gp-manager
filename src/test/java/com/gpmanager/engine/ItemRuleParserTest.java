package com.gpmanager.engine;

import java.util.Map;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ItemRuleParserTest
{
    @Test
    public void parsesIgnoredIdsAndSkipsInvalidTokens()
    {
        Set<Integer> values = ItemRuleParser.parseIgnoredIds("995, 526, nope, -1");
        assertEquals(2, values.size());
        assertTrue(values.contains(995));
        assertTrue(values.contains(526));
        assertFalse(values.contains(-1));
    }

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
