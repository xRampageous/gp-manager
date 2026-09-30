package com.gpmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

public final class MeasuredChargeReadTrackerTest
{
    private static final int DEATH_RUNE = 560;
    private static final int CHAOS_RUNE = 562;
    private static final int FIRE_RUNE = 554;
    private static final int ZULRAH_SCALES = 12934;
    private static final int COINS = 995;

    @Test
    public void parsesTridentCountsAndExactRecipes()
    {
        Ar seas = Ar.acs(
            "Your Trident of the seas has 2,000 charges.");
        assertNotNull(seas);
        assertTrue(seas.bookable);
        assertEquals(Ar.V.TRIDENT_SEAS, seas.variant);
        assertEquals(2_000L, seas.componentCounts.get(DEATH_RUNE).longValue());
        assertEquals(2_000L, seas.componentCounts.get(CHAOS_RUNE).longValue());
        assertEquals(10_000L, seas.componentCounts.get(FIRE_RUNE).longValue());
        assertEquals(20_000L, seas.componentCounts.get(COINS).longValue());

        Ar swamp = Ar.acs(
            "Your Trident of the swamp has one charge.");
        assertNotNull(swamp);
        assertEquals(1L, swamp.componentCounts.get(DEATH_RUNE).longValue());
        assertEquals(1L, swamp.componentCounts.get(CHAOS_RUNE).longValue());
        assertEquals(5L, swamp.componentCounts.get(FIRE_RUNE).longValue());
        assertEquals(1L, swamp.componentCounts.get(ZULRAH_SCALES).longValue());

        Ar empty = Ar.acs(
            "Your Trident of the seas has no charges.");
        assertNotNull(empty);
    }

    @Test
    public void enhancedTridentsUseTheirRuneAndComponentRecipes()
    {
        Ar swamp = Ar.acs(
            "Your Trident of the swamp (e) has 20,000 charges.");
        assertNotNull(swamp);
        assertTrue(swamp.bookable);
        assertEquals(Ar.V.TRIDENT_SWAMP_ENHANCED, swamp.variant);
        assertEquals(100_000L, swamp.componentCounts.get(FIRE_RUNE).longValue());

        Ar seas = Ar.acs(
            "Your Trident of the seas (e) has 20,000 charges.");
        assertNotNull(seas);
        assertTrue(seas.bookable);
        assertEquals(Ar.V.TRIDENT_SEAS_ENHANCED, seas.variant);
        assertEquals(20_000L, seas.componentCounts.get(DEATH_RUNE).longValue());
        assertEquals(20_000L, seas.componentCounts.get(CHAOS_RUNE).longValue());
        assertEquals(100_000L, seas.componentCounts.get(FIRE_RUNE).longValue());
        assertEquals(200_000L, seas.componentCounts.get(COINS).longValue());
        assertFalse(seas.componentCounts.containsKey(ZULRAH_SCALES));

        assertEquals(Ar.V.TRIDENT_SEAS,
            Ar.aja(ItemID.TOTS));
        assertEquals(Ar.V.TRIDENT_SEAS,
            Ar.ajb("Trident of the seas (full)"));
    }

    @Test
    public void parsesCurrentBottomlessBucketCheckSurfacesAndKeepsUseNoticesOut()
    {
        Ar empty = Ar.acs(
            "Your compost bucket is currently empty.");
        assertNotNull(empty);
        assertTrue(empty.bookable);
        assertEquals(0L, empty.componentCounts.get(ItemID.BUCKET_COMPOST).longValue());

        Ar holding = Ar.acs(
            "Your bottomless compost bucket is currently holding 42 uses of ultracompost.");
        assertNotNull(holding);
        assertTrue(holding.bookable);
        assertEquals(42L, holding.componentCounts.get(ItemID.BUCKET_ULTRACOMPOST).longValue());

        Ar singular = Ar.acs(
            "Your bottomless compost bucket is currently holding a single use of supercompost.");
        assertNotNull(singular);
        assertTrue(singular.bookable);
        assertEquals(1L, singular.componentCounts.get(ItemID.BUCKET_SUPERCOMPOST).longValue());

        // The post-application countdown and run-out notices are use evidence: a Check parser
        // must not bind them to a pending Check or reset unrelated measured baselines.
        assertNull(Ar.acs(
            "Your bottomless compost bucket has a single use of supercompost remaining."));
        assertNull(Ar.acs(
            "Your bottomless compost bucket has 40 uses of ultracompost remaining."));
        assertNull(Ar.acs(
            "Your bottomless compost bucket has run out of compost!"));
    }

