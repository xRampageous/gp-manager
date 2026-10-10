package com.gpmanager;

import net.runelite.api.MenuAction;

import java.util.*;

/**
 * Menu / chat vocabulary that turns a click into an accounting intent: explicit spend actions (eat,
 * drink, bury, offer, light), transform/production options that arm PRODUCTION context, gather
 * verbs that only name the activity, and the chat lines that confirm a spend after the click.
 * Anything not listed here stays ambiguous and settles through Review, never a guess.
 */
class ActionSignals {
    static final Map<String, String> TRANSFORM = GameData.byName("transform_skills");
    static final Map<String, String> GATHER = GameData.byName("gather_skills");

    /** Compiled menu/chat predicates; d7/d10 already contain transform and gather data. */
    static final Map<String, Phrases.Clause[][]> RULES = GameData.rules("menu_rules");

    /** Apply one compiled predicate group. */
    static boolean rule(String group, String text) {
        return Phrases.has(text, RULES.get(group));
    }

    /** Widget actions eligible for selected spell evidence. */
    static boolean isWidgetAction(MenuAction action) {
        return rule("widget", action == null ? null : action.name());
    }

    /** Raw consume verbs plus normalized explicit spends. */
    static boolean isSpend(String option) {
        return rule("spend.raw", option) || isSpendName(option);
    }

    /** Normalized light/offer/pray and burial/scatter verbs. */
    static boolean isSpendName(String option) {
        return rule("spend.norm", Phrases.lower(option));
    }

    /** Use tinderbox with fuel; target normalization preserves whitespace. */
    static boolean lightsFire(String option, String target) {
        return rule("use", Phrases.lower(option))
                && rule("fuel", Phrases.lower(target, false))
                && rule("fire", Phrases.lower(target, false));
    }

    /** Use bones/ashes on an altar. */
    static boolean offersBones(String option, String target) {
        return rule("use", Phrases.lower(option))
                && rule("altar", Phrases.lower(target, false))
                && rule("bones", Phrases.lower(target, false));
    }

    /** Positive Sinister Offering cast evidence. */
    static boolean isOffering(String option, String target) {
        return rule("cast", Phrases.lower(option))
                && rule("offering", Phrases.lower(target, false));
    }

    /** Chaos Altar saved a bone; this cannot confirm spend. */
    static boolean isSpared(String message) {
        return rule("spared", Phrases.lower(message, false));
    }

    /** Inventory-spend chat, excluding a saved bone. */
    static boolean isChatSpend(String message) {
        return !isSpared(message) && rule("spend.chat", Phrases.lower(message, false));
    }

    /**
     * Transform/production options (station or inventory) mapped to the skill whose XP confirms
     * them.
     */
    static String transformSkill(String option) {
        return TRANSFORM.getOrDefault(Phrases.lower(option), "");
    }

    /** Activity hint for object gather verbs; "General" when the verb is not a gather. */
    static String gatherActivity(String option) {
        return GATHER.getOrDefault(Phrases.orEmpty(option), "General");
    }

    /** Rake and plant still name the activity, but do not arm gather ownership evidence. */
    static boolean namesActivityOnly(String option) {
        return option != null
                && !"General".equals(gatherActivity(option))
                && !rule("gather.exclude", option);
    }
}
