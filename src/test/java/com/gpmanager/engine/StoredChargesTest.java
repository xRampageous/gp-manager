package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Carried items that hold their charges as items: the Fractured Archive rewards and the ash sanctifier. */
public class StoredChargesTest
{
    @Test
    public void everyTableIdIsTheNamedRuneLiteConstant() throws Exception
    {
        int rows = 0;
        for (String[] row : GameData.rows("stored_charges"))
        {
            rows++;
            for (int column = 0; column < row.length; column++)
            {
                if (!row[column].contains("="))
                {
                    continue;
                }
                String[] cell = row[column].split("=");
                Class<?> constants = column == 1 ? VarbitID.class : ItemID.class;
                assertEquals(row[column], Integer.parseInt(cell[0]), constants.getField(cell[1]).getInt(null));
            }
        }
        assertEquals("five charged items, 7 plank types, 19 scrolls, the spool, the ring and nine CHARGES_ counters", 42, rows);
    }

    /** RuneLite counters that are tracked another way, by the ChargedItem whose Check messages read them. */
    private static final Map<String, String> CHECKED = Map.ofEntries(
        Map.entry("CHARGES_BLADE_OF_SAELDOR_QUANTITY", "SAELDOR"), Map.entry("CHARGES_BLOOD_FURY_QUANTITY", "BLOOD_FURY"),
        Map.entry("CHARGES_BOW_OF_FAERDHINEN_QUANTITY", "BOWFA"), Map.entry("CHARGES_BRACELET_OF_ETHEREUM_QUANTITY", "ETHEREUM"),
        Map.entry("CHARGES_BRYOPHYTAS_STAFF_QUANTITY", "BRYOPHYTA"), Map.entry("CHARGES_CRYSTAL_ARMOUR_QUANTITY", "CRYSTAL_BODY"),
        Map.entry("CHARGES_CRYSTAL_TOOLS_QUANTITY", "CRYSTAL_AXE"), Map.entry("CHARGES_DIZANAS_QUIVER_QUANTITY", "DIZANAS"),
        Map.entry("CHARGES_ECHO_BOOTS_QUANTITY", "ECHO_BOOTS"), Map.entry("CHARGES_EYE_OF_AYAK_QUANTITY", "EYE_OF_AYAK"),
        Map.entry("CHARGES_PENDANT_OF_ATES_QUANTITY", "ATES"), Map.entry("CHARGES_RING_OF_SUFFERING_QUANTITY", "SUFFERING"),
        Map.entry("CHARGES_SAILORS_AMULET_QUANTITY", "SAILORS_AMULET"), Map.entry("CHARGES_SANGUINESTI_STAFF_QUANTITY", "SANGUINESTI"),
        Map.entry("CHARGES_SERPENTINE_HELM_QUANTITY", "SERPENTINE_HELM"), Map.entry("CHARGES_TOME_OF_EARTH_QUANTITY", "TOME_OF_EARTH"),
        Map.entry("CHARGES_TOME_OF_FIRE_QUANTITY", "TOME_OF_FIRE"), Map.entry("CHARGES_TOME_OF_WATER_QUANTITY", "TOME_OF_WATER"),
        Map.entry("CHARGES_TONALZTICS_OF_RALOS_QUANTITY", "TONALZTICS"), Map.entry("CHARGES_TOXIC_BLOWPIPE_QUANTITY", "BLOWPIPE"),
        Map.entry("CHARGES_TOXIC_STAFF_OF_THE_DEAD_QUANTITY", "TOXIC_STAFF"),
        Map.entry("CHARGES_TRIDENT_OF_THE_SEAS_E_QUANTITY", "TRIDENT_SEAS_ENHANCED"),
        Map.entry("CHARGES_TRIDENT_OF_THE_SEAS_QUANTITY", "TRIDENT_SEAS"), Map.entry("CHARGES_TUMEKENS_SHADOW_QUANTITY", "SHADOW"),
        Map.entry("CHARGES_VENATOR_BOW_QUANTITY", "VENATOR"), Map.entry("CHARGES_WARPED_SCEPTRE_QUANTITY", "WARPED"),
        Map.entry("CHARGES_WILDERNESS_WEAPON_QUANTITY", "CRAW"), Map.entry("VEOS_MEMOIR_CHARGES", "MEMOIRS"));

