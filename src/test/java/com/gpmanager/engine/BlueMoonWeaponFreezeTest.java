package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Blue Moon's Weapon Freeze (Moons of Peril) seals the wielded weapon in a block of ice until the
 * player punches it free; after a logout Eyatlalli holds it (OSRS Wiki: Blue Moon, Eyatlalli).
 * Profit Loss Calculator keys the freeze off PMOON_BOSS_BLUE_WEARPREVENT. The weapon stays carried
 * throughout, so the round trip books nothing.
 */
public class BlueMoonWeaponFreezeTest
{
    private static final int WEAPON = EquipmentInventorySlot.WEAPON.getSlotIdx();

    /** Mutable inventory, worn weapon slot and freeze varbit behind a proxy client. */
    static final class Harness
    {
        Item[] inventory = new Item[0];
        Item weapon;
        int frozen;
        /** The logged-in account; null leaves it unknown, as an offline client reports. */
        Long hash;
        final ContainerSnapshotFactory factory;

        Harness()
        {
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, args) -> {
                    if ("getItemContainer".equals(method.getName()))
                    {
                        int id = (int) args[0];
                        return id == InventoryID.INV ? container(inventory) : id == InventoryID.WORN ? worn() : null;
                    }
                    if ("getVarbitValue".equals(method.getName()))
                    {
                        return (int) args[0] == VarbitID.PMOON_BOSS_BLUE_WEARPREVENT ? frozen : 0;
                    }
                    if ("getAccountHash".equals(method.getName()))
                    {
                        return hash;
                    }
                    return null;
                });
            factory = new ContainerSnapshotFactory(client, null);
        }

        ItemContainer worn()
        {
            Item[] slots = new Item[14];
            slots[WEAPON] = weapon;
            return container(slots);
        }

        static ItemContainer container(Item[] items)
        {
            return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
                new Class<?>[]{ItemContainer.class}, (proxy, method, args) -> {
                    if ("getItems".equals(method.getName()))
                    {
                        return items;
                    }
                    if ("getItem".equals(method.getName()))
                    {
                        int slot = (int) args[0];
                        return slot < items.length ? items[slot] : null;
                    }
                    return null;
                });
        }

        long carried(int item)
        {
            return factory.capture(true, false).quantity(item);
        }
    }

    @Test
    public void aFrozenWeaponStaysCarriedUntilItReturns()
    {
        Harness game = new Harness();
        game.weapon = new Item(ItemID.ABYSSAL_WHIP, 1);
        assertEquals(1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.frozen = 1;
        assertEquals("the weapon still wielded as the freeze starts", 1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.weapon = null;
        assertEquals("sealed in ice", 1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.frozen = 0;
        assertEquals("the varbit clearing before the weapon is back", 1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.weapon = new Item(ItemID.ABYSSAL_WHIP, 1);
        assertEquals("punched free", 1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.weapon = null;
        assertEquals("a later deposit from the slot is a real change", 0L, game.carried(ItemID.ABYSSAL_WHIP));
    }

    @Test
    public void aFrozenThrownStackKeepsItsQuantity()
    {
        Harness game = new Harness();
        game.weapon = new Item(ItemID.DRAGON_DART, 2_000);
        game.carried(ItemID.DRAGON_DART);
        game.weapon = null;
        game.frozen = 1;
        assertEquals(2_000L, game.carried(ItemID.DRAGON_DART));
    }

    @Test
    public void eyatlalliReturningItToTheInventoryCountsItOnce()
    {
        Harness game = new Harness();
        game.weapon = new Item(ItemID.ABYSSAL_WHIP, 1);
        game.carried(ItemID.ABYSSAL_WHIP);
        game.weapon = null;
        game.frozen = 1;
        game.carried(ItemID.ABYSSAL_WHIP);
        game.frozen = 0;
        game.inventory = new Item[]{new Item(ItemID.ABYSSAL_WHIP, 1)};
        assertEquals(1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.inventory = new Item[0];
        assertEquals("the hold ended once the weapon was seen", 0L, game.carried(ItemID.ABYSSAL_WHIP));
    }

    @Test
    public void withoutTheFreezeAnEmptiedSlotIsNotHeld()
    {
        Harness game = new Harness();
        game.weapon = new Item(ItemID.ABYSSAL_WHIP, 1);
        game.carried(ItemID.ABYSSAL_WHIP);
        game.weapon = null;
        assertEquals(0L, game.carried(ItemID.ABYSSAL_WHIP));
    }

    @Test
    public void theRoundTripBooksNothing()
    {
        Harness game = new Harness();
        game.weapon = new Item(ItemID.DRAGON_DART, 500);
        Engine engine = new Engine(deltas -> {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, "Dragon dart", quantity, 1_000, quantity * 1_000)));
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 2;
            }
        });
        engine.ensureSession(1_000L);
        engine.setBaseline(game.factory.capture(true, false));
        long now = 1_600L;
        for (int tick = 0; tick < 30; tick++, now += 600L)
        {
            game.frozen = tick >= 2 && tick < 20 ? 1 : 0;
            Item before = game.weapon;
            game.weapon = tick >= 3 && tick < 22 ? null : new Item(ItemID.DRAGON_DART, 500);
            // Only a worn-container change marks the inventory dirty, as in the plugin.
            if ((before == null) != (game.weapon == null)) engine.markDirty();
            engine.processIfDirty(game.factory.capture(true, false), now, null);
        }
        assertTrue(engine.session().transactions().isEmpty());
        assertEquals(0L, engine.metrics(now).cost);
        assertEquals(0L, engine.metrics(now).income);
    }
    @Test
    public void aSpareOfTheSameWeaponIsNotTheFrozenOneReturning()
    {
        Harness game = new Harness();
        game.inventory = new Item[]{new Item(ItemID.ABYSSAL_WHIP, 1)};
        game.weapon = new Item(ItemID.ABYSSAL_WHIP, 1);
        assertEquals(2L, game.carried(ItemID.ABYSSAL_WHIP));
        game.weapon = null;
        game.frozen = 1;
        assertEquals("the spare and the frozen whip", 2L, game.carried(ItemID.ABYSSAL_WHIP));
        game.frozen = 0;
        assertEquals("still in the ice once the varbit clears", 2L, game.carried(ItemID.ABYSSAL_WHIP));
        game.inventory = new Item[]{new Item(ItemID.ABYSSAL_WHIP, 1), new Item(ItemID.ABYSSAL_WHIP, 1)};
        assertEquals("Eyatlalli returns it beside the spare", 2L, game.carried(ItemID.ABYSSAL_WHIP));
        game.inventory = new Item[]{new Item(ItemID.ABYSSAL_WHIP, 1)};
        assertEquals("the hold ended at the return", 1L, game.carried(ItemID.ABYSSAL_WHIP));
    }

    @Test
    public void frozenDartsKeepTheirQuantityBesideLooseDartsUntilTheyReturn()
    {
        Harness game = new Harness();
        game.inventory = new Item[]{new Item(ItemID.DRAGON_DART, 100)};
        game.weapon = new Item(ItemID.DRAGON_DART, 500);
        assertEquals(600L, game.carried(ItemID.DRAGON_DART));
        game.weapon = null;
        game.frozen = 1;
        for (int capture = 0; capture < 3; capture++)
        {
            assertEquals("capture " + capture, 600L, game.carried(ItemID.DRAGON_DART));
        }
        game.inventory = new Item[]{new Item(ItemID.DRAGON_DART, 130)};
        assertEquals("30 more picked up while frozen", 630L, game.carried(ItemID.DRAGON_DART));
        game.frozen = 0;
        assertEquals("the varbit clearing before the darts are back", 630L, game.carried(ItemID.DRAGON_DART));
        game.weapon = new Item(ItemID.DRAGON_DART, 500);
        assertEquals("punched free, counted once", 630L, game.carried(ItemID.DRAGON_DART));
        game.weapon = null;
        assertEquals("a later deposit from the slot is a real change", 130L, game.carried(ItemID.DRAGON_DART));
    }

    @Test
    public void anotherAccountNeverInheritsTheFrozenWeapon()
    {
        Harness game = new Harness();
        game.hash = 7L;
        game.weapon = new Item(ItemID.ABYSSAL_WHIP, 1);
        game.carried(ItemID.ABYSSAL_WHIP);
        game.weapon = null;
        game.frozen = 1;
        assertEquals(1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.frozen = 0;
        Engine other = ReviewFixesTest.engine();
        GpManagerPlugin plugin = switcher(game, other);
        game.hash = -1L;
        plugin.beginOwnerSwitch();
        game.hash = 8L;
        plugin.beginOwnerSwitch();
        game.inventory = new Item[]{new Item(ItemID.BRONZE_DAGGER, 1)};
        other.setBaseline(game.factory.capture(true, false));
        assertEquals(0L, other.baseline.quantity(ItemID.ABYSSAL_WHIP));
        game.weapon = new Item(ItemID.BRONZE_DAGGER, 1);
        game.inventory = new Item[0];
        other.markDirty();
        for (int tick = 0; tick < 8; tick++)
        {
            other.processIfDirty(game.factory.capture(true, false), ReviewFixesTest.T0 + (tick + 1) * 600L, null);
        }
        assertEquals("equipping the new account's own dagger is neutral", 0L,
            other.metrics(ReviewFixesTest.T0 + 10_000L).net);
    }

    @Test
    public void anUnknownOwnerReleasesTheWeaponAtAnOwnerSwitch()
    {
        Harness game = new Harness();
        game.weapon = new Item(ItemID.ABYSSAL_WHIP, 1);
        game.carried(ItemID.ABYSSAL_WHIP);
        game.weapon = null;
        game.frozen = 1;
        assertEquals(1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.frozen = 0;
        switcher(game, ReviewFixesTest.engine()).beginOwnerSwitch();
        assertEquals(0L, game.carried(ItemID.ABYSSAL_WHIP));
    }

    @Test
    public void aRelogOnTheSameAccountKeepsTheWeaponWithEyatlalli()
    {
        Harness game = new Harness();
        game.hash = 7L;
        game.weapon = new Item(ItemID.ABYSSAL_WHIP, 1);
        game.carried(ItemID.ABYSSAL_WHIP);
        game.weapon = null;
        game.frozen = 1;
        game.carried(ItemID.ABYSSAL_WHIP);
        game.frozen = 0;
        GpManagerPlugin plugin = switcher(game, ReviewFixesTest.engine());
        game.hash = -1L;
        plugin.beginOwnerSwitch();
        assertEquals("logged out", 1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.hash = 7L;
        plugin.beginOwnerSwitch();
        assertEquals("back on the same account, Eyatlalli still holds it", 1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.inventory = new Item[]{new Item(ItemID.ABYSSAL_WHIP, 1)};
        assertEquals("returned once", 1L, game.carried(ItemID.ABYSSAL_WHIP));
    }

    @Test
    public void droppingTheSpareWhileFrozenStillRecognisesTheReturn()
    {
        Harness game = new Harness();
        game.hash = 1L;
        game.inventory = new Item[]{new Item(ItemID.ABYSSAL_WHIP, 1)};
        game.weapon = new Item(ItemID.ABYSSAL_WHIP, 1);
        assertEquals(2L, game.carried(ItemID.ABYSSAL_WHIP));
        game.weapon = null;
        game.frozen = 1;
        assertEquals(2L, game.carried(ItemID.ABYSSAL_WHIP));
        game.inventory = new Item[0];
        assertEquals("the spare dropped, the frozen weapon still carried", 1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.frozen = 0;
        game.inventory = new Item[]{new Item(ItemID.ABYSSAL_WHIP, 1)};
        assertEquals("the returned weapon is one weapon, not two", 1L, game.carried(ItemID.ABYSSAL_WHIP));
    }

    @Test
    public void looseAmmoDroppedWhileFrozenStillRecognisesTheReturn()
    {
        Harness game = new Harness();
        game.inventory = new Item[]{new Item(ItemID.DRAGON_DART, 500)};
        game.weapon = new Item(ItemID.DRAGON_DART, 2_000);
        game.carried(ItemID.DRAGON_DART);
        game.weapon = null;
        game.frozen = 1;
        assertEquals(2_500L, game.carried(ItemID.DRAGON_DART));
        game.inventory = new Item[]{new Item(ItemID.DRAGON_DART, 300)};
        assertEquals("two hundred loose darts dropped", 2_300L, game.carried(ItemID.DRAGON_DART));
        game.inventory = new Item[]{new Item(ItemID.DRAGON_DART, 2_300)};
        assertEquals("the stack back in the pack, counted once", 2_300L, game.carried(ItemID.DRAGON_DART));
        game.frozen = 0;
        assertEquals(2_300L, game.carried(ItemID.DRAGON_DART));
    }

    @Test
    public void aRelogAfterTheSpareWasDroppedStillReturnsItOnce()
    {
        Harness game = new Harness();
        game.hash = 7L;
        game.inventory = new Item[]{new Item(ItemID.ABYSSAL_WHIP, 1)};
        game.weapon = new Item(ItemID.ABYSSAL_WHIP, 1);
        game.carried(ItemID.ABYSSAL_WHIP);
        game.weapon = null;
        game.frozen = 1;
        game.carried(ItemID.ABYSSAL_WHIP);
        game.inventory = new Item[0];
        assertEquals(1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.frozen = 0;
        GpManagerPlugin plugin = switcher(game, ReviewFixesTest.engine());
        game.hash = -1L;
        plugin.beginOwnerSwitch();
        game.hash = 7L;
        plugin.beginOwnerSwitch();
        assertEquals("Eyatlalli still holds it", 1L, game.carried(ItemID.ABYSSAL_WHIP));
        game.inventory = new Item[]{new Item(ItemID.ABYSSAL_WHIP, 1)};
        assertEquals("returned once", 1L, game.carried(ItemID.ABYSSAL_WHIP));
    }

    /** The real owner-switch hook, with persistence fencing stubbed to avoid disk access. */
    private static GpManagerPlugin switcher(Harness game, Engine engine)
    {
        GpManagerPlugin plugin = new GpManagerPlugin();
        plugin.containers = game.factory;
        plugin.storage = new Persistence(game.factory.client, null, null, null, engine)
        {
            @Override
            void fence()
            {
            }
        };
        plugin.offers = new OfferSlots();
        plugin.charges = new ChargeIntake(game.factory.client, null, ReviewFixesTest.CONFIG, engine, null);
        plugin.targets = GpManagerPluginProbe.tracker(game.factory.client, engine, ReviewFixesTest.CONFIG);
        return plugin;
    }
}
