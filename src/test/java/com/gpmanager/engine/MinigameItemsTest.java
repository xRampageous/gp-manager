package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Minigame-only items never book: a loaned loadout or table supply is granted and stripped, and
 * neither side is ownership (Coinflow MinigameQuarantine evidence, Muse review, owner 2026-10-12).
 * Persistent rewards stay tracked.
 */
public class MinigameItemsTest
{
    private static final int FRAGMENT = 25201; // Soul fragment, priced so a strip would show
    private static final int TICKET = ItemID.CASTLEWARS_TICKET;
    private static final int TORSO = ItemID.TITHE_REWARD_TORSO_MALE;
    private static final int AGS = ItemID.AGS;
    private static final int SIP_FULL = ItemID.NZONE4DOSEABSORPTIONPOTION;
    private static final int SIP_LEFT = ItemID.NZONE3DOSEABSORPTIONPOTION;
    private static final int ESSENCE = ItemID.GOTR_GUARDIAN_ESSENCE;
    private static final int STONE = ItemID.GOTR_GUARDIAN_STONE_ELEMENTAL;
    private static final int PORTAL = ItemID.GOTR_PORTAL_TALISMAN_WATER;
    private static final int WATER_RUNE = ItemID.WATERRUNE;
    private static final int RAW_FISH = ItemID.TEMPOROSS_RAW_HARPOONFISH;
    private static final int FISH = ItemID.TEMPOROSS_HARPOONFISH;
    private static final int CRYSTAL_FISH = ItemID.TEMPOROSS_CRYSTALLISED_HARPOONFISH;
    private static final int PASTE = ItemID.MM_MOX_PASTE;
    private static final int BASE = ItemID.MM_POTION_MMM_UNFINISHED;
    private static final int DIGWEED = ItemID.MM_LAB_SPECIAL_HERB;
    private static final int SALVAGE_SMALL = ItemID.SAILING_SMALL_SHIPWRECK_SALVAGE;
    private static final int SALVAGE_MERCHANT = ItemID.SAILING_MERCHANT_SHIPWRECK_SALVAGE;
    private static final int CARGO = ItemID.CARGO_CRATE_PLATEBODIES_ALDARIN;
    private static final int PART = ItemID.SAILING_BULL_SHARK_JAW;
    private static final int DRINK = ItemID.SAILING_CHARTING_DRINK_CRATE_SMUGGLED_RUM;
    private static final int COINS = ItemID.COINS;
    private static final int SCHEMATIC = ItemID.LOST_SCHEMATIC_SALVAGING_STATION;
    private static final int SCHEMATIC_KEY = ItemID.LOST_SCHEMATIC_KEY_CHINCHOMPA;
    private static final int BOTTLE_EMPTY = ItemID.SAILING_BOAT_BOTTLE_EMPTY;
    private static final int BOTTLE_FULL = ItemID.SAILING_BOAT_BOTTLE_FULL;

