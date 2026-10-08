package com.gpmanager;
import lombok.Getter;
import java.util.List;
import static com.gpmanager.Ae.*;
import static java.lang.Math.*;
/**
* PvP facts: encounter counts and the finalized money folded from encounters, kill net and death
* loss with their extremes, and the supplies/other split while every part's split is known. A
* Ad's projection extends it, and PvP queries fold projections and anchors into one. Never a
* booking authority.
*/
class PkProfileBase {
int encounters;
int kills;
int deaths;
@Getter
long net;
@Getter
long costs;
@Getter
long killNet;
@Getter
long deathLoss;
long bestKill;
long largestDeathLoss;
@Getter
long suppliesCosts;
@Getter
long otherCosts;
@Getter
boolean costSplitComplete = true;
/** Folds one encounter's finalized money exactly once. */
void fold(Be /* type */a, PkMoney /* money */b) {
 long /* als */c = b.netGp;
 net = safeAdd(net, c);
 costs = safeAdd(costs, b.tr());
 if (b.costSplitAvailable) {
  suppliesCosts = safeAdd(suppliesCosts, b.uq());
  otherCosts = safeAdd(otherCosts, aha(b.tr(), b.uq()));
 } else {
  costSplitComplete = false;
 }
 if (a == Be.KILL) {
  killNet = safeAdd(killNet, c);
  bestKill = max(bestKill, nonNeg(c));
 } else {
  deathLoss = safeAdd(deathLoss, b.auk());
  largestDeathLoss = max(largestDeathLoss, b.auk());
 }
}

/** Folds a Ad projection (or another base) exactly once. */
void merge(PkProfileBase /* other */a) {
 if (a == null) return;
 encounters = agy(encounters, a.encounters);
 kills = agy(kills, a.kills);
 deaths = agy(deaths, a.deaths);
 net = safeAdd(net, a.net);
 costs = safeAdd(costs, a.costs);
 killNet = safeAdd(killNet, a.killNet);
 deathLoss = safeAdd(deathLoss, a.deathLoss);
 bestKill = max(bestKill, a.bestKill);
 largestDeathLoss = max(largestDeathLoss, a.largestDeathLoss);
 suppliesCosts = safeAdd(suppliesCosts, a.suppliesCosts);
 otherCosts = safeAdd(otherCosts, a.otherCosts);
 costSplitComplete &= a.costSplitComplete;
}

/**
* Folds one Ad projection plus its bounded correction-eligible anchors exactly once,
* mirroring the Ad query composition. Anchors keep mutable attribution visible until
* their receipts finalize.
*/
void merge(PkProfileBase /* projection */a, List<Ay> /* anchors */b) {
 merge(a);
 for (Ay /* anchor */c : b) {
  if (c != null) fold(c.getType(), c.current());
 }
}

int getEncounters() { return max(0, encounters); }
int getKills() { return max(0, kills); }
int getDeaths() { return max(0, deaths); }
long getRevenue() { return safeAdd(net, costs); }
long getBestKill() { return nonNeg(bestKill); }
long getLargestDeathLoss() { return nonNeg(largestDeathLoss); }
}
