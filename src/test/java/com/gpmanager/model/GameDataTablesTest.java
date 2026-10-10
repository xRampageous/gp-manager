package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The game-knowledge tables, written out here with RuneLite's named constants, must match what
 * the plugin loads. Every id and region is swept, so a dropped, added or mistyped row fails.
 */
public class GameDataTablesTest
{
    private static final int MAX_ID = 65_536;

    static final Set<Integer> GE_TAX_EXEMPT = new HashSet<>(Arrays.asList(
        ItemID.OSRS_BOND, ItemID.BOUGHT_OSRS_BOND, ItemID.OSRS_BOND_UNTRADEABLE,
        ItemID.BRONZE_ARROW, ItemID.IRON_ARROW, ItemID.STEEL_ARROW, ItemID.BRONZE_DART, ItemID.IRON_DART,
        ItemID.STEEL_DART, ItemID.MINDRUNE,
        ItemID.BASS, ItemID.BREAD, ItemID.CAKE, ItemID.COOKED_CHICKEN, ItemID.COOKED_MEAT, ItemID.HERRING,
        ItemID.LOBSTER, ItemID.MACKEREL, ItemID.MEAT_PIE, ItemID.PIKE, ItemID.SALMON, ItemID.SHRIMP, ItemID.TUNA,
        ItemID._4DOSE1ENERGY, ItemID._3DOSE1ENERGY, ItemID._2DOSE1ENERGY, ItemID._1DOSE1ENERGY,
        ItemID.POH_TABLET_ARDOUGNETELEPORT, ItemID.POH_TABLET_CAMELOTTELEPORT, ItemID.POH_TABLET_FORTISTELEPORT,
        ItemID.POH_TABLET_FALADORTELEPORT, ItemID.POH_TABLET_KOURENDTELEPORT, ItemID.POH_TABLET_LUMBRIDGETELEPORT,
        ItemID.POH_TABLET_TELEPORTTOHOUSE, ItemID.POH_TABLET_VARROCKTELEPORT, ItemID.NECKLACE_OF_MINIGAMES_8,
        ItemID.RING_OF_DUELING_8,
        ItemID.CHISEL, ItemID.GARDENING_TROWEL, ItemID.GLASSBLOWINGPIPE, ItemID.HAMMER, ItemID.NEEDLE,
        ItemID.PESTLE_AND_MORTAR, ItemID.RAKE, ItemID.POH_SAW, ItemID.SECATEURS, ItemID.DIBBER, ItemID.SHEARS,
        ItemID.SPADE, ItemID.WATERING_CAN_0));

    static final Set<Integer> CURRENCY_PROXIES = new HashSet<>(Arrays.asList(
        ItemID.TZHAAR_TOKEN, ItemID.GAUNTLET_CRYSTAL_SHARD, ItemID.PRIF_CRYSTAL_SHARD_CRUSHED,
        21649, ItemID.STAR_DUST, ItemID.MGUILD_MINERALS, ItemID.MOTHERLODE_NUGGET, 27285,
        ItemID.HALLOWED_MARK_3, ItemID.BH_EMBLEM, 26879, ItemID.PRIF_CRYSTAL_SHARD,
        // 1.3.0: the degrading Amulet of the damned, imbued black masks and slayer helmets.
        ItemID.DAMNED_AMULET_DEGRADED, ItemID.SLAYER_HELM_I, ItemID.SW_SLAYER_HELM_I, ItemID.PVPA_SLAYER_HELM_I));
    static {
        for (int id = 11774; id <= 11784; id++) CURRENCY_PROXIES.add(id);
        for (int id = 25266; id <= 25276; id++) CURRENCY_PROXIES.add(id);
        for (int id = 26771; id <= 26781; id++) CURRENCY_PROXIES.add(id);
    }

