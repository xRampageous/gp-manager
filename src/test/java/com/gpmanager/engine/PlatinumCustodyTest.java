package com.gpmanager;

import com.gpmanager.SavedState.*;
import java.util.*;
import java.util.function.LongSupplier;
import net.runelite.api.*;
import net.runelite.api.gameval.ItemID;
import org.junit.*;
import static org.junit.Assert.*;
import static com.gpmanager.StoredChargeFixture.*;

/** Physical tokens stay visible; accounting evidence uses the same cash unit as inventory. */
public class PlatinumCustodyTest {
    static final int TOKENS = 13204, COINS = 995;
    LongSupplier previous;
    @Before public void clock() { previous = SafeMath.clock; SafeMath.clock = () -> NOW; }
    @After public void restoreClock() { SafeMath.clock = previous; }
    static ContainerSnapshot cash(long tokens) {
        return factory(tokens == 0 ? Map.of() : Map.of(TOKENS, tokens), Map.of()).capture(true, false);
    }
    static ItemContainer box(int tokens, int coins) {
        return LootingBagMovementTest.proxy(ItemContainer.class, (method, args) -> method.equals("getItems")
                ? new Item[]{new Item(TOKENS, tokens), new Item(COINS, coins)} : null);
    }

    @Test public void wildernessPvmBagTokensReturnWithoutIncomeAndResolveTheGrave() {
        Engine engine = engine();
        var before = factory(Map.of(ItemID.LOOTING_BAG, 1L), Map.of()).capture(true, false);
        engine.setBaseline(before);
        engine.rememberBag(Map.of(TOKENS, 2L));
        engine.markPvmDeath(20, before.items, engine.bagStock(), engine.events.owner);
        engine.rememberBag(Map.of());
        settle(engine, ContainerSnapshot.empty(), NOW + 1000);
        assertTrue(engine.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Loot", "Grave"), 20));
        settle(engine, cash(2), NOW + 20_000);
        assertEquals(0, engine.metrics(NOW + 30_000).income);
        assertEquals(100, engine.metrics(NOW + 30_000).cost); // only the destroyed empty bag
        assertFalse(engine.deathStatus().pending);
        assertEquals(0, reviews(engine));
    }

    @Test public void inventoryTokensMovedIntoTheBagAreNeutralAndRemainPhysicalStock() {
        var fixture = new LootingBagMovementTest.Fixture(engine());
        fixture.pack.put(TOKENS, 2L); fixture.baseline(); fixture.use(TOKENS, false);
        fixture.pack.remove(TOKENS); fixture.changed(); fixture.settle();
        assertEquals(0, fixture.engine.metrics(NOW + 100_000).net);
        assertEquals(0, reviews(fixture.engine));
        assertEquals(Map.of(TOKENS, 2L), fixture.engine.bagStock());
    }

    @Test public void splitTokenPickupBooksExactlyThePickedUpValue() {
        var fixture = new LootingBagMovementTest.Fixture(engine());
        fixture.pickup(TOKENS, 4, 1);
        assertEquals(4000, fixture.engine.metrics(NOW + 100_000).income);
        assertEquals(Map.of(TOKENS, 3L), fixture.engine.bagStock());
        assertEquals(0, reviews(fixture.engine));
    }

    @Test public void tokenKeyManifestKeepsItsOriginalKeyReceiptLink() {
        Engine engine = engine(); int key = ItemID.WILDY_LOOT_KEY0;
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markPkLoot(Map.of(key, 1L), 50, NOW);
        settle(engine, new ContainerSnapshot(Map.of(key, 1L)), NOW + 600);
        var receipt = engine.session().transactions().get(0);
        engine.setChestVisible(true);
        engine.observeKeyContainer(key, Map.of(TOKENS, 2L));
        engine.observeKeyContainer(key, Map.of());
        settle(engine, cash(2), NOW + 10_000);
        var received = engine.session().transactions().stream()
                .filter(row -> row.flows().stream().anyMatch(flow -> flow.item == COINS && flow.quantity > 0))
                .findFirst().orElseThrow();
        assertEquals(receipt.id(), received.claimId());
        assertEquals(2000, engine.metrics(NOW + 30_000).income);
    }

    @Test public void restoredRawTokenGraveNormalisesOutstandingAndDelayedHeldStock() {
        Engine engine = engine(); var state = engine.savedState();
        state.pendingDeathReclaim = new PendingDeath(List.of(new DeathItem(TOKENS, 2)),
                List.of(new DeathItem(TOKENS, 1)), 8, 0);
        engine.restore(state, NOW); engine.resume(NOW, PauseReason.IDLE, PauseReason.RECOVERY);
        engine.setBaseline(cash(1));
        settle(engine, cash(0), NOW + 600);
        assertEquals(0, engine.metrics(NOW + 10_000).cost);
        assertTrue(engine.noteReclaimIntent(BossRetrievalCatalogue.serviceFor("Loot", "Grave"), 20));
        settle(engine, cash(3), NOW + 20_000);
        assertEquals(0, engine.metrics(NOW + 30_000).income);
        assertFalse(engine.deathStatus().pending);
        assertNull(engine.savedState().pendingDeathReclaim);
    }

    @Test public void bankAllChestCashCountsTokensAndCoinsTogetherOnlyOnce() {
        Engine engine = engine(); int[] tick = {100}; ItemContainer[] contents = {box(2, 7)};
        var plugin = new GpManagerPlugin(); plugin.engine = engine;
        plugin.client = LootingBagMovementTest.proxy(Client.class, (method, args) ->
                method.equals("getTickCount") ? tick[0] : method.equals("getItemContainer") ? contents[0] : null);
        plugin.containers = new ContainerSnapshotFactory(plugin.client, null);
        plugin.bankAll(539); contents[0] = box(0, 0); tick[0]++;
        plugin.chestChanged(581, contents[0]); plugin.chestChanged(581, contents[0]);
        assertEquals(2007, engine.metrics(NOW + 10_000).income);
        assertEquals(2007, RewardChestBankAllTest.received(engine, COINS));
        assertEquals(0, RewardChestBankAllTest.received(engine, TOKENS));
    }

    @Test public void everyNativeRaidClaimMatchesTheNormalisedInventoryCash() {
        for (int kind = 0; kind < 4; kind++) for (boolean bank : new boolean[]{false, true}) {
            var nativeRaid = new AllRaidChestMatrixTest.Native(kind, 0);
            var fixture = nativeRaid.f; nativeRaid.open();
            fixture.containers.put(AllRaidChestMatrixTest.INV[kind], box(2, 0));
            fixture.nativeRaids.menu(AllRaidChestMatrixTest.button(kind, bank ? "BANK" : "INVENTORY"), fixture.now());
            fixture.containers.put(AllRaidChestMatrixTest.INV[kind], box(0, 0));
            fixture.nativeRaids.container(AllRaidChestMatrixTest.INV[kind], box(0, 0));
            if (!bank) fixture.inv();
            var row = fixture.row(COINS, 2000, 1, TransactionType.LOOT); nativeRaid.booked(row, bank);
            assertEquals("raid " + kind, nativeRaid.run, row.flows().get(0).raid);
            assertEquals(2000, nativeRaid.all());
        }
    }

    @Test public void privateCashProofReservesOnlyWholeTokensWithoutLosingItsSource() {
        for (int proof : new int[]{500, 1000, 1500, 2000}) {
            Engine engine = engine();
            engine.markLoot(Map.of(COINS, (long) proof), 50, "Loot from Guard", "Guard");
            long reserved = engine.events.reserve(Inbox.PRIVATE_LOOT, TOKENS, 2, Inbox.LOOT_RESERVE);
            assertEquals(proof / 1000, reserved);
            var source = engine.events.peek(Inbox.PRIVATE_LOOT);
            assertEquals(proof % 1000, source == null ? 0 : source.qty);
            if (reserved > 0) {
                var match = engine.consumeQueuedLoot(List.of(new Flow(COINS, "Coins", reserved * 1000, 1, reserved * 1000)));
                assertEquals("Loot from Guard", match.note);
            }
        }
    }

    @Test public void previouslyBookedRawTokenHistoryKeepsItsFrozenUnitsAndPrice() {
        Engine engine = engine();
        var old = new Transaction(NOW, TransactionType.LOOT, Context.LOOT, "Old receipt", "General", true,
                List.of(new Flow(TOKENS, "Platinum token", 2, 987, 1974)), Confidence.CONFIRMED, "", null);
        engine.add(old, false);
        var persisted = JsonCodec.gson().fromJson(JsonCodec.gson().toJson(engine.savedState()), SavedState.class);
        engine.restore(persisted, NOW);
        var retained = engine.session().transactions().get(0).flows().get(0);
        assertEquals(TOKENS, retained.item);
        assertEquals(2, retained.quantity);
        assertEquals(987, retained.unitPrice);
        assertEquals(1974, retained.value);
        assertEquals(1974, engine.metrics(NOW).income);
    }
}
