package com.gpmanager;

import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

/** Named deposits are gross gains; mining is bounded by what the inventory actually received. */
public class GemBagAcquisitionTest {
    @Test public void allContainerFamiliesAcceptOnlyTheirOwnGemSetInEitherState() throws Exception {
        int[] gems = {ItemID.UNCUT_OPAL, ItemID.UNCUT_JADE, ItemID.UNCUT_RED_TOPAZ,
            ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD, ItemID.UNCUT_RUBY,
            ItemID.UNCUT_DIAMOND, ItemID.UNCUT_DRAGONSTONE};
        int[][] families = {{ItemID.GEM_BAG, ItemID.GEM_BAG_OPEN}, {ItemID.GEM_POUCH, ItemID.GEM_POUCH_OPEN},
            {ItemID.GEM_SATCHEL, ItemID.GEM_SATCHEL_OPEN}, {ItemID.GEM_TOTE, ItemID.GEM_TOTE_OPEN},
            {ItemID.GEM_SACK, ItemID.GEM_SACK_OPEN}};
        for (int family = 0; family < families.length; family++) for (int container : families[family]) {
            QuiverSupportTest.Harness h = new QuiverSupportTest.Harness();
            h.inventory.put(container, 1L);
            for (int i = 0; i < gems.length; i++) {
                boolean supported = family == 4 || (family == 0 ? i >= 3 : i < 3);
                assertEquals(container + ":" + gems[i], supported, h.factory.gemBag(gems[i], 0));
                assertEquals(container + " open:" + gems[i], supported && container == families[family][1],
                    h.factory.gemBag(gems[i], 1));
            }
        }
    }

    @Test public void changingOwnerDiscardsUnsettledGemEvidence() throws Exception {
        QuiverSupportTest.Harness h = new QuiverSupportTest.Harness();
        h.inventory.put(ItemID.GEM_SACK_OPEN, 1L); h.engine.setBaseline(h.capture());
        h.chat("You just mined a Ruby!");
        h.engine.events.owner(1L); // Persistence fences the owner before replacing a profile.
        h.engine.resetData(QuiverSupportTest.T0 + 10L);
        h.engine.ensureSession(QuiverSupportTest.T0 + 20L);
        h.engine.setBaseline(h.capture()); h.finishBagTick();
        assertEquals(0L, h.net());
    }
    @Test public void thievingAndBothVyreFormatsBookDirectDepositsOnce() throws Exception {
        for (String message : new String[] {"You put a gem in your sack: Uncut ruby",
            "The following stolen loot gets added to your gem bag: Uncut ruby x 2.",
            "You put the stolen Uncut ruby into your gem sack."}) {
            QuiverSupportTest.Harness h=new QuiverSupportTest.Harness();
            h.inventory.put(ItemID.GEM_SACK,1L); h.inventory.put(ItemID.GEM_BAG,1L);
            h.engine.setBaseline(h.capture()); h.chat(message); h.finishBagTick();
            assertEquals(message,message.contains(" x 2")?40L:20L,h.net());
            assertEquals(1,h.rows().size());
            assertTrue(h.rows().get(0).counts());
        }
    }
    @Test public void severalGemMessagesInOneTickRetainEveryGem() throws Exception {
        QuiverSupportTest.Harness h=new QuiverSupportTest.Harness();
        h.inventory.put(ItemID.GEM_SACK_OPEN,1L); h.engine.setBaseline(h.capture());
        h.chat("You just mined a Ruby!"); h.chat("You just mined a Diamond!");
        h.finishBagTick(); assertEquals(40L,h.net());
    }
    @Test public void aGemThatReachedInventoryIsNeverCountedAgainAsBagIncome() throws Exception {
        QuiverSupportTest.Harness h=new QuiverSupportTest.Harness();
        h.inventory.put(ItemID.GEM_SACK_OPEN,1L); h.engine.setBaseline(h.capture());
        h.chat("You just mined a Ruby!"); h.inventory.put(ItemID.UNCUT_RUBY,1L);
        h.finishBagTick(); assertEquals(20L,h.net());
    }
    @Test public void wrongClosedMissingAndUnsupportedGemContainersDoNotInventIncome() throws Exception {
        for (int container : new int[] {-1,ItemID.GEM_POUCH_OPEN,ItemID.GEM_SACK}) {
            QuiverSupportTest.Harness h=new QuiverSupportTest.Harness();
            if (container>0) h.inventory.put(container,1L);
            h.engine.setBaseline(h.capture()); h.chat("You just mined a Ruby!");
            h.finishBagTick(); assertEquals(0L,h.net());
        }
    }
    @Test public void emptyingTheBagRetainsItsAlreadyBookedIncome() throws Exception {
        QuiverSupportTest.Harness h=new QuiverSupportTest.Harness();
        h.inventory.put(ItemID.GEM_SACK,1L); h.engine.setBaseline(h.capture());
        h.chat("You put the stolen Uncut ruby into your gem sack."); h.finishBagTick();
        h.engine.markTransfer("Empty gem_sack",5); h.inventory.put(ItemID.UNCUT_RUBY,1L); h.settle();
        assertEquals(20L,h.net());
    }
    @Test public void aSplitSameGemPickupCountsTheInventoryAndBagPortionsOnce() throws Exception {
        QuiverSupportTest.Harness h=new QuiverSupportTest.Harness();
        h.inventory.put(ItemID.GEM_SACK_OPEN,1L); h.engine.setBaseline(h.capture());
        h.chat("You just mined a Ruby!"); h.chat("You just mined a Ruby!");
        h.inventory.put(ItemID.UNCUT_RUBY,1L); h.finishBagTick();
        assertEquals(40L,h.net());
    }
    @Test public void aVyreSourceCallbackAndDepositAreOneAcquisition() throws Exception {
        QuiverSupportTest.Harness h=new QuiverSupportTest.Harness();
        h.inventory.put(ItemID.GEM_SACK,1L); h.engine.setBaseline(h.capture());
        h.engine.markLoot(java.util.Map.of(ItemID.UNCUT_RUBY,2L),5,"Loot from Vyre","Vyre");
        h.chat("The following stolen loot gets added to your gem sack: Uncut ruby x 2.");
        h.finishBagTick(); assertEquals(40L,h.net());
    }
}