    static List<String> keyChests()
    {
        List<String> rows = new ArrayList<>(Arrays.asList(
            ItemID.CRYSTAL_KEY + "|Crystal chest|true",
            ItemID.PRIF_CRYSTAL_KEY + "|Elven Crystal Chest|false",
            ItemID.SLAYER_WILDERNESS_KEY + "|Larran's small chest|true",
            ItemID.SLAYER_WILDERNESS_KEY + "|Larran's big chest|true",
            ItemID.ZOGRE_COFFINKEY + "|Ogre coffin|true",
            ItemID.MUDDY_KEY + "|Muddy chest|true",
            ItemID.SINISTER_KEY + "|Sinister chest|true",
            ItemID.HOSDUN_GRUBBY_KEY + "|Grubby chest|true",
            ItemID.KONAR_KEY + "|Brimstone chest|false",
            ItemID.VARLAMORE_NICE_KEY + "|Moon chest|false"));
        String[] metals = {"Bronze", "Steel", "Black", "Silver", "Gold"};
        String[] colours = {"red", "brown", "crimson", "black", "purple"};
        int[][] keys = {
            {ItemID.SHADEKEY_BRONZE_BLOODRED, ItemID.SHADEKEY_BRONZE_BROWN, ItemID.SHADEKEY_BRONZE_CRIMSON,
                ItemID.SHADEKEY_BRONZE_BLACK, ItemID.SHADEKEY_BRONZE_PURPLE},
            {ItemID.SHADEKEY_STEEL_BLOODRED, ItemID.SHADEKEY_STEEL_BROWN, ItemID.SHADEKEY_STEEL_CRIMSON,
                ItemID.SHADEKEY_STEEL_BLACK, ItemID.SHADEKEY_STEEL_PURPLE},
            {ItemID.SHADEKEY_BLACK_BLOODRED, ItemID.SHADEKEY_BLACK_BROWN, ItemID.SHADEKEY_BLACK_CRIMSON,
                ItemID.SHADEKEY_BLACK_BLACK, ItemID.SHADEKEY_BLACK_PURPLE},
            {ItemID.SHADEKEY_SILVER_BLOODRED, ItemID.SHADEKEY_SILVER_BROWN, ItemID.SHADEKEY_SILVER_CRIMSON,
                ItemID.SHADEKEY_SILVER_BLACK, ItemID.SHADEKEY_SILVER_PURPLE},
            {ItemID.SHADEKEY_GOLD_BLOODRED, ItemID.SHADEKEY_GOLD_BROWN, ItemID.SHADEKEY_GOLD_CRIMSON,
                ItemID.SHADEKEY_GOLD_BLACK, ItemID.SHADEKEY_GOLD_PURPLE}};
        for (int metal = 0; metal < metals.length; metal++)
        {
            for (int colour = 0; colour < colours.length; colour++)
            {
                rows.add(keys[metal][colour] + "|" + metals[metal] + " Chest (" + colours[colour] + ")|false");
            }
        }
        return rows;
    }

    static Map<Integer, String> agilityCourses()
    {
        Map<Integer, String> map = new HashMap<>();
        map.put(9781, "Gnome Stronghold Course");
        map.put(6200, "Shayzien Basic Course");
        map.put(5944, "Shayzien Advanced Course");
        map.put(12338, "Draynor Rooftop");
        map.put(13105, "Al Kharid Rooftop");
        map.put(13356, "Agility Pyramid");
        map.put(12853, "Varrock Rooftop");
        map.put(10559, "Penguin Agility Course");
        map.put(10039, "Barbarian Outpost Course");
        map.put(13878, "Canifis Rooftop");
        map.put(11050, "Ape Atoll Course");
        map.put(12084, "Falador Rooftop");
        map.put(11837, "Wilderness Agility Course");
        map.put(14234, "Werewolf Agility Course");
        map.put(10806, "Seers' Village Rooftop");
        map.put(13358, "Pollnivneach Rooftop");
        map.put(10553, "Rellekka Rooftop");
        map.put(12895, "Prifddinas Course");
        map.put(10547, "Ardougne Rooftop");
        map.put(11157, "Brimhaven Agility Arena");
        return map;
    }