    /** Counters deliberately left unmapped, with the reason (OSRS Wiki, 2026-11-02). */
    private static final Map<String, String> EXCLUDED = Map.ofEntries(
        Map.entry("CHARGES_ARCLIGHT_QUANTITY", "333 charges per untradeable ancient shard: whole shards never divide evenly"),
        Map.entry("CHARGES_BONECRUSHER_QUANTITY", "shared by the bonecrusher and its necklace; ecto-tokens are worth about 1 gp"),
        Map.entry("CHARGES_CRYSTAL_WEAK_QUANTITY", "no Wiki page or plugin names its item"),
        Map.entry("FOSSIL_MERFOLK_TRIDENT_CHARGES", "up to 10 pufferfish for breath underwater; not researched yet"),
        Map.entry("RONDACHE_CHARGES", "special-attack points from blocked damage, not items"),
        Map.entry("ZEAH_WATERINGCAN_CHARGES", "Gricoller's can holds water, refilled free"),
        Map.entry("EYEGLO_CRYSTAL_SAW_CHARGES", "the crystal saw degrades; no material goes in"),
        Map.entry("TOA_PICKAXE_CHARGES", "a raid-only pickaxe (raid_pickaxes.tsv)"),
        Map.entry("BUFF_WATER_CIRCLET_CHARGES", "the buff bar's copy of the circlet's counter"),
        Map.entry("SAILING_SIDEPANEL_BOAT_WIND_CHARGES", "a boat's wind ability, not an item"),
        Map.entry("SAILING_SIDEPANEL_BOAT_MAX_WIND_CHARGES", "a boat's wind ability, not an item"));

    /**
     * Flags any charged item RuneLite adds that GP Manager does not track yet: every charge counter
     * ({@code CHARGES_*_QUANTITY} or {@code *_CHARGES}) must be a stored_charges.tsv row, a Check-tracked
     * ChargedItem or a written exclusion. A RuneLite update that adds one fails here until it is decided.
     */
    @Test
    public void everyChargesVarbitIsMappedOrExcluded() throws Exception
    {
        var stored = new java.util.HashSet<Integer>();
        for (String[] row : GameData.rows("stored_charges"))
        {
            stored.add(Integer.parseInt(row[1].split("=")[0]));
        }
        int counters = 0;
        for (var field : VarbitID.class.getFields())
        {
            String name = field.getName();
            if (!name.matches("CHARGES_.*_QUANTITY|.*_CHARGES"))
            {
                continue;
            }
            counters++;
            boolean mapped = stored.contains(field.getInt(null));
            int ways = (mapped ? 1 : 0) + (CHECKED.containsKey(name) ? 1 : 0) + (EXCLUDED.containsKey(name) ? 1 : 0);
            assertEquals(name + ": add it to stored_charges.tsv, CHECKED or EXCLUDED (exactly one)", 1, ways);
            if (CHECKED.containsKey(name))
            {
                assertNotNull(name, ChargeCheck.ChargedItem.valueOf(CHECKED.get(name)));
            }
        }
        assertEquals("RuneLite 1.13.4's charge counters", 54, counters);
    }

