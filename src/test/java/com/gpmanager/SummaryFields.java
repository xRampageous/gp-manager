package com.gpmanager;
import java.util.*;
import java.time.ZoneId;
import java.awt.Color;
import java.awt.image.BufferedImage;

/** Direct, exhaustive DTO projection for parity. No reflection, deserialization or private access. */
class SummaryFields {
 static Object of(Object a) {
  if (a == null || a instanceof String || a instanceof Number || a instanceof Boolean || a instanceof Enum || a instanceof ZoneId) return a;
  if (a instanceof Color) return ((Color) a).getRGB();
  if (a instanceof BufferedImage) {
   BufferedImage b = (BufferedImage) a;
   return Arrays.asList(b.getWidth(), b.getHeight(), Arrays.toString(b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth())));
  }
  if (a instanceof int[]) return Arrays.toString((int[]) a);
  if (a instanceof Map) {
   Map<Object, Object> b = new HashMap<>();
   ((Map<?, ?>) a).forEach((k, v) -> b.put(of(k), of(v))); return b;
  }
  if (a instanceof Collection) {
   Collection<Object> b = a instanceof Set ? new HashSet<>() : new ArrayList<>();
   for (Object c : (Collection<?>) a) b.add(of(c)); return b;
  }
  if (a instanceof Ab) {
   Ab b = (Ab) a;
   return Arrays.asList("Ab", of(b.itemId), of(b.itemName), of(b.quantityDelta), of(b.unitPrice), of(b.valueDelta), of(b.priceSource), of(b.priceCapturedAtEpochMillis));
  }
  if (a instanceof Ao) {
   Ao b = (Ao) a;
   return Arrays.asList("Ao", of(b.entry), of(b.scopeName), of(b.readOnly), of(b.compacted), of(b.detailedHistoryAvailable), of(b.net), of(b.revision), of(b.capturedAt), of(b.gains), of(b.costs), of(b.market), of(b.review), of(b.corrected), of(b.total), of(b.reconciled), of(b.detail), of(b.marketRows), of(b.costCounts), of(b.pendingItems), of(b.deaths));
  }
  if (a instanceof Ao.Death) {
   Ao.Death b = (Ao.Death) a;
   return Arrays.asList("Ao.Death", of(b.transactionId), of(b.place), of(b.at), of(b.value), of(b.lost), of(b.kept), of(b.fees));
  }
  if (a instanceof Ao.Detail) {
   Ao.Detail b = (Ao.Detail) a;
   return Arrays.asList("Ao.Detail", of(b.group), of(b.receipts), of(b.exact));
  }
  if (a instanceof Ao.Eb) {
   Ao.Eb b = (Ao.Eb) a;
   return Arrays.asList("Ao.Eb", of(b.groups), of(b.total), of(b.entries), of(b.receipts), of(b.incomplete));
  }
  if (a instanceof Ao.Entry) {
   Ao.Entry b = (Ao.Entry) a;
   return Arrays.asList("Ao.Entry", of(b.scope), of(b.historySessionId), of(b.historyName), of(b.costView), of(b.search), of(b.highlightTransactionId), of(b.highlightContributionId), of(b.highlightGroupId), of(b.focusAudit));
  }
  if (a instanceof Ao.PendingItem) {
   Ao.PendingItem b = (Ao.PendingItem) a;
   return Arrays.asList("Ao.PendingItem", of(b.itemId), of(b.name), of(b.quantity), of(b.tip));
  }
  if (a instanceof Ao.ReviewData) {
   Ao.ReviewData b = (Ao.ReviewData) a;
   return Arrays.asList("Ao.ReviewData", of(b.rows), of(b.scopeCount));
  }
  if (a instanceof As) {
   As b = (As) a;
   return Arrays.asList("As", of(b.hasActive), of(b.activeName), of(b.activeSessionId), of(b.activeNet), of(b.activeGpPerHour), of(b.activeRateEstablished), of(b.activeMillis), of(b.activeNetTarget), of(b.activeTimeTarget), of(b.activeGrindId), of(b.activePace), of(b.myGrinds), of(b.recent), of(b.profileZone), of(b.detail), of(b.allTime));
  }
  if (a instanceof As.AllTime) {
   As.AllTime b = (As.AllTime) a;
   return Arrays.asList("As.AllTime", of(b.net), of(b.activeMillis), of(b.gpPerHour), of(b.grinds), of(b.splitAvailable), of(b.supplies), of(b.losses), of(b.bestNetName), of(b.bestNet), of(b.bestRateName), of(b.bestRate), of(b.wealth), of(b.bankAge), of(b.bank));
  }
  if (a instanceof As.Detail) {
   As.Detail b = (As.Detail) a;
   return Arrays.asList("As.Detail", of(b.sessionId), of(b.name), of(b.startedAt), of(b.net), of(b.revenue), of(b.supplies), of(b.losses), of(b.splitAvailable), of(b.activeMillis), of(b.elapsedMillis), of(b.rateEstablished), of(b.gpPerHour), of(b.netTargetGp), of(b.activeTimeTargetMillis), of(b.closed), of(b.compacted), of(b.linkedGrindId), of(b.history));
  }
  if (a instanceof As.MyGrind) {
   As.MyGrind b = (As.MyGrind) a;
   return Arrays.asList("As.MyGrind", of(b.definition), of(b.yk), of(b.hasLast), of(b.lastNet), of(b.lastStartedAt), of(b.hasBest), of(b.bestNet));
  }
  if (a instanceof As.Pace) {
   As.Pace b = (As.Pace) a;
   return Arrays.asList("As.Pace", of(b.present), of(b.available), of(b.line), of(b.second));
  }
  if (a instanceof As.Recent) {
   As.Recent b = (As.Recent) a;
   return Arrays.asList("As.Recent", of(b.sessionId), of(b.name), of(b.startedAt), of(b.net), of(b.activeMillis), of(b.linkedGrindId));
  }
  if (a instanceof Ba) {
   Ba b = (Ba) a;
   return Arrays.asList("Ba", of(b.recap));
  }
  if (a instanceof Ba.Highlight) {
   Ba.Highlight b = (Ba.Highlight) a;
   return Arrays.asList("Ba.Highlight", of(b.name), of(b.net));
  }
  if (a instanceof Ba.Recap) {
   Ba.Recap b = (Ba.Recap) a;
   return Arrays.asList("Ba.Recap", of(b.biggestGain), of(b.lz), of(b.highlightsUnavailable), of(b.sources));
  }
  if (a instanceof Bi.Row) {
   Bi.Row b = (Bi.Row) a;
   return Arrays.asList("Bi.Row", of(b.presentationId), of(b.timestampEpochMillis), of(b.slot), of(b.itemId), of(b.itemName), of(b.side), of(b.offeredQty), of(b.filledQty), of(b.basisUnitPrice), of(b.settledQty), of(b.observedSettlementGp), of(b.settlementAdjustmentGp), of(b.inferredGeTaxGp), of(b.receivedEachGp), of(b.settlementAtEpochMillis), of(b.realizedResultGp), of(b.realizedResultCorrectionAware), of(b.manualFinancialResult), of(b.correctionApplied), of(b.collectionAmbiguous), of(b.knownCostOnly), of(b.trackedBasisConsumedGp), of(b.expectedReferenceTaxGp), of(b.geReferenceGp), of(b.geDifferenceGp), of(b.geDifferenceAvailable), of(b.coverage), of(b.lifecycle), of(b.settlementId), of(b.scopeSessionId), of(b.listedDuring));
  }
  if (a instanceof Br.Group) {
   Br.Group b = (Br.Group) a;
   return Arrays.asList("Br.Group", of(b.semanticGroupId), of(b.itemId), of(b.table), of(b.primaryName), of(b.qe), of(b.actionLabel), of(b.usedBy), of(b.chargeUse), of(b.latestActivityAt), of(b.value), of(b.coverage), of(b.quantity), of(b.claim), of(b.normalizedConsume), of(b.receiptCount), of(b.composite), of(b.neutral), of(b.market), of(b.reviewRequired), of(b.corrected), of(b.marketPresentationId), of(b.representativeTransactionId), of(b.representativeContributionId), of(b.receiptIds), of(b.receipts), of(b.searchTerms), of(b.units), of(b.failed));
  }
  if (a instanceof Br.Receipt) {
   Br.Receipt b = (Br.Receipt) a;
   return Arrays.asList("Br.Receipt", of(b.contributionId), of(b.transactionId), of(b.at), of(b.itemId), of(b.itemName), of(b.quantity), of(b.value), of(b.automaticValue), of(b.unpriced), of(b.neutral), of(b.review), of(b.corrected), of(b.split), of(b.category), of(b.verb), of(b.actionDisplayName), of(b.why), of(b.qj), of(b.source), of(b.usedBy), of(b.priceBasis), of(b.marketSettlement));
  }
  if (a instanceof Br.Result) {
   Br.Result b = (Br.Result) a;
   return Arrays.asList("Br.Result", of(b.groups));
  }
  if (a instanceof Br.Summary) {
   Br.Summary b = (Br.Summary) a;
   return Arrays.asList("Br.Summary", of(b.gains), of(b.costs), of(b.market), of(b.entries), of(b.receipts), of(b.coverageComplete));
  }
  if (a instanceof Bu) {
   Bu b = (Bu) a;
   return Arrays.asList("Bu", of(b.activityHint), of(b.paused), of(b.elapsedMillis), of(b.revenue), of(b.costs), of(b.net), of(b.profitPerHour), of(b.suppliesCosts), of(b.otherCosts), of(b.costSplitAvailable));
  }
  if (a instanceof Ca) {
   Ca b = (Ca) a;
   return Arrays.asList("Ca", of(b.sessionId), of(b.hasSession), of(b.ownerLabel), of(b.elapsedMillis), of(b.paused), of(b.idle), of(b.net), of(b.gains), of(b.loss), of(b.supplies), of(b.costs), of(b.costSplitAvailable), of(b.rateEstablished), of(b.gpPerHour), of(b.goal), of(b.marketResult), of(b.marketPending), of(b.freePlay), of(b.activityLabel), of(b.target), of(b.pvpPossible), of(b.skulled), of(b.protectItem), of(b.risk), of(b.reviewCount), of(b.recent), of(b.pvpSession), of(b.kills), of(b.deaths), of(b.killNet), of(b.deathLoss), of(b.bestKill), of(b.pendingKeys), of(b.reclaimItems), of(b.streak), of(b.bestStreak), of(b.loggedOut), of(b.timeTargetMillis), of(b.resumeInSeconds), of(b.calibrating), of(b.hopping));
  }
  if (a instanceof Ca.Goal) {
   Ca.Goal b = (Ca.Goal) a;
   return Arrays.asList("Ca.Goal", of(b.target), of(b.current), of(b.reached), of(b.label), of(b.progress), of(b.eta), of(b.fraction), of(b.time));
  }
  if (a instanceof Ca.Recent) {
   Ca.Recent b = (Ca.Recent) a;
   return Arrays.asList("Ca.Recent", of(b.itemId), of(b.name), of(b.qty), of(b.value), of(b.neutral), of(b.unpriced), of(b.tag), of(b.receipts), of(b.receiptId), of(b.contributionId), of(b.actionLabel), of(b.quantity), of(b.composite), of(b.market), of(b.valueAvailable), of(b.ledgerCostView), of(b.ledgerReview), of(b.marketResult), of(b.marketSettlementValue), of(b.marketSide), of(b.open));
  }
  if (a instanceof Cb) {
   Cb b = (Cb) a;
   return Arrays.asList("Cb", of(b.visible), of(b.gem), of(b.title), of(b.timer), of(b.net), of(b.netColor), of(b.target), of(b.progress), of(b.trip), of(b.tripColor), of(b.chipUntil), of(b.rate), of(b.context), of(b.contextColor), of(b.gold), of(b.trayLabel), of(b.rows), of(b.more), of(b.tripId), of(b.foldAt), of(b.openedAt), of(b.builtAt));
  }
  if (a instanceof Cb.Row) {
   Cb.Row b = (Cb.Row) a;
   return Arrays.asList("Cb.Row", of(b.icon), of(b.name), of(b.tag), of(b.value), of(b.color), of(b.gold), of(b.born));
  }
  if (a instanceof Cu) {
   Cu b = (Cu) a;
   return Arrays.asList("Cu", of(b.transactionId), of(b.sessionId), of(b.timestampEpochMillis), of(b.ageMillis), of(b.items), of(b.value), of(b.why), of(b.validDecisions));
  }
  if (a instanceof Cu.Item) {
   Cu.Item b = (Cu.Item) a;
   return Arrays.asList("Cu.Item", of(b.itemId), of(b.name), of(b.quantityDelta), of(b.valueDelta));
  }
  if (a instanceof SavedState.Ap) {
   SavedState.Ap b = (SavedState.Ap) a;
   return Arrays.asList("SavedState.Ap", of(b.grindId), of(b.name), of(b.netTargetGp), of(b.activeTimeTargetMillis), of(b.favorite), of(b.archived));
  }
  throw new AssertionError("Unmapped summary output type: " + a.getClass().getName());
 }
}
