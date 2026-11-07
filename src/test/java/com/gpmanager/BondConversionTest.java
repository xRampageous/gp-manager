package com.gpmanager;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import org.junit.*;
import static org.junit.Assert.*;

/** Native conversion quote plus the measured state transition, never a guessed GE percentage. */
public class BondConversionTest {
    java.util.function.LongSupplier previous;
    @Before public void clock() {previous=SafeMath.clock;SafeMath.clock=()->ProductionRecipeTest.T0;}
    @After public void restoreClock() {SafeMath.clock=previous;}
    static final int OLD=ItemID.OSRS_BOND_UNTRADEABLE,NEW=ItemID.OSRS_BOND;
    static class Fixture {
        final ProductionRecentTest.Fixture f;
        final Map<Integer,Long> inventory=new HashMap<>();
        String quoted="357,123 coins",count="1";
        Fixture(long coins) {
            inventory.put(OLD,1L);if(coins>0)inventory.put(995,coins);
            f=new ProductionRecentTest.Fixture(inventory);
            f.engine.valuation=deltas->{
                var flows=new ArrayList<Flow>();
                deltas.forEach((id,q)->{
                    long price=id==995?1:id==OLD?1_200_000:12_000_000;
                    flows.add(new Flow(id,id==995?"Coins":id==OLD?"Old school bond (untradeable)":"Old school bond",q,
                        price,SafeMath.multiply(q,price),id==995?PriceSource.FACE_VALUE:PriceSource.GRAND_EXCHANGE));
                });return flows;
            };
            f.plugin.client=LootingBagMovementTest.proxy(Client.class,(method,args)->{
                if(method.equals("getGameState"))return GameState.LOGGED_IN;
                if(method.equals("getTickCount"))return 100;
                if(method.equals("getWidget") && (int)args[0]==InterfaceID.BondConvert.RIGHT)
                    return LootingBagMovementTest.proxy(Widget.class,(m,a)->{
                        if(m.equals("getChild"))return LootingBagMovementTest.proxy(Widget.class,(n,b)->
                            n.equals("getText") ? (int)a[0]==7?count:(int)a[0]==12?quoted:"" : null);
                        return null;
                    });
                if(method.equals("getItemContainer") && (int)args[0]==net.runelite.api.gameval.InventoryID.INV)
                    return LootingBagMovementTest.proxy(ItemContainer.class,(m,a)->m.equals("getItems")?
                        inventory.entrySet().stream().map(e->new Item(e.getKey(),e.getValue().intValue())).toArray(Item[]::new):null);
                return null;
            });
        }
        void accept() { click("Accept",InterfaceID.BondConvert.ACCEPT); }
        void cancel() { click("Cancel",InterfaceID.BondConvert.CANCEL); }
        void click(String option,int param1) {
            f.plugin.onMenuOptionClicked(new net.runelite.api.events.MenuOptionClicked(
                LootingBagMovementTest.proxy(MenuEntry.class,(m,a)->{
                    if(m.equals("getOption"))return option;
                    if(m.equals("getTarget"))return "";
                    if(m.equals("getType"))return MenuAction.CC_OP;
                    if(m.equals("getParam1"))return param1;
                    if(m.equals("getItemId"))return -1;
                    return null;
                })));
        }
        void walk() {
            f.plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Walk here",MenuAction.WALK,null,""));
        }
        void converted(long coins) {
            inventory.clear();inventory.put(NEW,1L);if(coins>0)inventory.put(995,coins);
            f.become(inventory);
        }
        boolean reviewed() {
            return f.engine.session().transactions().stream().anyMatch(ReviewRules::needsReview);
        }
        void change(long coins) {
            converted(coins);
        }
        void fee(long expected) {
            var live=f.live();
            assertEquals(0,live.income);assertEquals(-expected,live.net);
            assertFalse(f.engine.session().transactions().stream().anyMatch(ReviewRules::needsReview));
            assertTrue(f.engine.session().transactions().stream().filter(Transaction::counts)
                .flatMap(t->t.flows().stream()).allMatch(flow->flow.item==995));
        }
    }
    @Test public void bankPaidConversionUsesTheAcceptedInterfaceAmount() {
        var f=new Fixture(0);f.accept();f.change(0);f.fee(357123);
        f.accept();f.fee(357123);
    }
    @Test public void inventoryAndMixedPaymentsAreNotChargedTwice() {
        for(long held:new long[]{1_000_000,100_000}) {
            var f=new Fixture(held);f.accept();f.change(Math.max(0,held-357123));f.fee(357123);
        }
    }
    @Test public void cancelledMissingAndMalformedQuotesCannotInventABankFee() {
        for(String quote:Arrays.asList(null,"","---","not 357,123 coins")) {
            var f=new Fixture(0);f.quoted=quote;f.accept();
            assertEquals(0,f.f.live().net);
        }
        var f=new Fixture(0);f.accept();f.accept();assertEquals(0,f.f.live().net);
    }
    @Test public void aTrackingOrOwnerFenceCannotApplyAnOldQuote() {
        for(boolean owner:new boolean[]{false,true}) {
            var f=new Fixture(0);f.accept();
            if(owner)f.f.engine.events.owner(1);else f.f.engine.resetTracking(false);
            f.inventory.clear();f.inventory.put(NEW,1L);f.accept();
            assertEquals(0,f.f.live().net);
        }
    }
    @Test public void conversionMovesOnlyCapturedKnownBasisAndFencesOldCorrections() {
        var f=new Fixture(0);
        var acquired=new Transaction(f.f.at,TransactionType.GAIN,Context.GENERIC,"","",true,
            List.of(new Flow(OLD,"Old school bond (untradeable)",1,1_200_000,1_200_000,PriceSource.HIGH_ALCHEMY)),Confidence.CONFIRMED,"",null);
        f.f.engine.session().addTransaction(acquired,2000,false);
        f.accept();f.change(0);
        var share=f.f.engine.basis.reserve(NEW,1);
        assertEquals(1,share.trackedQty);assertEquals(1_200_000,share.basisGp);
        assertEquals(0,f.f.engine.basis.reserve(OLD,1).trackedQty);
        assertFalse(f.f.engine.correctTransaction(acquired.id(),Correction.TRANSFER,"old basis cannot duplicate"));
    }

