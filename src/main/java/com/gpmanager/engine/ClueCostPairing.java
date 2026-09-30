package com.gpmanager;
/**
* While a clue/casket/key path is active, digs / teleports / key spends attach as
* costs against that clue's Pending Rewards → Claimed batch. Unmatched supply
* spends stay normal Used.
*/
class ClueCostPairing {
String activeClueEncounterId;
String activeClueLabel;
synchronized void aue(String encounterId, String label) {
this.activeClueEncounterId = rk(encounterId);
this.activeClueLabel = rk(label);
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
synchronized boolean wt(String noteOrOption) {
String lower = Ag.norm(noteOrOption);
return activeClueEncounterId != null && lower != null && yv(lower);
}
static boolean yv(String lowerNote) {
if (Ag.empty(lowerNote)) {
return false;
}
return lowerNote.contains("dig")
|| lowerNote.contains("teleport")
|| lowerNote.contains("tele ")
|| lowerNote.contains("clue")
|| lowerNote.contains("casket")
|| lowerNote.contains("spade")
|| lowerNote.contains("key") && (Ag.has(lowerNote, "clue", "chest"));
}
static boolean ys(String name) {
String lower = Ag.norm(name);
return lower != null
&& (Ag.has(lower, "clue", "casket", "treasure trail"));
}
synchronized String awb() {
String label = activeClueLabel == null ? "clue" : activeClueLabel;
return "Clue cost paired to " + label;
}
synchronized void clear() {
axp();
}
static String rk(String value) {
if (Ag.blank(value)) {
return null;
}
return value.trim();
}
}
