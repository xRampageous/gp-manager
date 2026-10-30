package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.http.api.item.ItemPrice;
import org.junit.Test;
import static org.junit.Assert.*;

/** Live varps, ownership transitions and measured charge reconciliation, with explicit ticks. */
public class QuiverSupportTest {
    static final long T0 = 1_800_000_000_000L;
    static final GpManagerConfig CONFIG = new GpManagerConfig() {
        public int stabilizationTicks() { return 0; }
        public boolean keepTransferAuditRows() { return true; }
    };

    @Test public void liveAmmoVarpsWorkForEveryCarriedQuiverWithoutAContainer() throws Exception {
        for (int id : new int[] {ItemID.DIZANAS_QUIVER_UNCHARGED, ItemID.DIZANAS_QUIVER_CHARGED,
            ItemID.DIZANAS_QUIVER_UNCHARGED_TROUVER, ItemID.DIZANAS_QUIVER_CHARGED_TROUVER,
            ItemID.DIZANAS_QUIVER_INFINITE, ItemID.DIZANAS_QUIVER_INFINITE_TROUVER,
            ItemID.SKILLCAPE_MAX_DIZANAS, ItemID.SKILLCAPE_MAX_DIZANAS_TROUVER}) {
            Harness h = new Harness();
            h.worn.put(EquipmentInventorySlot.CAPE.getSlotIdx(), new Item(id, 1));
            h.ammo = 250;
            assertEquals(250L, h.capture().quantity(ItemID.DRAGON_ARROW));
            h.worn.clear(); h.inventory.put(id, 1L);
            assertEquals("unequipping retains ownership", 250L, h.capture().quantity(ItemID.DRAGON_ARROW));
        }
    }

    @Test public void staleVarpsAndDuplicateQuiversNeverAddUnownedOrDuplicateAmmo() throws Exception {
        Harness h = new Harness(); h.ammo = 250;
        assertEquals(0L, h.capture().quantity(ItemID.DRAGON_ARROW));
        h.equipQuiver(); h.inventory.put(ItemID.DIZANAS_QUIVER_INFINITE, 1L);
        assertEquals(250L, h.capture().quantity(ItemID.DRAGON_ARROW));
        assertEquals(0L, h.factory.capture(false, false).quantity(ItemID.DRAGON_ARROW));
        h.ammo = -1;
        assertEquals(0L, h.capture().quantity(ItemID.DRAGON_ARROW));
    }

    @Test public void loadingAndUnequippingAreNeutralAndFiringBooksOnlyTheSpentArrow() throws Exception {
        Harness h = new Harness(); h.equipQuiver(); h.ammo = 10;
        h.inventory.put(ItemID.DRAGON_ARROW, 5L); h.engine.setBaseline(h.capture());
        h.inventory.remove(ItemID.DRAGON_ARROW); h.ammo = 15; h.settle();
        assertEquals(0L, h.net());
        h.worn.clear(); h.inventory.put(ItemID.DIZANAS_QUIVER_CHARGED, 1L); h.settle();
        assertEquals(0L, h.net());
        h.ammo = 14; h.settle();
        assertEquals(-20L, h.net());
        assertEquals(ActionKind.FIRE, h.rows().get(0).action());
    }

    @Test public void quiverVarpEventsMarkTheSnapshotDirty() throws Exception {
        Harness h = new Harness();
        for (int id : new int[] {VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO,
            VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT}) {
            h.engine.dirty = false;
            VarbitChanged event = new VarbitChanged(); event.setVarbitId(-1); event.setVarpId(id);
            h.plugin.onVarbitChanged(event);
            assertTrue(h.engine.dirty);
        }
    }

    @Test public void checksReadChargesAndActualProgressReconcilesWithoutDoubleCounting() throws Exception {
        Harness h = new Harness(); h.equipQuiver();
        h.check(10); h.progress(100); h.progress(101); h.progress(101);
        assertEquals(-20L, h.net());
        h.check(9);
        assertEquals("the Check replaces, rather than duplicates, the observed use", -20L, h.net());
        assertTrue(h.rows().stream().filter(t -> t.net() != 0).allMatch(t ->
            t.flows().stream().allMatch(f -> CostKind.of(t, f) == CostKind.SUPPLIES)));
    }

    @Test public void twoChargeTicksAndLaterCheckAreStillOneMeasuredCost() throws Exception {
        Harness h = new Harness(); h.equipQuiver();
        h.check(10); h.progress(100); h.progress(102); h.progress(102);
        assertEquals(-40L, h.net());
        h.check(8); assertEquals(-40L, h.net());
        h.charges.resetOwner(); h.progress(103);
        assertEquals("another owner starts from its own first observed value", -40L, h.net());
    }

