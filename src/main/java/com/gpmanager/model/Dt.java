package com.gpmanager;
import java.math.BigDecimal;
import java.util.*;
import static com.gpmanager.Ae.*;
import static java.lang.Math.*;
class Dt {
int kills;
int deaths;
int currentStreak;
long revenue;
long costs;
long net;
long bestKill;
long largestDeathLoss;
long totalKillNet;
long totalDeathLoss;
long suppliesCosts;
long otherCosts;
boolean costSplitAvailable;
Double medianKillNetGp;
Double medianDeathLossGp;
Di detailScope;
int retainedDetailCount;
/** The empty aggregate: nothing counted. */
static Dt none() {
 return new Dt(0, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, false, null, null, Di.COMPLETE_HISTORY, 0);
}

Dt(int /* kills */a, int /* deaths */b, int /* currentStreak */c, long /* revenue */d, long /* costs */e, long /* net */f, long /* bestKill */g,
long /* largestDeathLoss */h, long /* totalKillNet */i, long /* totalDeathLoss */j, long /* suppliesCosts */k, long /* otherCosts */l,
boolean /* costSplitAvailable */m, Double /* medianKillNetGp */n, Double /* medianDeathLossGp */o, Di /* detailScope */p,
int /* retainedDetailCount */q) {
 this.kills = max(0, a);
 this.deaths = max(0, b);
 this.currentStreak = c;
 this.revenue = d;
 this.costs = e;
 this.net = f;
 this.bestKill = g;
 this.largestDeathLoss = nonNeg(h);
 this.totalKillNet = i;
 this.totalDeathLoss = nonNeg(j);
 this.suppliesCosts = k;
 this.otherCosts = l;
 this.detailScope = p == null ? Di.COMPLETE_HISTORY : p;
 this.retainedDetailCount = max(0, q);
 this.costSplitAvailable = m && safeAdd(k, l) == e;
 this.medianKillNetGp = this.kills > 0 ? n : null;
 this.medianDeathLossGp = this.deaths > 0 ? o : null;
}

int ue() { return kills + deaths; }
/** Exact middle-value median, averaging the two centre values for even counts. */
static Double median(List<Long> /* values */a) {
 if (Ag.empty(a)) return null;
 var /* sorted */b = new ArrayList<Long>(a);
 Collections.sort(b);
 int /* middle */c = b.size() / 2;
 if ((b.size() & 1) == 1) return b.get(c).doubleValue();
 return BigDecimal.valueOf(b.get(c - 1)).add(BigDecimal.valueOf(b.get(c)))
 .divide(BigDecimal.valueOf(2L)).doubleValue();
}
}
