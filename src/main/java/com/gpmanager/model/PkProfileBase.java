package com.gpmanager;
import lombok.Getter;
import java.util.List;
import static com.gpmanager.SafeMath.*;
import static java.lang.Math.*;
/**
* PvP facts: encounter counts and the finalized money folded from encounters, kill net and death
* loss with their extremes, and the supplies/other split while every part's split is known. A
* Session's projection extends it, and PvP queries fold projections and anchors into one. Never a
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
void fold(EncounterType type, PkMoney money) {
long encounterNet = money.netGp;
net = safeAdd(net, encounterNet);
costs = safeAdd(costs, money.getCostsGp());
if (money.costSplitAvailable) {
suppliesCosts = safeAdd(suppliesCosts, money.getSuppliesCostsGp());
otherCosts = safeAdd(otherCosts, safeSubtract(money.getCostsGp(), money.getSuppliesCostsGp()));
} else {
costSplitComplete = false;
}
if (type == EncounterType.KILL) {
killNet = safeAdd(killNet, encounterNet);
bestKill = max(bestKill, nonNeg(encounterNet));
} else {
deathLoss = safeAdd(deathLoss, money.getLossGp());
largestDeathLoss = max(largestDeathLoss, money.getLossGp());
}
}

/** Folds a Session projection (or another base) exactly once. */
void merge(PkProfileBase other) {
if (other == null) return;
encounters = safeAddCount(encounters, other.encounters);
kills = safeAddCount(kills, other.kills);
deaths = safeAddCount(deaths, other.deaths);
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
* Folds one Session projection plus its bounded correction-eligible anchors exactly once,
* mirroring the Session query composition. Anchors keep mutable attribution visible until
* their receipts finalize.
*/
void merge(PkProfileBase projection, List<PkMutableAttribution> anchors) {
merge(projection);
for (PkMutableAttribution anchor : anchors) {
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