    @Test
    public void wornAndOrnamentedChargeItemsKeepTheirExactVariantIdentity()
    {
        assertEquals(Ar.V.V1b,
            Ar.aja(ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT));
        assertEquals(Ar.V.V1b,
            Ar.aja(ItemID.TOXIC_BLOWPIPE));
        assertEquals(Ar.V.TRIDENT_SEAS,
            Ar.aja(ItemID.TOTS_CHARGED_ORN));
        assertEquals(Ar.V.TRIDENT_SEAS_ENHANCED,
            Ar.aja(ItemID.TOTS_I_CHARGED_ORN));
        assertEquals(Ar.V.TRIDENT_SWAMP,
            Ar.aja(ItemID.TOXIC_TOTS_CHARGED_ORN));
        assertEquals(Ar.V.TRIDENT_SWAMP_ENHANCED,
            Ar.aja(ItemID.TOXIC_TOTS_I_CHARGED_ORN));
    }

    @Test
    public void checkIdentityIncludesWidgetGroupAndSlotForInventoryAndWornItems()
    {
        int inventoryWidget = InterfaceID.Inventory.ITEMS;
        int equipmentWidget = InterfaceID.Wornitems.UNIVERSE;
        String inventory = ChargeIntake.nt(
            menuCheck(ItemID.TOXIC_BLOWPIPE_LOADED, inventoryWidget, 7),
            Ar.V.V1b, ItemID.TOXIC_BLOWPIPE_LOADED);
        String worn = ChargeIntake.nt(
            menuCheck(ItemID.TOXIC_BLOWPIPE_LOADED, equipmentWidget, 0),
            Ar.V.V1b, ItemID.TOXIC_BLOWPIPE_LOADED);
        assertEquals((inventoryWidget >>> 16) + ":7:" + ItemID.TOXIC_BLOWPIPE_LOADED
            + ":V1b", inventory);
        assertEquals((equipmentWidget >>> 16) + ":0:" + ItemID.TOXIC_BLOWPIPE_LOADED
            + ":V1b", worn);
        assertFalse(inventory.equals(worn));
    }

    @Test
    public void wornCheckWithoutItemFieldsResolvesItemAndWidgetFromTheSlotWidget()
    {
        int equipmentWidget = InterfaceID.Wornitems.UNIVERSE;
        MenuOptionClicked event = menuCheckViaWidget(ItemID.TOXIC_BLOWPIPE_LOADED, equipmentWidget, 3);
        assertEquals(ItemID.TOXIC_BLOWPIPE_LOADED, ChargeIntake.ns(event));
        assertEquals((equipmentWidget >>> 16) + ":3:" + ItemID.TOXIC_BLOWPIPE_LOADED
            + ":V1b",
            ChargeIntake.nt(event,
                Ar.V.V1b, ItemID.TOXIC_BLOWPIPE_LOADED));
    }

    @Test
    public void parsesBlowpipeDartsAndScalesAsIndependentComponents()
    {
        Ar read = Ar.acs(
            "Darts: <col=007f00>Adamant dart x 16,383</col>. "
                + "Scales: <col=007f00>1,234 (7.5%)</col>.");
        assertNotNull(read);
        assertTrue(read.bookable);
        assertEquals(ItemID.ADAMANT_DART, read.dartItemId);
        assertEquals(1_234L, read.componentCounts.get(ZULRAH_SCALES).longValue());
        assertEquals(16_383L, read.componentCounts.get(ItemID.ADAMANT_DART).longValue());

        Ar noDarts = Ar.acs(
            "Darts: None. Scales: 99 (0.6%).");
        assertNotNull(noDarts);
        assertTrue(noDarts.bookable);
        assertFalse(noDarts.awi());
        assertEquals(99L, noDarts.componentCounts.get(ZULRAH_SCALES).longValue());
        assertFalse(noDarts.componentCounts.containsKey(ItemID.ADAMANT_DART));
    }

    @Test
    public void unknownDartTypeReturnsUnsupportedReadToResetBaseline()
    {
        Ar unknown = Ar.acs(
            "Darts: Exotic dart x 100. Scales: 90 (1.0%).");
        assertNotNull(unknown);
        assertFalse(unknown.bookable);
        assertTrue(unknown.componentCounts.isEmpty());

        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(observe(tracker, blowpipe("Adamant", 100, 100)));
        assertNull(observe(tracker, unknown));
        assertNull(tracker.baseline("single-target"));
        assertNull(observe(tracker, blowpipe("Adamant", 95, 98)));
    }

    @Test
    public void tridentTrackerReturnsOnlyMeasuredNegativeIngredientCounts()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(observe(tracker, trident("seas", "", 12)));