    @Test public void progressNeverGuessesAnAverageOrSpendsOnLoginJumpsAndFreeQuivers() throws Exception {
        Harness h = new Harness(); h.equipQuiver();
        h.progress(100); h.progress(100); h.progress(1100); h.progress(0);
        assertEquals(0L, h.net());
        h.charges.resetTransient(); h.progress(600);
        assertEquals(0L, h.net());
        h.worn.put(EquipmentInventorySlot.CAPE.getSlotIdx(), new Item(ItemID.DIZANAS_QUIVER_INFINITE, 1));
        h.progress(601);
        assertEquals(0L, h.net());
        h.worn.clear(); h.progress(602);
        assertEquals(0L, h.net());
    }

    @Test public void quiverCheckAcceptsLockedMarkupProgressAndZero() {
        for (String message : new String[] {"Dizana's quiver has 1,000 charges remaining.",
            "Dizana's quiver (l) has <col=ff0000>1,000</col> charges remaining. It is 25% blessed.",
            "Dizana's quiver has 0 charges remaining."}) {
            ChargeCheck read = ChargeCheck.parse(message);
            assertNotNull(message, read); assertTrue(message, read.supported);
            assertTrue(read.counts.containsKey(ItemID.SUNFIRESPLINTER));
        }
    }

    @Test public void loadingSmallAmountsDoesNotSpendAndOtherOwnersPrimeAgain() throws Exception {
        Harness h=new Harness(); h.equipQuiver(); h.check(10); h.progress(100);
        ChargeCheck.ChargedItem variant=ChargeCheck.supported(ItemID.DIZANAS_QUIVER_CHARGED);
        h.engine.markChargeLoad(variant,ItemID.SUNFIRESPLINTER,"Sunfire splinter",
            h.charges.heldIdentity(variant,EquipmentInventorySlot.CAPE),10);
        h.progress(101); assertEquals(0L,h.net());
        h.charges.resetOwner(); h.progress(102); assertEquals(0L,h.net());
    }

    /** Splinters used on a carried quiver are a load, unlocked or locked: charging it books nothing. */
    @Test public void chargingACarriedQuiverWithSplintersIsNeutral() throws Exception {
        for (int[] quiver : new int[][] {{ItemID.DIZANAS_QUIVER_UNCHARGED,ItemID.DIZANAS_QUIVER_CHARGED},
            {ItemID.DIZANAS_QUIVER_UNCHARGED_TROUVER,ItemID.DIZANAS_QUIVER_CHARGED_TROUVER}}) {
            Harness h = new Harness();
            h.inventory.put(quiver[0],1L); h.inventory.put(ItemID.SUNFIRESPLINTER,500L);
            h.engine.setBaseline(h.capture());
            ChargeCheck.ChargedItem variant = ChargeCheck.supported(quiver[0]);
            assertNotNull(variant);
            h.engine.markChargeLoad(variant,ItemID.SUNFIRESPLINTER,"Sunfire splinter",null,10);
            h.inventory.clear(); h.inventory.put(quiver[1],1L);
            h.engine.markDirty(); h.settle(); h.settle();
            assertEquals(0L,h.net());
            assertTrue(h.rows().stream().noneMatch(ReviewRules::needsReview));
        }
    }

