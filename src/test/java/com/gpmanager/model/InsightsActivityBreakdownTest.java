package com.gpmanager.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class InsightsActivityBreakdownTest
{
    @Test
    public void rollsSpecificSourcesIntoGeneralizedCategories()
    {
        Map<String, Long> nets = new LinkedHashMap<>();
        nets.put("Woodcutting", 1_000L);
        nets.put("Fishing", -200L);
        nets.put("Chicken", 500L);
        nets.put("Chambers of Xeric", 2_000L);
        nets.put("PKing", -800L);

        InsightsActivityBreakdown breakdown = InsightsActivityBreakdown.from(nets);
        assertEquals(4, breakdown.getCategories().size());

        InsightsActivityBreakdown.CategoryRow skilling = breakdown.find("Skilling");
        assertNotNull(skilling);
        assertEquals(800L, skilling.getNet());
        assertEquals(Long.valueOf(1_000L), skilling.getSources().get("Woodcutting"));
        assertEquals(Long.valueOf(-200L), skilling.getSources().get("Fishing"));

        InsightsActivityBreakdown.CategoryRow pvm = breakdown.find("PvM");
        assertNotNull(pvm);
        assertEquals(500L, pvm.getNet());
        assertTrue(pvm.getSources().containsKey("Chicken"));

        InsightsActivityBreakdown.CategoryRow raids = breakdown.find("Raids");
        assertNotNull(raids);
        assertEquals(2_000L, raids.getNet());

        InsightsActivityBreakdown.CategoryRow pk = breakdown.find("PKing");
        assertNotNull(pk);
        assertEquals(-800L, pk.getNet());
    }

    @Test
    public void combatSkillsBucketToPvmNotSkilling()
    {
        assertEquals(InsightsActivityCategory.PVM, InsightsActivityBreakdown.categorize("Attack"));
        assertEquals(InsightsActivityCategory.SKILLING, InsightsActivityBreakdown.categorize("Crafting"));
        assertEquals(InsightsActivityCategory.SKILLING, InsightsActivityBreakdown.categorize("Prayer"));
        assertTrue(InsightsActivityBreakdown.isGeneralizedCategory("Skilling"));
        assertTrue(InsightsActivityBreakdown.isGeneralizedCategory("PvM"));
        assertFalse(InsightsActivityBreakdown.isGeneralizedCategory("Crafting"));
        assertTrue(InsightsActivityBreakdown.isSpecificNpcActivityTitle("Dark wizard"));
        assertTrue(InsightsActivityBreakdown.isSpecificNpcActivityTitle("Chicken"));
        assertFalse(InsightsActivityBreakdown.isSpecificNpcActivityTitle("Attack"));
        assertFalse(InsightsActivityBreakdown.isSpecificNpcActivityTitle("Barrows"));
        assertFalse(InsightsActivityBreakdown.isSpecificNpcActivityTitle("Crafting"));
    }

    @Test
    public void keyChestActivitiesGroupUnderMisc()
    {
        assertEquals(InsightsActivityCategory.OTHER,
            InsightsActivityBreakdown.categorize("Brimstone chest"));
        assertEquals(InsightsActivityCategory.OTHER,
            InsightsActivityBreakdown.categorize("Gold Chest (purple)"));
        assertEquals(InsightsActivityCategory.OTHER,
            InsightsActivityBreakdown.categorize("Larran's big chest"));
    }
}