    @Test public void aBatchUsesItsAcceptedTotalAndTheFeeKeepsOrdinaryRemoveUndoAndReload() {
        var f=new Fixture(0);f.inventory.put(OLD,2L);
        f.f.engine.setBaseline(new ContainerSnapshot(f.inventory));
        f.count="2";f.quoted="999,991 coins";f.accept();
        f.inventory.clear();f.inventory.put(NEW,2L);f.f.become(f.inventory);f.fee(999991);
        var fee=f.f.engine.session().transactions().stream().filter(Transaction::counts).findFirst().get();
        String prices=JsonCodec.gson().toJson(fee.flows());
        assertTrue(f.f.engine.correctEntries(List.of(fee.id()),Correction.IGNORE));
        assertEquals(0,f.f.live().net);
        var restored=ProductionRecipeTest.engine();restored.restore(SaveCopies.copy(f.f.engine.savedState()),f.f.at);
        assertTrue(restored.undoCorrection());assertEquals(-999991,restored.metrics(f.f.at+1).net);
        assertEquals(prices,JsonCodec.gson().toJson(restored.session().findTransaction(fee.id()).flows()));
    }

    @Test public void partialOrUnquotedTransitionsAndUnexplainedCashCannotProveTheFee() {
        var partial=new Fixture(0);partial.inventory.put(OLD,2L);partial.count="2";
        partial.f.engine.setBaseline(new ContainerSnapshot(partial.inventory));partial.accept();
        partial.inventory.put(OLD,1L);partial.inventory.put(NEW,1L);partial.accept();
        assertEquals(0,partial.f.live().net);
        var absent=new Fixture(0);absent.inventory.clear();absent.inventory.put(NEW,1L);absent.f.become(absent.inventory);
        assertTrue(absent.f.engine.session().transactions().stream().anyMatch(ReviewRules::needsReview));
        // Cash after Accept is unrelated to the accepted total: it neither proves nor disproves the
        // fee, and it is never absorbed into the bank share.
        for(long coins:new long[]{1_000_001,0}) {
            var f=new Fixture(1_000_000);f.accept();
            f.inventory.clear();f.inventory.put(NEW,1L);
            if(coins>0)f.inventory.put(995,coins);
            f.f.become(f.inventory);
            assertEquals(coins-1_000_000,f.f.live().net);
            assertFalse(f.reviewed());
        }
    }

