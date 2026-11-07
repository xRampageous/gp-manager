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
    static String golemGift(String receiver, String item) {
        return "As you complete the golem it leaves a gift " + receiver + " for you: 1 x " + item + ".";
    }

    @Test public void golemGiftsIntoEitherOpenGemContainerBookTheMeasuredGemOnce() throws Exception {
        for (int container : new int[] {ItemID.GEM_BAG_OPEN, ItemID.GEM_SACK_OPEN}) {
            for (String gem : new String[] {"diamond", "emerald", "ruby", "sapphire"}) {
                var harness = new QuiverSupportTest.Harness();
                harness.inventory.put(container, 1L);
                harness.engine.setBaseline(harness.capture());
                harness.chat(golemGift("in your gem " + (container == ItemID.GEM_BAG_OPEN ? "bag" : "sack"),
                        "Uncut " + gem));
                harness.finishBagTick();
                harness.finishBagTick();
                assertEquals(gem, 20, harness.net());
                assertEquals(1, harness.rows().size());
                assertEquals(1, harness.rows().get(0).flows().get(0).quantity);
                assertFalse(ReviewRules.needsReview(harness.rows().get(0)));
            }
        }
    }

    @Test public void golemSourceAndExplicitBagMessageDoNotBookTheGiftTwice() throws Exception {
        var harness = new QuiverSupportTest.Harness();
        harness.inventory.put(ItemID.GEM_BAG_OPEN, 1L);
        harness.engine.setBaseline(harness.capture());
        harness.engine.markLoot(java.util.Map.of(ItemID.UNCUT_DIAMOND, 1L), 5,
                "Golem Crafting", "Crafting");
        harness.chat(golemGift("in your gem bag", "Uncut diamond"));
        harness.finishBagTick();
        assertEquals(20, harness.net());
        assertEquals(1, harness.rows().size());
    }

    @Test public void aGroundGolemGiftWaitsForItsPickupInsteadOfBookingTheChat() throws Exception {
        var harness = new QuiverSupportTest.Harness();
        harness.inventory.put(ItemID.GEM_BAG_OPEN, 1L);
        harness.engine.setBaseline(harness.capture());
        harness.chat(golemGift("on the ground", "Uncut ruby"));
        harness.finishBagTick();
        assertEquals(0, harness.net());
        harness.inventory.put(ItemID.UNCUT_RUBY, 1L);
        harness.settle();
        assertEquals(20, harness.net());
    }

    @Test public void missingOrWrongGemContainersDoNotInventGolemGifts() throws Exception {
        for (int container : new int[] {-1, ItemID.GEM_POUCH_OPEN}) {
            var harness = new QuiverSupportTest.Harness();
            if (container > 0) harness.inventory.put(container, 1L);
            harness.engine.setBaseline(harness.capture());
            harness.chat(golemGift("in your gem bag", "Uncut diamond"));
            harness.finishBagTick();
            assertEquals(0, harness.net());
        }
    }

    @Test public void emptyingAGolemGiftKeepsTheOriginalReceiptAndPrice() throws Exception {
        var harness = new QuiverSupportTest.Harness();
        harness.inventory.put(ItemID.GEM_BAG_OPEN, 1L);
        harness.engine.setBaseline(harness.capture());
        harness.chat(golemGift("in your gem bag", "Uncut ruby"));
        harness.finishBagTick();
        assertEquals(20, harness.net());
        var receipt = harness.rows().get(0);
        harness.engine.markTransfer("Empty gem_bag", 5);
        harness.inventory.put(ItemID.UNCUT_RUBY, 1L);
        harness.settle();
        assertEquals(20, harness.net());
        assertEquals(receipt.id(), harness.rows().get(0).id());
        assertEquals(20, receipt.flows().get(0).unitPrice);
    }

}
