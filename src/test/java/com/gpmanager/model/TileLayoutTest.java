package com.gpmanager.model;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class TileLayoutTest
{
    @Test
    public void legacyLayoutLeavesBuiltInOrderAndAllTilesVisible()
    {
        TileLayout layout = new Gson().fromJson("{}", TileLayout.class);

        assertTrue(layout.getPages().isEmpty());
        assertTrue(layout.getOrderedTileIds("live").isEmpty());
        assertTrue(layout.getHiddenTileIds("live").isEmpty());
        assertFalse(layout.isHidden("live", "recent"));
    }

    @Test
    public void pageOrderAndHiddenIdsAreNormalizedAndCopySafe()
    {
        List<String> order = new ArrayList<>(Arrays.asList(" party ", "recent", "party", ""));
        Set<String> hidden = new LinkedHashSet<>(Arrays.asList("recent", "notices"));
        Map<String, TileLayout.PageLayout> pages = new LinkedHashMap<>();
        pages.put(" live ", new TileLayout.PageLayout(order, hidden));
        TileLayout layout = new TileLayout(pages);
        order.clear();
        hidden.clear();
        pages.clear();

        assertEquals(Arrays.asList("party", "recent"), layout.getOrderedTileIds("live"));
        assertEquals(new LinkedHashSet<>(Arrays.asList("recent", "notices")),
            layout.getHiddenTileIds("live"));
        assertTrue(layout.isHidden("live", "recent"));
        assertFalse(layout.isHidden("live", "party"));
    }

    @Test
    public void exposedCollectionsCannotMutateLayoutAndPagesAreIndependent()
    {
        Map<String, TileLayout.PageLayout> pages = new LinkedHashMap<>();
        pages.put("live", new TileLayout.PageLayout(Arrays.asList("party"),
            new LinkedHashSet<>(Arrays.asList("notices"))));
        TileLayout layout = new TileLayout(pages);

        try
        {
            layout.getOrderedTileIds("live").add("recent");
            fail("order view should be immutable");
        }
        catch (UnsupportedOperationException expected)
        {
            // Expected.
        }
        try
        {
            layout.getPages().clear();
            fail("page map should be immutable");
        }
        catch (UnsupportedOperationException expected)
        {
            // Expected.
        }

        assertEquals(Arrays.asList("party"), layout.getOrderedTileIds("live"));
        assertTrue(layout.getOrderedTileIds("ledger").isEmpty());
    }
}
