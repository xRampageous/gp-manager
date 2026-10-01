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
void fold(Be type, PkMoney money) {
 long als = money.netGp;
 net = safeAdd(net, als);
 costs = safeAdd(costs, money.tr());
 if (money.costSplitAvailable) {
  suppliesCosts = safeAdd(suppliesCosts, money.uq());
  otherCosts = safeAdd(otherCosts, aha(money.tr(), money.uq()));
 } else {
  costSplitComplete = false;
 }
 if (type == Be.KILL) {
  killNet = safeAdd(killNet, als);
  bestKill = max(bestKill, nonNeg(als));
 } else {
  deathLoss = safeAdd(deathLoss, money.auk());
  largestDeathLoss = max(largestDeathLoss, money.auk());
 }
}

/** Folds a Ad projection (or another base) exactly once. */
void merge(PkProfileBase other) {
 if (other == null) return;
 encounters = agy(encounters, other.encounters);
 kills = agy(kills, other.kills);
 deaths = agy(deaths, other.deaths);
 net = safeAdd(net, other.net);
 costs = safeAdd(costs, other.costs);
 killNet = safeAdd(killNet, other.killNet);
 deathLoss = safeAdd(deathLoss, other.deathLoss);
 bestKill = max(bestKill, other.bestKill);
 largestDeathLoss = max(largestDeathLoss, other.largestDeathLoss);
 suppliesCosts = safeAdd(suppliesCosts, other.suppliesCosts);
 otherCosts = safeAdd(otherCosts, other.otherCosts);
 costSplitComplete &= other.costSplitComplete;
}

/**
* Folds one Ad projection plus its bounded correction-eligible anchors exactly once,
* mirroring the Ad query composition. Anchors keep mutable attribution visible until
* their receipts finalize.
*/
void merge(PkProfileBase projection, List<Ay> anchors) {
 merge(projection);
 for (Ay anchor : anchors) {
  if (anchor != null) fold(anchor.getType(), anchor.current());
 }
}

int getEncounters() { return max(0, encounters); }
int getKills() { return max(0, kills); }
int getDeaths() { return max(0, deaths); }
long getRevenue() { return safeAdd(net, costs); }
long getBestKill() { return nonNeg(bestKill); }
long getLargestDeathLoss() { return nonNeg(largestDeathLoss); }
}
