package com.gpmanager;

import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class KeyChestCatalogueTest
{
    /** Exact chest-name lookup, case-insensitive and whitespace-trimmed. */
    private static KeyChestCatalogue.Entry entryForChest(String chestName)
    {
        for (KeyChestCatalogue.Entry entry : KeyChestCatalogue.entries())
        {
            if (normalize(entry.getChestName()).equals(normalize(chestName)))
            {
                return entry;
            }
        }
        return null;
    }

    /** True when the key's first mapped chest keeps the GE opportunity cost. */
    private static boolean isTradeableKey(int keyItemId)
    {
        java.util.List<KeyChestCatalogue.Entry> entries = KeyChestCatalogue.rs(keyItemId);
        return !entries.isEmpty() && entries.get(0).isTradeable();
    }

    private static String normalize(String value)
    {
        return value.trim().replace('’', '\'').toLowerCase(java.util.Locale.ROOT);
    }

    @Test public void cataloguesAllRequestedTradeableAndDeferredKeys()
    {
        assertEquals(35, KeyChestCatalogue.entries().size());
        assertTradeable(ItemID.CRYSTAL_KEY, "Crystal chest");
        assertTradeable(ItemID.SLAYER_WILDERNESS_KEY, "Larran's small chest");
        assertNotNull(entryForChest("Larran's big chest"));
        assertTradeable(ItemID.ZOGRE_COFFINKEY, "Ogre coffin");
        assertTradeable(ItemID.MUDDY_KEY, "Muddy chest");
        assertTradeable(ItemID.SINISTER_KEY, "Sinister chest");
        assertTradeable(ItemID.HOSDUN_GRUBBY_KEY, "Grubby chest");

        assertDeferred(ItemID.KONAR_KEY, "Brimstone chest");
        assertDeferred(ItemID.PRIF_CRYSTAL_KEY, "Elven Crystal Chest");
        assertDeferred(ItemID.VARLAMORE_NICE_KEY, "Moon chest");
        assertDeferred(ItemID.SHADEKEY_BRONZE_BLOODRED, "Bronze Chest (red)");
        assertDeferred(ItemID.SHADEKEY_STEEL_BROWN, "Steel Chest (brown)");
        assertDeferred(ItemID.SHADEKEY_BLACK_CRIMSON, "Black Chest (crimson)");
        assertDeferred(ItemID.SHADEKEY_SILVER_BLACK, "Silver Chest (black)");
        assertDeferred(ItemID.SHADEKEY_GOLD_PURPLE, "Gold Chest (purple)");
    }

    @Test public void everyShadeMetalColorPairIsDeferred()
    {
        int[] shadeKeys = {
            ItemID.SHADEKEY_BRONZE_BLOODRED, ItemID.SHADEKEY_BRONZE_BROWN,
            ItemID.SHADEKEY_BRONZE_CRIMSON, ItemID.SHADEKEY_BRONZE_BLACK, ItemID.SHADEKEY_BRONZE_PURPLE,
            ItemID.SHADEKEY_STEEL_BLOODRED, ItemID.SHADEKEY_STEEL_BROWN,
            ItemID.SHADEKEY_STEEL_CRIMSON, ItemID.SHADEKEY_STEEL_BLACK, ItemID.SHADEKEY_STEEL_PURPLE,
            ItemID.SHADEKEY_BLACK_BLOODRED, ItemID.SHADEKEY_BLACK_BROWN,
            ItemID.SHADEKEY_BLACK_CRIMSON, ItemID.SHADEKEY_BLACK_BLACK, ItemID.SHADEKEY_BLACK_PURPLE,
            ItemID.SHADEKEY_SILVER_BLOODRED, ItemID.SHADEKEY_SILVER_BROWN,
            ItemID.SHADEKEY_SILVER_CRIMSON, ItemID.SHADEKEY_SILVER_BLACK, ItemID.SHADEKEY_SILVER_PURPLE,
            ItemID.SHADEKEY_GOLD_BLOODRED, ItemID.SHADEKEY_GOLD_BROWN,
            ItemID.SHADEKEY_GOLD_CRIMSON, ItemID.SHADEKEY_GOLD_BLACK, ItemID.SHADEKEY_GOLD_PURPLE
        };
        for (int keyId : shadeKeys)
        {
            assertTrue("Shade key " + keyId + " is deferred", KeyChestCatalogue.wg(keyId));
            assertFalse("Shade key " + keyId + " is not tradeable", isTradeableKey(keyId));
            assertEquals(1, KeyChestCatalogue.rs(keyId).size());
        }
    }

    @Test public void unknownKeyAndTradeableKeysAreNeverDeferred()
    {
        assertFalse(KeyChestCatalogue.wg(995));
        assertFalse(KeyChestCatalogue.wg(ItemID.CRYSTAL_KEY));
        assertFalse(KeyChestCatalogue.wg(ItemID.SLAYER_WILDERNESS_KEY));
        assertTrue(isTradeableKey(ItemID.CRYSTAL_KEY));
    }

    private static void assertTradeable(int keyId, String chestName)
    {
        KeyChestCatalogue.Entry entry = entryForChest(chestName);
        assertNotNull(chestName, entry);
        assertEquals(keyId, entry.getKeyItemId());
        assertTrue(chestName, entry.isTradeable());
            assertTrue(chestName, entry.isTradeable());
    }

    private static void assertDeferred(int keyId, String chestName)
    {
        KeyChestCatalogue.Entry entry = entryForChest(chestName);
        assertNotNull(chestName, entry);
        assertEquals(keyId, entry.getKeyItemId());
        assertFalse(chestName, entry.isTradeable());
            assertFalse(chestName, entry.isTradeable());
    }
}