    @Test
    public void chargingTheAlchemistsAmuletIsNeutralAndTenChargesCostOneAmuletOfChemistry()
    {
        Engine engine = ReviewFixesTest.engine();
        long now = ReviewFixesTest.T0 + 1_000;
        engine.setBaseline(capture(Map.of(InventoryID.WORN, items(ItemID.AMULET_OF_CHEMISTRY_IMBUED_UNCHARGED, 1),
            InventoryID.INV, items(ItemID.AMULET_OF_CHEMISTRY, 5)), Map.of()));
        Map<Integer, Item[]> worn = Map.of(InventoryID.WORN, items(ItemID.AMULET_OF_CHEMISTRY_IMBUED_CHARGED, 1));
        engine.markDirty();
        settle(engine, capture(worn, Map.of(VarbitID.CHARGES_ALCHEMISTS_AMULET_QUANTITY, 50)), now);
        assertEquals("five amulets of chemistry become fifty charges", 0L, engine.metrics(now + 20_000).net);
        engine.markDirty();
        Transaction extraDoses = settle(engine, capture(worn, Map.of(VarbitID.CHARGES_ALCHEMISTS_AMULET_QUANTITY, 40)),
            now + 30_000);
        assertNotNull(extraDoses);
        assertEquals(TransactionType.CONSUMPTION, extraDoses.type());
        assertEquals(1L, extraDoses.quantity(ItemID.AMULET_OF_CHEMISTRY, false));
        engine.markDirty();
        settle(engine, capture(Map.of(InventoryID.WORN, items(ItemID.AMULET_OF_CHEMISTRY_IMBUED_UNCHARGED, 1),
            InventoryID.INV, items(ItemID.AMULET_OF_CHEMISTRY, 4)), Map.of()), now + 60_000);
        assertEquals("uncharging only moves the rest back", -100L, engine.metrics(now + 80_000).net);
    }

    @Test
    public void ringOfShadowsAndGiantsoulTeleportsCostTheirRecipe()
    {
        Engine engine = ReviewFixesTest.engine();
        long now = ReviewFixesTest.T0 + 1_000;
        engine.setBaseline(capture(Map.of(InventoryID.INV, items(ItemID.RING_OF_SHADOWS_UNCHARGED, 1,
            ItemID.GIANTSOUL_AMULET_UNCHARGED, 1, ItemID.BLOODRUNE, 2, ItemID.SOULRUNE, 2, ItemID.DEATHRUNE, 2,
            ItemID.LAWRUNE, 4, ItemID.BIG_BONES, 2)), Map.of()));
        Map<Integer, Item[]> charged = Map.of(InventoryID.INV, items(ItemID.RING_OF_SHADOWS, 1,
            ItemID.GIANTSOUL_AMULET_CHARGED, 1));
        engine.markDirty();
        settle(engine, capture(charged, Map.of(VarbitID.CHARGES_RING_OF_SHADOWS_QUANTITY, 2,
            VarbitID.CHARGES_GIANTSOUL_AMULET_QUANTITY, 2)), now);
        assertEquals("both items change state and the runes and bones only move", 0L, engine.metrics(now + 20_000).net);
        engine.markDirty();
        Transaction teleport = settle(engine, capture(charged, Map.of(VarbitID.CHARGES_RING_OF_SHADOWS_QUANTITY, 1,
            VarbitID.CHARGES_GIANTSOUL_AMULET_QUANTITY, 2)), now + 30_000);
        assertNotNull(teleport);
        assertEquals(1L, teleport.quantity(ItemID.DEATHRUNE, false));
        assertEquals("four runes at the fixture's 100 gp", -400L, engine.metrics(now + 50_000).net);
        engine.markDirty();
        settle(engine, capture(charged, Map.of(VarbitID.CHARGES_RING_OF_SHADOWS_QUANTITY, 1,
            VarbitID.CHARGES_GIANTSOUL_AMULET_QUANTITY, 1)), now + 60_000);
        assertEquals("a big bone and a law rune", -600L, engine.metrics(now + 80_000).net);
    }

