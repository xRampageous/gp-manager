package com.gpmanager;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Atomic recovery, immutable quotes and pooled-basis ownership boundaries. */
public class DelayedPriceReviewTest {
 static final long START=1700000000000L,AT=START+60000;
 java.util.function.LongSupplier old;
 @Before public void setup(){old=SafeMath.clock;SafeMath.clock=()->START;JsonCodec.bind(new com.google.gson.Gson());}
 @After public void restore(){SafeMath.clock=old;}
 void retry(DelayedPriceFixture h){SafeMath.clock=()->AT;for(var session:h.engine.sessions())for(var row:session.transactions())h.engine.recoverDose(session,row,AT);}
 String snapshot(Transaction row){return new com.google.gson.Gson().toJson(row);}
 void unchanged(DelayedPriceFixture h,Transaction row,String before){assertEquals(before,snapshot(row));assertTrue(ReviewRules.needsReview(row));assertEquals(0,h.engine.metrics(AT).net);}

 @Test public void allMissingPricesRecoverAtomicallyAndOnlyOnce(){
  var h=new DelayedPriceFixture();var row=h.sip();String id=row.id();long time=row.timestamp;
  h.ready();retry(h);
  assertFalse(ReviewRules.needsReview(row));assertEquals(-1000,h.engine.metrics(AT).net);
  assertEquals(id,row.id());assertEquals(time,row.timestamp);assertEquals(ActionKind.DRINK,row.action());assertEquals(Correction.AUTO,row.correction());
  assertEquals(1,h.engine.session().transactions().size());
  assertTrue(row.flows().stream().allMatch(f->f.pricedAt()==AT));
  long revision=h.engine.revision();String first=snapshot(row);retry(h);assertEquals(first,snapshot(row));assertEquals(revision,h.engine.revision());
 }
 @Test public void partialArrivalDoesNotChangeTheCapturedPair(){
  var h=new DelayedPriceFixture();var row=h.sip();String before=snapshot(row);h.prices.put(2434,3600);retry(h);unchanged(h,row,before);
  h.ready();retry(h);assertFalse(ReviewRules.needsReview(row));assertEquals(-1000,h.engine.metrics(AT).net);
 }
 @Test public void matchingKnownQuotesStayFrozen(){
  var h=new DelayedPriceFixture();h.prices.putAll(Map.of(2434,3600,139,2600));var row=h.sip();String flows=new com.google.gson.Gson().toJson(row.flows());
  h.ready();retry(h);assertFalse(ReviewRules.needsReview(row));assertEquals(-1000,h.engine.metrics(AT).net);assertEquals(flows,new com.google.gson.Gson().toJson(row.flows()));
 }
 @Test public void mismatchingKnownQuotesStayReview(){
  var h=new DelayedPriceFixture();h.prices.putAll(Map.of(2434,3600,139,2700));var row=h.sip();String before=snapshot(row);h.ready();retry(h);unchanged(h,row,before);
 }
 @Test public void conflictingOverrideDoesNotPublishAnyPrice(){
  var h=new DelayedPriceFixture();var row=h.sip();String before=snapshot(row);h.ready();h.overrides="139=5000";retry(h);unchanged(h,row,before);
 }
 @Test public void excludedRowsStayUntouched(){
  var h=new DelayedPriceFixture();var row=h.sip();assertTrue(h.engine.correctTransaction(row.id(),Correction.IGNORE,"owner"));String before=snapshot(row);h.ready();retry(h);assertEquals(before,snapshot(row));
 }
 @Test public void undoneOwnerDecisionsStillFenceAutomaticRecovery(){
  var h=new DelayedPriceFixture();var row=h.sip();assertTrue(h.engine.correctTransaction(row.id(),Correction.IGNORE,"owner"));assertTrue(h.engine.undoCorrection());String before=snapshot(row);h.ready();retry(h);unchanged(h,row,before);
 }
 @Test public void claimsRaidsAndMarketOwnershipAreExcluded(){
  for(int boundary=0;boundary<4;boundary++){
   var h=new DelayedPriceFixture();var row=h.sip();if(boundary==0)row.setSourceClaimId("claim");else if(boundary==1)row.flows().get(0).raid="raid";else if(boundary==2)row.context=Context.MARKET;else row.encounterId="encounter";
   String before=snapshot(row);h.ready();retry(h);unchanged(h,row,before);
  }
 }
 @Test public void aReservationOnEitherLegBlocksTheTransition(){
  for(int item:new int[]{2434,139}){
   var h=new DelayedPriceFixture();var row=h.sip();h.engine.basis.openReservationLookup=id->id==item?START+5000:0L;String before=snapshot(row),stock=new com.google.gson.Gson().toJson(h.engine.basis.stock);h.ready();retry(h);unchanged(h,row,before);assertEquals(stock,new com.google.gson.Gson().toJson(h.engine.basis.stock));
   h.engine.basis.openReservationLookup=id->0L;retry(h);assertFalse(ReviewRules.needsReview(row));
  }
 }
 @Test public void realizedOrUsedUnitsStayFenced(){
  for(int item:new int[]{2434,139})for(boolean used:new boolean[]{false,true}){
   var h=new DelayedPriceFixture();var row=h.sip();if(used)h.engine.basis.deplete(item,1,START+5000);else h.engine.basis.fenceRealized(item,START+5000);
   String before=snapshot(row),stock=new com.google.gson.Gson().toJson(h.engine.basis.stock);h.ready();retry(h);unchanged(h,row,before);assertEquals(stock,new com.google.gson.Gson().toJson(h.engine.basis.stock));
  }
 }
 @Test public void preEpochRowsAndLaterInputStockStayFenced(){
  for(boolean epoch:new boolean[]{false,true}){
   var h=new DelayedPriceFixture();var row=h.sip();if(epoch)h.engine.basis.epoch=START+5000;else{
    var pool=h.engine.basis.pool(2434);pool.availableQty=1;pool.availableBasisGp=3600;pool.acquiredAt=START+5000;
   }
   String before=snapshot(row),stock=new com.google.gson.Gson().toJson(h.engine.basis.stock);h.ready();retry(h);unchanged(h,row,before);assertEquals(stock,new com.google.gson.Gson().toJson(h.engine.basis.stock));
  }
 }
 @Test public void olderInputCoverageIsConsumedAndRemainderAppearsOnce(){
  var h=new DelayedPriceFixture();var row=h.sip();var pool=h.engine.basis.pool(2434);pool.availableQty=1;pool.availableBasisGp=3600;pool.acquiredAt=START;
  h.ready();retry(h);assertFalse(ReviewRules.needsReview(row));assertEquals(0,EngineProbe.availableQty(h.engine,2434));assertEquals(1,EngineProbe.availableQty(h.engine,139));assertEquals(3000,EngineProbe.availableBasisGp(h.engine,139));
  retry(h);assertEquals(1,EngineProbe.availableQty(h.engine,139));
 }
 @Test public void closedFactsAndSavedCopiesKeepTheirScopes(){
  var h=new DelayedPriceFixture();h.engine.startCustom("Potion run",START);var row=h.sip();var run=h.engine.session();String saved=EngineProbe.saveLiveSession(h.engine, run,run.sessionStartAt,START+5000);assertNotNull(saved);
  h.engine.finishCustom(START+6000);var closed=h.engine.history().get(0);assertEquals(0,MoneySnapshot.of(h.engine).metrics(closed,AT).net);
  h.ready();retry(h);assertEquals(-1000,closed.metrics(AT).net);assertEquals(-1000,MoneySnapshot.of(h.engine).metrics(closed,AT).net);
  assertTrue(ReviewRules.needsReview(h.engine.savedSession(saved).session.transactions().get(0)));
 }
 @Test public void malformedOverflowingAndAmbiguousPairsStayUntouched(){
  for(int state=0;state<3;state++){
   var h=new DelayedPriceFixture();var row=h.sip();if(state==0)row.flows().get(0).quantity=Long.MIN_VALUE;
   else if(state==1)row.flows().get(1).quantity=2;
   else {row.flows().get(0).quantity=-Long.MAX_VALUE;row.flows().get(1).quantity=Long.MAX_VALUE;}
   String before=snapshot(row);h.ready();retry(h);unchanged(h,row,before);
  }
 }
 @Test public void reloadKeepsTheRecoveredFinancialAndBasisState(){
  var h=new DelayedPriceFixture();var row=h.sip();h.ready();retry(h);assertFalse(ReviewRules.needsReview(row));
  var restored=new DelayedPriceFixture();restored.ready();restored.engine.restore(h.engine.savedState(),AT+1000);var copy=restored.engine.session().transactions().get(0);
  assertFalse(ReviewRules.needsReview(copy));assertEquals(-1000,restored.engine.metrics(AT+2000).net);assertEquals(1,EngineProbe.availableQty(restored.engine,139));
 }
}
