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
            ActionSignals.lightsFire("Use", "Tinderbox -> Willow logs"));
    }

    @Test
    public void unexplainedLossAndArbitraryUseRemainLosses()
    {
        Transaction unexplained = transaction("Logs");
        assertNull(ActionReader.resolve(null, unexplained.getFlows(), unexplained.getType(), "Firemaking"));
        assertEquals(CostKind.LOSS, CostKind.of(unexplained, unexplained.getFlows().get(0)));

        ActionKind arbitraryUse = ActionReader.verbOf("Use");
        assertNull(arbitraryUse);
        assertNull(ActionReader.resolve(arbitraryUse, unexplained.getFlows(),
            unexplained.getType(), "Firemaking"));
        assertEquals(CostKind.LOSS, CostKind.of(unexplained, unexplained.getFlows().get(0)));

        ActionKind mismatchedFuelIntent = ActionReader.resolve(ActionReader.verbOf("Light"),
            Collections.singletonList(new Flow(946, "Knife", -1L, 1, -1L)),
            TransactionType.CONSUMPTION, "Firemaking");
        assertNull("Light does not turn a non-fuel loss into Supplies", mismatchedFuelIntent);
    }

    private static void assertFuelAction(String menuOption, String name)
    {
        Transaction transaction = transaction(name);
        ActionKind resolved = ActionReader.resolve(ActionReader.verbOf(menuOption),
            transaction.getFlows(), transaction.getType(), "Firemaking");
        assertEquals(ActionKind.SUPPLIES, resolved);
        transaction.setActionKind(resolved);
        assertEquals(CostKind.SUPPLIES, CostKind.of(transaction, transaction.getFlows().get(0)));
    }

    private static Transaction transaction(String name)
    {
        return new Transaction(1_000L, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Firemaking", true,
            Collections.singletonList(new Flow(1519, name, -1L, 100, -100L)),
            Confidence.CONFIRMED, "explicit evidence test", null);
    }
}
