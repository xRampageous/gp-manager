package com.gpmanager;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exact display contracts for the reviewed production-sharing trims. */
public class ProductionSharingContractTest {
    static LedgerData.ResourceSummary item(String name,long quantity) {
        return new LedgerData.ResourceSummary(1,name,"",quantity,0,0,false);
    }
    static String summary(LedgerData.ResourceSummary... items) {
        return new LedgerData.Card("",1,0,0,false,"",List.of(items)).summaryText();
    }
    @Test public void summariesRetainEmptyNullOrderAndQuantityBoundaries() {
        assertEquals("",summary());
        assertEquals("Bread dough ×14 · Jug",summary(item("Bread dough",14),item("Jug",1)));
        assertEquals("Jug",summary(item("",1),item("Jug",1)));
        assertEquals("Jug · ",summary(item("Jug",1),item("",1)));
        assertEquals("null · Jug",summary(item(null,1),item("Jug",0)));
        assertEquals("Name  · Other ×2",summary(item("Name ",-1),item("Other",2)));
        assertEquals("Coins ×9,223,372,036,854,775,807",summary(item("Coins",Long.MAX_VALUE)));
    }
    @Test public void cookingBasesKeepMissingAndInvalidInputRefusals() {
        assertTrue(ActionReader.isCooking(List.of(ProductionRecipeTest.leg(327,"Raw sardine",-1),ProductionRecipeTest.leg(325,"Sardine",1))));
        for(String name:Arrays.asList(null,"","raw ","uncooked ","Feather"))
            assertFalse(ActionReader.isCooking(List.of(ProductionRecipeTest.leg(327,name,-1),ProductionRecipeTest.leg(325,"Sardine",1))));
        assertFalse(ActionReader.isCooking(List.of(ProductionRecipeTest.leg(327,"Raw sardine",-1),ProductionRecipeTest.leg(314,"Feather",1))));
    }
}
