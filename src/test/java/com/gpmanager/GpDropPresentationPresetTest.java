package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpDropPresentationPresetTest
{
    @Test
    public void iconValueOnlyIsAnExplicitItemIconPreset()
    {
        GpDropPresentationPreset preset = GpDropPresentationPreset.ICON_VALUE_ONLY;

        assertEquals(GpDropContentMode.EACH_ITEM, preset.getContentMode());
        assertEquals(GpDropItemTextMode.VALUE_ONLY, preset.getItemTextMode());
        assertEquals(GpDropIconMode.SMART, preset.getIconMode());
        assertFalse(preset.isQuantityOnIcon());
        assertFalse(preset.isCustom());
    }

    @Test
    public void customLeavesAdvancedControlsInCharge()
    {
        GpDropPresentationPreset preset = GpDropPresentationPreset.CUSTOM;

        assertTrue(preset.isCustom());
        assertEquals(null, preset.getContentMode());
        assertEquals(null, preset.getItemTextMode());
        assertEquals(null, preset.getIconMode());
    }

    @Test
    public void classicAmountsStayReadableAtLargeValues()
    {
        assertEquals("9999", GpManagerGpDropOverlay.formatClassicProfitTrackerAmount(9_999L));
        assertEquals("10K", GpManagerGpDropOverlay.formatClassicProfitTrackerAmount(10_000L));
        assertEquals("-999K", GpManagerGpDropOverlay.formatClassicProfitTrackerAmount(-999_999L));
        assertEquals("1.2M", GpManagerGpDropOverlay.formatClassicProfitTrackerAmount(1_250_000L));
        assertEquals("-2B", GpManagerGpDropOverlay.formatClassicProfitTrackerAmount(-2_000_000_000L));
    }
}