    @Test public void acceptedQuoteSurvivesUnrelatedInputBeforeTheNativeResult() {
        var f=new Fixture(0);f.accept();f.walk();f.converted(0);
        assertEquals("the successful bank-paid conversion must still cost its accepted fee",-357123,f.f.live().net);
        assertFalse(f.reviewed());
    }

    @Test public void unrelatedCashMovementsStayOutsideTheAcceptedFee() {
        var gain=new Fixture(1_000_000);gain.accept();gain.converted(1_000_000-357123+100);
        assertEquals(-357023,gain.f.live().net);
        var spend=new Fixture(1_000_000);spend.accept();spend.converted(1_000_000-357123-100);
        assertEquals(-357223,spend.f.live().net);
        assertFalse("a separate coin spend must not erase the proven bond pair",spend.reviewed());
    }

    @Test public void cancelExpiryAndReplacementInvalidateOnlyTheUnconfirmedQuote() {
        var cancel=new Fixture(0);cancel.accept();cancel.cancel();cancel.converted(0);
        assertEquals(0,cancel.f.live().net);assertTrue(cancel.reviewed());
        var expired=new Fixture(0);expired.accept();
        expired.f.engine.events.tick(expired.f.engine.events.now+51);
        expired.converted(0);assertEquals(0,expired.f.live().net);
        var replaced=new Fixture(0);replaced.accept();replaced.quoted="not a total";replaced.accept();
        replaced.converted(0);assertEquals(0,replaced.f.live().net);
    }

    @Test public void repeatedCallbacksAndDuplicateConfirmationBookTheFeeOnce() {
        var f=new Fixture(1_000_000);f.accept();
        f.inventory.clear();f.inventory.put(NEW,1L);f.inventory.put(995,642877L);
        f.f.become(f.inventory);f.f.become(f.inventory);
        assertEquals(-357123,f.f.live().net);
        var counted=f.f.engine.session().transactions().stream().filter(Transaction::counts)
            .map(t->t.type()+" "+t.flows()).collect(java.util.stream.Collectors.toList());
        assertEquals(String.valueOf(counted),1,counted.size());
        assertFalse(f.reviewed());
    }

    /** Reshape 2026-11-06: the fee books in the settle that measures the pair. A reset before that settle drops the transient quote, so no fee is invented. */
    @Test public void aResetBeforeTheSettleInventsNoFee() {
        var f=new Fixture(0);f.accept();f.f.engine.resetTracking(false);f.converted(0);
        assertEquals(0,f.f.live().net);
        assertTrue(f.f.engine.session().transactions().stream().noneMatch(t->GameData.msg("bond-fee").equals(t.note())));
    }

    /** Reshape 2026-11-06: a second Accept replaces the first quote, so two overlapping conversions settling together go to Review instead of a guessed shared fee. */
    @Test public void overlappingConversionsInOneSettleGoToReviewWithoutAFee() {
        var f=new Fixture(1_000_000);f.inventory.put(OLD,2L);
        f.f.engine.setBaseline(new ContainerSnapshot(f.inventory));f.accept();
        f.inventory.put(OLD,1L);f.accept();
        f.inventory.remove(OLD);f.inventory.put(NEW,2L);f.inventory.put(995,285754L);
        f.f.become(f.inventory);
        assertTrue(f.reviewed());
        assertTrue(f.f.engine.session().transactions().stream().noneMatch(t->GameData.msg("bond-fee").equals(t.note())));
    }