    private static final Map<Integer, String> NAMES = Map.ofEntries(
        Map.entry(FRAGMENT, "Soul fragment"), Map.entry(TICKET, "Castle Wars ticket"),
        Map.entry(TORSO, "Farmer's torso"), Map.entry(AGS, "Armadyl godsword"),
        Map.entry(SIP_FULL, "Absorption (4)"), Map.entry(SIP_LEFT, "Absorption (3)"),
        Map.entry(ESSENCE, "Guardian essence"), Map.entry(STONE, "Elemental guardian stone"),
        Map.entry(PORTAL, "Portal talisman (water)"), Map.entry(WATER_RUNE, "Water rune"),
        Map.entry(RAW_FISH, "Raw harpoonfish"), Map.entry(FISH, "Harpoonfish"),
        Map.entry(CRYSTAL_FISH, "Crystallised harpoonfish"), Map.entry(PASTE, "Mox paste"),
        Map.entry(BASE, "Mammoth-might mix"), Map.entry(DIGWEED, "Digweed"),
        Map.entry(SALVAGE_SMALL, "Small salvage"), Map.entry(SALVAGE_MERCHANT, "Merchant salvage"),
        Map.entry(CARGO, "Crate of platebodies"), Map.entry(PART, "Bull shark jaw"),
        Map.entry(DRINK, "Bottle of almost smuggled rum"), Map.entry(COINS, "Coins"),
        Map.entry(SCHEMATIC, "Salvaging station schematic"),
        Map.entry(SCHEMATIC_KEY, "Small key (Chinchompa Island)"),
        Map.entry(BOTTLE_EMPTY, "Boat bottle (empty)"),
        Map.entry(BOTTLE_FULL, "Boat bottle (full)"));
    private static final Map<Integer, Integer> PRICES = Map.ofEntries(
        Map.entry(FRAGMENT, 1_000), Map.entry(TICKET, 1), Map.entry(TORSO, 100_000),
        Map.entry(AGS, 12_000_000), Map.entry(SIP_FULL, 500), Map.entry(SIP_LEFT, 500),
        Map.entry(ESSENCE, 30), Map.entry(STONE, 30), Map.entry(PORTAL, 1),
        Map.entry(WATER_RUNE, 5), Map.entry(RAW_FISH, 10), Map.entry(FISH, 20),
        Map.entry(CRYSTAL_FISH, 30), Map.entry(PASTE, 30), Map.entry(BASE, 30),
        Map.entry(DIGWEED, 10), Map.entry(SALVAGE_SMALL, 200), Map.entry(SALVAGE_MERCHANT, 2_000),
        Map.entry(CARGO, 2_000), Map.entry(PART, 100), Map.entry(DRINK, 500), Map.entry(COINS, 1),
        Map.entry(SCHEMATIC, 1), Map.entry(SCHEMATIC_KEY, 1), Map.entry(BOTTLE_EMPTY, 500),
        Map.entry(BOTTLE_FULL, 500));

