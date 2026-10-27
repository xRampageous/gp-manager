package com.gpmanager;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Long prices stay long, and presentation arithmetic saturates rather than wrapping negative. */
public class WideValueArithmeticTest {
    static final long LARGE = Long.MAX_VALUE / 2 + 100;
    @Test public void partlyEatenFoodKeepsItsLongShareOfTheWholeQuote() {
        var valuation = new Valuation(ReviewFixesTest.CONFIG, id -> null,
                (id, active) -> id == 2327 ? 10_000_000_000L : 0, id -> false, Valuation.Economy.NORMAL);
        var half = valuation.value(Map.of(2331, 1L)).get(0);
        assertEquals(5_000_000_000L, half.unitPrice);
        assertEquals(5_000_000_000L, half.value);
    }
    @Test public void riskMultipliesWithSaturation() {
        var held = new Flow(1, "Synthetic gear", 2, LARGE, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, PvpState.risk(List.of(held), true, false));
    }
    @Test public void bagRiskAndCombinedRiskSaturate() {
        var fixture = new LootingBagMovementTest.Fixture(StoredChargeFixture.engine());
        fixture.engine.valuation = items -> {
            var flows = new ArrayList<Flow>();
            items.forEach((id, quantity) -> {
                long price = id == 1 || id == 2 ? LARGE : 0;
                flows.add(new Flow(id, "Synthetic gear", quantity, price, SafeMath.multiply(quantity, price)));
            });
            return flows;
        };
        fixture.pack.put(1, 5L); fixture.engine.rememberBag(Map.of(2, 2L));
        assertEquals(Long.MAX_VALUE, fixture.risk());
    }
    @Test public void trayResidualValueSaturatesAfterAPartialTake() {
        var tray = new HudTray();
        tray.add(1, "Synthetic gear", HudTray.State.PENDING, LARGE, 3, Long.MAX_VALUE, ReviewFixesTest.T0, 0);
        assertEquals(1, tray.take(HudTray.State.PENDING, 1, 1));
        assertEquals(2, tray.entries().get(0).quantity);
        assertEquals(Long.MAX_VALUE, tray.entries().get(0).value);
    }
}
