package com.gpmanager;

import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.FontType;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GpDropFontHandlerTest
{
    @Test
    public void legacyExpandedXpMapsToRunescapeSmallFamily()
    {
        FontType type = GpDropFontHandler.fromLegacyStyle(GpDropFontStyle.EXPANDED_XP);
        assertNotNull(type);
        assertNotNull(type.getFont());
        assertEquals(FontManager.getRunescapeSmallFont().getFamily(), type.getFamily());
        assertEquals(16, type.getSize());
        assertFalse(type.isBold());
    }

    @Test
    public void legacyBoldMapsToRunescapeBold()
    {
        FontType type = GpDropFontHandler.fromLegacyStyle(GpDropFontStyle.RUNESCAPE_BOLD);
        assertNotNull(type.getFont());
        assertTrue(type.isBold());
        assertEquals(16, type.getSize());
    }

    @Test
    public void resolveFallsBackWhenNull()
    {
        assertNotNull(GpDropFontHandler.resolve(null));
    }
}
