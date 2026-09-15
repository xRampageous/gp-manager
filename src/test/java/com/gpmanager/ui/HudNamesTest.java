package com.gpmanager.ui;

import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JComponent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class HudNamesTest
{
    @Test
    public void exactHudNameTableIsApplied()
    {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Grand Exchange Clerk", "GE Clerk");
        expected.put("Grand Exchange booth", "GE booth");
        expected.put("Bank deposit box", "Deposit box");
        expected.put("Priestess Zul-Gwenwynig", "Zul-Gwenwynig");
        expected.put("Orrvor quo Maten", "Orrvor");
        expected.put("Mysterious Stranger", "Stranger");
        expected.put("Alchemical Hydra", "Alch. Hydra");
        expected.put("Grotesque Guardians", "Grotesques");
        expected.put("Phosani's Nightmare", "Phosani");
        expected.put("Corrupted Gauntlet", "CG");
        expected.put("The Gauntlet", "Gauntlet");
        expected.put("Crystalline Hunllef", "Hunllef");
        expected.put("Corrupted Hunllef", "C. Hunllef");
        expected.put("King Black Dragon", "KBD");
        expected.put("Kalphite Queen", "KQ");
        expected.put("Corporeal Beast", "Corp");
        expected.put("Abyssal Sire", "Sire");
        expected.put("Thermonuclear smoke devil", "Thermy");
        expected.put("Commander Zilyana", "Zilyana");
        expected.put("General Graardor", "Graardor");
        expected.put("K'ril Tsutsaroth", "K'ril");
        expected.put("Kree'arra", "Kree'arra");
        expected.put("Fossil Island Wyvern (Ancient Wyvern)", "Wyvern");
        expected.put("Fossil Island Wyvern (Long-tailed Wyvern)", "Wyvern");
        expected.put("Fossil Island Wyvern (Spitting Wyvern)", "Wyvern");
        expected.put("Fossil Island Wyvern (Taloned Wyvern)", "Wyvern");
        expected.put("Dagannoth Prime", "Dag Prime");
        expected.put("Dagannoth Rex", "Dag Rex");
        expected.put("Dagannoth Supreme", "Dag Supreme");
        expected.put("Phantom Muspah", "Muspah");
        expected.put("Duke Sucellus", "Duke");
        expected.put("Crazy archaeologist", "Crazy arch");
        expected.put("Chaos Elemental", "Chaos Ele");
        expected.put("Brutal black dragon", "Brutal black");
        expected.put("Greater Nechryael", "G. Nechryael");
        expected.put("Mutated Bloodveld", "M. Bloodveld");
        expected.put("Lizardman shaman", "Shaman");
        expected.put("Aberrant spectre", "Ab. spectre");
        expected.put("Deviant spectre", "Dev. spectre");
        expected.put("Basilisk Knight", "Bas. Knight");
        expected.put("Amethyst crystals", "Amethyst");
        expected.put("Blisterwood tree", "Blisterwood");
        expected.put("Motherlode Mine", "Motherlode");
        expected.put("Volcanic Mine", "Volcanic");
        expected.put("Elven Crystal Chest", "Elven chest");
        expected.put("Larran's big chest", "Larran's chest");
        expected.put("Larran's small chest", "Larran's chest");
        expected.put("Raid / boss retrieval chest", "Retrieval chest");
        expected.put("Item Retrieval Service", "Retrieval");
        expected.put("Tool Leprechaun store", "Leprechaun");
        expected.put("GIM shared storage deposit", "Group storage");
        expected.put("GIM shared storage withdraw", "Group storage");
        expected.put("Coffer / minigame storage", "Coffer");
        expected.put("Sorceress's Garden", "Sorc. Garden");
        expected.put("Pyramid Plunder", "Plunder");
        expected.put("Rogues' Den", "Rogues' Den");
        expected.put("Hallowed Sepulchre", "Sepulchre");
        expected.put("Fortis Colosseum", "Colosseum");
        expected.put("Guardians of the Rift", "GotR");
        expected.put("Theatre of Blood (Entry)", "ToB (Entry)");
        expected.put("Theatre of Blood (Entry Mode)", "ToB (Entry)");
        expected.put("Theatre of Blood (Hard Mode)", "ToB (HM)");
        expected.put("Chambers of Xeric (Challenge Mode)", "CoX (CM)");
        expected.put("Tombs of Amascut (Expert)", "ToA (Expert)");

        for (Map.Entry<String, String> row : expected.entrySet())
        {
            assertEquals(row.getKey(), row.getValue(), HudNames.compact(row.getKey()));
        }
    }

    @Test
    public void genericRewritesKeepRecognisableNamesAndDropKnownQualifier()
    {
        assertEquals("GE teleport", HudNames.compact("Grand Exchange teleport"));
        assertEquals("ToB entry", HudNames.compact("Theatre of Blood entry"));
        assertEquals("CoX room", HudNames.compact("Chambers of Xeric room"));
        assertEquals("ToA room", HudNames.compact("Tombs of Amascut room"));
        assertEquals("GotR", HudNames.compact("Guardians of the Rift"));
        assertEquals("Sepulchre", HudNames.compact("Hallowed Sepulchre"));
        assertEquals("Colosseum", HudNames.compact("Fortis Colosseum"));
        assertEquals("Nightmare", HudNames.compact("The Nightmare"));
        assertEquals("Leviathan", HudNames.compact("The Leviathan"));
        assertEquals("Whisperer", HudNames.compact("The Whisperer"));
        assertEquals("Shade chest", HudNames.compact("Shade chest (Shades of Mort'ton)"));
        assertEquals("Goblin", HudNames.compact("Goblin"));
    }

    @Test
    public void trailingQualifiersAreDroppedForAnyNameNotInTheTable()
    {
        // Owner example: shop NPCs carry a trade qualifier the HUD does not need.
        assertEquals("Hofuthand", HudNames.compact("Hofuthand (weapons and armor)"));
        assertEquals("Thessalia", HudNames.compact("Thessalia (clothes)"));
        assertEquals("Banker", HudNames.compact("Banker (Varrock)"));
        assertEquals("Zaff", HudNames.compact("Zaff (staffs) (Varrock)"));
        // Nothing before the bracket: leave it alone rather than blank the title.
        assertEquals("(unnamed)", HudNames.compact("(unnamed)"));
        // Batch/kill suffixes still compose after the qualifier is dropped.
        assertEquals("Hofuthand \u00d74", HudNames.compact("Hofuthand (weapons and armor) \u00d74"));
    }

    @Test
    public void raidModeQualifiersAreShortenedNotDropped()
    {
        assertEquals("ToB (Story)", HudNames.compact("Theatre of Blood (Story Mode)"));
        assertEquals("ToB (HM)", HudNames.compact("Theatre of Blood (Hard Mode)"));
        assertEquals("CoX (CM)", HudNames.compact("Chambers of Xeric (Challenge Mode)"));
        assertEquals("ToA (Expert)", HudNames.compact("Tombs of Amascut (Expert Mode)"));
        assertEquals("ToA (Normal)", HudNames.compact("Tombs of Amascut (Normal Mode)"));
    }

    @Test
    public void narrowWidthTrimsWholeWordsBeforeCuttingInsideOne()
    {
        FontMetrics metrics = metrics();
        String name = "Elite Void Knight of Pest Control";
        int twoWords = metrics.stringWidth("Elite Void\u2026") + 2;
        assertEquals("Elite Void\u2026", HudNames.compact(name, twoWords, metrics));
        int oneWord = metrics.stringWidth("Elite\u2026") + 2;
        assertEquals("Elite\u2026", HudNames.compact(name, oneWord, metrics));
        // Below one word the character ellipsis is the only thing left.
        String tiny = HudNames.compact(name, metrics.stringWidth("Eli\u2026") + 1, metrics);
        assertTrue(tiny.endsWith("\u2026") && tiny.length() < "Elite".length());
        // Wide enough: untouched.
        assertEquals(name, HudNames.compact(name, 400, metrics));
    }

    @Test
    public void widthAwareWyvernNameKeepsVariantWhenItFits()
    {
        FontMetrics metrics = metrics();
        for (String variant : new String[] {"Ancient", "Long-tailed", "Spitting", "Taloned"})
        {
            String full = "Fossil Island Wyvern (" + variant + " Wyvern)";
            String narrow = HudNames.compact(full, 120, metrics);
            String wide = HudNames.compact(full, 260, metrics);

            assertEquals(variant + " Wyvern", narrow);
            assertEquals(full, wide);
            assertTrue(metrics.stringWidth(narrow) < metrics.stringWidth(wide));
        }
    }

    @Test
    public void titleWidthCompactionPreservesSessionStatusAndBatchSuffixes()
    {
        FontMetrics metrics = metrics();
        assertEquals("Session · PAUSED · GE Clerk ×3",
            HudNames.compact("Session · PAUSED · Grand Exchange Clerk ×3", 260, metrics));
    }

    @Test
    public void ledgerKeepsFullActivityName()
    {
        ProfitTransaction transaction = new ProfitTransaction(
            1_000L, null, TransactionType.GAIN, TrackingContext.GENERIC,
            "", "Grand Exchange Clerk", true, Collections.emptyList());

        // Only HUD+ titles compact; the Ledger and receipts keep the full name.
        assertEquals("Grand Exchange Clerk", transaction.getActivityName());
        assertNotEquals("GE Clerk", transaction.getActivityName());
        assertEquals("GE Clerk", HudNames.compact("Grand Exchange Clerk"));
    }

    private static FontMetrics metrics()
    {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            graphics.setFont(new Font("Dialog", Font.PLAIN, 12));
            return graphics.getFontMetrics();
        }
        finally
        {
            graphics.dispose();
        }
    }

}