    @Test
    public void chargeCountersHoldTheirMaterialsWhileCarried()
    {
        Map<Integer, Integer> counters = Map.of(VarbitID.CHARGES_SOUL_BEARER_QUANTITY, 30,
            VarbitID.CHARGES_CIRCLET_OF_WATER_QUANTITY, 12, VarbitID.CHARGES_HORN_OF_PLENTY_QUANTITY, 40,
            VarbitID.CHARGES_XERICS_TALISMAN_QUANTITY, 25, VarbitID.CHARGES_CELESTIAL_RING_QUANTITY, 900);
        ContainerSnapshot carried = capture(Map.of(InventoryID.INV, items(ItemID.ARCEUUS_SOULBEARER, 1,
            ItemID.HORN_OF_PLENTY, 1, ItemID.XERIC_TALISMAN, 1), InventoryID.WORN, items(ItemID.WATER_CIRCLET_CHARGED, 1,
            ItemID.CELESTIAL_SIGNET_CHARGED, 1)), counters);
        assertEquals(30L, carried.quantity(ItemID.BLOODRUNE));
        assertEquals(30L, carried.quantity(ItemID.SOULRUNE));
        assertEquals("five water runes a charge", 60L, carried.quantity(ItemID.WATERRUNE));
        assertEquals(40L, carried.quantity(ItemID.GRYPHON_FEATHER));
        assertEquals(25L, carried.quantity(ItemID.LIZARDMAN_FANG));
        assertEquals("the signet shares the ring's counter", 900L, carried.quantity(ItemID.STAR_DUST));
        ContainerSnapshot uncharged = capture(Map.of(InventoryID.INV, items(ItemID.HORN_OF_PLENTY_UNCHARGED, 1,
            ItemID.XERIC_TALISMAN_EMPTY, 1, ItemID.CELESTIAL_RING, 1)), counters);
        assertEquals(0L, uncharged.quantity(ItemID.GRYPHON_FEATHER));
        assertEquals(0L, uncharged.quantity(ItemID.LIZARDMAN_FANG));
        assertEquals(0L, uncharged.quantity(ItemID.STAR_DUST));
        for (int varbit : counters.keySet())
        {
            assertTrue(String.valueOf(varbit), ContainerSnapshotFactory.storedCharges(varbit));
        }
    }

    @Test
    public void storesCountTheirContentsOnlyWhileCarried()
    {
        Map<Integer, Integer> stored = Map.of(VarbitID.PLANK_SACK_OAK, 10, VarbitID.PLANK_SACK_MAHOGANY, 5,
            VarbitID.BOOKOFSCROLLS_NARDAH, 7, VarbitID.BOOKOFSCROLLS_CHASMOFFIRE, 3,
            VarbitID.BOWSTRING_SPOOL_CHARGES, 250, VarbitID.RING_OF_THE_ELEMENTS_CHARGES, 40);
        ContainerSnapshot carried = capture(Map.of(InventoryID.INV, items(ItemID.PLANK_SACK, 1,
            ItemID.BOOKOFSCROLLS_CHARGED, 1, ItemID.BOWSTRING_SPOOL, 1, ItemID.PLANK_OAK, 2),
            InventoryID.WORN, items(ItemID.RING_OF_ELEMENTS_CHARGED, 1)), stored);
        assertEquals("10 in the sack, 2 loose", 12L, carried.quantity(ItemID.PLANK_OAK));
        assertEquals(5L, carried.quantity(ItemID.PLANK_MAHOGANY));
        assertEquals(7L, carried.quantity(ItemID.TELEPORTSCROLL_NARDAH));
        assertEquals(3L, carried.quantity(ItemID.TELEPORTSCROLL_CHASMOFFIRE));
        assertEquals(250L, carried.quantity(ItemID.BOW_STRING));
        for (int rune : new int[] {ItemID.AIRRUNE, ItemID.EARTHRUNE, ItemID.FIRERUNE, ItemID.WATERRUNE, ItemID.LAWRUNE})
        {
            assertEquals("a worn ring holds one of each rune a charge", 40L, carried.quantity(rune));
        }
        ContainerSnapshot banked = capture(Map.of(InventoryID.INV, items(ItemID.PLANK_OAK, 2)), stored);
        assertEquals(2L, banked.quantity(ItemID.PLANK_OAK));
        assertEquals(0L, banked.quantity(ItemID.TELEPORTSCROLL_NARDAH));
        assertEquals(0L, banked.quantity(ItemID.LAWRUNE));
    }

    @Test
    public void storeVarbitsMarkTheInventoryDirty()
    {
        for (int varbit : new int[] {VarbitID.PLANK_SACK_PLAIN, VarbitID.PLANK_SACK_ROSEWOOD,
            VarbitID.BOOKOFSCROLLS_NARDAH, VarbitID.BOOKOFSCROLLS_ARDEAGLAIS, VarbitID.BOWSTRING_SPOOL_CHARGES,
            VarbitID.RING_OF_THE_ELEMENTS_CHARGES})
        {
            assertTrue(String.valueOf(varbit), ContainerSnapshotFactory.storedCharges(varbit));
        }
    }

