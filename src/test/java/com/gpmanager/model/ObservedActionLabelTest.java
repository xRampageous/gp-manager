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
            ActionLabel label = ActionLabel.fromSpellMenu("Cast", widgetId,
                SPELLBOOK_GROUP, "<col=ff9040>  " + spell + "  </col>", "Cast");
            assertNotNull(spell, label);
            assertEquals(spell, label.value());
        }
    }

    @Test
    public void genericCastTargetsAndAutocastNeverBecomeExactSpellNames()
    {
        int spellWidget = SPELLBOOK_GROUP << 16 | 17;
        assertNull(ActionLabel.fromSpellMenu("Cast", spellWidget, SPELLBOOK_GROUP,
            "Cast", "Autocast"));
        assertNull("target text is not supplied to the spell-name extractor",
            ActionLabel.fromSpellMenu("Cast", spellWidget, SPELLBOOK_GROUP,
                "Cast", "Cast"));
        assertNull("NPC/player/item widgets are not spellbook evidence",
            ActionLabel.fromSpellMenu("Cast", 413 << 16 | 17, SPELLBOOK_GROUP,
                "Ice Burst", "Goblin"));
        assertNull("Autocast selection is not correlated to later rune loss",
            ActionLabel.fromSpellMenu("Autocast", spellWidget, SPELLBOOK_GROUP,
                "Ice Burst", "Ice Burst"));
        assertNull(ActionLabel.fromSpellMenu("Use", spellWidget, SPELLBOOK_GROUP,
            "Ice Burst", "Ice Burst"));
    }

    @Test
    public void exactActionLabelIsBackwardCompatibleAndFinanciallyNeutral()
    {
        Transaction transaction = new Transaction(10L, null,
            TransactionType.CONSUMPTION, Context.GENERIC, "", "Vorkath", true,
            Arrays.asList(new Flow(560, "Death rune", -1L, 100, -100L)),
            ClassificationConfidence.CONFIRMED, "Exact rune cost", null);
        transaction.setActionKind(ActionKind.CAST);
        long netBefore = transaction.getNet();
        boolean countedBefore = transaction.isCounted();
        TransactionType typeBefore = transaction.getType();
        transaction.setObservedActionLabel(ActionLabel.of("Ice Burst"));

        assertEquals(netBefore, transaction.getNet());
        assertEquals(countedBefore, transaction.isCounted());
        assertEquals(typeBefore, transaction.getType());
        assertEquals(108, SavedState.CURRENT_SCHEMA_VERSION);

        Gson gson = new Gson();
        String json = gson.toJson(transaction);
        assertTrue("the bounded exact label persists as schema-105 presentation metadata",
            json.contains("\"observedActionLabel\":\"Ice Burst\""));
        Transaction loaded = gson.fromJson(json, Transaction.class);
        assertNotNull("a schema-105 receipt keeps its exact label", loaded.getObservedActionLabel());
        assertEquals("Ice Burst", loaded.getObservedActionLabel().value());
        assertEquals(netBefore, loaded.getNet());
        assertEquals(countedBefore, loaded.isCounted());
        assertEquals(typeBefore, loaded.getType());
    }
}
