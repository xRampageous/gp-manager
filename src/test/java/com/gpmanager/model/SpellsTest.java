package com.gpmanager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Owner report 2026-09-28: autocast Ice Barrage read "Cast · 3 rune types" with no icon. */
public class SpellsTest
{
    private static Flow rune(String name, long spent)
    {
        return new Flow(1, name, -spent, 100, -spent * 100L);
    }

    @Test
    public void theOwnersAutocastReadsAsIceBarrage()
    {
        // The owner's receipts: 4 Death, 2 Blood, 6 Water per cast, no Cast click.
        assertEquals("Ice Barrage", Spells.spellFor(Arrays.asList(rune("Death rune", 4), rune("Blood rune", 2),
            rune("Water rune", 6))));
        assertEquals("two casts in one settle", "Ice Barrage", Spells.spellFor(Arrays.asList(rune("Death rune", 8),
            rune("Blood rune", 4), rune("Water rune", 12))));
        assertEquals("Blood Barrage", Spells.spellFor(Arrays.asList(rune("Death rune", 4), rune("Blood rune", 4),
            rune("Soul rune", 1))));
        assertEquals("a steam rune pays water", "Ice Barrage", Spells.spellFor(Arrays.asList(rune("Death rune", 4),
            rune("Blood rune", 2), rune("Steam rune", 6))));
        assertEquals(-328, Spells.icon("Ice Barrage"));
        assertEquals("no spell, no icon", -1, Spells.icon(""));
    }

    @Test
    public void theContactSpellsUseTheNpcContactSprite()
    {
        // RuneLite 1.13.1 SpriteID.SPELL_NPC_CONTACT = 568; the game labels it Astral Contact now.
        assertEquals(-568, Spells.icon("Astral Contact"));
        assertEquals(-568, Spells.icon("NPC Contact"));
    }

    @Test
    public void arceuusCombatSpellsNameFromTheirRunes()
    {
        // Owner 2026-10-13 gap-fill: these autocast with their runes alone (no click label).
        assertEquals("Ghostly Grasp", Spells.spellFor(Arrays.asList(rune("Air rune", 4), rune("Chaos rune", 1))));
        assertEquals("Inferior Demonbane", Spells.spellFor(Arrays.asList(rune("Fire rune", 3), rune("Chaos rune", 1))));
        assertEquals("Superior Demonbane", Spells.spellFor(Arrays.asList(rune("Fire rune", 5), rune("Soul rune", 1))));
        assertEquals("Dark Demonbane", Spells.spellFor(Arrays.asList(rune("Fire rune", 7), rune("Soul rune", 2))));
        assertEquals("Undead Grasp", Spells.spellFor(Arrays.asList(rune("Fire rune", 12), rune("Blood rune", 1))));
        assertEquals(-1304, Spells.icon("Dark Demonbane"));
    }

    @Test
    public void anAmbiguousOrForeignCostStaysACast()
    {
        // A water staff covers the water, and a smoke staff would make Smoke Barrage cost the same.
        assertNull(Spells.spellFor(Arrays.asList(rune("Death rune", 4), rune("Blood rune", 2))));
        assertNull("uneven casts", Spells.spellFor(Arrays.asList(rune("Death rune", 4), rune("Blood rune", 3),
            rune("Water rune", 6))));
        assertNull("a rune the spell never uses", Spells.spellFor(Arrays.asList(rune("Death rune", 4),
            rune("Blood rune", 2), rune("Water rune", 6), rune("Nature rune", 1))));
        assertNull("not only runes", Spells.spellFor(Arrays.asList(rune("Death rune", 4), rune("Blood rune", 2),
            new Flow(2, "Steel dart", -1L, 4, -4L))));
    }

    @Test
    public void theTableIsWellFormed()
    {
        Set<String> names = new HashSet<>();
        for (String[] spell : Spells.TABLE)
        {
            assertEquals(Arrays.toString(spell), 3, spell.length);
            assertTrue("unique " + spell[0], names.add(spell[0]));
            assertTrue(Integer.parseInt(spell[1]) > 0);
            boolean nonElemental = false;
            for (String part : spell[2].split(" "))
            {
                String[] kv = part.split(":");
                assertTrue(part, Long.parseLong(kv[0]) > 0L);
                nonElemental |= !Spells.ELEMENTS.contains(" " + kv[1] + " ");
            }
            assertTrue("every spell has a rune a staff cannot cover: " + spell[0], nonElemental);
            // Its own full cost always names itself.
            List<Flow> exact = new java.util.ArrayList<>();
            for (String part : spell[2].split(" "))
            {
                String[] kv = part.split(":");
                exact.add(rune(Character.toUpperCase(kv[1].charAt(0)) + kv[1].substring(1) + " rune", Long.parseLong(kv[0])));
            }
            assertEquals(spell[0], Spells.spellFor(exact));
        }
    }
}