    @Test
    public void fillingThePlankSackIsNeutralAndBuildingFromItCostsThePlanks()
    {
        Engine engine = ReviewFixesTest.engine();
        long now = ReviewFixesTest.T0 + 1_000;
        engine.setBaseline(capture(Map.of(InventoryID.INV, items(ItemID.PLANK_SACK, 1, ItemID.PLANK_OAK, 10)), Map.of()));
        Map<Integer, Item[]> sackOnly = Map.of(InventoryID.INV, items(ItemID.PLANK_SACK, 1));
        engine.markDirty();
        assertNull("filling only moves the planks",
            settle(engine, capture(sackOnly, Map.of(VarbitID.PLANK_SACK_OAK, 10)), now));
        engine.markDirty();
        Transaction built = settle(engine, capture(sackOnly, Map.of(VarbitID.PLANK_SACK_OAK, 7)), now + 20_000);
        assertNotNull(built);
        assertEquals(3L, built.quantity(ItemID.PLANK_OAK, false));
        assertEquals(-300L, engine.metrics(now + 40_000).net);
    }

    @Test
    public void aTeleportFromTheScrollBookCostsOneScroll()
    {
        Engine engine = ReviewFixesTest.engine();
        long now = ReviewFixesTest.T0 + 1_000;
        Map<Integer, Item[]> book = Map.of(InventoryID.INV, items(ItemID.BOOKOFSCROLLS_CHARGED, 1));
        engine.setBaseline(capture(book, Map.of(VarbitID.BOOKOFSCROLLS_NARDAH, 5)));
        engine.markDirty();
        Transaction teleport = settle(engine, capture(book, Map.of(VarbitID.BOOKOFSCROLLS_NARDAH, 4)), now);
        assertNotNull(teleport);
        assertEquals(TransactionType.CONSUMPTION, teleport.type());
        assertEquals(1L, teleport.quantity(ItemID.TELEPORTSCROLL_NARDAH, false));
        assertEquals(-100L, engine.metrics(now + 20_000).net);
    }

    @Test
    public void flaxSpunIntoTheSpoolBooksItsBowString()
    {
        Engine engine = ReviewFixesTest.engine();
        long now = ReviewFixesTest.T0 + 1_000;
        engine.setBaseline(capture(Map.of(InventoryID.INV, items(ItemID.BOWSTRING_SPOOL, 1, ItemID.FLAX, 10)), Map.of()));
        engine.markDirty();
        Transaction spun = settle(engine, capture(Map.of(InventoryID.INV, items(ItemID.BOWSTRING_SPOOL, 1, ItemID.FLAX, 9)),
            Map.of(VarbitID.BOWSTRING_SPOOL_CHARGES, 1)), now);
        assertNotNull(spun);
        assertEquals("the string is the product, not a lost flax", 1L, spun.quantity(ItemID.BOW_STRING, true));
        assertEquals(0L, engine.metrics(now + 20_000).net);
    }

    @Test
    public void chargingTheRingIsNeutralAndATeleportCostsOneOfEachRune()
    {
        Engine engine = ReviewFixesTest.engine();
        long now = ReviewFixesTest.T0 + 1_000;
        engine.setBaseline(capture(Map.of(InventoryID.INV, items(ItemID.RING_OF_ELEMENTS, 1, ItemID.AIRRUNE, 2,
            ItemID.EARTHRUNE, 2, ItemID.FIRERUNE, 2, ItemID.WATERRUNE, 2, ItemID.LAWRUNE, 2)), Map.of()));
        Map<Integer, Item[]> ring = Map.of(InventoryID.INV, items(ItemID.RING_OF_ELEMENTS_CHARGED, 1));
        engine.markDirty();
        settle(engine, capture(ring, Map.of(VarbitID.RING_OF_THE_ELEMENTS_CHARGES, 2)), now);
        assertEquals("the ring changes state and the runes only move", 0L, engine.metrics(now + 20_000).net);
        engine.markDirty();
        Transaction teleport = settle(engine, capture(ring, Map.of(VarbitID.RING_OF_THE_ELEMENTS_CHARGES, 1)),
            now + 30_000);
        assertNotNull(teleport);
        assertEquals(1L, teleport.quantity(ItemID.LAWRUNE, false));
        assertEquals(1L, teleport.quantity(ItemID.AIRRUNE, false));
        assertEquals("five runes at the fixture's 100 gp", -500L, engine.metrics(now + 50_000).net);
    }