    static final Set<Integer> LMS_REGIONS = new HashSet<>(Arrays.asList(
        13658, 13659, 13660, 13661, 13662, 13663, 13664, 13665));
    static final Set<Integer> NEUTRAL_ZONE_REGIONS = new HashSet<>(Arrays.asList(7512, 7768));

    /** key|activity|interactable|expectedFee|note|ambiguousTarget, in lookup order. */
    static final List<String> RETRIEVAL_SERVICES = Arrays.asList(
        "zulrah|Zulrah|Priestess Zul-Gwenwynig|100000|Free below 50 kills and for Ultimate Ironmen|false",
        "vorkath|Vorkath|Torfinn|100000||false",
        "hydra|Alchemical Hydra|Orrvor quo Maten|100000||false",
        "grotesque_guardians|Grotesque Guardians|Magical chest|50000||false",
        "sepulchre|Hallowed Sepulchre|Mysterious Stranger|25000||false",
        "hespori|Hespori|Arno|25000||false",
        "nightmare|The Nightmare|Shura|60000||false",
        "phosani|Phosani's Nightmare|Sister Senga|60000||false",
        "mimic|The Mimic|Strange casket|90000||false",
        "robert_the_strong|Dragon Slayer II (Robert the Strong)|Item chest|100000||false",
        "vanstrom_klause|Sins of the Father (Vanstrom Klause)|Veliaf Hurtz|50000||false",
        "song_of_the_elves|Song of the Elves|Rebel Scout|80000||false",
        "jormungand|The Fremennik Exiles (The Jormungand)|Brundt the Chieftain|50000||false",
        "retrieval_chest|Raid / boss retrieval chest|Chest|" + BossRetrievalCatalogue.VARIABLE_FEE
            + "|Nex and Theatre of Blood 100,000; Tombs of Amascut up to 500,000 by raid level|true",
        "gravestone|Gravestone|Gravestone|" + BossRetrievalCatalogue.TIERED_FEE
            + "|Tiered per item, 500,000 cap; paid from Death's Coffer or bank when carried coins are absent|true",
        "deaths_office|Death's Office|Death|" + BossRetrievalCatalogue.TIERED_FEE
            + "|5% of items worth 100,000 or more (2.5% ironman)|true");

    static final List<String> REWARD_SOURCES = Arrays.asList(
        "chambers of xeric", "theatre of blood", "tombs of amascut", "barrows", "gauntlet", "tempoross",
        "wintertodt", "guardians of the rift", "fishing trawler", "drift net", "colosseum", "lunar chest",
        "loot chest", "wilderness loot", "clue", "casket", "reward", "bounty hunter", "crates", "bird house",
        "birdhouse", "herbiboar", "giants' foundry", "giants foundry");

    @Test
    public void chargeDartNamesKeepTheCanonicalItemIds()
    {
        assertEquals(Map.of("bronze dart", ItemID.BRONZE_DART, "iron dart", ItemID.IRON_DART,
            "steel dart", ItemID.STEEL_DART, "mithril dart", ItemID.MITHRIL_DART,
            "adamant dart", ItemID.ADAMANT_DART, "rune dart", ItemID.RUNE_DART,
            "amethyst dart", ItemID.AMETHYST_DART, "dragon dart", ItemID.DRAGON_DART), ChargeCheck.DARTS);
    }

    @Test
    public void geTaxExemptionsAreExactlyTheWikiList()
    {
        for (int id = -1; id < MAX_ID; id++)
        {
            assertEquals("item " + id, GE_TAX_EXEMPT.contains(id), GeTaxRule.isExempt(id));
        }
    }

    @Test
    public void currencyProxiesAreExactlyTheCatalogue()
    {
        for (int id = -1; id < MAX_ID; id++)
        {
            assertEquals("item " + id, CURRENCY_PROXIES.contains(id), CurrencyProxyCatalogue.isMapped(id));
        }
    }

    @Test
    public void keyChestEntriesKeepTheirOrder()
    {
        List<String> loaded = new ArrayList<>();
        for (KeyChestCatalogue.Entry entry : KeyChestCatalogue.entries())
        {
            loaded.add(entry.getKeyItemId() + "|" + entry.getChestName() + "|" + entry.isTradeable());
        }
        assertEquals(keyChests(), loaded);
    }

