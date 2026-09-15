package com.gpmanager.ui.bento;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The palette filters by every word of the query over group, title and subtitle, and Enter opens the first hit. */
public class SearchPageTest
{
    @Test
    public void everyWordMustMatchAndHitsGroup()
    {
        List<String> opened = new ArrayList<>();
        SearchPage.Source source = new SearchPage.Source()
        {
            @Override
            public List<SearchPage.Hit> hits(String query)
            {
                return Arrays.asList(
                    new SearchPage.Hit("Items", Icon.glyph("◈", BentoTheme.MUTED), "Draconic visage", "Gains · this session", "+5.2M", BentoTheme.POSITIVE, () -> opened.add("item")),
                    new SearchPage.Hit("Sessions", Icon.glyph("◈", BentoTheme.MUTED), "Vorkath", "Bossing · Yesterday 20:45", "+1.2M", BentoTheme.POSITIVE, () -> opened.add("session")),
                    new SearchPage.Hit("Settings", Icon.glyph("⚙", BentoTheme.MUTED), "PvP layout", "Preferences › Appearance", null, null, () -> opened.add("setting")));
            }

            @Override
            public void back()
            {
                opened.add("back");
            }
        };
        SearchPage page = new SearchPage(source);
        page.searchForPreview("vork");
        assertEquals(1, page.resultCountForTest());
        page.searchForPreview("pvp appearance");
        assertEquals(1, page.resultCountForTest());
        page.searchForPreview("session");
        // "session" appears in the item's subtitle and the Sessions group name.
        assertEquals(2, page.resultCountForTest());
        page.searchForPreview("nothing here");
        assertEquals(0, page.resultCountForTest());
        page.searchForPreview("");
        assertEquals(0, page.resultCountForTest());
        assertTrue(opened.isEmpty());

        SearchPage.Hit hit = source.hits("x").get(0);
        assertTrue(hit.matches("draconic"));
        assertTrue(hit.matches("gains visage"));
        assertFalse(hit.matches("draconic settings"));
    }
}
