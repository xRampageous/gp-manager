package com.gpmanager;

import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.*;

public class BountyHunterLootTest {
    static PvpContext sample(Map<Integer,Integer> flags) {
        var client = LootingBagMovementTest.proxy(Client.class, (m,a) ->
                m.equals("getVarbitValue") ? flags.getOrDefault((int)a[0],0) : null);
        var player = ReviewFixesTest.proxy(Player.class, Map.of("getWorldLocation",new WorldPoint(3200,3200,0)));
        return new PvpContextReader(client).sample(player);
    }
    @Test public void modernBountyArenaIsDangerousWithoutOldWorldFlags() {
        var context=sample(Map.of(VarbitID.BH_IN_BH,1));
        assertEquals(PvpContext.DANGEROUS_PVP,context);
        assertEquals(GpManagerPlugin.DeathOutcome.TRACK_PK,GpManagerPlugin.deathOutcome(true,10,false,context));
    }
    @Test public void bountyLobbyAndLmsWinOverConflictingDangerSignals() {
        for (int safe : new int[]{VarbitID.BH_IN_LOBBY,VarbitID.BR_INGAME}) {
            var context=sample(Map.of(VarbitID.BH_IN_BH,1,VarbitID.INSIDE_WILDERNESS,1,safe,1));
            assertTrue(context.isSafe());
        }
        assertEquals(PvpContext.NONE,sample(Map.of()));
    }
    @Test public void noKeyKillCountsOnlyActualPickupsOnce() {
        var engine=LmsBorrowedItemsTest.engine();
        engine.markPkLoot(Map.of(4151,2L),20,2000);
        LmsBorrowedItemsTest.settle(engine,Map.of(),2100);
        assertEquals(0,engine.metrics(2200).income);
        LmsBorrowedItemsTest.settle(engine,Map.of(4151,1L),2300);
        assertEquals(100,engine.metrics(2400).income);
        LmsBorrowedItemsTest.settle(engine,Map.of(4151,2L),2500);
        LmsBorrowedItemsTest.settle(engine,Map.of(4151,2L),2600);
        assertEquals(200,engine.metrics(2700).income);
        assertEquals(1,engine.session().pk().kills);
        assertTrue(engine.pendingClaims().isEmpty());
        assertTrue(engine.session().transactions().stream().filter(Transaction::counts)
                .allMatch(row -> row.original()==TransactionType.PK_LOOT));
    }
    @Test public void noKeyPickupStraightIntoBagIsStillMeasuredOnce() {
        var fixture=new LootingBagMovementTest.Fixture();
        fixture.engine.markPkLoot(Map.of(1079,2L),20,LootingBagMovementTest.T0);
        fixture.pickup(1079,2L,0);
        long income=fixture.engine.metrics(LootingBagMovementTest.T0+20000).income;
        assertTrue(income>0);
        assertEquals(2L,fixture.engine.bagStock().get(1079).longValue());
        fixture.settle();
        assertEquals(income,fixture.engine.metrics(LootingBagMovementTest.T0+21000).income);
        assertTrue(fixture.engine.pendingClaims().isEmpty());
    }
}