    @Test
    public void agilityCoursesCoverExactlyTheirRegions()
    {
        Map<Integer, String> expected = agilityCourses();
        for (int region = -1; region < MAX_ID; region++)
        {
            assertEquals("region " + region, expected.get(region), AgilityCourses.courseName(region));
        }
    }

    @Test
    public void minigameRegionsAreExactlyTheHints()
    {
        for (int region = -1; region < MAX_ID; region++)
        {
            assertEquals("lms " + region, LMS_REGIONS.contains(region), MinigameRegionHints.isLmsRegion(region));
            assertEquals("neutral " + region, NEUTRAL_ZONE_REGIONS.contains(region),
                MinigameRegionHints.isNeutralZoneRegion(region));
        }
    }

    @Test
    public void retrievalServicesKeepEveryField()
    {
        for (String row : RETRIEVAL_SERVICES)
        {
            String[] f = row.split("\\|", -1);
            BossRetrievalCatalogue.Service service = BossRetrievalCatalogue.serviceFor("Claim", f[2]);
            assertNotNull(f[2], service);
            assertEquals(row, service.key + "|" + service.activity + "|" + service.interactable + "|"
                + service.expectedFee + "|" + service.note + "|" + service.ambiguousTarget);
        }
        assertTrue(BossRetrievalCatalogue.serviceFor("Claim", "Gravestone of Zezima") != null);
        assertEquals(null, BossRetrievalCatalogue.serviceFor("Claim", "Banker"));
    }

    @Test
    public void rewardSourcesMatchByName()
    {
        for (String name : REWARD_SOURCES)
        {
            assertTrue(name, RewardChestCatalogue.isRewardChest("The " + name.toUpperCase() + " pool"));
        }
        assertFalse(RewardChestCatalogue.isRewardChest("Goblin"));
        assertFalse(RewardChestCatalogue.isRewardChest("Abyssal demon"));
    }

    @Test
    public void orderedTablesKeepTheirOrder()
    {
        List<String> services = new ArrayList<>();
        for (String[] row : GameData.rows("retrieval_services"))
        {
            services.add(String.join("|", row));
        }
        assertEquals(RETRIEVAL_SERVICES, services);
        List<String> sources = new ArrayList<>();
        for (String[] row : GameData.rows("reward_chests"))
        {
            sources.add(row[0]);
        }
        assertEquals(REWARD_SOURCES, sources);
    }

    /** A named row must carry the id RuneLite gives that name, so a renumbered item is caught. */
    @Test
    public void itemNamesInTheTablesMatchRuneLiteIds() throws ReflectiveOperationException
    {
        int named = 0;
        for (String table : new String[]{"tax_exempt", "currencies", "key_chests", "dismantle"})
        {
            for (String[] row : GameData.rows(table))
            {
                String constant = row[row.length - 1];
                if (constant.matches("[A-Z0-9_]+"))
                {
                    assertEquals(table + " " + constant, ItemID.class.getField(constant).getInt(null),
                        Integer.parseInt(row[0]));
                    named++;
                }
            }
        }
        assertEquals(50 + 9 + 35 + 8, named);
    }