    @Test
    public void carriedItemsCountTheirChargesAsComponents()
    {
        ContainerSnapshot obligator = capture(Map.of(InventoryID.WORN, items(ItemID.OBLIGATOR, 1),
            InventoryID.INV, items(ItemID.NATURERUNE, 5)), Map.of(VarbitID.CHARGES_OBLIGATOR_QUANTITY, 1_234));
        assertEquals("1,234 charges hold 123 whole shards", 123L, obligator.quantity(ItemID.ASCENSION_SHARDS));
        assertEquals("two nature runes a charge, plus 5 loose", 2_473L, obligator.quantity(ItemID.NATURERUNE));
        assertEquals(2_468L, obligator.quantity(ItemID.DEATHRUNE));

        ContainerSnapshot folio = capture(Map.of(InventoryID.INV, items(ItemID.ARCHIVISTS_FOLIO, 1)),
            Map.of(VarbitID.CHARGES_ARCHIVISTS_FOLIO_QUANTITY, 12));
        assertEquals("five teleports a shard", 2L, folio.quantity(ItemID.ASCENSION_SHARDS));

        ContainerSnapshot tome = capture(Map.of(InventoryID.WORN, items(ItemID.ZORYAS_TOME, 1)),
            Map.of(VarbitID.CHARGES_ZORYAS_TOME_QUANTITY, 100));
        assertEquals(300L, tome.quantity(ItemID.ASTRALRUNE));
        assertEquals(200L, tome.quantity(ItemID.NATURERUNE));
    }

    @Test
    public void bankedOrUnchargedItemsHoldNothing()
    {
        Map<Integer, Integer> charges = Map.of(VarbitID.CHARGES_OBLIGATOR_QUANTITY, 500,
            VarbitID.CHARGES_ZORYAS_TOME_QUANTITY, 500, VarbitID.CHARGES_ARCHIVISTS_FOLIO_QUANTITY, 500);
        ContainerSnapshot banked = capture(Map.of(InventoryID.INV, items(ItemID.DEATHRUNE, 40)), charges);
        assertEquals(40L, banked.quantity(ItemID.DEATHRUNE));
        assertEquals(0L, banked.quantity(ItemID.ASCENSION_SHARDS));
        ContainerSnapshot uncharged = capture(Map.of(InventoryID.WORN, items(ItemID.OBLIGATOR_UNCHARGED, 1,
            ItemID.ZORYAS_TOME_UNCHARGED, 1), InventoryID.INV, items(ItemID.ARCHIVISTS_FOLIO_EMPTY, 1)), charges);
        assertEquals(0L, uncharged.quantity(ItemID.NATURERUNE));
        assertEquals(0L, uncharged.quantity(ItemID.ASCENSION_SHARDS));
    }

    @Test
    public void chargesTheGameCountsAreTheVarbitsThePluginWatches()
    {
        for (int varbit : new int[] {VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY, VarbitID.CHARGES_OBLIGATOR_QUANTITY,
            VarbitID.CHARGES_ARCHIVISTS_FOLIO_QUANTITY, VarbitID.CHARGES_ZORYAS_TOME_QUANTITY,
            VarbitID.CHARGES_COWBELL_AMULET_QUANTITY})
        {
            assertTrue(String.valueOf(varbit), ContainerSnapshotFactory.storedCharges(varbit));
        }
    }