    @Test public void conversionSettlesThenAnImmediateRunChangeAndReloadKeepTheMovedBasis() {
        var f=new Fixture(0);
        var acquired=new Transaction(f.f.at,TransactionType.GAIN,Context.GENERIC,"","",true,
            List.of(new Flow(OLD,"Old school bond (untradeable)",1,1_200_000,1_200_000,PriceSource.HIGH_ALCHEMY)),Confidence.CONFIRMED,"",null);
        f.f.engine.session().addTransaction(acquired,2000,false);f.accept();
        f.inventory.clear();f.inventory.put(NEW,1L);f.f.become(f.inventory);
        assertNull(f.f.engine.events.peek(Inbox.BOND_PAYMENT));
        assertEquals(-357123,f.f.live().net-1_200_000);
        f.f.engine.startCustom("After conversion",f.f.at+600);
        var restored=ProductionRecipeTest.engine();restored.restore(SaveCopies.copy(f.f.engine.savedState()),f.f.at+1200);
        assertEquals("the completed conversion's paid basis belongs to the tradeable raw id",1,
            restored.basis.known(NEW).availableQty);
        assertEquals(1_200_000,restored.basis.known(NEW).availableBasisGp);
        assertEquals(0,restored.basis.known(OLD).availableQty);
    }

    @Test public void knownPartialAndUnknownBasisHandoff() {
        var partial=new Fixture(0);partial.inventory.put(OLD,3L);
        partial.f.engine.setBaseline(new ContainerSnapshot(partial.inventory));
        var acquired=new Transaction(partial.f.at,TransactionType.GAIN,Context.GENERIC,"","",true,
            List.of(new Flow(OLD,"Old school bond (untradeable)",2,600_000,1_200_000,PriceSource.HIGH_ALCHEMY)),Confidence.CONFIRMED,"",null);
        partial.f.engine.session().addTransaction(acquired,0,false);
        partial.count="3";partial.quoted="1,071,369 coins";partial.accept();
        partial.inventory.clear();partial.inventory.put(NEW,3L);
        partial.f.become(partial.inventory);
        assertEquals(2,partial.f.engine.basis.known(NEW).availableQty);
        assertEquals(1_200_000,partial.f.engine.basis.known(NEW).availableBasisGp);
        assertEquals(0,partial.f.engine.basis.known(OLD).availableQty);
        var unknown=new Fixture(0);unknown.accept();unknown.converted(0);
        assertEquals(0,unknown.f.engine.basis.known(NEW).availableQty);
        assertEquals(0,unknown.f.engine.basis.known(NEW).availableBasisGp);
    }

    @Test public void recentLedgerAndCanonicalTotalsReconcileAfterAConversion() {
        var f=new Fixture(0);f.accept();f.change(0);
        var live=f.f.live();
        assertEquals(-357123,live.net);
        assertEquals(-357123,f.f.engine.metrics(f.f.at+1).net);
        var fee=live.recent.stream().filter(row->row.value==-357123).findFirst().orElseThrow();
        var ledger=LedgerData.capture(f.f.engine,f.f.at+1,LivePage.entryFor(fee));
        assertEquals(live.net,ledger.detail.group.value);
    }

    @Test public void aSaturatedBankFeeKeepsUnrelatedCashOutOfTheClampedBucket() {
        var f=new Fixture(0);f.quoted=Long.MAX_VALUE+" coins";f.accept();f.converted(100);
        assertEquals(-Long.MAX_VALUE+100,f.f.live().net);
        assertFalse(f.reviewed());
    }
    @Test public void aPreSwapBaselineKeepsTheAcceptedFeeForTheMeasuredPair() {
        for (long held : new long[] {0, 100_000, 1_000_000}) {
            var fixture = new Fixture(held);
            fixture.accept();
            fixture.f.engine.setBaseline(new ContainerSnapshot(fixture.inventory));
            fixture.converted(Math.max(0, held - 357123));
            fixture.fee(357123);
            fixture.f.become(fixture.inventory);
            fixture.fee(357123);
            assertEquals(1, fixture.f.engine.session().transactions().stream()
                    .filter(Transaction::counts).count());
        }
    }

    @Test public void aPostSwapBaselineCannotInventAConversionAndItsQuoteExpires() {
        var fixture = new Fixture(0);
        fixture.accept();
        fixture.inventory.clear();
        fixture.inventory.put(NEW, 1L);
        fixture.f.engine.setBaseline(new ContainerSnapshot(fixture.inventory));
        fixture.f.become(fixture.inventory);
        assertEquals(0, fixture.f.live().net);
        fixture.f.engine.events.tick(fixture.f.engine.events.now + 51);
        assertNull(fixture.f.engine.events.peek(Inbox.BOND_PAYMENT));
    }

}
