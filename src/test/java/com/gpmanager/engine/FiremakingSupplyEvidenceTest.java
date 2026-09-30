package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FiremakingSupplyEvidenceTest
{
    @Test
    public void explicitLightAndTendToFuelLossesAreTypedSupplies()
    {
        assertFuelAction("Light", "Willow logs");
        assertFuelAction("Tend-to", "Blisterwood logs");
        assertFuelAction("Tend to", "Juniper logs");
        assertTrue("existing Use Tinderbox evidence remains supported",
            Dw.ww("Use", "Tinderbox -> Willow logs"));
    }

    @Test
    public void unexplainedLossAndArbitraryUseRemainLosses()
    {
        Ac unexplained = transaction("Logs");
        assertNull(Bn.resolve(null, unexplained.getFlows(), unexplained.getType(), "Firemaking"));
        assertEquals(CostKind.LOSS, CostKind.of(unexplained, unexplained.getFlows().get(0)));

        Au arbitraryUse = Bn.tu("Use");
        assertNull(arbitraryUse);
        assertNull(Bn.resolve(arbitraryUse, unexplained.getFlows(),
            unexplained.getType(), "Firemaking"));
        assertEquals(CostKind.LOSS, CostKind.of(unexplained, unexplained.getFlows().get(0)));

        Au mismatchedFuelIntent = Bn.resolve(Bn.tu("Light"),
            Collections.singletonList(new Ab(946, "Knife", -1L, 1, -1L)),
            Ai.CONSUMPTION, "Firemaking");
        assertNull("Light does not turn a non-fuel loss into Supplies", mismatchedFuelIntent);
    }

    private static void assertFuelAction(String menuOption, String name)
    {
        Ac transaction = transaction(name);
        Au resolved = Bn.resolve(Bn.tu(menuOption),
            transaction.getFlows(), transaction.getType(), "Firemaking");
        assertEquals(Au.SUPPLIES, resolved);
        transaction.setActionKind(resolved);
        assertEquals(CostKind.SUPPLIES, CostKind.of(transaction, transaction.getFlows().get(0)));
    }

    private static Ac transaction(String name)
    {
        return new Ac(1_000L, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Firemaking", true,
            Collections.singletonList(new Ab(1519, name, -1L, 100, -100L)),
            Bd.CONFIRMED, "explicit evidence test", null);
    }
}
