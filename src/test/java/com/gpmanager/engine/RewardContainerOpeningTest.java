package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.Map;
import net.runelite.api.ItemComposition;
import static net.runelite.api.gameval.ItemID.*;
import net.runelite.client.game.ItemMapping;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/**
 * Untradeable reward containers opened for their contents (OSRS Wiki, 2026-11-07; Gilded Ledger's
 * minigame rewards listed the Wintertodt supply crate). Each keeps its own alchemy value, so
 * opening one is the container leaving and its contents arriving: income, never a Review.
 */
@RunWith(Parameterized.class)
public class RewardContainerOpeningTest {
    @Parameterized.Parameters(name="{1}") public static Object[][] containers() {
        return new Object[][] {
            {PIRATE_CASKET,"Casket",30},
            {TOA_RUNE_CACHE,"Cache of runes",60},
            {FROZEN_CACHE,"Frozen cache",60},
            {FORGOTTEN_LOCKBOX,"Forgotten lockbox",60},
            {MACRO_QUIZ_MYSTERY_BOX,"Mystery box",3},
            {BH_EP_CRATE_2,"Bounty crate (tier 1)",300},
            {BH_EP_CRATE_10,"Bounty crate (tier 9)",300},
            {WINT_REROLL_BOX,"Extra supply crate",300},
            {CONSTRUCTION_SUPPLY_CRATE,"Supply crate",87},
            {CASTLEWARS_CRATE,"Castle wars supply crate",1}
        };
    }
    @Parameterized.Parameter(0) public int container;
    @Parameterized.Parameter(1) public String name;
    @Parameterized.Parameter(2) public int ha;
    static final int REWARD = UNCUT_SAPPHIRE;
    private long now = 1_000_000;

    private ItemComposition definition(int id) {
        return (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
                new Class<?>[]{ItemComposition.class}, (proxy, method, args) -> {
            switch(method.getName()) {
                case "getName": return id == container ? name : id == COINS ? "Coins" : "Uncut sapphire";
                case "getId": return id;
                case "getHaPrice": return id == container ? ha : 1;
                case "isTradeable": return id != container;
                default: return method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? 0 : null;
            }
        });
    }
    private Engine engine() {
        JsonCodec.bind(new com.google.gson.Gson());
        var values = new Valuation(new GpManagerConfig(){}, this::definition, (id,active) -> id == REWARD ? 50 : 0,
                id -> Valuation.hasUnsupportedMapping(ItemMapping.map(id)), Valuation.Economy.NORMAL);
        var engine = new Engine(values,new TransactionClassifier(),new GpManagerConfig(){
            @Override public int stabilizationTicks(){return 0;}
        });
        engine.startCustom("Reward container",now);
        engine.setBaseline(new ContainerSnapshot(Map.of(container,1L)));
        return engine;
    }

    @Test public void openingBooksTheContentsAsIncomeWithoutAReview() {
        var engine = engine(); var opened = Map.of(REWARD,4L,COINS,500L);
        engine.processIfDirty(new ContainerSnapshot(opened),now += 3000,null);
        engine.processIfDirty(new ContainerSnapshot(opened),now + 600,null);
        var rows = engine.session().transactions();
        assertEquals(rows.toString(),1,rows.size());
        assertFalse(name+" must not wait for a decision",ReviewRules.needsReview(rows.get(0)));
        assertEquals("the contents less the container's own alchemy value",4*50L+500-ha,engine.metrics(now+1000).net);
    }
}
