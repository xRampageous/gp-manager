package com.gpmanager;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.gpmanager.ChargeCheck.ChargedItem.*;
import com.gpmanager.ChargeCheck.ChargedItem;
import static com.gpmanager.ActionSignals.rule;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.Phrases.lower;
import static com.gpmanager.FlowFilters.firstMatch;

/** The optional possessive sharing must retain every supported and malformed Check surface. */
public class ChargeSurfaceTrimTest {
    @Test public void everyCatalogueNameAndPrefixKeepsItsPreviousSurface() {
        for (var item : ChargedItem.values()) {
            assertFalse(item.displayName, lower(item.displayName, false).startsWith("your "));
            for (String prefix : List.of("", "your ", "your your ", "the ", "other "))
                for (String suffix : List.of(" has 1 charge.", " only has 1 charge.", " has junk",
                        " only has ", " has", " has no charges left.", " has 999999999999999999999999 charges.")) {
                    String line = prefix + lower(item.displayName, false) + suffix;
                    assertEquals(line, previousSurface(line), ChargeCheck.surface(line));
                }
        }
        for (String line : List.of("", "your tome is charged with soaked pages.",
                "your tome is charged with soiled pages.", "the pendant has 4 charges.",
                "your bow has 1 charge.", "your sailors' amulet has 106 charges."))
            assertEquals(line, previousSurface(line), ChargeCheck.surface(line));
    }

    static ChargedItem previousSurface(String lower) {
        if (rule("blowpipe-check", lower)) return BLOWPIPE; if (lower.startsWith("the pendant has ")) return ATES;
        if (rule("sailors-check", lower))
            // The sailors' amulet's own Check wording ("...has 106 charges." or "...charges
            // left."); the blood fury's hit-count line names itself below (owner 2026-10-14).
            return SAILORS_AMULET;
        if (lower.startsWith(msg("tome-charged")))
        {
            // The page names the tome; a bare "your tome" line stays fire and the clicked row
            // redirects it (owner 2026-10-12).
            if (lower.contains("soaked")) return TOME_OF_WATER; return lower.contains("soiled") ? TOME_OF_EARTH : TOME_OF_FIRE;
        }
        if (lower.startsWith(msg("amulet-blood"))) return BLOOD_FURY;
        if (lower.startsWith(msg("eye-capacity")) || lower.startsWith(msg("eye-loaded")))
            return EYE_OF_AYAK;
        for (var variant : ChargedItem.values()) {
            if (variant == BLOWPIPE) continue; String name = lower(variant.displayName, false);
            if (lower.startsWith("your " + name + " has ") || lower.startsWith("your " + name + " only has ")
                    || lower.startsWith(name + " has ") || lower.startsWith(name + " only has "))
                return variant;
        }
        // A Check that names no weapon ("Your bow has…"): the first row sharing that wording.
        return firstMatch(List.of(ChargedItem.values()), variant -> variant.check != null && variant.check.matcher(lower).matches());
    }

}