    @Test
    public void spendingChargesIsOneSupplyCostAndChargingBooksNothing()
    {
        Engine engine = ReviewFixesTest.engine();
        long now = ReviewFixesTest.T0 + 1_000;
        Map<Integer, Item[]> held = Map.of(InventoryID.WORN, items(ItemID.OBLIGATOR, 1),
            InventoryID.INV, items(ItemID.ASCENSION_SHARDS, 2, ItemID.NATURERUNE, 40, ItemID.DEATHRUNE, 40));
        engine.setBaseline(capture(held, Map.of(VarbitID.CHARGES_OBLIGATOR_QUANTITY, 100)));

        // Charging with 2 shards, 40 nature and 40 death runes adds 20 charges: the items only move.
        Map<Integer, Item[]> charged = Map.of(InventoryID.WORN, items(ItemID.OBLIGATOR, 1));
        engine.markDirty();
        assertNull("charging is not a cost", settle(engine,
            capture(charged, Map.of(VarbitID.CHARGES_OBLIGATOR_QUANTITY, 120)), now));
        assertEquals(0L, engine.metrics(now + 10_000).net);

        // Ten attacks spend ten charges: one shard, twenty nature and twenty death runes.
        engine.markDirty();
        Transaction spend = settle(engine, capture(charged, Map.of(VarbitID.CHARGES_OBLIGATOR_QUANTITY, 110)),
            now + 20_000);
        assertNotNull(spend);
        assertEquals(TransactionType.CONSUMPTION, spend.type());
        assertEquals("spent, counted as a cost", 1L, spend.quantity(ItemID.ASCENSION_SHARDS, false));
        assertEquals(20L, spend.quantity(ItemID.NATURERUNE, false));
        assertEquals(20L, spend.quantity(ItemID.DEATHRUNE, false));
        assertEquals("41 items at the fixture's 100 gp", -4_100L, engine.metrics(now + 40_000).net);
    }

    @Test
    public void aCounterSharedByTwoCarriedItemsCountsOnceAndSpendsOnce()
    {
        Map<Integer, Item[]> both = Map.of(InventoryID.INV, items(ItemID.CELESTIAL_RING_CHARGED, 1,
            ItemID.CELESTIAL_SIGNET_CHARGED, 1));
        assertEquals("one pool of 900 stardust", 900L,
            capture(both, Map.of(VarbitID.CHARGES_CELESTIAL_RING_QUANTITY, 900)).quantity(ItemID.STAR_DUST));
        Engine engine = ReviewFixesTest.engine();
        long now = ReviewFixesTest.T0 + 1_000;
        engine.setBaseline(capture(both, Map.of(VarbitID.CHARGES_CELESTIAL_RING_QUANTITY, 900)));
        engine.markDirty();
        settle(engine, capture(both, Map.of(VarbitID.CHARGES_CELESTIAL_RING_QUANTITY, 899)), now);
        assertEquals("one charge, one stardust at the fixture's 100 gp", -100L, engine.metrics(now + 20_000).net);
        ContainerSnapshot two = capture(Map.of(InventoryID.INV, items(ItemID.PLANK_SACK, 1)),
            Map.of(VarbitID.PLANK_SACK_OAK, 4, VarbitID.PLANK_SACK_TEAK, 3));
        assertEquals("other stores keep their own counters", 4L, two.quantity(ItemID.PLANK_OAK));
        assertEquals(3L, two.quantity(ItemID.PLANK_TEAK));
    }

    private static Transaction settle(Engine engine, ContainerSnapshot next, long at)
    {
        Transaction booked = null;
        for (int tick = 0; tick < 8 && booked == null; tick++)
        {
            booked = engine.processIfDirty(next, at + tick * 600L, null);
        }
        return booked;
    }

    private static Item[] items(int... idQuantity)
    {
        Item[] items = new Item[idQuantity.length / 2];
        for (int i = 0; i < items.length; i++)
        {
            items[i] = new Item(idQuantity[2 * i], idQuantity[2 * i + 1]);
        }
        return items;
    }

    private static ContainerSnapshot capture(Map<Integer, Item[]> containers, Map<Integer, Integer> varbits)
    {
        Map<Integer, ItemContainer> byId = new HashMap<>();
        containers.forEach((id, items) -> byId.put(id, (ItemContainer) Proxy.newProxyInstance(
            ItemContainer.class.getClassLoader(), new Class<?>[]{ItemContainer.class},
            (proxy, method, args) -> "getItems".equals(method.getName()) ? items : null)));
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getItemContainer":
                        return byId.get((int) args[0]);
                    case "getVarbitValue":
                        return varbits.getOrDefault((int) args[0], 0);
                    default:
                        return null;
                }
            });
        return new ContainerSnapshotFactory(client, null).capture(true, false);
    }
}
