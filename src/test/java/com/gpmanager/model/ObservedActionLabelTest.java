package com.gpmanager;

import com.google.gson.Gson;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exact names come only from direct spellbook evidence and stay presentation-only metadata. */
public class ObservedActionLabelTest
{
    private static final int SPELLBOOK_GROUP = 412;

    @Test
    public void directSpellbookNamesAreSanitizedAndRetained()
    {
        int widgetId = SPELLBOOK_GROUP << 16 | 17;
        for (String spell : new String[] {"Ice Burst", "Ice Barrage", "Vengeance", "High Level Alchemy"})
        {
            Bb label = Bb.tv("Cast", widgetId,
                SPELLBOOK_GROUP, "<col=ff9040>  " + spell + "  </col>", "Cast");
            assertNotNull(spell, label);
            assertEquals(spell, label.value());
        }
    }

    @Test
    public void genericCastTargetsAndAutocastNeverBecomeExactSpellNames()
    {
        int spellWidget = SPELLBOOK_GROUP << 16 | 17;
        assertNull(Bb.tv("Cast", spellWidget, SPELLBOOK_GROUP,
            "Cast", "Autocast"));
        assertNull("target text is not supplied to the spell-name extractor",
            Bb.tv("Cast", spellWidget, SPELLBOOK_GROUP,
                "Cast", "Cast"));
        assertNull("NPC/player/item widgets are not spellbook evidence",
            Bb.tv("Cast", 413 << 16 | 17, SPELLBOOK_GROUP,
                "Ice Burst", "Goblin"));
        assertNull("Autocast selection is not correlated to later rune loss",
            Bb.tv("Autocast", spellWidget, SPELLBOOK_GROUP,
                "Ice Burst", "Ice Burst"));
        assertNull(Bb.tv("Use", spellWidget, SPELLBOOK_GROUP,
            "Ice Burst", "Ice Burst"));
    }

    @Test
    public void exactActionLabelIsBackwardCompatibleAndFinanciallyNeutral()
    {
        Ac transaction = new Ac(10L, null,
            Ai.CONSUMPTION, Aj.GENERIC, "", "Vorkath", true,
            Arrays.asList(new Ab(560, "Death rune", -1L, 100, -100L)),
            Bd.CONFIRMED, "Exact rune cost", null);
        transaction.setActionKind(Au.CAST);
        long netBefore = transaction.getNet();
        boolean countedBefore = transaction.isCounted();
        Ai typeBefore = transaction.getType();
        transaction.ahu(Bb.of("Ice Burst"));

        assertEquals(netBefore, transaction.getNet());
        assertEquals(countedBefore, transaction.isCounted());
        assertEquals(typeBefore, transaction.getType());
        assertEquals(108, SavedState.CURRENT_SCHEMA_VERSION);

        Gson gson = new Gson();
        String json = gson.toJson(transaction);
        assertTrue("the bounded exact label persists as schema-105 presentation metadata",
            json.contains("\"observedActionLabel\":\"Ice Burst\""));
        Ac loaded = gson.fromJson(json, Ac.class);
        assertNotNull("a schema-105 receipt keeps its exact label", loaded.uc());
        assertEquals("Ice Burst", loaded.uc().value());
        assertEquals(netBefore, loaded.getNet());
        assertEquals(countedBefore, loaded.isCounted());
        assertEquals(typeBefore, loaded.getType());
    }
}
