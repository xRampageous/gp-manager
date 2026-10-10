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
        ChargeCheck seas = ChargeCheck.parse(
            "Your Trident of the seas has 2,000 charges.");
        assertNotNull(seas);
        assertTrue(seas.bookable);
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS, seas.variant);
        assertEquals(2_000L, seas.componentCounts.get(DEATH_RUNE).longValue());
        assertEquals(2_000L, seas.componentCounts.get(CHAOS_RUNE).longValue());
        assertEquals(10_000L, seas.componentCounts.get(FIRE_RUNE).longValue());
        assertEquals(20_000L, seas.componentCounts.get(COINS).longValue());

        ChargeCheck swamp = ChargeCheck.parse(
            "Your Trident of the swamp has one charge.");
        assertNotNull(swamp);
        assertEquals(1L, swamp.componentCounts.get(DEATH_RUNE).longValue());
        assertEquals(1L, swamp.componentCounts.get(CHAOS_RUNE).longValue());
        assertEquals(5L, swamp.componentCounts.get(FIRE_RUNE).longValue());
        assertEquals(1L, swamp.componentCounts.get(ZULRAH_SCALES).longValue());

        ChargeCheck empty = ChargeCheck.parse(
            "Your Trident of the seas has no charges.");
        assertNotNull(empty);
    }

    @Test
    public void enhancedTridentsUseTheirRuneAndComponentRecipes()
    {
        ChargeCheck swamp = ChargeCheck.parse(
            "Your Trident of the swamp (e) has 20,000 charges.");
        assertNotNull(swamp);
        assertTrue(swamp.bookable);
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SWAMP_ENHANCED, swamp.variant);
        assertEquals(100_000L, swamp.componentCounts.get(FIRE_RUNE).longValue());

        ChargeCheck seas = ChargeCheck.parse(
            "Your Trident of the seas (e) has 20,000 charges.");
        assertNotNull(seas);
        assertTrue(seas.bookable);
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS_ENHANCED, seas.variant);
        assertEquals(20_000L, seas.componentCounts.get(DEATH_RUNE).longValue());
        assertEquals(20_000L, seas.componentCounts.get(CHAOS_RUNE).longValue());
        assertEquals(100_000L, seas.componentCounts.get(FIRE_RUNE).longValue());
        assertEquals(200_000L, seas.componentCounts.get(COINS).longValue());
        assertFalse(seas.componentCounts.containsKey(ZULRAH_SCALES));

        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS,
            ChargeCheck.supportedForId(ItemID.TOTS));
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS,
            ChargeCheck.supportedForName("Trident of the seas (full)"));
    }


    @Test
    public void wornAndOrnamentedChargeItemsKeepTheirExactVariantIdentity()
    {
        assertEquals(ChargeCheck.ChargedItem.BLOWPIPE,
            ChargeCheck.supportedForId(ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT));
        assertEquals(ChargeCheck.ChargedItem.BLOWPIPE,
            ChargeCheck.supportedForId(ItemID.TOXIC_BLOWPIPE));
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS,
            ChargeCheck.supportedForId(ItemID.TOTS_CHARGED_ORN));
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS_ENHANCED,
            ChargeCheck.supportedForId(ItemID.TOTS_I_CHARGED_ORN));
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SWAMP,
            ChargeCheck.supportedForId(ItemID.TOXIC_TOTS_CHARGED_ORN));
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SWAMP_ENHANCED,
            ChargeCheck.supportedForId(ItemID.TOXIC_TOTS_I_CHARGED_ORN));
    }

    @Test
    public void checkIdentityIncludesWidgetGroupAndSlotForInventoryAndWornItems()
    {
        int inventoryWidget = InterfaceID.Inventory.ITEMS;
        int equipmentWidget = InterfaceID.Wornitems.UNIVERSE;
        String inventory = ChargeIntake.clickedIdentity(
            menuCheck(ItemID.TOXIC_BLOWPIPE_LOADED, inventoryWidget, 7),
            ChargeCheck.ChargedItem.BLOWPIPE, ItemID.TOXIC_BLOWPIPE_LOADED);
        String worn = ChargeIntake.clickedIdentity(
            menuCheck(ItemID.TOXIC_BLOWPIPE_LOADED, equipmentWidget, 0),
            ChargeCheck.ChargedItem.BLOWPIPE, ItemID.TOXIC_BLOWPIPE_LOADED);
        assertEquals((inventoryWidget >>> 16) + ":7:" + ItemID.TOXIC_BLOWPIPE_LOADED
            + ":BLOWPIPE", inventory);
        assertEquals((equipmentWidget >>> 16) + ":0:" + ItemID.TOXIC_BLOWPIPE_LOADED
            + ":BLOWPIPE", worn);
        assertFalse(inventory.equals(worn));
    }

    @Test
    public void wornCheckWithoutItemFieldsResolvesItemAndWidgetFromTheSlotWidget()
    {
        int equipmentWidget = InterfaceID.Wornitems.UNIVERSE;
        MenuOptionClicked event = menuCheckViaWidget(ItemID.TOXIC_BLOWPIPE_LOADED, equipmentWidget, 3);
        assertEquals(ItemID.TOXIC_BLOWPIPE_LOADED, ChargeIntake.weaponItemId(event));
        assertEquals((equipmentWidget >>> 16) + ":3:" + ItemID.TOXIC_BLOWPIPE_LOADED
            + ":BLOWPIPE",
            ChargeIntake.clickedIdentity(event,
                ChargeCheck.ChargedItem.BLOWPIPE, ItemID.TOXIC_BLOWPIPE_LOADED));
    }

    @Test
    public void parsesBlowpipeDartsAndScalesAsIndependentComponents()
    {
        ChargeCheck read = ChargeCheck.parse(
            "Darts: <col=007f00>Adamant dart x 16,383</col>. "
                + "Scales: <col=007f00>1,234 (7.5%)</col>.");
        assertNotNull(read);
        assertTrue(read.bookable);
        assertEquals(ItemID.ADAMANT_DART, read.dartItemId);
        assertEquals(1_234L, read.componentCounts.get(ZULRAH_SCALES).longValue());
        assertEquals(16_383L, read.componentCounts.get(ItemID.ADAMANT_DART).longValue());

        ChargeCheck noDarts = ChargeCheck.parse(
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
        ChargeCheck unknown = ChargeCheck.parse(
            "Darts: Exotic dart x 100. Scales: 90 (1.0%).");
        assertNotNull(unknown);
        assertFalse(unknown.bookable);
        assertTrue(unknown.componentCounts.isEmpty());

        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        ChargeOutcomeTest.none(observe(tracker, blowpipe("Adamant", 100, 100)));
        ChargeOutcomeTest.none(observe(tracker, unknown));
        assertNull(tracker.baselines.get("single-target"));
        ChargeOutcomeTest.none(observe(tracker, blowpipe("Adamant", 95, 98)));
    }

    @Test
    public void tridentTrackerReturnsOnlyMeasuredNegativeIngredientCounts()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        ChargeOutcomeTest.none(observe(tracker, trident("seas", "", 12)));

        ChargeDelta spend = observe(tracker, trident("seas", "", 9));
        assertNotNull(spend);
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS, spend.getVariant());
        assertEquals(-3L, quantityDelta(spend, DEATH_RUNE));
        assertEquals(-3L, quantityDelta(spend, CHAOS_RUNE));
        assertEquals(-15L, quantityDelta(spend, FIRE_RUNE));
        assertEquals(-30L, quantityDelta(spend, COINS));

        assertTrue("an unchanged Check is an empty delta",
            observe(tracker, trident("seas", "", 9)).getComponentDeltas().isEmpty());
        ChargeOutcomeTest.none(observe(tracker, trident("seas", "", 11)));
        ChargeOutcomeTest.none(observe(tracker, trident("swamp", "", 5)));
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
        ChargeOutcomeTest.none(observe(tracker, blowpipe("Adamant", 1_000, 1_000)));
        ChargeDelta spend = observe(tracker, blowpipe("Adamant", 987, 996));
        assertNotNull(spend);
        assertEquals(2, spend.getComponentDeltas().size());
        assertEquals(-13L, quantityDelta(spend, ZULRAH_SCALES));
        assertEquals(-4L, quantityDelta(spend, ItemID.ADAMANT_DART));

        ChargeDelta independent = observe(tracker, blowpipe("Adamant", 980, 1_002));
        assertNotNull(independent);
        assertEquals(1, independent.getComponentDeltas().size());
        assertEquals(-7L, quantityDelta(independent, ZULRAH_SCALES));
        assertFalse("loaded darts are not unchanged", independent.unchanged.contains(ItemID.ADAMANT_DART));

        ChargeDelta dartsKept = observe(tracker, blowpipe("Adamant", 975, 1_002));
        assertEquals(-5L, quantityDelta(dartsKept, ZULRAH_SCALES));
        assertTrue("no dart left the pipe", dartsKept.unchanged.contains(ItemID.ADAMANT_DART));
        assertFalse(dartsKept.unchanged.contains(ZULRAH_SCALES));
    }

    @Test
    public void dartsLoadedSinceTheLastCheckAreNeverReadAsUnused()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(blowpipe("Adamant", 100, 100), "pipe", 1_000L);
        // Fired 10, picked 5 back up and reloaded them: the pipe reads 95, not 90.
        tracker.noteLoad(ChargeCheck.ChargedItem.BLOWPIPE, "pipe", 2_000L, ItemID.ADAMANT_DART, 5L);
        ChargeDelta reloaded = tracker.observe(blowpipe("Adamant", 100, 95), "pipe", 3_000L);
        assertEquals(ChargeDelta.CheckResult.SPEND, reloaded.result);
        assertEquals("all 10 left the pipe", -10L, quantityDelta(reloaded, ItemID.ADAMANT_DART));
        assertTrue(reloaded.unchanged.contains(ZULRAH_SCALES));

        // Fired 5 and loaded exactly 5: the count is unchanged, the use is not.
        tracker.noteLoad(ChargeCheck.ChargedItem.BLOWPIPE, "pipe", 4_000L, ItemID.ADAMANT_DART, 5L);
        ChargeDelta toppedUp = tracker.observe(blowpipe("Adamant", 100, 95), "pipe", 5_000L);
        assertEquals(ChargeDelta.CheckResult.SPEND, toppedUp.result);
        assertEquals(-5L, quantityDelta(toppedUp, ItemID.ADAMANT_DART));
        assertFalse(toppedUp.unchanged.contains(ItemID.ADAMANT_DART));
    }

    @Test
    public void aLoadIsCountedOnlyAgainstTheReadItFollows()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(blowpipe("Adamant", 100, 100), "pipe", 2_000L);
        // Clicked before that read (a "charged with" line reads the weapon before the load books).
        tracker.noteLoad(ChargeCheck.ChargedItem.BLOWPIPE, "pipe", 1_000L, ItemID.ADAMANT_DART, 5L);
        // Another weapon's load never touches this one.
        tracker.noteLoad(ChargeCheck.ChargedItem.BLOWPIPE, "other", 3_000L, ItemID.ADAMANT_DART, 5L);
        ChargeDelta delta = tracker.observe(blowpipe("Adamant", 100, 98), "pipe", 4_000L);
        assertEquals(-2L, quantityDelta(delta, ItemID.ADAMANT_DART));

        // The next read starts from nothing loaded.
        ChargeDelta next = tracker.observe(blowpipe("Adamant", 100, 98), "pipe", 5_000L);
        assertEquals(ChargeDelta.CheckResult.SAME, next.result);
    }

    @Test
    public void aLoadWithNoKnownWeaponForgetsOnlyThatKindsReads()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(blowpipe("Adamant", 100, 100), "pipe", 1_000L);
        tracker.observe(blowpipe("Adamant", 100, 100), "spare pipe", 1_000L);
        tracker.observe(ChargeCheck.parse("Your Trident of the seas has 10 charges."), "trident", 1_000L);
        // Two blowpipes: the load could be either.
        tracker.noteLoad(ChargeCheck.ChargedItem.BLOWPIPE, null, 2_000L, ItemID.ADAMANT_DART, 5L);
        assertNull(tracker.baselines.get("pipe"));
        assertNull(tracker.baselines.get("spare pipe"));
        assertNotNull(tracker.baselines.get("trident"));
    }

    @Test
    public void aLoadWithNoKnownWeaponGoesToTheOnlyOneOfItsKind()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(blowpipe("Adamant", 100, 100), "pipe", 1_000L);
        tracker.noteLoad(ChargeCheck.ChargedItem.BLOWPIPE, null, 2_000L, ItemID.ADAMANT_DART, 5L);
        assertNotNull(tracker.baselines.get("pipe"));
        ChargeDelta delta = tracker.observe(blowpipe("Adamant", 100, 103), "pipe", 3_000L);
        assertEquals("5 loaded, 2 fired", -2L, quantityDelta(delta, ItemID.ADAMANT_DART));
    }

    @Test
    public void resettingOneKindKeepsTheOthers()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(blowpipe("Adamant", 100, 100), "pipe", 1_000L);
        tracker.observe(ChargeCheck.parse("Your Trident of the seas has 10 charges."), "trident", 1_000L);
        tracker.resetVariant(ChargeCheck.ChargedItem.BLOWPIPE);
        assertNull(tracker.baselines.get("pipe"));
        assertNotNull(tracker.baselines.get("trident"));
    }

    @Test
    public void moreThanWasLoadedIsAnUnseenLoadNotSpend()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(blowpipe("Adamant", 100, 100), "pipe", 1_000L);
        tracker.noteLoad(ChargeCheck.ChargedItem.BLOWPIPE, "pipe", 2_000L, ItemID.ADAMANT_DART, 5L);
        ChargeDelta delta = tracker.observe(blowpipe("Adamant", 100, 120), "pipe", 3_000L);
        assertEquals(ChargeDelta.CheckResult.LOAD, delta.result);
        assertTrue(delta.getComponentDeltas().isEmpty());
    }

    @Test
    public void blowpipeDartTypeSwitchKeepsIndependentScaleMeasurement()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        ChargeOutcomeTest.none(observe(tracker, blowpipe("Adamant", 100, 100)));
        ChargeDelta switchDelta = observe(tracker, blowpipe("Rune", 90, 99));
        assertNotNull(switchDelta);
        assertEquals(1, switchDelta.getComponentDeltas().size());
        assertEquals(-10L, quantityDelta(switchDelta, ZULRAH_SCALES));
        assertTrue("a dart switch proves nothing about either dart", switchDelta.unchanged.isEmpty());
        assertEquals(ItemID.RUNE_DART, tracker.baselines.get("single-target").getRead().dartItemId);
        ChargeDelta afterSwitch = observe(tracker, blowpipe("Rune", 80, 97));
        assertNotNull(afterSwitch);
        assertEquals(-10L, quantityDelta(afterSwitch, ZULRAH_SCALES));
        assertEquals(-2L, quantityDelta(afterSwitch, ItemID.RUNE_DART));
    }

    @Test
    public void sameVariantWeaponTargetChangeSeedsInsteadOfComparingDifferentWeapons()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        ChargeCheck seas = ChargeCheck.parse(
            "Your Trident of the seas has 5,000 charges.");
        ChargeOutcomeTest.none(tracker.observe(seas, "inventory:slot-3:item-11907", System.currentTimeMillis()));
        ChargeOutcomeTest.none(tracker.observe(ChargeCheck.parse(
            "Your Trident of the seas has 1,000 charges."), "inventory:slot-9:item-11907", System.currentTimeMillis()));

        ChargeDelta spend = tracker.observe(ChargeCheck.parse(
            "Your Trident of the seas has 999 charges."), "inventory:slot-9:item-11907", System.currentTimeMillis());
        assertNotNull(spend);
        assertEquals(-1L, quantityDelta(spend, 560));
    }

    @Test
    public void checkTargetIdentityRecognizesChargedAndUnchargedItems()
    {
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS,
            ChargeCheck.supportedForId(11907));
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS,
            ChargeCheck.supportedForId(11908));
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SWAMP,
            ChargeCheck.supportedForId(12899));
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SWAMP_ENHANCED,
            ChargeCheck.supportedForId(22294));
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS_ENHANCED,
            ChargeCheck.supportedForId(22288));
        assertEquals(ChargeCheck.ChargedItem.BLOWPIPE,
            ChargeCheck.supportedForId(12926));
    }

    @Test
    public void explicitNoDartsClosesPriorKnownDartCount()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        ChargeOutcomeTest.none(observe(tracker, blowpipe("Dragon", 500, 3)));
        ChargeDelta spend = observe(tracker, noDarts(497));
        assertNotNull(spend);
        assertEquals(-3L, quantityDelta(spend, ZULRAH_SCALES));
        assertEquals(-3L, quantityDelta(spend, ItemID.DRAGON_DART));
    }

    @Test
    public void supportedIdentityAndLoadComponentChecksAreExact()
    {
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS,
            ChargeCheck.supportedForName("<col=ff9040>Trident of the seas</col>"));
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SWAMP,
            ChargeCheck.supportedForName("Trident of the swamp"));
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SWAMP_ENHANCED,
            ChargeCheck.supportedForName("Trident of the swamp (e)"));
        assertEquals(ChargeCheck.ChargedItem.BLOWPIPE,
            ChargeCheck.supportedForName("Toxic blowpipe"));
        assertEquals(ChargeCheck.ChargedItem.TRIDENT_SEAS_ENHANCED,
            ChargeCheck.supportedForName("Trident of the seas (e)"));
        assertNull(ChargeCheck.supportedForName("Blazing blowpipe"));

        assertTrue(ChargeCheck.isLoadComponent(
            ChargeCheck.ChargedItem.TRIDENT_SEAS, DEATH_RUNE, "Death runes"));
        assertTrue(ChargeCheck.isLoadComponent(
            ChargeCheck.ChargedItem.TRIDENT_SEAS, COINS, "Coins"));
        assertFalse(ChargeCheck.isLoadComponent(
            ChargeCheck.ChargedItem.TRIDENT_SEAS, ZULRAH_SCALES, "Zulrah's scales"));
        assertTrue(ChargeCheck.isLoadComponent(
            ChargeCheck.ChargedItem.TRIDENT_SWAMP_ENHANCED, ZULRAH_SCALES, "Zulrah's scales"));
        assertTrue(ChargeCheck.isLoadComponent(
            ChargeCheck.ChargedItem.BLOWPIPE, ItemID.ADAMANT_DART, "Adamant dart"));
        assertFalse(ChargeCheck.isLoadComponent(
            ChargeCheck.ChargedItem.BLOWPIPE, ItemID.ADAMANT_DART, "Rune dart"));
        assertFalse(ChargeCheck.isLoadComponent(
            ChargeCheck.ChargedItem.BLOWPIPE, ItemID.DRAGON_DART_P, "Dragon dart"));
    }

    @Test
    public void unrelatedAndMalformedMessagesCannotSpend()
    {
        assertNull(ChargeCheck.parse("You cast a spell."));
        ChargeCheck malformed = ChargeCheck.parse(
            "Darts: Adamant dart x many. Scales: 100 (1.0%).");
        assertNotNull(malformed);
        assertFalse(malformed.bookable);

        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        ChargeOutcomeTest.none(observe(tracker, trident("seas", "", 10)));
        ChargeOutcomeTest.none(observe(tracker, malformed));
        assertNull(tracker.baselines.get("single-target"));
    }

    private static ChargeDelta observe(
        MeasuredChargeReadTracker tracker,
        ChargeCheck read)
    {
        return tracker.observe(read, "single-target", System.currentTimeMillis());
    }

    private static ChargeCheck trident(String type, String enhanced, long charges)
    {
        String count = charges == 1L ? "one charge" : charges == 0L ? "no charges" : charges + " charges";
        return ChargeCheck.parse(
            "Your Trident of the " + type + enhanced + " has " + count + ".");
    }

    private static ChargeCheck blowpipe(String dart, long scales, long darts)
    {
        return ChargeCheck.parse(
            "Darts: " + dart + " dart x " + darts + ". Scales: " + scales + " (1.0%).");
    }

    private static ChargeCheck noDarts(long scales)
    {
        return ChargeCheck.parse(
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