    private static Engine settle(Map<Integer, Long> before, Map<Integer, Long> after)
    {
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, NAMES.get(id), quantity, PRICES.get(id),
                quantity * PRICES.get(id))));
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig()
        {
        });
        engine.ensureSession(1_000_000L);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(before)));
        engine.markDirty();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.processIfDirty(new ContainerSnapshot(new HashMap<>(after)), 1_000_000L + tick * 600L, null);
        }
        return engine;
    }

    @Test
    public void aLoanedFragmentGrantAndStripBothBookNothing()
    {
        // Grants and strips arrive as separate settles; the priced fragment would book a gain or
        // a loss without the strip.
        Engine granted = settle(Map.of(), Map.of(FRAGMENT, 1L));
        assertTrue(granted.session().transactions().toString(),
            granted.session().transactions().isEmpty());
        assertEquals(0L, granted.metrics(1_010_000L).net);

        Engine stripped = settle(Map.of(FRAGMENT, 1L), Map.of());
        assertTrue(stripped.session().transactions().toString(),
            stripped.session().transactions().isEmpty());
        assertEquals(0L, stripped.metrics(1_010_000L).net);
    }

    @Test
    public void aLoanerGrantMixedWithRealLootBooksOnlyTheLoot()
    {
        Engine engine = settle(Map.of(), Map.of(FRAGMENT, 1L, AGS, 1L));
        List<Transaction> rows = engine.session().transactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertTrue(rows.get(0).counts());
        assertEquals(12_000_000L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void persistentRewardsStillBook()
    {
        Engine ticket = settle(Map.of(), Map.of(TICKET, 1L));
        assertTrue(ticket.session().transactions().stream().anyMatch(Transaction::counts));

        Engine torso = settle(Map.of(), Map.of(TORSO, 1L));
        assertTrue(torso.session().transactions().stream().anyMatch(Transaction::counts));
    }

    @Test
    public void aLoanedDoseSipNeverFormsARealDosePair()
    {
        // -(4) + (3) in one settle: without the strip the dose-pair normalization could rewrite
        // the pair into real potion ids; stripped first, nothing books.
        Engine engine = settle(Map.of(SIP_FULL, 1L), Map.of(SIP_LEFT, 1L));
        assertTrue(engine.session().transactions().toString(),
            engine.session().transactions().isEmpty());
        assertEquals(0L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void aGuardianStoneGrantAndStripBothBookNothing()
    {
        Engine granted = settle(Map.of(), Map.of(STONE, 1L));
        assertTrue(granted.session().transactions().toString(),
            granted.session().transactions().isEmpty());
        assertEquals(0L, granted.metrics(1_010_000L).net);

        Engine stripped = settle(Map.of(STONE, 1L), Map.of());
        assertTrue(stripped.session().transactions().toString(),
            stripped.session().transactions().isEmpty());
        assertEquals(0L, stripped.metrics(1_010_000L).net);
    }

    @Test
    public void aPortalTalismanLossBooksNothing()
    {
        Engine engine = settle(Map.of(PORTAL, 1L), Map.of());
        assertTrue(engine.session().transactions().toString(),
            engine.session().transactions().isEmpty());
        assertEquals(0L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void theAltarCraftingBooksOnlyTheCraftedRunes()
    {
        // The live GOTR shape: the essence leaves, a guardian stone and the runes arrive; the
        // strip leaves the runes as the one counted gain.
        Engine engine = settle(Map.of(ESSENCE, 24L), Map.of(WATER_RUNE, 106L, STONE, 24L));
        List<Transaction> rows = engine.session().transactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertTrue(rows.get(0).counts());
        assertEquals(106L * 5L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void aTemporossAmmoFishGrantAndStripBothBookNothing()
    {
        // The ammo fish are minigame-only (not bankable, not tradeable, cannon ammunition);
        // the strip makes both sides invisible, like the other minigame supplies (owner
        // 2026-10-13 sweep).
        Engine granted = settle(Map.of(), Map.of(RAW_FISH, 1L, FISH, 1L, CRYSTAL_FISH, 1L));
        assertTrue(granted.session().transactions().toString(),
            granted.session().transactions().isEmpty());
        assertEquals(0L, granted.metrics(1_010_000L).net);

        Engine stripped = settle(Map.of(RAW_FISH, 1L, FISH, 1L, CRYSTAL_FISH, 1L), Map.of());
        assertTrue(stripped.session().transactions().toString(),
            stripped.session().transactions().isEmpty());
        assertEquals(0L, stripped.metrics(1_010_000L).net);
    }

    @Test
    public void aMixologyChainGrantAndStripBothBookNothing()
    {
        // Mastering Mixology's internal chain — the refinery's pastes, the order potions and the
        // digweed: the herbs book their cost at the refinery and the rewards book at the shop
        // (owner 2026-10-13).
        Engine granted = settle(Map.of(), Map.of(PASTE, 10L, BASE, 1L, DIGWEED, 1L));
        assertTrue(granted.session().transactions().toString(),
            granted.session().transactions().isEmpty());
        assertEquals(0L, granted.metrics(1_010_000L).net);

        Engine stripped = settle(Map.of(PASTE, 10L, BASE, 1L, DIGWEED, 1L), Map.of());
        assertTrue(stripped.session().transactions().toString(),
            stripped.session().transactions().isEmpty());
        assertEquals(0L, stripped.metrics(1_010_000L).net);
    }

    @Test
    public void aSailingSalvageGrantAndStripBothBookNothing()
    {
        // Sailing's shipwreck salvage is sorted into the loot at a salvaging station: the raw
        // salvage is an intermediate like the Mixology chain, so neither side of it books
        // (owner 2026-10-13).
        Engine granted = settle(Map.of(), Map.of(SALVAGE_SMALL, 1L, SALVAGE_MERCHANT, 1L));
        assertTrue(granted.session().transactions().toString(),
            granted.session().transactions().isEmpty());
        assertEquals(0L, granted.metrics(1_010_000L).net);

        Engine stripped = settle(Map.of(SALVAGE_SMALL, 1L, SALVAGE_MERCHANT, 1L), Map.of());
        assertTrue(stripped.session().transactions().toString(),
            stripped.session().transactions().isEmpty());
        assertEquals(0L, stripped.metrics(1_010_000L).net);
    }

    @Test
    public void theSailingSalvageSortBooksOnlyTheSortedLoot()
    {
        // The sort shape: the raw salvage leaves, the sorted drops arrive; the strip leaves the
        // counted gain as the only flow, where an unstripped pair would fail mixed.
        Engine engine = settle(Map.of(SALVAGE_SMALL, 4L), Map.of(WATER_RUNE, 10L));
        List<Transaction> rows = engine.session().transactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertTrue(rows.get(0).counts());
        assertEquals(10L * 5L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void aSailingCourierCargoGrantAndDepositBothBookNothing()
    {
        // Courier cargo only exists for the delivery: collected at the ledger table, carried
        // through the cargo hold and handed in - neither the grant nor the deposit is ownership
        // (owner 2026-10-13).
        Engine granted = settle(Map.of(), Map.of(CARGO, 2L));
        assertTrue(granted.session().transactions().toString(),
            granted.session().transactions().isEmpty());
        assertEquals(0L, granted.metrics(1_010_000L).net);

        Engine deposited = settle(Map.of(CARGO, 2L), Map.of());
        assertTrue(deposited.session().transactions().toString(),
            deposited.session().transactions().isEmpty());
        assertEquals(0L, deposited.metrics(1_010_000L).net);
    }

    @Test
    public void aSailingBountyHandInBooksOnlyTheCoins()
    {
        // The hand-in shape is mixed (parts out, coins in); the strip leaves the coin gain as the
        // only flow, where unstripped parts would fail the settle as uncertain (owner 2026-10-13).
        Engine engine = settle(Map.of(PART, 5L), Map.of(COINS, 25_000L));
        List<Transaction> rows = engine.session().transactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertTrue(rows.get(0).counts());
        assertEquals(25_000L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void theSailingChartingDrinkSampleBooksOnlyItsLoot()
    {
        // A sealed crate's one-shot drink is only the wrapper: sampling it keeps the real effects
        // counted - here the loot it grants - and the bottle itself never books (owner 2026-10-13).
        Engine engine = settle(Map.of(DRINK, 1L), Map.of(WATER_RUNE, 15L));
        List<Transaction> rows = engine.session().transactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertTrue(rows.get(0).counts());
        assertEquals(15L * 5L, engine.metrics(1_010_000L).net);
    }

    @Test
    public void aSailingUnlockChainGrantAndReadBookNothing()
    {
        // A lost schematic and its key only exist to teach a recipe: digging, unlocking or
        // reading them is never ownership; the facility's build costs still book (owner 1.3.4
        // review).
        Engine granted = settle(Map.of(), Map.of(SCHEMATIC, 1L, SCHEMATIC_KEY, 1L));
        assertTrue(granted.session().transactions().toString(),
            granted.session().transactions().isEmpty());
        assertEquals(0L, granted.metrics(1_010_000L).net);

        Engine read = settle(Map.of(SCHEMATIC, 1L, SCHEMATIC_KEY, 1L), Map.of());
        assertTrue(read.session().transactions().toString(),
            read.session().transactions().isEmpty());
        assertEquals(0L, read.metrics(1_010_000L).net);
    }

    @Test
    public void theBottledBoatCarrierNeverBooksOnlyTheEmptyBottleDoes()
    {
        // Bottling a boat consumes the tradeable empty bottle and yields the untradeable carrier;
        // the carrier is the boat, not a purchase, so only the empty bottle's use books
        // (owner 1.3.4 review).
        Engine bottled = settle(Map.of(BOTTLE_EMPTY, 1L), Map.of(BOTTLE_FULL, 1L));
        List<Transaction> rows = bottled.session().transactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertTrue(rows.get(0).counts());
        assertEquals(-500L, bottled.metrics(1_010_000L).net);

        Engine released = settle(Map.of(BOTTLE_FULL, 1L), Map.of());
        assertTrue(released.session().transactions().toString(),
            released.session().transactions().isEmpty());
        assertEquals(0L, released.metrics(1_010_000L).net);
    }
}
