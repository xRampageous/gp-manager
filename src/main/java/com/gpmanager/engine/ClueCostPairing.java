package com.gpmanager;
/**
* While a clue/casket/key path is active, digs / teleports / key spends attach as
* costs against that clue's Pending Rewards → Claimed batch. Unmatched supply
* spends stay normal Used.
*/
class ClueCostPairing {
String activeClueEncounterId;
String activeClueLabel;
synchronized void aue(String /* encounterId */a, String /* label */b) {
 this.activeClueEncounterId = rk(a);
 this.activeClueLabel = rk(b);
}

synchronized void axp() {
 activeClueEncounterId = null;
 activeClueLabel = null;
}

synchronized boolean isActive() {
 return activeClueEncounterId != null;
}

synchronized String tj() {
 return activeClueEncounterId;
}

/**
* Classify a spend note while a clue path may be open.
* Dig / teleport / clue key → CLUE_COST; food / potions → UNRELATED_USED.
*/
synchronized boolean wt(String /* noteOrOption */a) {
 String /* lower */b = Ag.norm(a);
 return activeClueEncounterId != null && b != null && yv(b);
}

static boolean yv(String /* lowerNote */a) {
 if (Ag.empty(a)) return false;
 return a.contains("dig") || a.contains("teleport") || a.contains("tele ")
 || a.contains("clue") || a.contains("casket") || a.contains("spade")
 || a.contains("key") && (Ag.has(a, "clue", "chest"));
}

static boolean ys(String /* name */a) {
 String /* lower */b = Ag.norm(a);
 return b != null && (Ag.has(b, "clue", "casket", "treasure trail"));
}

synchronized String awb() {
 String /* label */a = activeClueLabel == null ? "clue" : activeClueLabel;
 return "Clue cost paired to " + a;
}

synchronized void clear() {
 axp();
}

static String rk(String /* value */a) {
 if (Ag.blank(a)) return null;
 return a.trim();
}
}
