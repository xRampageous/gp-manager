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
        ChargeRead seas = ChargeRead.parseCheckMessage(
            "Your Trident of the seas has 2,000 charges.");
        assertNotNull(seas);
        assertTrue(seas.bookable);
        assertEquals(ChargeRead.Variant.TRIDENT_SEAS, seas.variant);
        assertEquals(2_000L, seas.componentCounts.get(DEATH_RUNE).longValue());
        assertEquals(2_000L, seas.componentCounts.get(CHAOS_RUNE).longValue());
        assertEquals(10_000L, seas.componentCounts.get(FIRE_RUNE).longValue());
        assertEquals(20_000L, seas.componentCounts.get(COINS).longValue());

        ChargeRead swamp = ChargeRead.parseCheckMessage(
            "Your Trident of the swamp has one charge.");
        assertNotNull(swamp);
        assertEquals(1L, swamp.componentCounts.get(DEATH_RUNE).longValue());
        assertEquals(1L, swamp.componentCounts.get(CHAOS_RUNE).longValue());
        assertEquals(5L, swamp.componentCounts.get(FIRE_RUNE).longValue());
        assertEquals(1L, swamp.componentCounts.get(ZULRAH_SCALES).longValue());

        ChargeRead empty = ChargeRead.parseCheckMessage(
            "Your Trident of the seas has no charges.");
        assertNotNull(empty);
    }

    @Test
    public void enhancedTridentsUseTheirRuneAndComponentRecipes()
    {
        ChargeRead swamp = ChargeRead.parseCheckMessage(
            "Your Trident of the swamp (e) has 20,000 charges.");
        assertNotNull(swamp);
        assertTrue(swamp.bookable);
        assertEquals(ChargeRead.Variant.TRIDENT_SWAMP_ENHANCED, swamp.variant);
        assertEquals(100_000L, swamp.componentCounts.get(FIRE_RUNE).longValue());

        ChargeRead seas = ChargeRead.parseCheckMessage(
            "Your Trident of the seas (e) has 20,000 charges.");
        assertNotNull(seas);
        assertTrue(seas.bookable);
        assertEquals(ChargeRead.Variant.TRIDENT_SEAS_ENHANCED, seas.variant);
        assertEquals(20_000L, seas.componentCounts.get(DEATH_RUNE).longValue());
        assertEquals(20_000L, seas.componentCounts.get(CHAOS_RUNE).longValue());
        assertEquals(100_000L, seas.componentCounts.get(FIRE_RUNE).longValue());
        assertEquals(200_000L, seas.componentCounts.get(COINS).longValue());
        assertFalse(seas.componentCounts.containsKey(ZULRAH_SCALES));

        assertEquals(ChargeRead.Variant.TRIDENT_SEAS,
            ChargeRead.supportedVariantForItemId(ItemID.TOTS));
        assertEquals(ChargeRead.Variant.TRIDENT_SEAS,
            ChargeRead.supportedVariantForItemName("Trident of the seas (full)"));
    }


    @Test
    public void wornAndOrnamentedChargeItemsKeepTheirExactVariantIdentity()
    {
        assertEquals(ChargeRead.Variant.V1b,
            ChargeRead.supportedVariantForItemId(ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT));
        assertEquals(ChargeRead.Variant.V1b,
            ChargeRead.supportedVariantForItemId(ItemID.TOXIC_BLOWPIPE));
        assertEquals(ChargeRead.Variant.TRIDENT_SEAS,
            ChargeRead.supportedVariantForItemId(ItemID.TOTS_CHARGED_ORN));
        assertEquals(ChargeRead.Variant.TRIDENT_SEAS_ENHANCED,
            ChargeRead.supportedVariantForItemId(ItemID.TOTS_I_CHARGED_ORN));
        assertEquals(ChargeRead.Variant.TRIDENT_SWAMP,
            ChargeRead.supportedVariantForItemId(ItemID.TOXIC_TOTS_CHARGED_ORN));
        assertEquals(ChargeRead.Variant.TRIDENT_SWAMP_ENHANCED,
            ChargeRead.supportedVariantForItemId(ItemID.TOXIC_TOTS_I_CHARGED_ORN));
    }

    @Test
    public void checkIdentityIncludesWidgetGroupAndSlotForInventoryAndWornItems()
    {
        int inventoryWidget = InterfaceID.Inventory.ITEMS;
        int equipmentWidget = InterfaceID.Wornitems.UNIVERSE;
        String inventory = ChargeIntake.chargeWeaponTargetIdentity(
            menuCheck(ItemID.TOXIC_BLOWPIPE_LOADED, inventoryWidget, 7),
            ChargeRead.Variant.V1b, ItemID.TOXIC_BLOWPIPE_LOADED);
        String worn = ChargeIntake.chargeWeaponTargetIdentity(
            menuCheck(ItemID.TOXIC_BLOWPIPE_LOADED, equipmentWidget, 0),
            ChargeRead.Variant.V1b, ItemID.TOXIC_BLOWPIPE_LOADED);
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
        assertEquals(ItemID.TOXIC_BLOWPIPE_LOADED, ChargeIntake.chargeWeaponItemId(event));
        assertEquals((equipmentWidget >>> 16) + ":3:" + ItemID.TOXIC_BLOWPIPE_LOADED
            + ":V1b",
            ChargeIntake.chargeWeaponTargetIdentity(event,
                ChargeRead.Variant.V1b, ItemID.TOXIC_BLOWPIPE_LOADED));
    }

    @Test
    public void parsesBlowpipeDartsAndScalesAsIndependentComponents()
    {
        ChargeRead read = ChargeRead.parseCheckMessage(
            "Darts: <col=007f00>Adamant dart x 16,383</col>. "
                + "Scales: <col=007f00>1,234 (7.5%)</col>.");
        assertNotNull(read);
        assertTrue(read.bookable);
        assertEquals(ItemID.ADAMANT_DART, read.dartItemId);
        assertEquals(1_234L, read.componentCounts.get(ZULRAH_SCALES).longValue());
        assertEquals(16_383L, read.componentCounts.get(ItemID.ADAMANT_DART).longValue());

        ChargeRead noDarts = ChargeRead.parseCheckMessage(
            "Darts: None. Scales: 99 (0.6%).");
        assertNotNull(noDarts);
        assertTrue(noDarts.bookable);
        assertFalse(noDarts.hasDarts());
        assertEquals(99L, noDarts.componentCounts.get(ZULRAH_SCALES).longValue());
        assertFalse(noDarts.componentCounts.containsKey(ItemID.ADAMANT_DART));
    }

    @Test
    public void unknownDartTypeReturnsUnsupportedReadToResetBaseline()
    {
        ChargeRead unknown = ChargeRead.parseCheckMessage(
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

        ChargeDelta spend = observe(tracker, trident("seas", "", 9));
        assertNotNull(spend);
        assertEquals(ChargeRead.Variant.TRIDENT_SEAS, spend.getVariant());
        assertEquals(-3L, quantityDelta(spend, DEATH_RUNE));
        assertEquals(-3L, quantityDelta(spend, CHAOS_RUNE));
        assertEquals(-15L, quantityDelta(spend, FIRE_RUNE));
        assertEquals(-30L, quantityDelta(spend, COINS));

        assertNull(observe(tracker, trident("seas", "", 9)));
        assertNull(observe(tracker, trident("seas", "", 11)));
        assertNull(observe(tracker, trident("swamp", "", 5)));
        ChargeDelta swampSpend = observe(tracker, trident("swamp", "", 3));
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
        ChargeDelta spend = observe(tracker, blowpipe("Adamant", 987, 996));
        assertNotNull(spend);
        assertEquals(2, spend.getComponentDeltas().size());
        assertEquals(-13L, quantityDelta(spend, ZULRAH_SCALES));
        assertEquals(-4L, quantityDelta(spend, ItemID.ADAMANT_DART));

        ChargeDelta independent = observe(tracker, blowpipe("Adamant", 980, 1_002));
        assertNotNull(independent);
        assertEquals(1, independent.getComponentDeltas().size());
        assertEquals(-7L, quantityDelta(independent, ZULRAH_SCALES));
    }

    @Test
    public void blowpipeDartTypeSwitchKeepsIndependentScaleMeasurement()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(observe(tracker, blowpipe("Adamant", 100, 100)));
        ChargeDelta switchDelta = observe(tracker, blowpipe("Rune", 90, 99));
        assertNotNull(switchDelta);
        assertEquals(1, switchDelta.getComponentDeltas().size());
        assertEquals(-10L, quantityDelta(switchDelta, ZULRAH_SCALES));
        assertEquals(ItemID.RUNE_DART, tracker.baseline("single-target").getRead().dartItemId);
        ChargeDelta afterSwitch = observe(tracker, blowpipe("Rune", 80, 97));
        assertNotNull(afterSwitch);
        assertEquals(-10L, quantityDelta(afterSwitch, ZULRAH_SCALES));
        assertEquals(-2L, quantityDelta(afterSwitch, ItemID.RUNE_DART));
    }

    @Test
    public void sameVariantWeaponTargetChangeSeedsInsteadOfComparingDifferentWeapons()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        ChargeRead seas = ChargeRead.parseCheckMessage(
            "Your Trident of the seas has 5,000 charges.");
        assertNull(tracker.observe(seas, "inventory:slot-3:item-11907", System.currentTimeMillis()));
        assertNull(tracker.observe(ChargeRead.parseCheckMessage(
            "Your Trident of the seas has 1,000 charges."), "inventory:slot-9:item-11907", System.currentTimeMillis()));

        ChargeDelta spend = tracker.observe(ChargeRead.parseCheckMessage(
            "Your Trident of the seas has 999 charges."), "inventory:slot-9:item-11907", System.currentTimeMillis());
        assertNotNull(spend);
        assertEquals(-1L, quantityDelta(spend, 560));
    }

    @Test
    public void checkTargetIdentityRecognizesChargedAndUnchargedItems()
    {
        assertEquals(ChargeRead.Variant.TRIDENT_SEAS,
            ChargeRead.supportedVariantForItemId(11907));
        assertEquals(ChargeRead.Variant.TRIDENT_SEAS,
            ChargeRead.supportedVariantForItemId(11908));
        assertEquals(ChargeRead.Variant.TRIDENT_SWAMP,
            ChargeRead.supportedVariantForItemId(12899));
        assertEquals(ChargeRead.Variant.TRIDENT_SWAMP_ENHANCED,
            ChargeRead.supportedVariantForItemId(22294));
        assertEquals(ChargeRead.Variant.TRIDENT_SEAS_ENHANCED,
            ChargeRead.supportedVariantForItemId(22288));
        assertEquals(ChargeRead.Variant.V1b,
            ChargeRead.supportedVariantForItemId(12926));
    }

    @Test
    public void explicitNoDartsClosesPriorKnownDartCount()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(observe(tracker, blowpipe("Dragon", 500, 3)));
        ChargeDelta spend = observe(tracker, noDarts(497));
        assertNotNull(spend);
        assertEquals(-3L, quantityDelta(spend, ZULRAH_SCALES));
        assertEquals(-3L, quantityDelta(spend, ItemID.DRAGON_DART));
    }

    @Test
    public void supportedIdentityAndLoadComponentChecksAreExact()
    {
        assertEquals(ChargeRead.Variant.TRIDENT_SEAS,
            ChargeRead.supportedVariantForItemName("<col=ff9040>Trident of the seas</col>"));
        assertEquals(ChargeRead.Variant.TRIDENT_SWAMP,
            ChargeRead.supportedVariantForItemName("Trident of the swamp"));
        assertEquals(ChargeRead.Variant.TRIDENT_SWAMP_ENHANCED,
            ChargeRead.supportedVariantForItemName("Trident of the swamp (e)"));
        assertEquals(ChargeRead.Variant.V1b,
            ChargeRead.supportedVariantForItemName("Toxic blowpipe"));
        assertEquals(ChargeRead.Variant.TRIDENT_SEAS_ENHANCED,
            ChargeRead.supportedVariantForItemName("Trident of the seas (e)"));
        assertNull(ChargeRead.supportedVariantForItemName("Blazing blowpipe"));

        assertTrue(ChargeRead.isSupportedLoadComponent(
            ChargeRead.Variant.TRIDENT_SEAS, DEATH_RUNE, "Death runes"));
        assertTrue(ChargeRead.isSupportedLoadComponent(
            ChargeRead.Variant.TRIDENT_SEAS, COINS, "Coins"));
        assertFalse(ChargeRead.isSupportedLoadComponent(
            ChargeRead.Variant.TRIDENT_SEAS, ZULRAH_SCALES, "Zulrah's scales"));
        assertTrue(ChargeRead.isSupportedLoadComponent(
            ChargeRead.Variant.TRIDENT_SWAMP_ENHANCED, ZULRAH_SCALES, "Zulrah's scales"));
        assertTrue(ChargeRead.isSupportedLoadComponent(
            ChargeRead.Variant.V1b, ItemID.ADAMANT_DART, "Adamant dart"));
        assertFalse(ChargeRead.isSupportedLoadComponent(
            ChargeRead.Variant.V1b, ItemID.ADAMANT_DART, "Rune dart"));
        assertFalse(ChargeRead.isSupportedLoadComponent(
            ChargeRead.Variant.V1b, ItemID.DRAGON_DART_P, "Dragon dart"));
    }

    @Test
    public void unrelatedAndMalformedMessagesCannotSpend()
    {
        assertNull(ChargeRead.parseCheckMessage("You cast a spell."));
        ChargeRead malformed = ChargeRead.parseCheckMessage(
            "Darts: Adamant dart x many. Scales: 100 (1.0%).");
        assertNotNull(malformed);
        assertFalse(malformed.bookable);

        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(observe(tracker, trident("seas", "", 10)));
        assertNull(observe(tracker, malformed));
        assertNull(tracker.baseline("single-target"));
    }

    private static ChargeDelta observe(
        MeasuredChargeReadTracker tracker,
        ChargeRead read)
    {
        return tracker.observe(read, "single-target", System.currentTimeMillis());
    }

    private static ChargeRead trident(String type, String enhanced, long charges)
    {
        String count = charges == 1L ? "one charge" : charges == 0L ? "no charges" : charges + " charges";
        return ChargeRead.parseCheckMessage(
            "Your Trident of the " + type + enhanced + " has " + count + ".");
    }

    private static ChargeRead blowpipe(String dart, long scales, long darts)
    {
        return ChargeRead.parseCheckMessage(
            "Darts: " + dart + " dart x " + darts + ". Scales: " + scales + " (1.0%).");
    }

    private static ChargeRead noDarts(long scales)
    {
        return ChargeRead.parseCheckMessage(
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

    private static long quantityDelta(ChargeDelta delta, int itemId)
    {
        assertNotNull(delta);
        for (ChargeDelta.ComponentDelta component : delta.getComponentDeltas())
        {
            if (component.getItemId() == itemId)
            {
                return component.getQuantityDelta();
            }
        }
        throw new AssertionError("Missing component delta for item " + itemId);
    }
}