    static final class Harness {
        final Map<Integer, Long> inventory = new LinkedHashMap<>();
        final Map<Integer, Item> worn = new LinkedHashMap<>();
        int ammo, tick = 10;
        final Client client;
        final ItemLookup lookup;
        final ContainerSnapshotFactory factory;
        final Engine engine;
        final ChargeIntake charges;
        final GpManagerPlugin plugin;
        Harness() throws Exception {
            JsonCodec.bind(new com.google.gson.Gson());
            client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (p,m,a) -> {
                    switch (m.getName()) {
                        case "getGameState": return GameState.LOGGED_IN;
                        case "getTickCount": return tick;
                        case "getItemDefinition": return composition((int)a[0]);
                        case "getItemContainer":
                            int id = (int)a[0];
                            return id == InventoryID.INV ? container(false) : id == InventoryID.WORN ? container(true) : null;
                        case "getVarpValue": return (int)a[0] == VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO
                            ? ItemID.DRAGON_ARROW : (int)a[0] == VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT ? ammo : 0;
                        default: return zero(m.getReturnType());
                    }
                });
            lookup = new ItemLookup(null) {
                ItemComposition composition(int id) { return Harness.this.composition(id); }
                List<ItemPrice> find(String query) {
                    List<ItemPrice> found = new ArrayList<>();
                    for (int id : GEM_NAMES.keySet()) if (GEM_NAMES.get(id).toLowerCase().contains(query.toLowerCase())) {
                        ItemPrice item = new ItemPrice(); item.setId(id); item.setName(GEM_NAMES.get(id)); found.add(item);
                    }
                    return found;
                }
            };
            factory = new ContainerSnapshotFactory(client, lookup);
            Valuation valuation = ModelProbe.valuation(CONFIG, this::composition, id -> 20);
            engine = new Engine(deltas -> {
                List<Flow> flows = new ArrayList<>();
                deltas.forEach((id,q) -> flows.add(new Flow(id, name(id), q, 20, q*20, PriceSource.GRAND_EXCHANGE)));
                return flows;
            }, new TransactionClassifier(), CONFIG);
            engine.startCustom("Quiver and gems", T0); engine.setBaseline(capture());
            charges = new ChargeIntake(client, lookup, CONFIG, engine, valuation);
            plugin = new GpManagerPluginProbe();
            for (Object[] field : new Object[][] {{"client",client},{"engine",engine},{"config",CONFIG},
                {"charges",charges},{"lookup",lookup},{"containers",factory},
                {"targets",GpManagerPluginProbe.tracker(client,engine,CONFIG)}}) {
                java.lang.reflect.Field f = GpManagerPlugin.class.getDeclaredField((String)field[0]);
                f.setAccessible(true); f.set(plugin,field[1]);
            }
        }
        void equipQuiver() { worn.put(EquipmentInventorySlot.CAPE.getSlotIdx(), new Item(ItemID.DIZANAS_QUIVER_CHARGED,1)); }
        ContainerSnapshot capture() { return factory.capture(true,false); }
        void settle() {
            for (int i=0;i<2;i++) { tick++; engine.processIfDirty(capture(),T0+tick*600L,null); }
        }
        long net() { return engine.metrics(T0+60_000L).net; }
        List<Transaction> rows() { return engine.session().transactions(); }
        void progress(int n) {
            VarbitChanged event=new VarbitChanged(); event.setVarbitId(VarbitID.CHARGES_DIZANAS_QUIVER_BLESSING);
            event.setVarpId(-1); event.setValue(n); plugin.onVarbitChanged(event);
        }
        void check(int n) {
            ChargeCheck.ChargedItem variant=ChargeCheck.supported(ItemID.DIZANAS_QUIVER_CHARGED);
            assertNotNull("quiver family is supported",variant);
            String identity=charges.heldIdentity(variant,EquipmentInventorySlot.CAPE);
            charges.startCheck(variant,identity,tick,5);
            charges.observeCheck("Dizana's quiver has "+n+" charges remaining.",T0+tick*600L);
        }
        void chat(String text) {
            ChatMessage event=new ChatMessage(); event.setType(ChatMessageType.GAMEMESSAGE); event.setMessage(text);
            plugin.onChatMessage(event);
        }
        void finishBagTick() { tick++; plugin.bagSettle(); settle(); }
        ItemContainer container(boolean equipment) {
            return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),new Class<?>[]{ItemContainer.class},
                (p,m,a) -> {
                    if (m.getName().equals("getItems")) return equipment ? worn.values().toArray(new Item[0])
                        : inventory.entrySet().stream().map(e->new Item(e.getKey(),e.getValue().intValue())).toArray(Item[]::new);
                    if (m.getName().equals("getItem")) return equipment ? worn.get((int)a[0]) : null;
                    if (m.getName().equals("size")) return equipment ? 14 : 28;
                    return zero(m.getReturnType());
                });
        }
        ItemComposition composition(int id) {
            return (ItemComposition)Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),new Class<?>[]{ItemComposition.class},
                (p,m,a) -> m.getName().equals("getName") ? name(id) : m.getName().equals("getIntValue")
                    ? (int)a[0] == ParamID.QUIVER_AMMO_AVAILABLE && name(id).contains("quiver") ? 1 : 0
                    : zero(m.getReturnType()));
        }
        static Object zero(Class<?> type) { return type==int.class?0:type==boolean.class?false:type==long.class?0L:null; }
    }
    static final Map<Integer,String> GEM_NAMES=Map.of(ItemID.UNCUT_RUBY,"Uncut ruby",ItemID.UNCUT_DIAMOND,"Uncut diamond",ItemID.UNCUT_OPAL,"Uncut opal");
    static String name(int id) {
        if (GEM_NAMES.containsKey(id)) return GEM_NAMES.get(id);
        if (id==ItemID.DRAGON_ARROW) return "Dragon arrow";
        if (id==ItemID.SUNFIRESPLINTER) return "Sunfire splinter";
        if (id==ItemID.DIZANAS_QUIVER_CHARGED) return "Dizana's quiver";
        if (id==ItemID.DIZANAS_QUIVER_UNCHARGED||id==ItemID.DIZANAS_QUIVER_UNCHARGED_TROUVER||id==ItemID.DIZANAS_QUIVER_CHARGED_TROUVER
            ||id==ItemID.DIZANAS_QUIVER_INFINITE||id==ItemID.DIZANAS_QUIVER_INFINITE_TROUVER||id==ItemID.SKILLCAPE_MAX_DIZANAS
            ||id==ItemID.SKILLCAPE_MAX_DIZANAS_TROUVER) return "Blessed quiver";
        return "Item "+id;
    }
}
