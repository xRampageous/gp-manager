package com.gpmanager;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
public class DelayedPriceRetryTest {
 static final long START=1700000000000L;
 java.util.function.LongSupplier old;
 @Before public void setup(){old=SafeMath.clock;SafeMath.clock=()->START;JsonCodec.bind(new com.google.gson.Gson());}
 @After public void restore(){SafeMath.clock=old;}
 void freshQueue(DelayedPriceFixture h,Transaction row){h.engine.doseRetries.clear();h.engine.queueDose(h.engine.session(),row);}
 void tick(DelayedPriceFixture h,long at){h.engine.processIfDirty(new ContainerSnapshot(Map.of(139,1L)),at,null);}
 String json(Object value){return new com.google.gson.Gson().toJson(value);}
 @Test public void newlyBookedReviewRecoversThroughTheNativeTickPath(){
  var h=new DelayedPriceFixture();var row=h.sip();assertEquals(1,h.engine.doseRetries.size());
  long due=h.engine.doseRetries.peek().at;h.ready();tick(h,Math.max(START+10000,due));
  assertFalse(ReviewRules.needsReview(row));assertEquals(-1000,h.engine.metrics(START+60000).net);assertTrue(h.engine.doseRetries.isEmpty());
 }
 @Test public void retriesWaitForTheirDeadlineAndStopAfterFourAttempts(){
  var h=new DelayedPriceFixture();var row=h.sip();freshQueue(h,row);
  long at=START;long[] delay={10000,60000,1860000};
  for(int i=0;i<4;i++){
   var next=h.engine.doseRetries.peek();assertNotNull(next);h.engine.retryDose(at);
   if(i<3){assertEquals(i+1,next.attempts);assertEquals(at+delay[i],next.at);h.engine.retryDose(next.at-1);assertEquals(i+1,next.attempts);at=next.at;}
  }
  assertTrue(h.engine.doseRetries.isEmpty());h.ready();h.engine.retryDose(at+999999);assertTrue(ReviewRules.needsReview(row));assertEquals(0,h.engine.metrics(at).net);
 }
 @Test public void aFutureRetryDoesNotBlockAnotherReadyRowAndOnlyOneRunsPerTick(){
  var h=new DelayedPriceFixture();var first=h.sip();freshQueue(h,first);var second=SaveCopies.copy(first);second.id=Phrases.uuid();h.engine.add(second,false);
  tick(h,START+5000);assertEquals(2,h.engine.doseRetries.size());assertEquals(1,h.engine.doseRetries.stream().mapToInt(value->value.attempts).sum());
  tick(h,START+5600);assertEquals(2,h.engine.doseRetries.stream().mapToInt(value->value.attempts).sum());
 }
 @Test public void deletedAndCompactedReceiptsAreDiscardedWithoutMutation(){
  var h=new DelayedPriceFixture();var row=h.sip();freshQueue(h,row);String before=json(row);h.engine.session().transactions.clear();h.ready();h.engine.retryDose(START+60000);
  assertEquals(before,json(row));assertTrue(h.engine.doseRetries.isEmpty());assertEquals(0,h.engine.metrics(START+60000).net);
 }
 @Test public void aDetachedReplacedSessionCannotBeRecovered(){
  var h=new DelayedPriceFixture();h.engine.startCustom("Old",START);var row=h.sip();freshQueue(h,row);String before=json(row);h.engine.custom=null;h.engine.current=h.engine.general;h.ready();h.engine.retryDose(START+60000);
  assertEquals(before,json(row));assertTrue(h.engine.doseRetries.isEmpty());
 }
 @Test public void playerCorrectionsAfterQueueingRemainUntouched(){
  for(boolean undo:new boolean[]{false,true}){
   var h=new DelayedPriceFixture();var row=h.sip();freshQueue(h,row);assertTrue(h.engine.correctTransaction(row.id(),Correction.IGNORE,"owner"));if(undo)assertTrue(h.engine.undoCorrection());
   String before=json(row);h.ready();h.engine.retryDose(START+60000);assertEquals(before,json(row));assertEquals(0,h.engine.metrics(START+60000).net);
  }
 }
 @Test public void resetClearsPendingWork(){
  var h=new DelayedPriceFixture();var row=h.sip();String before=json(row);h.engine.resetData(START+10000);h.ready();h.engine.retryDose(START+60000);assertTrue(h.engine.doseRetries.isEmpty());assertEquals(before,json(row));
 }
 @Test public void restoreIndexesCanonicalRowsAndExcludesSavedSnapshots(){
  var h=new DelayedPriceFixture();var row=h.sip();String id=EngineProbe.saveLiveSession(h.engine, h.engine.session(),h.engine.session().sessionStartAt,START+5000);assertNotNull(id);
  var restored=new DelayedPriceFixture();restored.engine.restore(SaveCopies.copy(h.engine.savedState()),START+10000);assertEquals(1,restored.engine.doseRetries.size());restored.ready();restored.engine.retryDose(START+60000);
  assertFalse(ReviewRules.needsReview(restored.engine.session().transactions.get(0)));assertTrue(ReviewRules.needsReview(restored.engine.savedSession(id).session.transactions.get(0)));
 }
 @Test public void profileInstallTransfersOnlyTheNewOwnersQueue(){
  var h=new DelayedPriceFixture();var oldRow=h.sip();String before=json(oldRow);var other=new DelayedPriceFixture();var newRow=other.sip();
  var draft=h.engine.prepareProfile("other",SaveCopies.copy(other.engine.savedState()),START+10000);h.engine.installProfile(draft,"other");assertEquals(1,h.engine.doseRetries.size());h.ready();h.engine.retryDose(START+60000);
  assertEquals(before,json(oldRow));assertFalse(ReviewRules.needsReview(h.engine.session().transactions.get(0)));assertEquals(newRow.id(),h.engine.session().transactions.get(0).id());
 }
 @Test public void pausedTrackingDoesNotConsumeRetryAttempts(){
  var h=new DelayedPriceFixture();var row=h.sip();freshQueue(h,row);h.engine.session().pause(START+5000,PauseReason.MANUAL);h.ready();tick(h,START+60000);
  assertTrue(ReviewRules.needsReview(row));assertEquals(0,h.engine.doseRetries.peek().attempts);
 }
 @Test public void exhaustedReviewsWaitForTheNextProfileLoad(){
  var h=new DelayedPriceFixture();var row=h.sip();freshQueue(h,row);while(!h.engine.doseRetries.isEmpty())h.engine.retryDose(Math.max(START,h.engine.doseRetries.peek().at));
  h.ready();SafeMath.clock=()->START+2000000;h.engine.recheck();assertTrue(ReviewRules.needsReview(row));
  h.engine.restore(SaveCopies.copy(h.engine.savedState()),START+2000000);h.engine.retryDose(START+2000000);assertFalse(ReviewRules.needsReview(h.engine.session().transactions.get(0)));assertEquals(-1000,h.engine.metrics(START+2000000).net);
 }
 @Test public void normalizerIsTheSinglePairAuthority(){
  var h=new DelayedPriceFixture();var row=h.sip();freshQueue(h,row);h.ready();int[] calls={0};
  h.engine.valuation=new FlowValuator(){
   public List<Flow> value(Map<Integer,Long> changes){return h.service.value(changes);}
   public List<Flow> value(Map<Integer,Long> changes,long at){return h.service.value(changes,at);}
   public List<Flow> normalizeConsumedFlows(List<Flow> flows,ActionKind action,long at){calls[0]++;return h.service.normalizeConsumedFlows(flows,action,at);}
  };
  h.engine.retryDose(START+60000);assertFalse(ReviewRules.needsReview(row));assertEquals(1,calls[0]);
 }
 @Test public void aNoOpNormalizerCannotAuthorizeRecovery(){
  var h=new DelayedPriceFixture();var row=h.sip();freshQueue(h,row);String before=json(row);h.ready();h.engine.valuation=changes->h.service.value(changes,START+60000);
  h.engine.retryDose(START+60000);assertEquals(before,json(row));assertTrue(ReviewRules.needsReview(row));
 }
 @Test public void theLoginScanDoesNotBypassTheOneRowTickBudget(){
  var h=new DelayedPriceFixture();var first=h.sip();freshQueue(h,first);var second=SaveCopies.copy(first);second.id=Phrases.uuid();second.timestamp++;h.engine.add(second,false);h.ready();
  h.engine.recheck();assertTrue(ReviewRules.needsReview(first));assertTrue(ReviewRules.needsReview(second));
  tick(h,START+60000);assertEquals(1,sessionReviews(h));tick(h,START+60600);assertEquals(0,sessionReviews(h));assertEquals(-2000,h.engine.metrics(START+60600).net);
 }
 long sessionReviews(DelayedPriceFixture h){return h.engine.session().transactions.stream().filter(ReviewRules::needsReview).count();}
 @Test public void restoredCandidatesUseReceiptOrderWhenTheirDeadlinesMatch(){
  var h=new DelayedPriceFixture();var first=h.sip();var second=SaveCopies.copy(first);second.id=Phrases.uuid();second.timestamp++;var third=SaveCopies.copy(second);third.id=Phrases.uuid();third.timestamp++;
  h.engine.session().transactions=new ArrayList<>(List.of(third,second,first));h.engine.restore(SaveCopies.copy(h.engine.savedState()),START+10000);h.ready();
  for(var expected:List.of(first,second,third)){assertEquals(expected.id(),h.engine.doseRetries.peek().row.id());h.engine.retryDose(START+60000);}
  assertEquals(0,sessionReviews(h));assertEquals(-3000,h.engine.metrics(START+60000).net);
 }
}
