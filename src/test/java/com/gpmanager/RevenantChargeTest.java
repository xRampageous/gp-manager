package com.gpmanager;

import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Owner 1.2: revenant weapons spend one revenant ether per attack, read from the Check. */
public class RevenantChargeTest
{
    @Test
    public void theChecksOwnLinesParseAsEther()
    {
        ChargeCheck bow = ChargeCheck.parse("Your bow has 198 charges left powering it.");
        assertNotNull("the owner's live line must parse", bow);
        assertTrue(bow.supported);
        assertEquals(Long.valueOf(198L), bow.counts.get(ItemID.WILD_CAVE_SHARD));
        assertEquals(Long.valueOf(1L), ChargeCheck.parse("Your sceptre has 1 charge left powering it.").counts
            .get(ItemID.WILD_CAVE_SHARD));
        assertEquals(Long.valueOf(1_250L), ChargeCheck.parse("Your chainmace has 1,250 charges left powering it.").counts
            .get(ItemID.WILD_CAVE_SHARD));
    }

    @Test
    public void theClickedWeaponNamesTheRowTheLineLeavesOpen()
    {
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> "getTickCount".equals(method.getName()) ? 10 : null);
        Engine engine = HudBuilderTest.engine();
        ChargeIntake intake = new ChargeIntake(client, null, HudBuilderTest.config(true, 4), engine, null);
        intake.startCheck(ChargeCheck.ChargedItem.WEBWEAVER, "worn:webweaver", 10, 2);
        assertTrue(intake.observeCheck("Your bow has 198 charges left powering it.", 1_000L));
        MeasuredChargeReadTracker.Baseline read = engine.checks.baseline.get("worn:webweaver");
        assertNotNull("a Check of the Webweaver bow is kept for that bow", read);
        assertEquals(ChargeCheck.ChargedItem.WEBWEAVER, read.getRead().variant);
    }

    @Test
    public void etherUsedOnTheWeaponIsALoad()
    {
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.CRAW, ItemID.WILD_CAVE_SHARD, "Revenant ether"));
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.ACCURSED, ItemID.WILD_CAVE_SHARD, "Revenant ether"));
    }
}
