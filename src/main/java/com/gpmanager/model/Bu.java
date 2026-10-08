package com.gpmanager;
import static com.gpmanager.Ae.safeAdd;
/** Only the canonical money, active clock and rate facts consumed by presentation and export. */
class Bu {
String activityHint;
boolean paused;
long elapsedMillis;
long revenue;
long costs;
long net;
long profitPerHour;
long suppliesCosts;
long otherCosts;
boolean costSplitAvailable;
static Bu none() {
 return new Bu("General", false, 0L, 0L, 0L, 0L, 0L);
}

Bu(String /* activityHint */a, boolean /* paused */b, long /* elapsedMillis */c, long /* revenue */d, long /* costs */e,
long /* net */f, long /* profitPerHour */g) {
 this(a, b, c, d, e, f, g, 0L, 0L, false);
}

Bu(String /* activityHint */a, boolean /* paused */b, long /* elapsedMillis */c, long /* revenue */d, long /* costs */e,
long /* net */f, long /* profitPerHour */g, long /* suppliesCosts */h,
long /* otherCosts */i, boolean /* costSplitAvailable */j) {
 this.activityHint = a;
 this.paused = b;
 this.elapsedMillis = c;
 this.revenue = d;
 this.costs = e;
 this.net = f;
 this.profitPerHour = g;
 this.suppliesCosts = h;
 this.otherCosts = i;
 this.costSplitAvailable = j && safeAdd(h, i) == e;
}
}
