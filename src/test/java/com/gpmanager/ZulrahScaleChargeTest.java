package com.gpmanager;

import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Owner 1.3.3: the Toxic staff of the dead and the serpentine, tanzanite and magma helms hold
 * Zulrah's scales. Their Check says only "Scales: N" (Wildy QoL's parsing), so the clicked item
 * names the row, and a later Check measures the scales used.
 */
public class ZulrahScaleChargeTest
{
    @Test
    public void bothCheckShapesReadAsScales()
    {
        ChargeCheck helm = ChargeCheck.parse("Scales: <col=007f00>11,000 (100.0%)</col>");
        assertNotNull(helm);
        assertTrue(helm.supported);
        assertEquals(Long.valueOf(11_000L), helm.counts.get(ItemID.SNAKEBOSS_SCALE));
        assertEquals(Long.valueOf(4_200L), ChargeCheck.parse("Scales: 4,200").counts.get(ItemID.SNAKEBOSS_SCALE));
    }

    @Test
    public void theClickedHelmNamesTheRowAndALaterCheckBooksTheScalesUsed()
    {
        Engine engine = HudBuilderTest.engine();
        ChargeIntake intake = new ChargeIntake(client(), null, HudBuilderTest.config(true, 4), engine, null);
        intake.startCheck(ChargeCheck.ChargedItem.SERPENTINE_HELM, "worn:helm", 10, 2);
        assertTrue(intake.observeCheck("Scales: 11,000 (100.0%)", 1_000L));
        MeasuredChargeReadTracker.Baseline read = engine.checks.baseline.get("worn:helm");
        assertNotNull("a Check of the helm is kept for that helm", read);
        assertEquals(ChargeCheck.ChargedItem.SERPENTINE_HELM, read.getRead().variant);
    }

    @Test
    public void scalesUsedOnTheStaffOrHelmAreALoad()
    {
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.TOXIC_STAFF, ItemID.SNAKEBOSS_SCALE, "Zulrah's scales"));
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.SERPENTINE_HELM, ItemID.SNAKEBOSS_SCALE, "Zulrah's scales"));
        assertEquals(ChargeCheck.ChargedItem.SERPENTINE_HELM, ChargeCheck.supportedForName("Tanzanite helm"));
        assertEquals(ChargeCheck.ChargedItem.TOXIC_STAFF, ChargeCheck.supported(ItemID.TOXIC_SOTD_CHARGED));
    }

    private static Client client()
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> "getTickCount".equals(method.getName()) ? 10 : null);
    }
}
