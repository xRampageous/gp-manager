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
        Ar bow = Ar.acs("Your bow has 198 charges left powering it.");
        assertNotNull("the owner's live line must parse", bow);
        assertTrue(bow.bookable);
        assertEquals(Long.valueOf(198L), bow.componentCounts.get(ItemID.WILD_CAVE_SHARD));
        assertEquals(Long.valueOf(1L), Ar.acs("Your sceptre has 1 charge left powering it.").componentCounts
            .get(ItemID.WILD_CAVE_SHARD));
        assertEquals(Long.valueOf(1_250L), Ar.acs("Your chainmace has 1,250 charges left powering it.").componentCounts
            .get(ItemID.WILD_CAVE_SHARD));
    }

    @Test
    public void theClickedWeaponNamesTheRowTheLineLeavesOpen()
    {
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> "getTickCount".equals(method.getName()) ? 10 : null);
        Am engine = HudBuilderTest.engine();
        ChargeIntake intake = new ChargeIntake(client, null, HudBuilderTest.config(true, 4), engine, null);
        intake.ck(Ar.V.WEBWEAVER, "worn:webweaver", 10, 2);
        assertTrue(intake.abt("Your bow has 198 charges left powering it.", 1_000L));
        MeasuredChargeReadTracker.Baseline read = engine.measuredChargeReadTracker.baseline("worn:webweaver");
        assertNotNull("a Check of the Webweaver bow is kept for that bow", read);
        assertEquals(Ar.V.WEBWEAVER, read.getRead().variant);
    }

    @Test
    public void etherUsedOnTheWeaponIsALoad()
    {
        assertTrue(Ar.xq(Ar.V.CRAW, ItemID.WILD_CAVE_SHARD, "Revenant ether"));
        assertTrue(Ar.xq(Ar.V.ACCURSED, ItemID.WILD_CAVE_SHARD, "Revenant ether"));
    }
}