    /** Every msg key the plugin uses has its text, and text.tsv holds nothing the code no longer uses. */
    @Test
    public void everyTextKeyIsUsedAndPresent() throws java.io.IOException
    {
        Set<String> used = new java.util.TreeSet<>();
        Set<String> prefixes = new java.util.TreeSet<>();
        java.util.regex.Pattern literal = java.util.regex.Pattern.compile("\\b(?:msg|transfer)\\(\\s*\"([^\"]+)\"\\s*[,)]");
        java.util.regex.Pattern prefix = java.util.regex.Pattern.compile("\\bmsg\\(\\s*\"([\\w-]+\\.)\"\\s*\\+");
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(
            java.nio.file.Paths.get("src/main/java")))
        {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.filter(
                path -> path.toString().endsWith(".java"))::iterator)
            {
                String code = new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
                for (java.util.regex.Matcher m = literal.matcher(code); m.find(); )
                {
                    used.add(m.group(1));
                }
                for (java.util.regex.Matcher m = prefix.matcher(code); m.find(); )
                {
                    prefixes.add(m.group(1));
                }
            }
        }
        // Rules and pattern aliases now read text keys from TSV as well as Java.
        java.util.regex.Pattern reference = java.util.regex.Pattern.compile("(?<=[\\t|+])@([\\w.-]+)");
        try (java.util.stream.Stream<java.nio.file.Path> tables = java.nio.file.Files.walk(
            java.nio.file.Paths.get("src/main/resources")))
        {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) tables.filter(
                p -> p.toString().endsWith(".tsv"))::iterator)
            {
                String data = java.nio.file.Files.readString(file);
                for (java.util.regex.Matcher m = reference.matcher(data); m.find(); ) used.add(m.group(1));
            }
        }
        assertTrue(used.size() > 50);
        assertTrue(GameData.TEXT.keySet().containsAll(used));
        for (String key : GameData.TEXT.keySet())
        {
            assertTrue("unused text row " + key, used.contains(key)
                || prefixes.stream().anyMatch(key::startsWith));
            assertFalse(key, GameData.msg(key).trim().isEmpty());
        }
        for (String p : prefixes)
        {
            assertTrue("no rows for " + p, GameData.TEXT.keySet().stream().anyMatch(k -> k.startsWith(p)));
        }
    }
    /** d24 has no item ids; every action is a public RuneLite menu constant, and every key is used. */
    @Test
    public void menuAndChatTableHasOnlyUsedKeysAndSupportedActions()
    {
        assertEquals(Set.of("widget", "spend.raw", "spend.norm", "use", "fuel", "fire", "altar",
            "bones", "cast", "offering", "spared", "spend.chat", "gather.exclude"), ActionSignals.RULES.keySet());
        Set<net.runelite.api.MenuAction> expected = Set.of(net.runelite.api.MenuAction.WIDGET_TARGET,
            net.runelite.api.MenuAction.WIDGET_TARGET_ON_NPC, net.runelite.api.MenuAction.WIDGET_TARGET_ON_PLAYER,
            net.runelite.api.MenuAction.WIDGET_TARGET_ON_WIDGET, net.runelite.api.MenuAction.WIDGET_TARGET_ON_GROUND_ITEM,
            net.runelite.api.MenuAction.WIDGET_TARGET_ON_GAME_OBJECT);
        assertEquals(expected.size(), ActionSignals.RULES.get("widget").length);
        for (net.runelite.api.MenuAction action : net.runelite.api.MenuAction.values())
            assertEquals(action.name(), expected.contains(action), ActionSignals.isWidgetAction(action));
        assertFalse(ActionSignals.isWidgetAction(null));
    }

    /** d25 uses names, not item ids; each row is a consumed verb or item predicate. */
    @Test
    public void actionAndItemRulesKeepEveryUsedKeyAndOrderedOutcome()
    {
        assertEquals(Set.of("drink", "eat", "~bury", "~scatter", "offer", "cast", "supplies",
            "vessel", "rune", "claim", "ingredient", "food", "partial"), ActionReader.RULES.keySet());
        List<String> loaded = new ArrayList<>();
        for (String[] row : GameData.rows("action_rules"))
        {
            assertEquals(3, row.length);
            if (!row[2].isEmpty()) loaded.add(row[0] + "|" + ActionKind.valueOf(row[2]).name());
        }
        assertEquals(Arrays.asList("drink|DRINK", "eat|EAT", "~bury|BURY", "~scatter|SCATTER", "offer|OFFER", "cast|CAST"), loaded);
        assertEquals(GameData.msg("light-tend"), GameData.pattern("light-tend").pattern());
        assertEquals(GameData.msg("pizza-pie"), GameData.pattern("pizza-pie").pattern());
        assertEquals(".*(pizza|cake|pie).*", GameData.pattern("pizza-cake-pie").pattern());
    }

    /** d26 contains phrases rather than gameval ids; d13 continues to validate region ids. */
    @Test public void neutralTransferTableRowsAllCompileWithPhrasesAndNotes() {
        List<String[]> rows = GameData.cachedRows("neutral_transfers");
        assertEquals(24, rows.size());
        assertEquals(rows.stream().map(row -> row[0]).collect(java.util.stream.Collectors.toList()),
            new ArrayList<>(NeutralTransfers.RULES.keySet()));
        for (String[] row : rows) {
            assertEquals(4, row.length);
            assertFalse(row[0], row[1].isEmpty());
            assertFalse(row[0], GameData.text(row[3]).isEmpty());
        }
    }

    @Test public void cataloguePatternsUseExistingVerbKeyAndNamedDispenserFragments() {
        assertEquals(GameData.msg("claim-reclaim"), BossRetrievalCatalogue.RECLAIM.pattern());
        assertEquals("grave.*", BossRetrievalCatalogue.GRAVE.pattern());
        assertEquals(".*(agility dispenser|ticket dispenser).*", AgilityCourses.DISPENSER.pattern());
        assertEquals(java.util.regex.Pattern.DOTALL, BossRetrievalCatalogue.GRAVE.flags());
        assertEquals(java.util.regex.Pattern.DOTALL, AgilityCourses.DISPENSER.flags());
    }

    @Test public void rewardFragmentsKeepEveryUsedKeyAndCompiledWitness() {
        assertEquals(new java.util.LinkedHashSet<>(REWARD_SOURCES), RewardChestCatalogue.RULES.keySet());
        for (String[] row : GameData.rows("reward_chests")) {
            assertEquals(2, row.length); assertEquals(row[0], row[1]);
            assertTrue(Phrases.has(row[0], RewardChestCatalogue.RULES.get(row[0])));
        }
    }

    @Test public void spellRecipesKeepAllNamesCountsAndSupportedRuneNames() {
        Map<String, Integer> ids = Map.of("air", ItemID.AIRRUNE, "water", ItemID.WATERRUNE,
            "earth", ItemID.EARTHRUNE, "fire", ItemID.FIRERUNE, "mind", ItemID.MINDRUNE,
            "chaos", ItemID.CHAOSRUNE, "death", ItemID.DEATHRUNE, "blood", ItemID.BLOODRUNE,
            "soul", ItemID.SOULRUNE, "wrath", ItemID.WRATHRUNE);
        List<String> names = new ArrayList<>(); Set<String> used = new HashSet<>();
        for (String[] row : GameData.rows("spell_recipes")) {
            assertEquals(2, row.length); names.add(row[0]);
            for (String part : row[1].split(" ")) {
                String[] split = part.split(":"); used.add(split[1]);
                assertTrue(ids.get(split[1]) > 0);
                assertEquals(Long.valueOf(split[0]), Spells.RECIPES.get(row[0]).get(split[1]));
            }
        }
        assertEquals(ids.keySet(), used);
        assertEquals(names, new ArrayList<>(Spells.RECIPES.keySet()));
    }

    @Test public void activityTableKeepsEveryUsedListAndPredicate() {
        assertEquals(Set.of("curated", "short", "combat", "pursuit", "generic"), ActivityLabel.RULES.keySet());
        Map<String,List<String>> values = new HashMap<>();
        for (String[] row : GameData.rows("activity_rules")) {
            assertEquals(3,row.length);
            values.put(row[0], Arrays.stream(row[2].split("\\|"))
                .map(GameData::text).collect(java.util.stream.Collectors.toList()));
        }
        assertEquals(Arrays.asList("PKing","Trading","Death reclaim"),values.get("pursuit"));
        assertEquals(Arrays.asList("General","NPC loot"),values.get("generic"));
        for (net.runelite.api.Skill skill : net.runelite.api.Skill.values())
            assertEquals(ActivityLabel.COMBAT.contains(skill.getName()),Phrases.has(skill.getName(),ActivityLabel.RULES.get("combat")));
    }

}