        Cm spend = observe(tracker, trident("seas", "", 9));
        assertNotNull(spend);
        assertEquals(Ar.V.TRIDENT_SEAS, spend.getVariant());
        assertEquals(-3L, quantityDelta(spend, DEATH_RUNE));
        assertEquals(-3L, quantityDelta(spend, CHAOS_RUNE));
        assertEquals(-15L, quantityDelta(spend, FIRE_RUNE));
        assertEquals(-30L, quantityDelta(spend, COINS));

        assertNull(observe(tracker, trident("seas", "", 9)));
        assertNull(observe(tracker, trident("seas", "", 11)));
        assertNull(observe(tracker, trident("swamp", "", 5)));
        Cm swampSpend = observe(tracker, trident("swamp", "", 3));
        assertNotNull(swampSpend);
        assertEquals(-2L, quantityDelta(swampSpend, DEATH_RUNE));
        assertEquals(-10L, quantityDelta(swampSpend, FIRE_RUNE));
        assertEquals(-2L, quantityDelta(swampSpend, ZULRAH_SCALES));
        assertEquals(4, swampSpend.getComponentDeltas().size());
    }

    @Test
    public void blowpipeTrackerMeasuresComponentsAndNeverUsesRatios()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(observe(tracker, blowpipe("Adamant", 1_000, 1_000)));
        Cm spend = observe(tracker, blowpipe("Adamant", 987, 996));
        assertNotNull(spend);
        assertEquals(2, spend.getComponentDeltas().size());
        assertEquals(-13L, quantityDelta(spend, ZULRAH_SCALES));
        assertEquals(-4L, quantityDelta(spend, ItemID.ADAMANT_DART));

        Cm independent = observe(tracker, blowpipe("Adamant", 980, 1_002));
        assertNotNull(independent);
        assertEquals(1, independent.getComponentDeltas().size());
        assertEquals(-7L, quantityDelta(independent, ZULRAH_SCALES));
    }

    @Test
    public void blowpipeDartTypeSwitchKeepsIndependentScaleMeasurement()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(observe(tracker, blowpipe("Adamant", 100, 100)));
        Cm switchDelta = observe(tracker, blowpipe("Rune", 90, 99));
        assertNotNull(switchDelta);
        assertEquals(1, switchDelta.getComponentDeltas().size());
        assertEquals(-10L, quantityDelta(switchDelta, ZULRAH_SCALES));
        assertEquals(ItemID.RUNE_DART, tracker.baseline("single-target").getRead().dartItemId);
        Cm afterSwitch = observe(tracker, blowpipe("Rune", 80, 97));
        assertNotNull(afterSwitch);
        assertEquals(-10L, quantityDelta(afterSwitch, ZULRAH_SCALES));
        assertEquals(-2L, quantityDelta(afterSwitch, ItemID.RUNE_DART));
    }

    @Test
    public void sameVariantWeaponTargetChangeSeedsInsteadOfComparingDifferentWeapons()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        Ar seas = Ar.acs(
            "Your Trident of the seas has 5,000 charges.");
        assertNull(tracker.observe(seas, "inventory:slot-3:item-11907", System.currentTimeMillis()));
        assertNull(tracker.observe(Ar.acs(
            "Your Trident of the seas has 1,000 charges."), "inventory:slot-9:item-11907", System.currentTimeMillis()));

        Cm spend = tracker.observe(Ar.acs(
            "Your Trident of the seas has 999 charges."), "inventory:slot-9:item-11907", System.currentTimeMillis());
        assertNotNull(spend);
        assertEquals(-1L, quantityDelta(spend, 560));
    }

    @Test
    public void checkTargetIdentityRecognizesChargedAndUnchargedItems()
    {
        assertEquals(Ar.V.TRIDENT_SEAS,
            Ar.aja(11907));
        assertEquals(Ar.V.TRIDENT_SEAS,
            Ar.aja(11908));
        assertEquals(Ar.V.TRIDENT_SWAMP,
            Ar.aja(12899));
        assertEquals(Ar.V.TRIDENT_SWAMP_ENHANCED,
            Ar.aja(22294));
        assertEquals(Ar.V.TRIDENT_SEAS_ENHANCED,
            Ar.aja(22288));
        assertEquals(Ar.V.V1b,
            Ar.aja(12926));
    }

    @Test
    public void explicitNoDartsClosesPriorKnownDartCount()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(observe(tracker, blowpipe("Dragon", 500, 3)));
        Cm spend = observe(tracker, noDarts(497));
        assertNotNull(spend);
        assertEquals(-3L, quantityDelta(spend, ZULRAH_SCALES));
        assertEquals(-3L, quantityDelta(spend, ItemID.DRAGON_DART));
    }

    @Test
    public void supportedIdentityAndLoadComponentChecksAreExact()
    {
        assertEquals(Ar.V.TRIDENT_SEAS,
            Ar.ajb("<col=ff9040>Trident of the seas</col>"));
        assertEquals(Ar.V.TRIDENT_SWAMP,
            Ar.ajb("Trident of the swamp"));
        assertEquals(Ar.V.TRIDENT_SWAMP_ENHANCED,
            Ar.ajb("Trident of the swamp (e)"));
        assertEquals(Ar.V.V1b,
            Ar.ajb("Toxic blowpipe"));
        assertEquals(Ar.V.TRIDENT_SEAS_ENHANCED,
            Ar.ajb("Trident of the seas (e)"));
        assertNull(Ar.ajb("Blazing blowpipe"));

        assertTrue(Ar.xq(
            Ar.V.TRIDENT_SEAS, DEATH_RUNE, "Death runes"));
        assertTrue(Ar.xq(
            Ar.V.TRIDENT_SEAS, COINS, "Coins"));
        assertFalse(Ar.xq(
            Ar.V.TRIDENT_SEAS, ZULRAH_SCALES, "Zulrah's scales"));
        assertTrue(Ar.xq(
            Ar.V.TRIDENT_SWAMP_ENHANCED, ZULRAH_SCALES, "Zulrah's scales"));
        assertTrue(Ar.xq(
            Ar.V.V1b, ItemID.ADAMANT_DART, "Adamant dart"));
        assertFalse(Ar.xq(
            Ar.V.V1b, ItemID.ADAMANT_DART, "Rune dart"));
        assertFalse(Ar.xq(
            Ar.V.V1b, ItemID.DRAGON_DART_P, "Dragon dart"));
    }

    @Test
    public void unrelatedAndMalformedMessagesCannotSpend()
    {
        assertNull(Ar.acs("You cast a spell."));
        Ar malformed = Ar.acs(
            "Darts: Adamant dart x many. Scales: 100 (1.0%).");
        assertNotNull(malformed);
        assertFalse(malformed.bookable);

        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(observe(tracker, trident("seas", "", 10)));
        assertNull(observe(tracker, malformed));
        assertNull(tracker.baseline("single-target"));
    }

    private static Cm observe(
        MeasuredChargeReadTracker tracker,
        Ar read)
    {
        return tracker.observe(read, "single-target", System.currentTimeMillis());
    }

    private static Ar trident(String type, String enhanced, long charges)
    {
        String count = charges == 1L ? "one charge" : charges == 0L ? "no charges" : charges + " charges";
        return Ar.acs(
            "Your Trident of the " + type + enhanced + " has " + count + ".");
    }

    private static Ar blowpipe(String dart, long scales, long darts)
    {
        return Ar.acs(
            "Darts: " + dart + " dart x " + darts + ". Scales: " + scales + " (1.0%).");
    }

    private static Ar noDarts(long scales)
    {
        return Ar.acs(
            "Darts: None. Scales: " + scales + " (1.0%).");
    }

    private static MenuOptionClicked menuCheck(int itemId, int widgetId, int slot)
    {
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getOption": return "Check";
                    case "getTarget": return "Toxic blowpipe";
                    case "getIdentifier": return itemId;
                    case "getItemId": return itemId;
                    case "getType": return MenuAction.ITEM_FIRST_OPTION;
                    case "getParam0": return slot;
                    case "getParam1": return widgetId;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
        return new MenuOptionClicked(entry);
    }

    /** A worn Check whose item and widget fields are omitted; the slot widget carries both. */
    private static MenuOptionClicked menuCheckViaWidget(int itemId, int widgetId, int slot)
    {
        Widget widget = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
            new Class<?>[] {Widget.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getId": return widgetId;
                    case "getItemId": return itemId;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
        MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
            new Class<?>[] {MenuEntry.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getOption": return "Check";
                    case "getTarget": return "Toxic blowpipe";
                    case "getIdentifier": return -1;
                    case "getItemId": return -1;
                    case "getType": return MenuAction.ITEM_FIRST_OPTION;
                    case "getParam0": return slot;
                    case "getParam1": return 0;
                    case "getWidget": return widget;
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                }
            });
        return new MenuOptionClicked(entry);
    }

    private static long quantityDelta(Cm delta, int itemId)
    {
        assertNotNull(delta);
        for (Cm.ComponentDelta component : delta.getComponentDeltas())
        {
            if (component.getItemId() == itemId)
            {
                return component.getQuantityDelta();
            }
        }
        throw new AssertionError("Missing component delta for item " + itemId);
    }
}
