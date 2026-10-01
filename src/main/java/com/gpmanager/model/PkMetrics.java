package com.gpmanager;
import java.math.BigDecimal;
import java.util.*;
import static com.gpmanager.SafeMath.*;
import static java.lang.Math.*;
class PkMetrics {
final int kills;
final int deaths;
final int currentStreak;
final long revenue;
final long costs;
final long net;
final long bestKill;
final long largestDeathLoss;
final long totalKillNet;
final long totalDeathLoss;
final long suppliesCosts;
final long otherCosts;
final boolean costSplitAvailable;
final Double medianKillNetGp;
final Double medianDeathLossGp;
final PkDetailScope detailScope;
final int retainedDetailCount;
/** The empty aggregate: nothing counted. */
static PkMetrics none() {
 return new PkMetrics(0, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, false, null, null, PkDetailScope.COMPLETE_HISTORY, 0);
}

PkMetrics(int kills, int deaths, int currentStreak, long revenue, long costs, long net, long bestKill,
long largestDeathLoss, long totalKillNet, long totalDeathLoss, long suppliesCosts, long otherCosts,
boolean costSplitAvailable, Double medianKillNetGp, Double medianDeathLossGp, PkDetailScope detailScope,
int retainedDetailCount) {
 this.kills = max(0, kills);
 this.deaths = max(0, deaths);
 this.currentStreak = currentStreak;
 this.revenue = revenue;
 this.costs = costs;
 this.net = net;
 this.bestKill = bestKill;
 this.largestDeathLoss = nonNeg(largestDeathLoss);
 this.totalKillNet = totalKillNet;
 this.totalDeathLoss = nonNeg(totalDeathLoss);
 this.suppliesCosts = suppliesCosts;
 this.otherCosts = otherCosts;
 this.detailScope = detailScope == null ? PkDetailScope.COMPLETE_HISTORY : detailScope;
 this.retainedDetailCount = max(0, retainedDetailCount);
 this.costSplitAvailable = costSplitAvailable && safeAdd(suppliesCosts, otherCosts) == costs;
 this.medianKillNetGp = this.kills > 0 ? medianKillNetGp : null;
 this.medianDeathLossGp = this.deaths > 0 ? medianDeathLossGp : null;
}

int getEncounterCount() { return kills + deaths; }
/** Exact middle-value median, averaging the two centre values for even counts. */
static Double median(List<Long> values) {
 if (ModelText.empty(values)) return null;
 var sorted = new ArrayList<Long>(values);
 Collections.sort(sorted);
 int middle = sorted.size() / 2;
 if ((sorted.size() & 1) == 1) return sorted.get(middle).doubleValue();
 return BigDecimal.valueOf(sorted.get(middle - 1)).add(BigDecimal.valueOf(sorted.get(middle)))
 .divide(BigDecimal.valueOf(2L)).doubleValue();
}
}
